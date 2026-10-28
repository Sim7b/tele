package io.github.sim7b.tele.data.vod

/**
 * The library: entries of every source grouped into titles, whatever the playlist categories.
 * Two entries are the same title when they share a TMDB id, or a title and a year; an entry without
 * a TMDB id joins the title that has one for the same title and year.
 */
object VodLibrary {

    fun build(entries: List<VodEntry>): List<VodTitle> {
        // Title and year -> TMDB group key, from the entries that know their TMDB id
        val tmdbByTitle = HashMap<String, String>()
        entries.forEach { entry ->
            val tmdb = entry.tmdbId?.takeIf { it.isNotBlank() && it != "0" } ?: return@forEach
            tmdbByTitle.putIfAbsent(VodNames.groupKey(entry.kind, entry.title, entry.year, null), VodNames.groupKey(entry.kind, entry.title, entry.year, tmdb))
        }

        val groups = LinkedHashMap<String, MutableList<VodEntry>>()
        entries.forEach { entry ->
            val own = VodNames.groupKey(entry.kind, entry.title, entry.year, entry.tmdbId)
            val key = if (entry.tmdbId.isNullOrBlank() || entry.tmdbId == "0") {
                tmdbByTitle[own] ?: own
            } else {
                own
            }
            groups.getOrPut(key) { ArrayList(1) }.add(entry)
        }
        // A movie without a year joins the only movie of the same title that has one
        val byTitle = HashMap<String, MutableList<String>>()
        groups.forEach { (key, group) ->
            val first = group.first()
            if (first.kind == VodKind.MOVIE && group.any { it.year != null }) {
                byTitle.getOrPut(VodNames.key(first.title)) { ArrayList(1) }.add(key)
            }
        }
        groups.keys.toList().forEach { key ->
            val group = groups[key] ?: return@forEach
            val first = group.first()
            if (first.kind != VodKind.MOVIE || group.any { it.year != null }) return@forEach
            val target = byTitle[VodNames.key(first.title)]?.singleOrNull() ?: return@forEach
            groups.remove(key)
            groups[target]?.addAll(group)
        }
        return groups.map { (key, group) -> merge(key, group) }
    }

    private fun merge(key: String, group: List<VodEntry>): VodTitle {
        val first = group.first()
        // The most complete entry gives the texts; images from the first entry that has them
        val best = group.maxBy { score(it) }
        return VodTitle(
            key = key,
            kind = first.kind,
            title = best.title,
            year = group.firstNotNullOfOrNull { it.year },
            posterUrl = best.posterUrl ?: group.firstNotNullOfOrNull { it.posterUrl },
            backdropUrl = best.backdropUrl ?: group.firstNotNullOfOrNull { it.backdropUrl },
            plot = best.plot ?: group.firstNotNullOfOrNull { it.plot },
            genres = best.genres.ifEmpty { group.firstOrNull { it.genres.isNotEmpty() }?.genres.orEmpty() },
            rating = group.firstNotNullOfOrNull { it.rating },
            tmdbId = group.firstNotNullOfOrNull { it.tmdbId?.takeIf { id -> id.isNotBlank() && id != "0" } },
            imdbId = group.firstNotNullOfOrNull { it.imdbId?.takeIf { id -> id.isNotBlank() } },
            addedAt = group.mapNotNull { it.addedAt }.maxOrNull(),
            entries = group
        )
    }

    private fun score(entry: VodEntry): Int =
        (if (entry.posterUrl != null) 4 else 0) + (if (entry.plot != null) 2 else 0) + (if (entry.genres.isNotEmpty()) 1 else 0)

    /** Genres of the library, most frequent first. */
    fun topGenres(titles: List<VodTitle>, limit: Int): List<String> =
        titles.flatMap { it.genres }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }

    private const val MAX_LIST_PLOT = 320

    /**
     * Summary kept in a catalog (the hero of the home shows 3 lines): the full one comes with the sheet.
     * Catalogs hold tens of thousands of titles.
     */
    fun shortPlot(plot: String?): String? {
        val text = plot?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (text.length <= MAX_LIST_PLOT) text else text.take(MAX_LIST_PLOT).substringBeforeLast(' ') + "…"
    }

    /** Genres of a provider field: "Action, Drame / Thriller" -> [Action, Drame, Thriller]. */
    fun splitGenres(value: String?): List<String> =
        value?.split(',', '/', '|', ';')
            ?.map { it.trim().replaceFirstChar { c -> c.titlecase() } }
            ?.filter { it.isNotEmpty() && it.length <= 40 }
            ?.distinct()
            .orEmpty()

    /** Rating on 10 from a provider value ("7.4", "74%", "3.7" on 5 when [fiveBased]). */
    fun rating(value: String?, fiveBased: Boolean = false): Double? {
        val number = value?.trim()?.removeSuffix("%")?.replace(',', '.')?.toDoubleOrNull() ?: return null
        val onTen = when {
            fiveBased -> number * 2
            number > 10 -> number / 10
            else -> number
        }
        return onTen.takeIf { it > 0 && it <= 10 }
    }
}
