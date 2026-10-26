package io.github.sim7b.tele.data.remote

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import com.squareup.moshi.JsonReader
import io.github.sim7b.tele.data.parser.CappedInputStream
import io.github.sim7b.tele.data.parser.invalidResponse
import okio.buffer
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.InputStream

/**
 * Streaming reader of JSON lists: provider lists (100,000+ live streams, EPG grids of tens of MB) are read
 * one element at a time instead of being held in memory as a single string and object tree.
 */
object JsonStreams {

    // Limits against huge or malicious responses (the nesting depth is bounded by the reader itself)
    private const val MAX_BYTES = 256L * 1024 * 1024
    private const val MAX_ITEMS = 500_000

    /**
     * Calls [action] for each object of the list: a raw JSON array, or the "data" / "results" array of
     * a paginated object. Other elements are skipped. Returns the number of objects read.
     * Each object is converted to org.json with the same number types as org.json's own parser.
     */
    fun forEachObject(
        input: InputStream,
        maxBytes: Long = MAX_BYTES,
        maxItems: Int = MAX_ITEMS,
        action: (JSONObject) -> Unit
    ): Int {
        val source = CappedInputStream(input, maxBytes).source().buffer()
        try {
            JsonReader.of(source).use { reader ->
                return when (reader.peek()) {
                    JsonReader.Token.BEGIN_ARRAY -> readObjects(reader, maxItems, action)
                    JsonReader.Token.BEGIN_OBJECT -> {
                        var count = 0
                        var found = false
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val name = reader.nextName()
                            if (!found && (name == "data" || name == "results") && reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
                                count = readObjects(reader, maxItems, action)
                                found = true
                                // The rest of a paginated object is not needed
                                break
                            }
                            reader.skipValue()
                        }
                        count
                    }
                    else -> throw invalidResponse()
                }
            }
        } catch (e: JsonEncodingException) {
            // Malformed JSON: the message may quote the response
            throw invalidResponse()
        } catch (e: JsonDataException) {
            throw invalidResponse()
        } catch (e: EOFException) {
            // Empty or truncated body
            throw invalidResponse()
        }
    }

    private fun readObjects(reader: JsonReader, maxItems: Int, action: (JSONObject) -> Unit): Int {
        var count = 0
        reader.beginArray()
        while (reader.hasNext() && count < maxItems) {
            if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                action(readValue(reader) as JSONObject)
                count++
            } else {
                reader.skipValue()
            }
        }
        return count
    }

    private fun readValue(reader: JsonReader): Any = when (reader.peek()) {
        JsonReader.Token.BEGIN_OBJECT -> JSONObject().also { obj ->
            reader.beginObject()
            while (reader.hasNext()) obj.put(reader.nextName(), readValue(reader))
            reader.endObject()
        }
        JsonReader.Token.BEGIN_ARRAY -> JSONArray().also { array ->
            reader.beginArray()
            while (reader.hasNext()) array.put(readValue(reader))
            reader.endArray()
        }
        JsonReader.Token.STRING -> reader.nextString()
        JsonReader.Token.NUMBER -> number(reader.nextString())
        JsonReader.Token.BOOLEAN -> reader.nextBoolean()
        JsonReader.Token.NULL -> {
            reader.nextNull<Any>()
            JSONObject.NULL
        }
        else -> throw invalidResponse()
    }

    /** Integer literals stay integers ("stream_id": 123 is read as "123", not "123.0"). */
    internal fun number(literal: String): Any {
        if (literal.none { it == '.' || it == 'e' || it == 'E' }) {
            literal.toLongOrNull()?.let { return if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
        }
        return literal.toDoubleOrNull() ?: literal
    }
}
