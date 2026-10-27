package io.github.sim7b.tele.data.bouquet

import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.EpgProgram
import io.github.sim7b.tele.data.model.StreamSource
import io.github.sim7b.tele.data.remote.StreamNames

/**
 * Turns the provider channels into the displayed ones:
 * - a channel recognized in the [bouquet] takes its proper name, its usual number and its category;
 * - the other categories are cleaned and grouped by meaning ("Sport", "FR | Sports", "France Sport"),
 *   or join a bouquet category ("FR | Documentaires" -> Discovery);
 * - the versions of a same channel ("TF1 FHD", "TF1 HD", "TF1 HEVC", from one or several sources) become
 *   one channel, whose versions are its streams. Across sources only when [mergeAcrossSources].
 *
 * A merged channel keeps the id of its favorite version, else of its first one: favorites, hidden channels
 * and chosen streams keep working; [Channel.memberIds] lists the ids of all its versions.
 */
class ChannelGrouping(private val bouquet: Bouquet?, private val names: ChannelNames) {

    fun build(
        channels: List<Channel>,
        mergeAcrossSources: Boolean,
        favoriteIds: Set<String>,
        sourceNames: Map<String, String>,
        categoryLabel: (BouquetCategory) -> String
    ): List<Channel> {
        if (channels.isEmpty()) return channels
        val matches = channels.map { bouquet?.match(it.name) }
        val categories = categoryNames(channels, categoryLabel)

        // Versions of a same channel, in the order of their first appearance
        val groups = LinkedHashMap<String, MutableList<Int>>()
        channels.forEachIndexed { index, channel ->
            val identity = matches[index]?.let { "b:${it.name}" }
                ?: names.channelKey(channel.name).takeIf { it.isNotEmpty() }?.let { "n:$it" }
                ?: "id:${channel.id}"
            val key = if (mergeAcrossSources) identity else "${channel.sourceId}/$identity"
            groups.getOrPut(key) { ArrayList(1) }.add(index)
        }

        return groups.values.map { indexes ->
            val match = matches[indexes.first()]
            val group = match?.let { categoryLabel(it.category) } ?: categories[indexes.first()]
            if (indexes.size == 1) {
                val channel = channels[indexes.first()]
                if (match == null) {
                    if (group == channel.group) channel else channel.copy(group = group)
                } else {
                    channel.copy(name = match.name, number = match.number ?: channel.number, group = group)
                }
            } else {
                merge(indexes.map { channels[it] }, match, group, favoriteIds, sourceNames)
            }
        }
    }

    private fun merge(
        versions: List<Channel>,
        match: BouquetChannel?,
        group: String,
        favoriteIds: Set<String>,
        sourceNames: Map<String, String>
    ): Channel {
        val primary = versions.firstOrNull { it.id in favoriteIds } ?: versions.first()
        val showSource = versions.map { it.sourceId }.distinct().size > 1
        val streams = versions.flatMap { version ->
            val label = names.versionDisplay(version.name)
            val provider = if (showSource) sourceNames[version.sourceId] else null
            val tags = StreamNames.qualityTags(version.name)
            if (version.availableStreams.isEmpty()) {
                listOf(StreamSource(name = label, url = version.streamUrl, id = "$MERGED_PREFIX${version.id}", provider = provider, qualityTags = tags))
            } else {
                // Xtream: TS / HLS variants of each version
                version.availableStreams.map { variant ->
                    variant.copy(
                        name = "$label · ${variant.name}",
                        id = "$MERGED_PREFIX${version.id}:${variant.id}",
                        provider = provider ?: variant.provider,
                        qualityTags = (tags + variant.qualityTags).distinct()
                    )
                }
            }
        }
        return primary.copy(
            name = match?.name ?: names.channelDisplay(primary.name),
            group = group,
            number = match?.number ?: versions.firstNotNullOfOrNull { it.number },
            logoUrl = primary.logoUrl ?: versions.firstNotNullOfOrNull { it.logoUrl },
            currentProgram = primary.currentProgram ?: versions.firstNotNullOfOrNull { it.currentProgram },
            availableStreams = streams,
            memberIds = versions.map { it.id }
        )
    }

    /**
     * Displayed category of each channel (bouquet ones excepted): a provider category meaning a bouquet one
     * takes its label; the others are grouped by key and take their shortest cleaned name.
     */
    private fun categoryNames(channels: List<Channel>, categoryLabel: (BouquetCategory) -> String): List<String> {
        val byGroup = HashMap<String, String>()
        val displayByKey = HashMap<String, String>()
        val keys = HashMap<String, String>()
        channels.forEach { channel ->
            val group = channel.group
            if (group == Channel.NO_GROUP || group in keys) return@forEach
            val bouquetCategory = bouquet?.categoryOf(group)
            if (bouquetCategory != null) {
                byGroup[group] = categoryLabel(bouquetCategory)
                keys[group] = ""
                return@forEach
            }
            val key = names.categoryKey(group).ifEmpty { group }
            keys[group] = key
            val display = names.categoryDisplay(group)
            val current = displayByKey[key]
            if (current == null || display.length < current.length) displayByKey[key] = display
        }
        return channels.map { channel ->
            val group = channel.group
            byGroup[group] ?: keys[group]?.let { displayByKey[it] } ?: group
        }
    }

    companion object {
        const val MERGED_PREFIX = "merged:"

        /** What the grouping depends on: the channels without their current program. */
        fun structureOf(channels: List<Channel>): List<Channel> =
            channels.map { if (it.currentProgram == null) it else it.copy(currentProgram = null) }

        /**
         * [display] channels built from channels of the same structure, with the current programs of
         * [channels]: a grouped channel takes the program of its first version that has one. Unchanged
         * channels stay the same objects, and [display] itself when nothing changed.
         */
        fun withPrograms(display: List<Channel>, channels: List<Channel>): List<Channel> {
            val programs = HashMap<String, EpgProgram>(channels.size * 2)
            channels.forEach { channel -> channel.currentProgram?.let { programs[channel.id] = it } }
            var changed = false
            val updated = display.map { channel ->
                val program = programs[channel.id] ?: channel.memberIds.firstNotNullOfOrNull { programs[it] }
                if (program == channel.currentProgram) {
                    channel
                } else {
                    changed = true
                    channel.copy(currentProgram = program)
                }
            }
            return if (changed) updated else display
        }
    }
}
