package io.github.sim7b.tele.data.epg

/** Texts of the guide as some providers send them, escaped twice ("\"Un jour\"", "l\'été"): made readable. */
object EpgText {

    fun clean(text: String?): String? =
        text?.replace("\\\"", "\"")?.replace("\'", "'")?.replace("\n", " ")?.replace(Regex("""\s{2,}"""), " ")?.trim()?.ifEmpty { null }
}
