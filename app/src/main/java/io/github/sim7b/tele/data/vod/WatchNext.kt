package io.github.sim7b.tele.data.vod

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.net.toUri
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import io.github.sim7b.tele.data.search.AppIntents
import io.github.sim7b.tele.data.storage.VodProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Play next" row of the Android TV home: the movies and series started in the app, each opening its sheet
 * in the app. Only this app's rows are read and changed; a TV without that row (phone) is left alone.
 */
class WatchNext(private val context: Context) {

    /** Makes the row show [items] (latest first): new ones added, the others updated or removed. */
    @SuppressLint("RestrictedApi")
    suspend fun publish(items: List<Pair<VodTitle, VodProgress>>) = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val existing = HashMap<String, Long>()
            resolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, WatchNextProgram.PROJECTION, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val program = WatchNextProgram.fromCursor(cursor)
                    program.internalProviderId?.let { existing[it] = program.id }
                }
            }
            val wanted = items.associateBy { it.first.key }
            existing.filterKeys { it !in wanted }.values.forEach { id ->
                resolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null)
            }
            items.forEach { (title, progress) ->
                val values = program(title, progress).toContentValues()
                val id = existing[title.key]
                if (id != null) {
                    resolver.update(TvContractCompat.buildWatchNextProgramUri(id), values, null, null)
                } else {
                    resolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values)
                }
            }
        } catch (e: Exception) {
            // No TV provider (phone, tablet) or refused: nothing on the TV home
            Log.w(TAG, "Play next row unavailable: ${e.javaClass.simpleName}")
        }
    }

    @SuppressLint("RestrictedApi")
    private fun program(title: VodTitle, progress: VodProgress): WatchNextProgram {
        val movie = title.kind == VodKind.MOVIE
        val image = title.backdropUrl ?: title.posterUrl
        return WatchNextProgram.Builder()
            .setType(if (movie) TvContractCompat.PreviewPrograms.TYPE_MOVIE else TvContractCompat.PreviewPrograms.TYPE_TV_SERIES)
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setLastEngagementTimeUtcMillis(progress.updatedAt)
            .setTitle(title.title)
            .setDescription(title.plot)
            .apply {
                image?.let {
                    setPosterArtUri(it.toUri())
                    setPosterArtAspectRatio(
                        if (title.backdropUrl != null) TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9 else TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3
                    )
                }
                if (progress.durationMs > 0) {
                    setDurationMillis(progress.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    setLastPlaybackPositionMillis(progress.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                }
            }
            .setIntentUri(AppIntents.titleLink(title.key).toUri())
            .setInternalProviderId(title.key)
            .build()
    }

    private companion object {
        const val TAG = "WatchNext"
    }
}
