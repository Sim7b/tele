package io.github.sim7b.tele.data.storage

import io.github.sim7b.tele.data.model.GuideProgram
import io.github.sim7b.tele.data.optStringOrNull
import io.github.sim7b.tele.data.remote.CachedGuide
import io.github.sim7b.tele.data.remote.GuideValidator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * XMLTV guide of a source as matched to its channels, for a startup with the programs already there and a
 * conditional reload (the guide file is only downloaded again when it changed).
 *
 * One JSON object per line, like [ChannelCache]: a guide may cover tens of thousands of channels. The guide
 * URLs (which may embed credentials) are only stored hashed. To be used off the main thread.
 */
class GuideCache(private val file: File) {

    fun save(guide: CachedGuide) {
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            temp.bufferedWriter().use { writer ->
                val validators = JSONObject()
                guide.validators.forEach { (key, validator) ->
                    validators.put(key, JSONObject().put("etag", validator.etag).put("lastModified", validator.lastModified))
                }
                writer.write(
                    JSONObject()
                        .put(KEY_FINGERPRINT, guide.fingerprint)
                        .put(KEY_SAVED_AT, guide.savedAt)
                        .put(KEY_VALIDATORS, validators)
                        .toString()
                )
                writer.newLine()
                guide.programs.forEach { (channelId, programs) ->
                    val array = JSONArray()
                    programs.forEach { array.put(programToJson(it)) }
                    writer.write(JSONObject().put(KEY_CHANNEL, channelId).put(KEY_PROGRAMS, array).toString())
                    writer.newLine()
                }
            }
            if (!temp.renameTo(file)) throw IOException("Unable to replace the guide cache")
        } finally {
            temp.delete()
        }
    }

    /** Cached guide, programs ended before [keepFrom] dropped; null without a readable cache. */
    fun load(keepFrom: Long): CachedGuide? {
        if (!file.exists()) return null
        file.bufferedReader().use { reader ->
            val header = reader.readLine()?.let { JSONObject(it) } ?: return null
            val validators = HashMap<String, GuideValidator>()
            header.optJSONObject(KEY_VALIDATORS)?.let { json ->
                json.keys().forEach { key ->
                    val validator = json.optJSONObject(key) ?: return@forEach
                    validators[key] = GuideValidator(validator.optStringOrNull("etag"), validator.optStringOrNull("lastModified"))
                }
            }
            val programs = HashMap<String, List<GuideProgram>>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val obj = JSONObject(line)
                val array = obj.optJSONArray(KEY_PROGRAMS) ?: continue
                val list = (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let(::programFromJson)?.takeIf { it.endMillis > keepFrom }
                }
                if (list.isNotEmpty()) programs[obj.getString(KEY_CHANNEL)] = list
            }
            return CachedGuide(
                fingerprint = header.optString(KEY_FINGERPRINT),
                savedAt = header.optLong(KEY_SAVED_AT),
                validators = validators,
                programs = programs
            )
        }
    }

    fun clear() {
        file.delete()
    }

    private fun programToJson(program: GuideProgram) = JSONObject().apply {
        put("id", program.id)
        put("channelUuid", program.channelUuid)
        put("title", program.title)
        put("description", program.description)
        put("start", program.startTimeIso)
        put("end", program.endTimeIso)
        put("posterUrl", program.posterUrl)
        put("rating", program.rating)
        put("startMillis", program.startMillis)
        put("endMillis", program.endMillis)
        put("category", program.category)
    }

    private fun programFromJson(obj: JSONObject) = GuideProgram(
        id = obj.optString("id"),
        channelUuid = obj.optString("channelUuid"),
        title = obj.optString("title"),
        description = obj.optStringOrNull("description"),
        startTimeIso = obj.optString("start"),
        endTimeIso = obj.optString("end"),
        posterUrl = obj.optStringOrNull("posterUrl"),
        rating = obj.optStringOrNull("rating"),
        startMillis = obj.optLong("startMillis"),
        endMillis = obj.optLong("endMillis"),
        category = obj.optStringOrNull("category")
    )

    private companion object {
        const val KEY_FINGERPRINT = "fingerprint"
        const val KEY_SAVED_AT = "savedAt"
        const val KEY_VALIDATORS = "validators"
        const val KEY_CHANNEL = "channel"
        const val KEY_PROGRAMS = "programs"
    }
}
