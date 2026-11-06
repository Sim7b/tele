package io.github.sim7b.tele.data.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A list of channels made by the user ("Sport", "Kids"), besides the favorites: a category of its own. */
data class ChannelList(val id: String, val name: String, val channelIds: List<String>)

/** A list with its channels found in the playlists, in the order they were added. */
data class ChannelListView(val id: String, val name: String, val channels: List<Channel>)

/** Lists of channels: changes (each returns the new lists) and their storage. */
object ChannelLists {

    const val MAX_LISTS = 20
    private const val MAX_NAME = 60

    /** A new list named [name], holding [channelId] when given; unchanged at [MAX_LISTS] lists or without a name. */
    fun create(lists: List<ChannelList>, name: String, channelId: String? = null, id: String = UUID.randomUUID().toString()): List<ChannelList> {
        val clean = name.trim().take(MAX_NAME)
        if (clean.isEmpty() || lists.size >= MAX_LISTS) return lists
        return lists + ChannelList(id, clean, listOfNotNull(channelId))
    }

    /** Adds the channel to list [listId] (at its end), or removes it. */
    fun toggle(lists: List<ChannelList>, listId: String, channelId: String): List<ChannelList> = lists.map { list ->
        if (list.id != listId) list
        else list.copy(channelIds = if (channelId in list.channelIds) list.channelIds - channelId else list.channelIds + channelId)
    }

    fun rename(lists: List<ChannelList>, listId: String, name: String): List<ChannelList> {
        val clean = name.trim().take(MAX_NAME)
        if (clean.isEmpty()) return lists
        return lists.map { if (it.id == listId) it.copy(name = clean) else it }
    }

    fun delete(lists: List<ChannelList>, listId: String): List<ChannelList> = lists.filterNot { it.id == listId }

    /** The lists without the channels matching [removed] (channels of a deleted source). */
    fun without(lists: List<ChannelList>, removed: (String) -> Boolean): List<ChannelList> =
        lists.map { list -> list.copy(channelIds = list.channelIds.filterNot(removed)) }

    /**
     * The lists with their channels among [channels] (a grouped channel is found by any of its versions),
     * in the order added; the lists without any channel found are kept (they may fill again).
     */
    fun resolve(lists: List<ChannelList>, channels: List<Channel>): List<ChannelListView> {
        if (lists.isEmpty()) return emptyList()
        val wanted = lists.flatMapTo(HashSet()) { it.channelIds }
        val byId = HashMap<String, Channel>()
        channels.forEach { channel -> channel.allIds.forEach { id -> if (id in wanted) byId.putIfAbsent(id, channel) } }
        return lists.map { list -> ChannelListView(list.id, list.name, list.channelIds.mapNotNull { byId[it] }.distinct()) }
    }

    fun toJson(lists: List<ChannelList>): JSONArray = JSONArray().apply {
        lists.forEach { list ->
            put(JSONObject().put("id", list.id).put("name", list.name).put("channels", JSONArray(list.channelIds)))
        }
    }

    /** Lists saved by [toJson]; broken entries are skipped. */
    fun fromJson(array: JSONArray?): List<ChannelList> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = item.optString("name").takeIf { it.isNotBlank() }?.take(MAX_NAME) ?: return@mapNotNull null
            val channels = item.optJSONArray("channels")?.let { ids ->
                (0 until ids.length()).mapNotNull { ids.optString(it).takeIf { id -> id.isNotBlank() } }.distinct()
            }.orEmpty()
            ChannelList(id, name, channels)
        }.distinctBy { it.id }.take(MAX_LISTS)
    }

    fun fromJson(raw: String?): List<ChannelList> =
        try {
            raw?.let { fromJson(JSONArray(it)) }.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
}
