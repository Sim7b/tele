package io.github.sim7b.tele.data.bouquet

import androidx.annotation.StringRes
import io.github.sim7b.tele.R
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Categories of the reference bouquets, close to the ones of the Canal+ offer. */
enum class BouquetCategory(@param:StringRes val labelRes: Int, vararg synonyms: String) {
    TNT(R.string.bouquet_tnt, "tnt", "generaliste", "chaine tnt"),
    NEWS(R.string.bouquet_news, "info", "infos", "news", "actualites", "information", "info continue"),
    SPORT(R.string.bouquet_sport, "sport", "sportif"),
    CINEMA(R.string.bouquet_cinema, "cinema", "films", "movies", "cine"),
    SERIES(R.string.bouquet_series, "series", "divertissement", "series et divertissement", "entertainment", "shows"),
    KIDS(R.string.bouquet_kids, "jeunesse", "enfants", "kids", "dessins animes", "animation", "cartoon", "junior"),
    DOCUMENTARY(R.string.bouquet_documentary, "decouverte", "documentaires", "docu", "documentary", "nature", "histoire"),
    MUSIC(R.string.bouquet_music, "musique", "music", "clip"),

    // National channels of a bouquet, and the French free-to-air ones seen from another country.
    // No synonyms: a country name is removed from the category keys of that country (empty key)
    FRANCE(R.string.bouquet_category_france),
    BELGIUM(R.string.bouquet_category_belgium),
    SWITZERLAND(R.string.bouquet_category_switzerland),
    CANADA(R.string.bouquet_category_canada);

    /** Provider category names that mean this one (compared as category keys, see [ChannelNames.categoryKey]). */
    val synonyms: List<String> = synonyms.toList()
}

/** A channel of a reference bouquet: its proper name, its usual number and its known spellings. */
class BouquetChannel(val name: String, val category: BouquetCategory, val number: Int?, val aliases: List<String>)

/**
 * Reference channel list used to recognize provider channels: exact name or known spelling first, then
 * a close name (same digits, small edit distance), so that "TF1 FHD", "FR | TF1 HEVC" and "TF1 ᴴᴰ" are TF1.
 */
class Bouquet(val channels: List<BouquetChannel>, val names: ChannelNames) {

    // Category keys of the synonyms, normalized like the provider categories ("News" and "news" alike)
    private val categoryByKey: Map<String, BouquetCategory> = buildMap {
        BouquetCategory.entries.forEach { category -> 
            category.synonyms.forEach { synonym ->
                names.categoryKey(synonym).takeIf { it.isNotEmpty() }?.let { putIfAbsent(it, category) }
            }
        }
    }

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
        return categoryByKey[names.categoryKey(providerCategory)]
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

        fun france(locale: Locale = Locale.FRANCE): Bouquet = requireNotNull(of(BouquetPreference.FRANCE, locale))

        /**
         * Bouquet of [preference], null for none. Country words of the user's [locale] and of the bouquet's
         * languages are ignored in the names ("BE | La Une", "FR: TF1", "CH - RTS 1").
         */
        fun of(preference: BouquetPreference, locale: Locale): Bouquet? {
            val channels = when (preference) {
                BouquetPreference.FRANCE -> FranceBouquet.channels
                BouquetPreference.BELGIUM -> NeighborBouquets.belgium
                BouquetPreference.SWITZERLAND -> NeighborBouquets.switzerland
                BouquetPreference.CANADA -> NeighborBouquets.canada
                BouquetPreference.NONE -> return null
            }
            val tokens = (listOf(locale, Locale.FRANCE) + preference.locales).flatMapTo(LinkedHashSet()) { ChannelNames.homeTokens(it) }
            return Bouquet(channels, ChannelNames(tokens))
        }
    }
}

/** Reference bouquet chosen in the settings. */
enum class BouquetPreference(
    @param:StringRes val labelRes: Int,
    @param:StringRes val descriptionRes: Int,
    /** Languages and country of the bouquet's channels. */
    val locales: List<Locale> = emptyList()
) {
    FRANCE(R.string.bouquet_france, R.string.bouquet_france_description, listOf(Locale.FRANCE)),
    BELGIUM(R.string.bouquet_belgium, R.string.bouquet_belgium_description, listOf(Locale.forLanguageTag("fr-BE"), Locale.forLanguageTag("nl-BE"))),
    SWITZERLAND(
        R.string.bouquet_switzerland,
        R.string.bouquet_switzerland_description,
        listOf(Locale.forLanguageTag("fr-CH"), Locale.forLanguageTag("de-CH"), Locale.forLanguageTag("it-CH"))
    ),
    CANADA(R.string.bouquet_canada, R.string.bouquet_canada_description, listOf(Locale.CANADA_FRENCH, Locale.CANADA)),
    NONE(R.string.bouquet_none, R.string.bouquet_none_description)
}
