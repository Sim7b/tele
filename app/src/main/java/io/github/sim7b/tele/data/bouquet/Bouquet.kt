package io.github.sim7b.tele.data.bouquet

import androidx.annotation.StringRes
import io.github.sim7b.tele.R
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Categories of the reference bouquets, close to the ones of the Canal+ offer. */
enum class BouquetCategory(@param:StringRes val labelRes: Int, vararg synonyms: String) {
    TNT(R.string.bouquet_tnt, "tnt", "generaliste", "chainetnt", "national", "general"),
    NEWS(R.string.bouquet_news, "info", "news", "actualite", "information", "infocontinue"),
    SPORT(R.string.bouquet_sport, "sport", "sportif"),
    CINEMA(R.string.bouquet_cinema, "cinema", "film", "movie", "cine"),
    SERIES(R.string.bouquet_series, "serie", "divertissement", "serieetdivertissement", "entertainment", "show"),
    KIDS(R.string.bouquet_kids, "jeunesse", "enfant", "kid", "dessinanime", "cartoon", "junior"),
    DOCUMENTARY(R.string.bouquet_documentary, "decouverte", "documentaire", "docu", "documentary", "nature", "histoire"),
    MUSIC(R.string.bouquet_music, "musique", "music", "clip");

    /** Category keys (see [ChannelNames.categoryKey]) of provider categories that mean this one. */
    val synonymKeys: Set<String> = synonyms.toSet()
}

/** A channel of a reference bouquet: its proper name, its usual number and its known spellings. */
class BouquetChannel(val name: String, val category: BouquetCategory, val number: Int?, val aliases: List<String>)

/**
 * Reference channel list used to recognize provider channels: exact name or known spelling first, then
 * a close name (same digits, small edit distance), so that "TF1 FHD", "FR | TF1 HEVC" and "TF1 ᴴᴰ" are TF1.
 */
class Bouquet(val channels: List<BouquetChannel>, val names: ChannelNames) {

    private val byKey: Map<String, BouquetChannel> = buildMap {
        channels.forEach { channel ->
            (listOf(channel.name) + channel.aliases).forEach { alias -> putIfAbsent(names.channelKey(alias), channel) }
        }
    }

    // Results by provider name: playlists repeat names, and the guide refresh republishes every 30 s
    private val cache = ConcurrentHashMap<String, Any>()

    fun match(channelName: String): BouquetChannel? {
        val cached = cache[channelName]
        if (cached != null) return cached as? BouquetChannel
        val key = names.channelKey(channelName)
        val found = byKey[key] ?: closest(key)
        cache[channelName] = found ?: NO_MATCH
        return found
    }

    private fun closest(key: String): BouquetChannel? {
        if (key.length < MIN_FUZZY_LENGTH) return null
        val digits = key.filter { it.isDigit() }
        var best: BouquetChannel? = null
        var bestScore = 0.0
        for ((candidate, channel) in byKey) {
            // Never across numbers (France 2 / France 3) nor across first letters
            if (candidate.firstOrNull() != key.first() || candidate.filter { it.isDigit() } != digits) continue
            if (kotlin.math.abs(candidate.length - key.length) > 2) continue
            val score = similarity(key, candidate)
            if (score > bestScore) {
                bestScore = score
                best = channel
            }
        }
        return best.takeIf { bestScore >= MIN_SIMILARITY }
    }

    /** Category of a provider category name, when it means one of the bouquet ones ("FR | Sports" -> SPORT). */
    fun categoryOf(providerCategory: String): BouquetCategory? {
        val key = names.categoryKey(providerCategory)
        return BouquetCategory.entries.firstOrNull { key in it.synonymKeys }
    }

    companion object {
        private val NO_MATCH = Any()
        private const val MIN_FUZZY_LENGTH = 5
        private const val MIN_SIMILARITY = 0.85

        /** 1 - edit distance / length of the longest name. */
        internal fun similarity(a: String, b: String): Double {
            if (a == b) return 1.0
            var previous = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)
            for (i in 1..a.length) {
                current[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
                }
                val swap = previous
                previous = current
                current = swap
            }
            return 1.0 - previous[b.length].toDouble() / maxOf(a.length, b.length)
        }

        fun france(locale: Locale = Locale.FRANCE): Bouquet =
            Bouquet(FranceBouquet.channels, ChannelNames(ChannelNames.homeTokens(locale) + ChannelNames.homeTokens(Locale.FRANCE)))
    }
}

/** Reference bouquet chosen in the settings. */
enum class BouquetPreference(@param:StringRes val labelRes: Int, @param:StringRes val descriptionRes: Int) {
    FRANCE(R.string.bouquet_france, R.string.bouquet_france_description),
    NONE(R.string.bouquet_none, R.string.bouquet_none_description)
}
