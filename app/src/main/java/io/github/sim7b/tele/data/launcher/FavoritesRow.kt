package io.github.sim7b.tele.data.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.search.AppIntents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A favorite channel on the TV home: its name, what it shows now and its picture, opening it in the app. */
data class HomeChannelItem(
    val channelId: String,
    val name: String,
    val now: String?,
    val image: String?,
    val logo: Boolean
) {
    companion object {
        /** The favorites to show outside the app: not those of a category locked by the parental code. */
        fun of(favorites: List<Channel>, lockedGroups: Set<String>): List<HomeChannelItem> =
            favorites.filterNot { it.group in lockedGroups }.map { channel ->
                val program = channel.currentProgram
                HomeChannelItem(
                    channelId = channel.id,
                    name = channel.name,
                    now = program?.let { "${it.title} · ${it.startTime} – ${it.endTime}" },
                    image = program?.posterUrl ?: channel.logoUrl,
                    logo = program?.posterUrl == null
                )
            }
    }
}

/**
 * Row of the favorite channels on the Android TV home (a preview channel of the app): each one opens the channel
 * in the app. Its programs are updated in place (only this app's row is read and changed); a TV without the rows of
 * apps (phone, launchers that show none) is left alone.
 */
class FavoritesRow(private val context: Context) {

    @SuppressLint("RestrictedApi")
    suspend fun publish(items: List<HomeChannelItem>) = withContext(Dispatchers.IO) {
        try {
            val helper = PreviewChannelHelper(context)
            val channelId = channelId(helper, items.isNotEmpty()) ?: return@withContext
            val resolver = context.contentResolver
            val existing = HashMap<String, Long>()
            resolver.query(TvContractCompat.buildPreviewProgramsUriForChannel(channelId), PreviewProgram.PROJECTION, null, null, null)
                ?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val program = PreviewProgram.fromCursor(cursor)
                        program.internalProviderId?.let { existing[it] = program.id }
                    }
                }
            val wanted = items.take(MAX_ITEMS)
            val ids = wanted.map { it.channelId }.toSet()
            existing.filterKeys { it !in ids }.values.forEach { helper.deletePreviewProgram(it) }
            wanted.forEachIndexed { index, item ->
                val program = program(channelId, item, weight = wanted.size - index)
                val id = existing[item.channelId]
                if (id != null) helper.updatePreviewProgram(id, program) else helper.publishPreviewProgram(program)
            }
        } catch (e: Exception) {
            // No TV provider (phone, tablet) or refused: nothing on the TV home
            Log.w(TAG, "Favorites row unavailable: ${e.javaClass.simpleName}")
        }
    }

    /** The row of the app, made (the first one is shown at once) when there is something to show. */
    @SuppressLint("RestrictedApi")
    private fun channelId(helper: PreviewChannelHelper, create: Boolean): Long? {
        helper.allChannels.firstOrNull { it.internalProviderId == ROW_ID }?.let { return it.id }
        if (!create) return null
        val logo = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap(LOGO_SIZE, LOGO_SIZE) ?: return null
        val channel = PreviewChannel.Builder()
            .setDisplayName(context.getString(R.string.home_row_favorites))
            .setAppLinkIntentUri(AppIntents.homeLink().toUri())
            .setInternalProviderId(ROW_ID)
            .setLogo(logo)
            .build()
        return helper.publishDefaultChannel(channel)
    }

    @SuppressLint("RestrictedApi")
    private fun program(channelId: Long, item: HomeChannelItem, weight: Int): PreviewProgram =
        PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
            .setTitle(item.name)
            .setDescription(item.now)
            .setWeight(weight)
            .apply {
                item.image?.let {
                    setPosterArtUri(it.toUri())
                    setPosterArtAspectRatio(
                        if (item.logo) TvContractCompat.PreviewPrograms.ASPECT_RATIO_1_1 else TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
                    )
                }
            }
            .setIntentUri(AppIntents.channelLink(item.channelId).toUri())
            .setInternalProviderId(item.channelId)
            .build()

    private companion object {
        const val TAG = "FavoritesRow"
        const val ROW_ID = "favorites"
        const val MAX_ITEMS = 20
        const val LOGO_SIZE = 160
    }
}
