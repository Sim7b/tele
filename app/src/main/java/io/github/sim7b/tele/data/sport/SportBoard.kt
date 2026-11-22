package io.github.sim7b.tele.data.sport

import io.github.sim7b.tele.data.search.ChannelSearch
import org.json.JSONArray
import org.json.JSONObject

/** A team or a driver in a match, a standing. */
data class SportSide(val name: String, val short: String?, val logo: String?, val score: String? = null, val form: String? = null)

/** A match, a race or a session of a race weekend of a competition. */
data class SportFixture(
    val id: String,
    val startMillis: Long,
    val state: State,
    val name: String?,
    val home: SportSide? = null,
    val away: SportSide? = null,
    // A session of a race weekend ("FP1", "Qual", "Race")
    val session: String? = null,
    val venue: String? = null
) {
    enum class State { UPCOMING, LIVE, FINISHED }
}

/** A row of a standing. */
data class SportStanding(
    val rank: Int,
    val name: String,
    val short: String?,
    val logo: String?,
    val played: String?,
    val points: String?,
    val record: String?,
    val diff: String?
)

/** A table of a competition (one, or one per conference, the drivers and the constructors). */
data class SportTable(val name: String?, val rows: List<SportStanding>)

/** A followed competition: its calendar and its standings, from the Télé API plugin. */
data class SportCompetition(
    val topic: String,
    val name: String,
    val logo: String?,
    val fixtures: List<SportFixture>,
    val tables: List<SportTable>
)

/**
 * The Sport section: which competitions to ask for the followed teams and competitions, the answer of the plugin
 * read as untrusted, and the guide programs showing each match.
 */
object SportBoard {

    /** The competitions the plugin reads (sport.py), in the catalog order. */
    val SUPPORTED: Set<String> = linkedSetOf(
        "football/france/ligue1", "football/france/ligue2", "football/france/cdf", "football/france/d1",
        "football/england/pl", "football/england/facup", "football/spain/liga", "football/italy/seriea",
        "football/germany/bundesliga", "football/europe/ucl", "football/europe/uel", "football/europe/uecl",
        "football/international/worldcup", "football/international/euro", "football/international/nations",
        "football/international/can", "rugby/top14", "rugby/prod2", "rugby/sixnations", "rugby/championscup",
        "rugby/worldcup", "motorsport/f1", "motorsport/motogp", "basketball/nba", "basketball/wnba",
        "basketball/betclic", "handball/starligue", "usports/nfl", "usports/nhl", "usports/mlb", "combat/ufc", "golf/pga"
    )

    const val MAX_COMPETITIONS = 8
    private const val MAX_FIXTURES = 60
    private const val MAX_ROWS = 60

    /**
     * Competitions to show for the [followed] entries of the user ("@football/france/ligue1/paris-saint-germain"):
     * a competition, the one of a team, or the competitions of a sport or a country followed whole. Typed words
     * (not from the catalog) give none.
     */
    fun competitionsFor(followed: List<String>): List<String> {
        val found = LinkedHashSet<String>()
        followed.forEach { entry ->
            val id = SportCatalog.topicOf(entry)?.id ?: return@forEach
            val own = generateSequence(id) { it.substringBeforeLast('/', "").takeIf { parent -> parent.isNotEmpty() } }
                .firstOrNull { it in SUPPORTED }
            if (own != null) found += own else found += SUPPORTED.filter { it.startsWith("$id/") }
        }
        return found.take(MAX_COMPETITIONS)
    }

    /** The words of the followed teams ([SportCatalog] names and nicknames), to highlight them. */
    fun followedTeams(followed: List<String>): List<List<String>> =
        followed.mapNotNull { SportCatalog.topicOf(it) }
            .filter { topic -> topic.children.isEmpty() && topic.id.substringBeforeLast('/') in SUPPORTED }
            .flatMap { topic -> (listOf(topic.name) + topic.aliases).map { ChannelSearch.words(it) } }
            .filter { it.isNotEmpty() }

    /** [name] (a team of a standing or a match) is one of the [teams] followed. */
    fun isFollowed(teams: List<List<String>>, vararg names: String?): Boolean {
        if (teams.isEmpty()) return false
        return names.filterNotNull().any { name ->
            val words = ChannelSearch.words(name).toHashSet()
            teams.any { team -> team.all { it in words } }
        }
    }

    fun fromJson(json: JSONObject?): List<SportCompetition> {
        val array = json?.optJSONArray("competitions") ?: return emptyList()
        return objects(array).take(MAX_COMPETITIONS).mapNotNull { item ->
            val topic = text(item, "topic") ?: return@mapNotNull null
            SportCompetition(
                topic = topic,
                name = text(item, "name") ?: topic.substringAfterLast('/'),
                logo = url(item, "logo"),
                fixtures = objects(item.optJSONArray("events")).take(MAX_FIXTURES).mapNotNull(::fixture).sortedBy { it.startMillis },
                tables = objects(item.optJSONArray("standings")).take(6).mapNotNull { table ->
                    val rows = objects(table.optJSONArray("rows")).take(MAX_ROWS).mapNotNull(::standing)
                    if (rows.isEmpty()) null else SportTable(text(table, "name"), rows)
                }
            )
        }
    }

    private fun fixture(item: JSONObject): SportFixture? {
        val start = item.optLong("start", 0L).takeIf { it > 0 } ?: return null
        return SportFixture(
            id = text(item, "id") ?: start.toString(),
            startMillis = start,
            state = when (item.optString("state")) {
                "in" -> SportFixture.State.LIVE
                "post" -> SportFixture.State.FINISHED
                else -> SportFixture.State.UPCOMING
            },
            name = text(item, "name", 120),
            home = item.optJSONObject("home")?.let(::side),
            away = item.optJSONObject("away")?.let(::side),
            session = text(item, "session", 12),
            venue = text(item, "venue")
        )
    }

    private fun side(item: JSONObject): SportSide? {
        val name = text(item, "name") ?: return null
        return SportSide(name, text(item, "short", 12), url(item, "logo"), text(item, "score", 12), text(item, "form", 10))
    }

    private fun standing(item: JSONObject): SportStanding? {
        val name = text(item, "name") ?: return null
        return SportStanding(
            rank = item.optInt("rank", 0).coerceIn(0, 999),
            name = name,
            short = text(item, "short", 12),
            logo = url(item, "logo"),
            played = text(item, "played", 12),
            points = text(item, "points", 12),
            record = text(item, "record", 20),
            diff = text(item, "diff", 12)
        )
    }

    private fun objects(array: JSONArray?): List<JSONObject> =
        (0 until minOf(array?.length() ?: 0, 500)).mapNotNull { array?.optJSONObject(it) }

    private fun text(item: JSONObject, key: String, limit: Int = 80): String? =
        item.optString(key).takeIf { !item.isNull(key) && it.isNotBlank() }?.trim()?.take(limit)

    // Only https pictures (shown by the image loader of the app)
    private fun url(item: JSONObject, key: String): String? = text(item, key, 400)?.takeIf { it.startsWith("https://") }

    /**
     * The guide program showing [fixture]: among the programs of the followed sport ([events]), one starting
     * within [MATCH_WINDOW_MS] of it, whose title names one of its teams (any program of that time for a race).
     */
    fun programFor(fixture: SportFixture, events: List<SportEvent>): SportEvent? {
        val near = events.filter { kotlin.math.abs(it.program.startMillis - fixture.startMillis) <= MATCH_WINDOW_MS }
        if (near.isEmpty()) return null
        val teams = listOfNotNull(fixture.home, fixture.away)
        if (teams.isEmpty()) return near.minBy { kotlin.math.abs(it.program.startMillis - fixture.startMillis) }
        val names = teams.flatMap { side -> listOfNotNull(side.name, side.short) }
            .map { name -> ChannelSearch.words(name).filter { it.length >= 3 && it !in COMMON_WORDS } }
            .filter { it.isNotEmpty() }
        return near.firstOrNull { event ->
            val title = (ChannelSearch.words(event.program.title) + ChannelSearch.words(event.program.description.orEmpty())).toHashSet()
            names.any { words -> words.any { it in title } }
        }
    }

    /** The logos of the two teams of the match [event] shows, when a followed competition lists it; else none. */
    fun logosFor(event: SportEvent, competitions: List<SportCompetition>): List<String> {
        val fixture = competitions.asSequence().flatMap { it.fixtures.asSequence() }
            .firstOrNull { it.home?.logo != null && it.away?.logo != null && programFor(it, listOf(event)) != null }
            ?: return emptyList()
        return listOfNotNull(fixture.home?.logo, fixture.away?.logo)
    }

    const val MATCH_WINDOW_MS = 45 * 60_000L

    // Words of team names too common to tell a team ("FC Nantes": "nantes" tells it, "fc" does not)
    private val COMMON_WORDS = setOf("fc", "ac", "as", "sc", "rc", "club", "olympique", "stade", "real", "united", "city", "sporting", "racing", "de", "la", "le")
}
