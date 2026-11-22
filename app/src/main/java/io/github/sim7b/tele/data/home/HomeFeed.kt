package io.github.sim7b.tele.data.home

import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.sport.SportEvent
import io.github.sim7b.tele.data.storage.VodProgress
import io.github.sim7b.tele.data.vod.VodTitle

/** Something shown on the home: a channel (its program on air), a movie or a series, a followed match. */
sealed interface HomeItem {
    val key: String

    data class Live(val channel: Channel) : HomeItem {
        override val key: String get() = "ch:${channel.id}"
    }

    data class Title(val title: VodTitle, val progress: VodProgress? = null) : HomeItem {
        override val key: String get() = "vod:${title.key}"
    }

    data class Event(val event: SportEvent) : HomeItem {
        override val key: String get() = "ev:${event.channel.id}:${event.program.startMillis}"
    }
}

/** What the home puts first: the featured items at the top (one at a time), then the "Up next" row. */
object HomeFeed {

    const val MAX_FEATURED = 5
    const val MAX_UP_NEXT = 20

    /**
     * "Up next": the followed matches on air, then the movies and series to resume and the coming matches in turn
     * (the latest resumed first, the next match first). Matches already over are left to the guide.
     */
    fun upNext(
        resume: List<Pair<VodTitle, VodProgress>>,
        sport: List<SportEvent>,
        now: Long,
        limit: Int = MAX_UP_NEXT
    ): List<HomeItem> {
        val onAir = sport.filter { it.program.startMillis <= now && it.program.endMillis > now }.map { HomeItem.Event(it) }
        val coming = sport.filter { it.program.startMillis > now }.sortedBy { it.program.startMillis }.map { HomeItem.Event(it) }
        val titles = resume.map { (title, progress) -> HomeItem.Title(title, progress) }
        return (onAir + interleave(titles, coming)).distinctBy { it.key }.take(limit)
    }

    /**
     * The featured items, shown one after the other at the top: the program on air of a favorite, a title to
     * resume, a followed match, in turn. Never empty while there is a channel: the first favorite (or channel)
     * stands in.
     */
    fun featured(favorites: List<Channel>, upNext: List<HomeItem>, channels: List<Channel>, max: Int = MAX_FEATURED): List<HomeItem> {
        val live = favorites.filter { it.currentProgram != null }.map { HomeItem.Live(it) }
        val titles = upNext.filterIsInstance<HomeItem.Title>()
        val events = upNext.filterIsInstance<HomeItem.Event>()
        val picked = roundRobin(listOf(live, titles, events)).distinctBy { it.key }.take(max)
        if (picked.isNotEmpty()) return picked
        return listOfNotNull((favorites.firstOrNull() ?: channels.firstOrNull())?.let { HomeItem.Live(it) })
    }

    private fun <T> interleave(first: List<T>, second: List<T>): List<T> = roundRobin(listOf(first, second))

    private fun <T> roundRobin(lists: List<List<T>>): List<T> {
        val result = ArrayList<T>()
        val longest = lists.maxOfOrNull { it.size } ?: 0
        for (index in 0 until longest) lists.forEach { list -> list.getOrNull(index)?.let(result::add) }
        return result
    }
}
