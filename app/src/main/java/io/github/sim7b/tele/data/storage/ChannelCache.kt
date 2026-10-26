package io.github.sim7b.tele.data.storage

import io.github.sim7b.tele.data.epg.EpgResolver
import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.EpgProgram
import io.github.sim7b.tele.data.model.StreamSource
import io.github.sim7b.tele.data.optStringOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Channels and current programs of the active source, for an instant startup without a spinner.
 *
 * Stored in its own file, one JSON object per line: playlists may hold tens of thousands of channels,
 * which must neither sit in the SharedPreferences (loaded at once, rewritten on every setting change)
 * nor be read as a single huge string. To be used off the main thread.
 */
class ChannelCache(private val file: File) {

    /** Replaces the cache with the channels of source [configId] (written to a temporary file, then renamed). */
    fun save(configId: String, channels: List<Channel>) {
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            temp.bufferedWriter().use { writer ->
                writer.write(JSONObject().put(KEY_CONFIG_ID, configId).toString())
                writer.newLine()
                channels.forEach { channel ->
                    writer.write(channelToJson(channel).toString())
                    writer.newLine()
                }
            }
            if (!temp.renameTo(file)) throw IOException("Unable to replace the channel cache")
        } finally {
            temp.delete()
        }
    }

    /**
     * Cached channels of source [configId] (empty when the cache belongs to another source).
     * Programs that ended since caching are dropped.
     */
    fun load(configId: String, now: Long = System.currentTimeMillis()): List<Channel> {
        if (!file.exists()) return emptyList()
        file.bufferedReader().use { reader ->
            val header = reader.readLine()?.let { JSONObject(it) } ?: return emptyList()
            if (header.optString(KEY_CONFIG_ID) != configId) return emptyList()
            val channels = ArrayList<Channel>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank()) channels.add(channelFromJson(JSONObject(line), now))
            }
            return channels
        }
    }

    fun clear() {
        file.delete()
    }

    private fun channelToJson(channel: Channel) = JSONObject().apply {
        put("id", channel.id)
        put("name", channel.name)
        put("group", channel.group)
        put("logoUrl", channel.logoUrl)
        put("streamUrl", channel.streamUrl)
        put("tvgId", channel.tvgId)
        put("isFavorite", channel.isFavorite)
        put("serverId", channel.serverId)
        channel.number?.let { put("number", it) }
        if (channel.availableStreams.isNotEmpty()) {
            put("streams", JSONArray().apply { channel.availableStreams.forEach { put(streamToJson(it)) } })
        }
        channel.currentProgram?.let { put("currentProgram", programToJson(it)) }
    }

    private fun channelFromJson(obj: JSONObject, now: Long) = Channel(
        id = obj.getString("id"),
        name = obj.getString("name"),
        group = obj.getString("group"),
        logoUrl = obj.optStringOrNull("logoUrl"),
        streamUrl = obj.getString("streamUrl"),
        tvgId = obj.optStringOrNull("tvgId"),
        isFavorite = obj.optBoolean("isFavorite", false),
        currentProgram = obj.optJSONObject("currentProgram")?.let { programFromJson(it) }?.let { EpgResolver.refreshCached(it, now) },
        availableStreams = obj.optJSONArray("streams")?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::streamFromJson) }
        }.orEmpty(),
        serverId = obj.optStringOrNull("serverId"),
        number = obj.optInt("number", -1).takeIf { it > 0 }
    )

    private fun streamToJson(stream: StreamSource) = JSONObject().apply {
        put("name", stream.name)
        put("url", stream.url)
        put("id", stream.id)
        put("provider", stream.provider)
        put("qualityTags", JSONArray(stream.qualityTags))
    }

    private fun streamFromJson(obj: JSONObject): StreamSource? {
        val url = obj.optStringOrNull("url") ?: return null
        return StreamSource(
            name = obj.optStringOrNull("name") ?: url,
            url = url,
            id = obj.optStringOrNull("id"),
            provider = obj.optStringOrNull("provider"),
            qualityTags = obj.optJSONArray("qualityTags")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf { tag -> tag.isNotBlank() } }
            }.orEmpty()
        )
    }

    private fun programToJson(program: EpgProgram) = JSONObject().apply {
        put("title", program.title)
        put("category", program.category)
        put("startTime", program.startTime)
        put("endTime", program.endTime)
        put("progress", program.progress.toDouble())
        put("posterUrl", program.posterUrl)
        put("description", program.description)
        put("nextTitle", program.nextTitle)
        put("nextStartTime", program.nextStartTime)
        put("startMillis", program.startMillis)
        put("endMillis", program.endMillis)
    }

    private fun programFromJson(obj: JSONObject) = EpgProgram(
        title = obj.optString("title", ""),
        category = obj.optStringOrNull("category"),
        startTime = obj.optString("startTime", ""),
        endTime = obj.optString("endTime", ""),
        progress = obj.optDouble("progress", 0.0).toFloat(),
        posterUrl = obj.optStringOrNull("posterUrl"),
        description = obj.optStringOrNull("description"),
        nextTitle = obj.optStringOrNull("nextTitle"),
        nextStartTime = obj.optStringOrNull("nextStartTime"),
        startMillis = obj.optLong("startMillis", 0L),
        endMillis = obj.optLong("endMillis", 0L)
    )

    private companion object {
        const val KEY_CONFIG_ID = "configId"
    }
}
