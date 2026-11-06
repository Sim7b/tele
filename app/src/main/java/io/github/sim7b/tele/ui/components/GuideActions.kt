package io.github.sim7b.tele.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.sim7b.tele.R
import io.github.sim7b.tele.data.epg.Catchup
import io.github.sim7b.tele.data.model.Channel
import io.github.sim7b.tele.data.model.ChannelList
import io.github.sim7b.tele.data.model.ChannelListView
import io.github.sim7b.tele.data.model.GuideProgram
import io.github.sim7b.tele.data.model.StreamSource
import io.github.sim7b.tele.data.storage.Reminder
import io.github.sim7b.tele.data.storage.Reminders

/**
 * What can be done from the guide (channel panel and guide grid) besides watching a channel:
 * reminders of coming programs, replay of past ones, and the menu of a channel (long press on OK).
 */
class GuideActions(
    val reminders: List<Reminder> = emptyList(),
    val toggleReminder: (Channel, GuideProgram) -> Unit = { _, _ -> },
    // Null: no replay possible (see Catchup)
    val replay: ((Channel, GuideProgram) -> Unit)? = null,
    val isFavorite: (Channel) -> Boolean = { false },
    val toggleFavorite: (Channel) -> Unit = {},
    val hideChannel: (Channel) -> Unit = {},
    val loadStreams: (suspend (Channel) -> Result<List<StreamSource>>)? = null,
    val selectStream: (Channel, StreamSource?) -> Unit = { _, _ -> },
    val automaticStreamDescription: (Channel) -> String = { "" },
    // Lists of channels made by the user: categories of their own, and their rows in the channel menu
    val channelLists: List<ChannelListView> = emptyList(),
    val savedLists: List<ChannelList> = emptyList(),
    val toggleInList: (listId: String, Channel) -> Unit = { _, _ -> },
    val createList: (name: String, Channel) -> Unit = { _, _ -> }
) {
    /** Lists holding channels, by their category key (after the favorites and the recent channels). */
    val listCategories: Map<String, ChannelListView> by lazy {
        channelLists.filter { it.channels.isNotEmpty() }.associateBy { CHANNEL_LIST_PREFIX + it.id }
    }

    fun hasReminder(channel: Channel, program: GuideProgram): Boolean =
        reminders.any { Reminders.matches(it, channel.id, program.startMillis) }

    fun canReplay(channel: Channel, program: GuideProgram, now: Long): Boolean =
        replay != null && Catchup.isReplayable(channel, program, now)

    fun reminderOf(channel: Channel, program: GuideProgram) =
        Reminder(channel.id, channel.name, program.title, program.startMillis, program.endMillis)
}

/** Prefix of the category keys of the lists of channels. */
const val CHANNEL_LIST_PREFIX = "__list__:"

/** What OK does on a program of the guide. */
enum class ProgramChoice { WATCH, REPLAY, MENU }

/** On air or past without replay: the channel; past with replay: the replay; to come: its menu (reminder). */
fun GuideActions.choiceFor(channel: Channel, program: GuideProgram?, now: Long): ProgramChoice = when {
    program == null -> ProgramChoice.WATCH
    program.startMillis > now -> ProgramChoice.MENU
    canReplay(channel, program, now) -> ProgramChoice.REPLAY
    else -> ProgramChoice.WATCH
}

/** Marks of a program: replay available, reminder set. */
@Composable
fun programBadges(replay: Boolean, reminder: Boolean): List<String> {
    val replayTag = stringResource(R.string.program_replay_tag)
    val reminderTag = stringResource(R.string.program_reminder_tag)
    return listOfNotNull(replayTag.takeIf { replay }, reminderTag.takeIf { reminder })
}
