package io.github.sim7b.tele.data.bouquet

import io.github.sim7b.tele.data.bouquet.BouquetCategory.BELGIUM
import io.github.sim7b.tele.data.bouquet.BouquetCategory.CANADA
import io.github.sim7b.tele.data.bouquet.BouquetCategory.CINEMA
import io.github.sim7b.tele.data.bouquet.BouquetCategory.DOCUMENTARY
import io.github.sim7b.tele.data.bouquet.BouquetCategory.FRANCE
import io.github.sim7b.tele.data.bouquet.BouquetCategory.KIDS
import io.github.sim7b.tele.data.bouquet.BouquetCategory.MUSIC
import io.github.sim7b.tele.data.bouquet.BouquetCategory.NEWS
import io.github.sim7b.tele.data.bouquet.BouquetCategory.SERIES
import io.github.sim7b.tele.data.bouquet.BouquetCategory.SPORT
import io.github.sim7b.tele.data.bouquet.BouquetCategory.SWITZERLAND
import io.github.sim7b.tele.data.bouquet.BouquetCategory.TNT

private fun ch(name: String, category: BouquetCategory, vararg aliases: String) =
    BouquetChannel(name, category, null, aliases.toList())

/**
 * French channels as seen from another country: same names and categories, the free-to-air ones grouped
 * as "France", no number (each operator numbers them its own way).
 */
private val frenchChannelsAbroad: List<BouquetChannel> = FranceBouquet.channels.map { channel ->
    BouquetChannel(channel.name, if (channel.category == TNT) FRANCE else channel.category, null, channel.aliases)
}

/**
 * Reference bouquets of the French-speaking neighbors: their national channels (no number, numbering
 * depends on the operator), then the French channels that their providers usually carry too.
 */
internal object NeighborBouquets {

    val belgium: List<BouquetChannel> = listOf(
        // French-speaking
        ch("La Une", BELGIUM, "RTBF La Une", "La 1", "RTBF 1"),
        ch("Tipik", BELGIUM, "La Deux", "La 2", "RTBF Tipik", "Tipik Vision"),
        ch("La Trois", BELGIUM, "La 3", "RTBF La Trois"),
        ch("RTL TVI", BELGIUM, "RTL-TVI", "RTL TVi"),
        ch("Club RTL", BELGIUM),
        ch("Plug RTL", BELGIUM),
        ch("AB3", BELGIUM, "AB 3"),
        ch("RTL District", BELGIUM),
        ch("BX1", BELGIUM, "BX 1"),
        ch("Télé MB", BELGIUM, "Tele MB"),
        ch("Télésambre", BELGIUM, "Telesambre"),
        ch("TV Lux", BELGIUM),
        ch("Canal C", BELGIUM),
        ch("Ma Télé", BELGIUM, "Ma Tele"),
        ch("No Télé", BELGIUM, "No Tele"),
        ch("RTC Télé Liège", BELGIUM, "RTC", "RTC Liege"),
        ch("Antenne Centre", BELGIUM),
        ch("Canal Zoom", BELGIUM),
        ch("TV Com", BELGIUM),
        ch("Eleven Pro League 1", SPORT, "Eleven Pro League", "Eleven Sports 1"),
        ch("Eleven Pro League 2", SPORT, "Eleven Sports 2"),
        ch("Eleven Pro League 3", SPORT, "Eleven Sports 3"),
        ch("VOO Sport", SPORT, "VOOsport", "VOO Sport 1"),
        ch("LN24", NEWS, "LN 24"),
        ch("Canal Z", NEWS),
        ch("ABXplore", DOCUMENTARY, "AB Xplore"),
        // Dutch-speaking
        ch("VRT 1", BELGIUM, "VRT1", "Eén", "Een"),
        ch("VRT Canvas", BELGIUM, "Canvas"),
        ch("VTM", BELGIUM),
        ch("VTM 2", BELGIUM, "VTM2"),
        ch("VTM 3", BELGIUM, "VTM3"),
        ch("VTM 4", BELGIUM, "VTM4"),
        ch("VTM Gold", BELGIUM),
        ch("Play4", BELGIUM, "Play 4", "Vier"),
        ch("Play5", BELGIUM, "Play 5"),
        ch("Play6", BELGIUM, "Play 6"),
        ch("Play7", BELGIUM, "Play 7"),
        ch("Ketnet", KIDS),
        ch("VTM Kids", KIDS),
        ch("Kanaal Z", NEWS),
        ch("Play Sports", SPORT)
    ) + frenchChannelsAbroad

    val switzerland: List<BouquetChannel> = listOf(
        ch("RTS 1", SWITZERLAND, "RTS Un", "RTS1"),
        ch("RTS 2", SWITZERLAND, "RTS Deux", "RTS2"),
        ch("SRF 1", SWITZERLAND, "SRF1"),
        ch("SRF zwei", SWITZERLAND, "SRF 2", "SRF2"),
        ch("SRF info", NEWS),
        ch("RSI LA 1", SWITZERLAND, "RSI La1", "RSI 1"),
        ch("RSI LA 2", SWITZERLAND, "RSI La2", "RSI 2"),
        ch("3+", SWITZERLAND, "3 Plus"),
        ch("4+", SWITZERLAND, "4 Plus"),
        ch("5+", SWITZERLAND, "5 Plus"),
        ch("6+", SWITZERLAND, "6 Plus"),
        ch("7+", SWITZERLAND, "7 Plus"),
        ch("Rouge TV", SWITZERLAND),
        ch("Canal Alpha", SWITZERLAND),
        ch("TeleBielingue", SWITZERLAND, "Tele Bielingue"),
        ch("One TV", SWITZERLAND),
        ch("TV24", SWITZERLAND, "TV 24"),
        ch("TV25", SWITZERLAND, "TV 25"),
        ch("S1", SWITZERLAND),
        ch("Léman Bleu", SWITZERLAND, "Leman Bleu"),
        ch("La Télé", SWITZERLAND, "La Tele"),
        ch("Canal 9", SWITZERLAND, "Canal9"),
        ch("Blue Sport", SPORT),
        ch("Blue Zoom", SERIES),
        ch("Teleclub Zoom", CINEMA)
    ) + frenchChannelsAbroad

    val canada: List<BouquetChannel> = listOf(
        // French-speaking (Québec)
        ch("ICI Radio-Canada Télé", CANADA, "Radio-Canada", "ICI Tele", "ICI Télé", "SRC", "Radio Canada"),
        ch("TVA", CANADA),
        ch("Noovo", CANADA, "V Télé", "V Tele"),
        ch("Télé-Québec", CANADA, "Tele-Quebec", "Tele Quebec", "Télé Québec"),
        ch("Unis TV", CANADA, "Unis", "TV5 Unis"),
        ch("MAtv", CANADA, "MA TV"),
        ch("MétéoMédia", NEWS, "MeteoMedia", "Météo Média"),
        ch("Canal Savoir", DOCUMENTARY),
        ch("Investigation", DOCUMENTARY),
        ch("ICI ARTV", SERIES, "ARTV"),
        ch("Casa", SERIES),
        ch("Zeste", SERIES),
        ch("Frissons TV", SERIES),
        ch("ICI RDI", NEWS, "RDI"),
        ch("LCN", NEWS),
        ch("RDS", SPORT),
        ch("RDS 2", SPORT, "RDS2"),
        ch("RDS Info", SPORT),
        ch("TVA Sports", SPORT),
        ch("TVA Sports 2", SPORT),
        ch("Canal D", DOCUMENTARY),
        ch("Historia", DOCUMENTARY),
        ch("Évasion", DOCUMENTARY, "Evasion"),
        ch("ICI Explora", DOCUMENTARY, "Explora"),
        ch("Canal Vie", SERIES),
        ch("Séries+", SERIES, "Series+", "Séries Plus"),
        ch("Z", SERIES, "Z Télé", "Z Tele"),
        ch("Max", SERIES),
        ch("Addik", SERIES, "AddikTV"),
        ch("Prise 2", SERIES),
        ch("Super Écran", CINEMA, "Super Ecran", "Super Écran 1", "Super Ecran 1"),
        ch("Cinépop", CINEMA, "Cinepop"),
        ch("Télétoon", KIDS, "Teletoon"),
        ch("Yoopa", KIDS),
        ch("TFO", KIDS),
        ch("ICI Musique", MUSIC),
        // English-speaking
        ch("CBC", CANADA, "CBC Television"),
        ch("CTV", CANADA),
        ch("Global", CANADA, "Global TV"),
        ch("Citytv", CANADA, "City TV"),
        ch("CBC News Network", NEWS, "CBC News"),
        ch("CTV News Channel", NEWS),
        ch("TSN", SPORT, "TSN 1", "TSN1"),
        ch("Sportsnet", SPORT, "Sportsnet One")
    ) + frenchChannelsAbroad
}
