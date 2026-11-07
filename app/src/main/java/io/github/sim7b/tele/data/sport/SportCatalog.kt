package io.github.sim7b.tele.data.sport

/**
 * A sport, a country, a competition or a team to follow: its [name] shown, the other ways a guide writes it
 * ([aliases], searched but not shown), and what it holds ([children]). Following one follows what it holds.
 */
data class SportTopic(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val children: List<SportTopic> = emptyList()
) {
    /** Every way to write it and what it holds (the words searched in the guide). */
    fun keywords(): List<String> = listOf(name) + aliases + children.flatMap { it.keywords() }
}

/**
 * The sports offered to follow, by sport, then country or competition, then team. Each entry is stored as
 * "@id" with the followed words (see [SportEvents]); the names and nicknames of the guides are its aliases.
 */
object SportCatalog {

    private fun topic(id: String, name: String, vararg aliases: String, children: List<SportTopic> = emptyList()) =
        SportTopic(id, name, aliases.toList(), children)

    private fun teams(prefix: String, vararg teams: Pair<String, List<String>>): List<SportTopic> =
        teams.map { (name, aliases) -> SportTopic("$prefix/${slug(name)}", name, aliases) }

    private fun slug(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    val topics: List<SportTopic> = listOf(
        topic(
            "football", "Football", "Foot", "Soccer",
            children = listOf(
                topic(
                    "football/france", "France",
                    children = listOf(
                        topic(
                            "football/france/ligue1", "Ligue 1", "L1", "Ligue 1 McDonald's",
                            children = teams(
                                "football/france/ligue1",
                                "Paris Saint-Germain" to listOf("PSG", "Paris SG", "Paris-SG"),
                                "Olympique de Marseille" to listOf("OM", "Marseille"),
                                "Olympique Lyonnais" to listOf("OL", "Lyon"),
                                "AS Monaco" to listOf("Monaco", "ASM"),
                                "LOSC Lille" to listOf("LOSC", "Lille"),
                                "OGC Nice" to listOf("Nice", "OGCN"),
                                "RC Lens" to listOf("Lens", "RCL"),
                                "Stade Rennais" to listOf("SRFC", "Rennes"),
                                "RC Strasbourg" to listOf("Strasbourg", "RCSA"),
                                "FC Nantes" to listOf("Nantes", "FCN"),
                                "Toulouse FC" to listOf("TFC", "Toulouse"),
                                "Stade Brestois" to listOf("Brest", "SB29"),
                                "AJ Auxerre" to listOf("Auxerre", "AJA"),
                                "Angers SCO" to listOf("Angers", "SCO"),
                                "Le Havre AC" to listOf("Le Havre", "HAC"),
                                "FC Lorient" to listOf("Lorient", "FCL"),
                                "FC Metz" to listOf("Metz"),
                                "Paris FC" to listOf("PFC")
                            )
                        ),
                        topic("football/france/ligue2", "Ligue 2", "L2", "Ligue 2 BKT"),
                        topic("football/france/cdf", "Coupe de France"),
                        topic("football/france/d1", "Arkema Première Ligue", "D1 Arkema", "Première Ligue"),
                        topic("football/france/team", "Équipe de France", "Les Bleus", "Bleus", "Les Bleues")
                    )
                ),
                topic(
                    "football/england", "Angleterre",
                    children = listOf(
                        topic(
                            "football/england/pl", "Premier League", "EPL",
                            children = teams(
                                "football/england/pl",
                                "Arsenal" to listOf("Gunners"),
                                "Chelsea" to emptyList(),
                                "Liverpool" to listOf("Reds"),
                                "Manchester City" to listOf("Man City"),
                                "Manchester United" to listOf("Man United", "Man Utd"),
                                "Tottenham" to listOf("Spurs"),
                                "Newcastle" to emptyList(),
                                "Aston Villa" to emptyList()
                            )
                        ),
                        topic("football/england/facup", "FA Cup")
                    )
                ),
                topic(
                    "football/spain", "Espagne",
                    children = listOf(
                        topic(
                            "football/spain/liga", "Liga", "LaLiga", "La Liga",
                            children = teams(
                                "football/spain/liga",
                                "Real Madrid" to listOf("Real"),
                                "FC Barcelone" to listOf("Barça", "Barca", "Barcelone", "Barcelona"),
                                "Atlético de Madrid" to listOf("Atletico", "Atlético Madrid")
                            )
                        )
                    )
                ),
                topic(
                    "football/italy", "Italie",
                    children = listOf(
                        topic(
                            "football/italy/seriea", "Serie A",
                            children = teams(
                                "football/italy/seriea",
                                "Juventus" to listOf("Juve"),
                                "Inter Milan" to listOf("Inter", "Internazionale"),
                                "AC Milan" to listOf("Milan AC"),
                                "Naples" to listOf("Napoli"),
                                "AS Roma" to listOf("Roma")
                            )
                        )
                    )
                ),
                topic(
                    "football/germany", "Allemagne",
                    children = listOf(
                        topic(
                            "football/germany/bundesliga", "Bundesliga",
                            children = teams(
                                "football/germany/bundesliga",
                                "Bayern Munich" to listOf("Bayern"),
                                "Borussia Dortmund" to listOf("Dortmund", "BVB"),
                                "Bayer Leverkusen" to listOf("Leverkusen")
                            )
                        )
                    )
                ),
                topic(
                    "football/europe", "Coupes d'Europe",
                    children = listOf(
                        topic("football/europe/ucl", "Ligue des champions", "Champions League", "LDC", "UCL", "C1"),
                        topic("football/europe/uel", "Ligue Europa", "Europa League", "UEL", "C3"),
                        topic("football/europe/uecl", "Ligue Conférence", "Conference League", "UECL", "C4")
                    )
                ),
                topic(
                    "football/international", "Sélections",
                    children = listOf(
                        topic("football/international/worldcup", "Coupe du monde", "World Cup", "Mondial"),
                        topic("football/international/euro", "Euro", "Championnat d'Europe", "UEFA Euro"),
                        topic("football/international/nations", "Ligue des nations", "Nations League"),
                        topic("football/international/can", "Coupe d'Afrique des nations", "CAN", "AFCON")
                    )
                )
            )
        ),
        topic(
            "rugby", "Rugby",
            children = listOf(
                topic(
                    "rugby/top14", "Top 14",
                    children = teams(
                        "rugby/top14",
                        "Stade Toulousain" to listOf("Toulouse"),
                        "Union Bordeaux-Bègles" to listOf("UBB", "Bordeaux-Bègles", "Bordeaux"),
                        "RC Toulon" to listOf("RCT", "Toulon"),
                        "ASM Clermont" to listOf("Clermont", "ASM Clermont Auvergne"),
                        "Stade Rochelais" to listOf("La Rochelle"),
                        "Racing 92" to listOf("Racing"),
                        "Stade Français" to listOf("Stade Français Paris"),
                        "Castres Olympique" to listOf("Castres"),
                        "Aviron Bayonnais" to listOf("Bayonne"),
                        "Section Paloise" to listOf("Pau"),
                        "Lyon OU" to listOf("LOU", "Lyon"),
                        "Montpellier HR" to listOf("MHR", "Montpellier"),
                        "USA Perpignan" to listOf("USAP", "Perpignan"),
                        "US Montauban" to listOf("Montauban", "USM")
                    )
                ),
                topic("rugby/prod2", "Pro D2"),
                topic("rugby/sixnations", "Tournoi des Six Nations", "Six Nations", "6 Nations"),
                topic("rugby/championscup", "Champions Cup", "Coupe des champions"),
                topic("rugby/worldcup", "Coupe du monde de rugby", "Rugby World Cup"),
                topic("rugby/xv", "XV de France", "France XV")
            )
        ),
        topic(
            "motorsport", "Sports mécaniques",
            children = listOf(
                topic("motorsport/f1", "Formule 1", "F1", "Formula 1", "Formula One"),
                topic("motorsport/motogp", "MotoGP", "Moto GP", "Moto2", "Moto3"),
                topic("motorsport/wrc", "Rallye WRC", "WRC", "Championnat du monde des rallyes"),
                topic("motorsport/lemans", "24 Heures du Mans", "24 h du Mans", "Le Mans"),
                topic("motorsport/formulae", "Formule E", "Formula E"),
                topic("motorsport/dakar", "Dakar", "Rallye Dakar")
            )
        ),
        topic(
            "tennis", "Tennis",
            children = listOf(
                topic("tennis/rg", "Roland-Garros", "Roland Garros", "French Open"),
                topic("tennis/wimbledon", "Wimbledon"),
                topic("tennis/usopen", "US Open"),
                topic("tennis/aus", "Open d'Australie", "Australian Open"),
                topic("tennis/atp", "ATP", "Masters 1000"),
                topic("tennis/wta", "WTA"),
                topic("tennis/davis", "Coupe Davis", "Davis Cup")
            )
        ),
        topic(
            "cycling", "Cyclisme", "Vélo",
            children = listOf(
                topic("cycling/tdf", "Tour de France"),
                topic("cycling/giro", "Giro", "Tour d'Italie"),
                topic("cycling/vuelta", "Vuelta", "Tour d'Espagne"),
                topic("cycling/roubaix", "Paris-Roubaix"),
                topic("cycling/classics", "Classiques", "Milan-San Remo", "Tour des Flandres", "Liège-Bastogne-Liège")
            )
        ),
        topic(
            "basketball", "Basket", "Basketball", "Basket-ball",
            children = listOf(
                topic("basketball/nba", "NBA"),
                topic("basketball/euroleague", "EuroLeague", "Euroligue"),
                topic("basketball/betclic", "Betclic Élite", "Pro A", "LNB"),
                topic("basketball/wnba", "WNBA")
            )
        ),
        topic(
            "handball", "Handball", "Hand",
            children = listOf(
                topic("handball/starligue", "Starligue", "Liqui Moly Starligue"),
                topic("handball/ehf", "EHF Champions League")
            )
        ),
        topic(
            "usports", "Sports américains",
            children = listOf(
                topic("usports/nfl", "NFL", "Super Bowl", "Football américain"),
                topic("usports/nhl", "NHL", "Hockey sur glace"),
                topic("usports/mlb", "MLB", "Baseball")
            )
        ),
        topic(
            "combat", "Sports de combat",
            children = listOf(
                topic("combat/ufc", "UFC", "MMA"),
                topic("combat/boxing", "Boxe", "Boxing")
            )
        ),
        topic(
            "golf", "Golf",
            children = listOf(
                topic("golf/ryder", "Ryder Cup"),
                topic("golf/pga", "PGA Tour", "PGA"),
                topic("golf/masters", "Masters d'Augusta", "The Masters")
            )
        ),
        topic("olympics", "Jeux olympiques", "JO", "Olympics", "Jeux paralympiques", "Paralympiques"),
        topic("athletics", "Athlétisme", "Athletics", "Diamond League", "Ligue de diamant"),
        topic("swimming", "Natation", "Swimming"),
        topic("ski", "Ski", "Coupe du monde de ski", "Biathlon"),
        topic("darts", "Fléchettes", "Darts")
    )

    private val byId: Map<String, SportTopic> by lazy {
        val map = HashMap<String, SportTopic>()
        fun add(topic: SportTopic) {
            map[topic.id] = topic
            topic.children.forEach(::add)
        }
        topics.forEach(::add)
        map
    }

    fun find(id: String): SportTopic? = byId[id]

    /** Entry stored for a topic of the catalog. */
    fun entry(topic: SportTopic): String = "@" + topic.id

    /** The topic of a stored entry ("@football/france/ligue1"), null for words typed by the user. */
    fun topicOf(entry: String): SportTopic? = if (entry.startsWith("@")) byId[entry.removePrefix("@")] else null

    /** The words searched for the followed entries: those of the topics (and what they hold), the typed ones as they are. */
    fun keywords(entries: List<String>): List<String> = entries.flatMap { entry ->
        if (entry.startsWith("@")) topicOf(entry)?.keywords().orEmpty() else listOf(entry)
    }.distinct()
}
