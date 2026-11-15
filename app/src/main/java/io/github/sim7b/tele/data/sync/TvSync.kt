package io.github.sim7b.tele.data.sync

import io.github.sim7b.tele.data.storage.Reminder
import io.github.sim7b.tele.data.storage.VodCollection
import io.github.sim7b.tele.data.storage.VodProgress
import org.json.JSONArray
import org.json.JSONObject

/** collection -> key -> value as JSON text. */
typealias SyncItems = Map<String, Map<String, String>>

/**
 * Sync of the favorites, VOD positions, my list, collections and reminders between the TVs of a Dispatcharr user,
 * through the Télé API plugin (`/api/tele/v1/sync/`), the most recent change winning item by item.
 *
 * Each TV keeps the items as they were at its last sync: what differs now was changed here (sent with the time
 * of the change), what is gone was deleted here. Values are JSON arrays, whose text is the same however the
 * server orders the keys of an object, so that comparing them never shows a change that did not happen.
 */
object TvSync {
    const val FAVORITES = "favorites"
    const val VOD_PROGRESS = "vod_progress"
    const val MY_LIST = "my_list"
    const val COLLECTIONS = "collections"
    const val REMINDERS = "reminders"
    val COLLECTION_NAMES = listOf(FAVORITES, VOD_PROGRESS, MY_LIST, COLLECTIONS, REMINDERS)

    /** A local item: its value as JSON text, and when it changed when the item knows it (VOD positions). */
    data class Local(val json: String, val changedAt: Long? = null)

    /** The changes made here since [synced], in the format of the plugin ({collection: {key: {"v", "t", "d"}}}). */
    fun changes(local: Map<String, Map<String, Local>>, synced: SyncItems, now: Long): JSONObject {
        val changes = JSONObject()
        for (collection in COLLECTION_NAMES) {
            val mine = local[collection].orEmpty()
            val before = synced[collection].orEmpty()
            val items = JSONObject()
            mine.forEach { (key, item) ->
                if (before[key] != item.json) {
                    items.put(key, JSONObject().put("v", JSONArray(item.json)).put("t", item.changedAt ?: now))
                }
            }
            before.keys.filter { it !in mine }.forEach { key -> items.put(key, JSONObject().put("d", true).put("t", now)) }
            if (items.length() > 0) changes.put(collection, items)
        }
        return changes
    }

    /** The items of an answer of the plugin, deletions left out; values that are not arrays are skipped. */
    fun items(answer: JSONObject?): SyncItems {
        val all = answer?.optJSONObject("items") ?: return emptyMap()
        return COLLECTION_NAMES.associateWith { collection ->
            val items = all.optJSONObject(collection) ?: return@associateWith emptyMap()
            val result = HashMap<String, String>()
            items.keys().forEach { key ->
                val entry = items.optJSONObject(key) ?: return@forEach
                if (entry.optBoolean("d", false)) return@forEach
                entry.optJSONArray("v")?.let { result[key] = it.toString() }
            }
            result
        }
    }

    /**
     * What to keep after a sync: the merged [server] items, except those changed here while the sync ran
     * ([during] differs from [sent]), kept as they are now and sent at the next sync.
     */
    fun resolve(server: SyncItems, sent: Map<String, Map<String, Local>>, during: Map<String, Map<String, Local>>): Pair<SyncItems, SyncItems> {
        val kept = HashMap<String, Map<String, String>>()
        val synced = HashMap<String, Map<String, String>>()
        for (collection in COLLECTION_NAMES) {
            val result = HashMap(server[collection].orEmpty())
            val remembered = HashMap(server[collection].orEmpty())
            val before = sent[collection].orEmpty()
            val now = during[collection].orEmpty()
            (before.keys + now.keys).forEach { key ->
                if (before[key]?.json == now[key]?.json) return@forEach
                // Changed here meanwhile: the local value stays, and is still to send
                now[key]?.let { result[key] = it.json } ?: result.remove(key)
                before[key]?.let { remembered[key] = it.json } ?: remembered.remove(key)
            }
            kept[collection] = result
            synced[collection] = remembered
        }
        return kept to synced
    }

    // ------------------------------------------------------------------ Values of each collection

    fun favorites(order: List<String>): Map<String, Local> =
        order.withIndex().associate { (index, id) -> id to Local(JSONArray().put(index).toString()) }

    fun favoriteOrder(items: Map<String, String>): List<String> =
        items.mapNotNull { (id, json) -> rank(json)?.let { id to it } }.sortedWith(compareBy({ it.second }, { it.first })).map { it.first }

    fun myList(list: List<String>): Map<String, Local> = favorites(list)

    fun myListOrder(items: Map<String, String>): List<String> = favoriteOrder(items)

    fun progress(progress: Map<String, VodProgress>): Map<String, Local> = progress.mapValues { (_, p) ->
        Local(JSONArray().put(p.titleKey).put(p.positionMs).put(p.durationMs).put(p.updatedAt).toString(), changedAt = p.updatedAt)
    }

    fun progressOf(items: Map<String, String>): Map<String, VodProgress> = items.mapNotNull { (key, json) ->
        val array = parse(json) ?: return@mapNotNull null
        val titleKey = array.optString(0).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        key to VodProgress(key, titleKey, array.optLong(1).coerceAtLeast(0), array.optLong(2).coerceAtLeast(0), array.optLong(3).coerceAtLeast(0))
    }.toMap()

    fun collections(collections: List<VodCollection>): Map<String, Local> = collections.withIndex().associate { (index, c) ->
        c.id to Local(JSONArray().put(index).put(c.name).put(JSONArray(c.titleKeys)).toString())
    }

    fun collectionsOf(items: Map<String, String>): List<VodCollection> = items.mapNotNull { (id, json) ->
        val array = parse(json) ?: return@mapNotNull null
        val name = array.optString(1).takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val titles = array.optJSONArray(2)?.let { list -> (0 until list.length()).mapNotNull { list.optString(it).takeIf(String::isNotEmpty) } }.orEmpty()
        Triple(array.optInt(0, Int.MAX_VALUE), id, VodCollection(id, name.take(MAX_NAME), titles.distinct()))
    }.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }

    fun reminders(reminders: List<Reminder>): Map<String, Local> = reminders.associate { r ->
        "${r.channelId}:${r.startMillis}" to Local(JSONArray().put(r.channelId).put(r.channelName).put(r.title).put(r.startMillis).put(r.endMillis).toString())
    }

    fun remindersOf(items: Map<String, String>): List<Reminder> = items.values.mapNotNull { json ->
        val array = parse(json) ?: return@mapNotNull null
        val channelId = array.optString(0).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val start = array.optLong(3)
        val end = array.optLong(4)
        if (start <= 0 || end <= start) return@mapNotNull null
        Reminder(channelId, array.optString(1), array.optString(2), start, end)
    }.sortedBy { it.startMillis }

    private fun rank(json: String): Int? = parse(json)?.takeIf { it.length() > 0 }?.optInt(0, -1)?.takeIf { it >= 0 }

    private fun parse(json: String): JSONArray? = try {
        JSONArray(json)
    } catch (e: org.json.JSONException) {
        null
    }

    private const val MAX_NAME = 60

    /** [items] as JSON (the kept state of the last sync) and back. */
    fun toJson(items: SyncItems): String = JSONObject().apply {
        items.forEach { (collection, values) -> put(collection, JSONObject(values)) }
    }.toString()

    fun fromJson(json: String?): SyncItems {
        val root = try {
            JSONObject(json ?: return emptyMap())
        } catch (e: org.json.JSONException) {
            return emptyMap()
        }
        return COLLECTION_NAMES.associateWith { collection ->
            val values = root.optJSONObject(collection) ?: return@associateWith emptyMap()
            values.keys().asSequence().mapNotNull { key -> values.optString(key).takeIf { it.isNotEmpty() }?.let { key to it } }.toMap()
        }
    }
}
