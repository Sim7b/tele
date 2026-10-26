package io.github.sim7b.tele.data.bouquet

import io.github.sim7b.tele.data.bouquet.BouquetCategory.CINEMA
import io.github.sim7b.tele.data.bouquet.BouquetCategory.DOCUMENTARY
import io.github.sim7b.tele.data.bouquet.BouquetCategory.KIDS
import io.github.sim7b.tele.data.bouquet.BouquetCategory.MUSIC
import io.github.sim7b.tele.data.bouquet.BouquetCategory.NEWS
import io.github.sim7b.tele.data.bouquet.BouquetCategory.SERIES
import io.github.sim7b.tele.data.bouquet.BouquetCategory.SPORT
import io.github.sim7b.tele.data.bouquet.BouquetCategory.TNT

/**
 * French reference bouquet: the free-to-air channels (TNT) with their usual numbers, and the channels of
 * the Canal+ offer by theme. Numbers are only given where they are well established; channels whose
 * number changed or that stopped in 2025 (C8, NRJ 12, T18, Novo19, Gulli) keep the provider's number.
 */
internal object FranceBouquet {

    private fun ch(name: String, category: BouquetCategory, number: Int? = null, vararg aliases: String) =
        BouquetChannel(name, category, number, aliases.toList())

    val channels: List<BouquetChannel> = listOf(
        // Free-to-air (TNT)
        ch("TF1", TNT, 1),
        ch("France 2", TNT, 2),
        ch("France 3", TNT, 3, "France 3 National"),
        ch("Canal+", TNT, 4, "Canal Plus", "Canal+ France"),
        ch("France 5", TNT, 5),
        ch("M6", TNT, 6, "M 6"),
        ch("Arte", TNT, 7, "Arte France"),
        ch("C8", TNT, null),
        ch("W9", TNT, 9),
        ch("TMC", TNT, 10),
        ch("TFX", TNT, 11),
        ch("NRJ 12", TNT, null, "NRJ12"),
        ch("LCP", TNT, 13, "LCP Assemblée nationale", "LCP AN", "LCP Public Sénat"),
        ch("Public Sénat", TNT, null),
        ch("France 4", TNT, 14),
        ch("BFMTV", TNT, 15, "BFM TV"),
        ch("CNews", TNT, 16, "C News"),
        ch("CStar", TNT, 17, "C Star"),
        ch("Gulli", TNT, null),
        ch("T18", TNT, null),
        ch("Novo19", TNT, null, "Novo 19"),
        ch("TF1 Séries Films", TNT, 20, "TF1 Series Films", "TF1 SF"),
        ch("L'Équipe", TNT, 21, "L'Equipe", "La Chaîne L'Équipe", "L'Equipe TV", "La chaine L'Equipe"),
        ch("6ter", TNT, 22, "6 ter"),
        ch("RMC Story", TNT, 23),
        ch("RMC Découverte", TNT, 24, "RMC Decouverte"),
        ch("Chérie 25", TNT, 25, "Cherie 25"),
        ch("LCI", TNT, 26, "LCI La Chaîne Info", "La Chaine Info"),
        ch("franceinfo", TNT, 27, "France Info", "franceinfo:", "France Info TV"),

        // Cinema
        ch("Canal+ Cinéma(s)", CINEMA, null, "Canal+ Cinéma", "Canal+ Cinema", "Canal+ Cinémas", "Canal+ Cinemas"),
        ch("Canal+ Grand Écran", CINEMA, null, "Canal+ Grand Ecran"),
        ch("Canal+ Box Office", CINEMA, null),
        ch("Ciné+ Premier", CINEMA, null, "Cine+ Premier", "Ciné Plus Premier"),
        ch("Ciné+ Frisson", CINEMA, null, "Cine+ Frisson"),
        ch("Ciné+ Émotion", CINEMA, null, "Cine+ Emotion", "Ciné+ Emotion"),
        ch("Ciné+ Famiz", CINEMA, null, "Cine+ Famiz", "Ciné+ Family"),
        ch("Ciné+ Classic", CINEMA, null, "Cine+ Classic"),
        ch("Ciné+ Festival", CINEMA, null, "Cine+ Festival"),
        ch("Ciné+ OCS", CINEMA, null, "Cine+ OCS"),
        ch("OCS Max", CINEMA, null),
        ch("OCS Pulp", CINEMA, null),
        ch("OCS Géants", CINEMA, null, "OCS Geants"),
        ch("OCS Choc", CINEMA, null),
        ch("Paramount Channel", CINEMA, null),
        ch("TCM Cinéma", CINEMA, null, "TCM Cinema", "TCM"),
        ch("Action", CINEMA, null),

        // Series and entertainment
        ch("Canal+ Séries", SERIES, null, "Canal+ Series"),
        ch("Série Club", SERIES, null, "Serie Club"),
        ch("13ème Rue", SERIES, null, "13e Rue", "13eme Rue", "13 Rue"),
        ch("Syfy", SERIES, null, "SyFy France"),
        ch("Warner TV", SERIES, null),
        ch("TV Breizh", SERIES, null),
        ch("RTL9", SERIES, null, "RTL 9"),
        ch("AB1", SERIES, null, "AB 1"),
        ch("Polar+", SERIES, null, "Polar Plus"),
        ch("Comédie+", SERIES, null, "Comedie+", "Comédie Plus"),
        ch("Paris Première", SERIES, null, "Paris Premiere"),
        ch("Téva", SERIES, null, "Teva"),
        ch("Game One", SERIES, null),
        ch("Novelas TV", SERIES, null),
        ch("Sundance TV", SERIES, null),
        ch("E!", SERIES, null, "E! Entertainment"),

        // Sport
        ch("Canal+ Sport", SPORT, null, "Canal+ Sports"),
        ch("Canal+ Sport 360", SPORT, null, "Canal+ Sports 360"),
        ch("Canal+ Foot", SPORT, null),
        ch("Canal+ Premier League", SPORT, null),
        ch("Infosport+", SPORT, null, "Infosport Plus", "Info Sport+"),
        ch("Eurosport 1", SPORT, null, "Eurosport"),
        ch("Eurosport 2", SPORT, null),
        ch("beIN Sports 1", SPORT, null, "beIN Sport 1", "beIN Sports"),
        ch("beIN Sports 2", SPORT, null, "beIN Sport 2"),
        ch("beIN Sports 3", SPORT, null, "beIN Sport 3"),
        ch("RMC Sport 1", SPORT, null, "RMC Sport"),
        ch("RMC Sport 2", SPORT, null),
        ch("Golf+", SPORT, null, "Golf Plus"),
        ch("Automoto", SPORT, null, "Automoto La Chaîne"),
        ch("Equidia", SPORT, null),
        ch("Sport en France", SPORT, null),
        ch("DAZN 1", SPORT, null, "DAZN"),

        // Kids
        ch("Canal+ Kids", KIDS, null),
        ch("Disney Channel", KIDS, null),
        ch("Disney Junior", KIDS, null),
        ch("Nickelodeon", KIDS, null, "Nick"),
        ch("Nickelodeon Junior", KIDS, null, "Nick Jr"),
        ch("Cartoon Network", KIDS, null),
        ch("Cartoonito", KIDS, null),
        ch("Boomerang", KIDS, null),
        ch("Boing", KIDS, null),
        ch("Piwi+", KIDS, null, "Piwi Plus"),
        ch("Tiji", KIDS, null),
        ch("Canal J", KIDS, null),
        ch("Télétoon+", KIDS, null, "Teletoon+", "Teletoon Plus"),
        ch("Mangas", KIDS, null),
        ch("J-One", KIDS, null, "J One"),

        // Documentaries and discovery
        ch("Canal+ Docs", DOCUMENTARY, null, "Canal+ Documentaires"),
        ch("Planète+", DOCUMENTARY, null, "Planete+", "Planète Plus"),
        ch("Planète+ Aventure", DOCUMENTARY, null, "Planete+ Aventure", "Planète+ A&E"),
        ch("Planète+ Crime", DOCUMENTARY, null, "Planete+ Crime", "Planète+ CI"),
        ch("National Geographic", DOCUMENTARY, null, "Nat Geo", "National Geographic Channel"),
        ch("Nat Geo Wild", DOCUMENTARY, null, "National Geographic Wild"),
        ch("Histoire TV", DOCUMENTARY, null),
        ch("Toute l'Histoire", DOCUMENTARY, null, "Toute l Histoire"),
        ch("Ushuaïa TV", DOCUMENTARY, null, "Ushuaia TV"),
        ch("Science & Vie TV", DOCUMENTARY, null, "Science et Vie TV"),
        ch("Animaux", DOCUMENTARY, null),
        ch("Discovery Channel", DOCUMENTARY, null, "Discovery"),
        ch("Investigation Discovery", DOCUMENTARY, null, "ID"),
        ch("Seasons", DOCUMENTARY, null),
        ch("Chasse & Pêche", DOCUMENTARY, null, "Chasse et Peche"),
        ch("Trek", DOCUMENTARY, null),

        // News
        ch("France 24", NEWS, null),
        ch("Euronews", NEWS, null),
        ch("CNN International", NEWS, null, "CNN"),
        ch("BBC News", NEWS, null),
        ch("BFM Business", NEWS, null),
        ch("i24news", NEWS, null, "i24 News"),
        ch("TV5Monde", NEWS, null, "TV5 Monde", "TV5"),
        ch("Sky News", NEWS, null),
        ch("Bloomberg", NEWS, null),

        // Music
        ch("MTV", MUSIC, null),
        ch("MTV Hits", MUSIC, null),
        ch("M6 Music", MUSIC, null),
        ch("MCM", MUSIC, null),
        ch("Mezzo", MUSIC, null),
        ch("Trace Urban", MUSIC, null),
        ch("RFM TV", MUSIC, null),
        ch("NRJ Hits", MUSIC, null),
        ch("Melody", MUSIC, null)
    )
}
