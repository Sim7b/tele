package io.github.sim7b.tele.data.vod

/**
 * Ratings of the movies and series: TMDB ones only (the providers' are often 10.0 for anything), and only with
 * enough votes to mean something (a 10.0 from three votes says nothing).
 */
object VodRating {
    const val MIN_VOTES = 50

    /** [vote] out of 10 when [votes] are enough, else null (unknown number of votes: null too). */
    fun trusted(vote: Double?, votes: Int?): Double? =
        vote?.takeIf { it > 0 && it <= 10 && (votes ?: -1) >= MIN_VOTES }
}
