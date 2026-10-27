package io.github.sim7b.tele.data.model

/**
 * Channel lists of the screens, derived from the displayed channels and the user's choices. Built off the
 * main thread: playlists may hold tens of thousands of channels, and the display changes every 30 s.
 *
 * - [unhidden]: channels not individually hidden (a grouped channel is hidden once all its versions are);
 *   favorites are taken from them, even if their category is hidden;
 * - [visible]: channels of the grid, the panel and zapping, without the hidden categories;
 * - [favorites]: the top tiles, see [pickFavorites];
 * - [categoryCounts]: categories and channel counts, in the chosen order (for the settings).
 */
class HomeLists(
    val unhidden: List<Channel>,
    val visible: List<Channel>,
    val favorites: List<Channel>,
    val categoryCounts: List<Pair<String, Int>>
) {
    companion object {
        const val FAVORITE_SLOTS = 8

        val EMPTY = HomeLists(emptyList(), emptyList(), emptyList(), emptyList())

        fun build(
            display: List<Channel>,
            hiddenIds: Set<String>,
            hiddenGroups: Set<String>,
            favoriteIds: List<String>,
            categoryOrder: List<String>
        ): HomeLists {
            val unhidden = display.filterNot { channel -> channel.allIds.all { it in hiddenIds } }
            val visible = unhidden.filterNot { it.group in hiddenGroups }
            val counts = unhidden.groupingBy { it.group }.eachCount()
            return HomeLists(
                unhidden = unhidden,
                visible = visible,
                favorites = pickFavorites(unhidden, visible, favoriteIds),
                categoryCounts = Categories.ordered(counts.keys.toList(), categoryOrder).map { it to counts.getValue(it) }
            )
        }

        /**
         * The top tiles: the favorites in the chosen order (even if their category is hidden),
         * filled up with the first visible channels.
         */
        fun pickFavorites(allChannels: List<Channel>, visibleChannels: List<Channel>, favoriteIds: List<String>): List<Channel> {
            // Only the favorites are indexed: the lists may hold tens of thousands of channels
            val favoriteSet = favoriteIds.toHashSet()
            val byId = allChannels.filter { it.id in favoriteSet }.associateBy { it.id }
            val favorites = favoriteIds.mapNotNull { byId[it] }
            val filler = visibleChannels.asSequence().filterNot { it.id in favoriteSet }.take(FAVORITE_SLOTS)
            return (favorites + filler).take(FAVORITE_SLOTS)
        }
    }
}
