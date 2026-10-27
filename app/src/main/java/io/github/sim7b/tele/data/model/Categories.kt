package io.github.sim7b.tele.data.model

import androidx.annotation.StringRes
import io.github.sim7b.tele.R

/** Categories with the same displayed name in several enabled sources. */
enum class CategoryMode(@param:StringRes val labelRes: Int, @param:StringRes val descriptionRes: Int) {
    /** One category gathering the channels of every source. */
    MERGE(R.string.category_mode_merge, R.string.category_mode_merge_description),

    /** One category per source, followed by the source name ("Sport · Neo"). */
    SEPARATE(R.string.category_mode_separate, R.string.category_mode_separate_description)
}

/**
 * Displayed categories: the name given by the user (renamed on the device, keyed by the source group),
 * else the group of the source. In [CategoryMode.SEPARATE], a name used by several sources gets the
 * source name; in [CategoryMode.MERGE], same names form one category (renaming can also merge two).
 * Hidden categories and the home filter use these displayed names.
 */
object Categories {

    const val SOURCE_SEPARATOR = " · "

    fun renamed(group: String, renames: Map<String, String>): String = renames[group] ?: group

    /**
     * [categories] in the order chosen by the user: the ones of [order] first, in that order, then the
     * others in their own order (new categories land at the end of the ordered ones).
     */
    fun ordered(categories: List<String>, order: List<String>): List<String> {
        if (order.isEmpty()) return categories
        val rank = order.withIndex().associate { (index, name) -> name to index }
        return categories.sortedBy { rank[it] ?: Int.MAX_VALUE }
    }

    /** New order after moving [category] by [step] places among [current] (displayed order). */
    fun moved(current: List<String>, category: String, step: Int): List<String> {
        val from = current.indexOf(category)
        if (from == -1) return current
        val to = (from + step).coerceIn(0, current.lastIndex)
        if (to == from) return current
        return current.toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * [channels] with their displayed category in [Channel.group]; channels whose category does not change
     * are returned as is (no copy).
     */
    fun apply(
        channels: List<Channel>,
        renames: Map<String, String>,
        mode: CategoryMode,
        sourceNames: Map<String, String>
    ): List<Channel> {
        if (renames.isEmpty() && (mode == CategoryMode.MERGE || sourceNames.size < 2)) return channels
        val shared = if (mode == CategoryMode.SEPARATE) sharedNames(channels, renames) else emptySet()
        return channels.map { channel ->
            val display = displayName(channel, renames, shared, sourceNames)
            if (display == channel.group) channel else channel.copy(group = display)
        }
    }

    /**
     * Source groups behind displayed category [display]: the keys to rename (or to reset) for it.
     * [channels] are the channels as the sources provide them.
     */
    fun sourceGroupsOf(
        display: String,
        channels: List<Channel>,
        renames: Map<String, String>,
        mode: CategoryMode,
        sourceNames: Map<String, String>
    ): Set<String> {
        val shared = if (mode == CategoryMode.SEPARATE) sharedNames(channels, renames) else emptySet()
        return channels.asSequence()
            .filter { displayName(it, renames, shared, sourceNames) == display }
            .map { it.group }
            .toSet()
    }

    /** Displayed names, after the renames, used by more than one source. */
    private fun sharedNames(channels: List<Channel>, renames: Map<String, String>): Set<String> {
        val sourcesByName = HashMap<String, MutableSet<String>>()
        channels.forEach { sourcesByName.getOrPut(renamed(it.group, renames)) { HashSet(2) }.add(it.sourceId) }
        return sourcesByName.filterValues { it.size > 1 }.keys
    }

    private fun displayName(
        channel: Channel,
        renames: Map<String, String>,
        shared: Set<String>,
        sourceNames: Map<String, String>
    ): String {
        val name = renamed(channel.group, renames)
        val source = sourceNames[channel.sourceId]
        // Channels without category keep the shared "Other" label (its translated name is added by the UI)
        return if (name in shared && source != null && name.isNotEmpty()) name + SOURCE_SEPARATOR + source else name
    }
}
