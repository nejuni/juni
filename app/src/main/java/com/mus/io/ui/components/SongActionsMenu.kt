package com.nejuni.juni.ui.components

import com.nejuni.juni.ui.icons.SfIcons
import com.nejuni.juni.ui.components.trackArtSize
import androidx.compose.ui.layout.ContentScale
import com.nejuni.juni.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.nejuni.juni.ui.player.ThinSlider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nejuni.juni.data.model.LikeStatus
import com.nejuni.juni.data.model.ROW_ART_PX
import com.nejuni.juni.data.model.Song
import com.nejuni.juni.data.model.artworkAt
import com.nejuni.juni.playback.SleepTimer
import com.nejuni.juni.ui.components.thumbnailBorder
import com.nejuni.juni.ui.theme.ArtworkPalette
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Long-press menu for a track, in the shape music apps normally use.
 *
 * The account actions lead — rating, playlists, library — because they are
 * what the menu is opened for; the queue and navigation rows below it were
 * always the fallback for "I meant to do something with this song".
 *
 * Everything that writes to the account is hidden outright when [signedIn] is
 * false rather than shown and refused. A track playing from a local file or a
 * finished download (`song.localUri != null`) carries no YouTube identity to
 * rate, fetch again or share, so it gets the offline player's rows instead:
 * Like and Add to playlist against the device's own playlists (see
 * [com.nejuni.juni.data.OfflinePlaylists]), signed in or not.
 *
 * [showSleepTimer] and [onShare] are the player's extras: a sleep timer isn't a
 * property of some row in a list, so it only appears where it means something.
 *
 * [onDownload] is only the *start* of a download — cancelling one and deleting
 * a saved file are answered here, because neither needs anything the caller
 * has. Starting one might: below API 29 it needs a storage permission that only
 * an Activity can ask for.
 *
 * Only the rows, in the iOS panel's own colours: the frame is the host's —
 * [IosMenuPopover] hung off the ⋮ or the player's ⋯ that opened it, or
 * [HeldContextMenu] under the row a hold lifted.
 */
@Composable
fun SongActionsMenu(
    song: Song,
    likeStatus: LikeStatus,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onToggleLike: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    /**
     * Removes this track from the account's listening history. Null hides the
     * row — the History screen passes one only when the menu is opened there.
     */
    onRemoveFromHistory: (() -> Unit)? = null,
    showSleepTimer: Boolean = false,
    /**
     * Copies what the app logged while starting this track. Null everywhere
     * except the player, where "this track" means something.
     */
    onCopyLog: (() -> Unit)? = null,
    /** Opens the timing control offered only by the main player's menu. */
    onLyricsOffset: (() -> Unit)? = null,
    /**
     * Removes every queue row but the current one. Null outside the player:
     * the queue belongs to playback, not to a row in a list.
     */
    onClearQueue: (() -> Unit)? = null,
    /**
     * Opens the Automix mix popup on the next transition. Null hides the row —
     * the player passes one only while Automix is on.
     */
    onAutomixOverview: (() -> Unit)? = null,
) {
    var pickingSleepTimer by remember { mutableStateOf(false) }
    // A menu has no pages to swap, so Song Info opens as a dialog.
    // Neutral rather than the sleeve's colours: the panel floats over the page
    // or the player, and an accent pitched against the artwork's tint is not
    // guaranteed to read against the panel.
    val palette = neutralMenuPalette()
    val liked = likeStatus == LikeStatus.LIKE
    // A local file or a finished download has no YouTube identity behind it to
    // rate, save, queue into a playlist, fetch again, or share a link for.
    val isOffline = song.localUri != null

    val rows: @Composable ColumnScope.() -> Unit = {
        // Groups are drawn as they turn out to have something in them, and the
        // separator goes *before* a group, never after — so a menu whose last
        // group is empty doesn't end on a stray line.
        var drawnAny = false
        fun startsGroup(): Boolean = drawnAny.also { drawnAny = true }
        val canRate = isOffline

        // Leads with the actions it is opened for most, as tiles: the rating
        // — kept up after a tap, so the new state shows in place — and Share.
        if (canRate) {
            if (startsGroup()) IosMenuSeparator()
            IosMenuQuickActions {
                if (canRate) {
                    IosMenuQuickAction(
                        icon = if (liked) SfIcons.StarFill else SfIcons.Star,
                        label = stringResource(R.string.like),
                        tint = if (liked) palette.accent else null,
                        onClick = onToggleLike,
                    )
                }
            }
        }

        // Leads the list whenever it's available: which recording is playing
        // is the one question that has to be answered before any of the rows
        // below mean anything — there is no point rating, queueing or
        // downloading the wrong version of a song.

        // The account's rows, or for a local file or download the offline
        // player's own pair: a heart that files the track under this device's
        // Liked playlist, and the device's playlists. [likeStatus] is that
        // playlist's answer there, not YouTube's, and neither needs an account.
        if (canRate) {
            if (startsGroup()) IosMenuSeparator()
            ActionRow(
                icon = SfIcons.AddToPlaylist,
                label = stringResource(R.string.add_to_playlist),
                accent = palette.accent,
                onClick = onAddToPlaylist,
            )
            onRemoveFromPlaylist?.let {
                ActionRow(
                    icon = SfIcons.RemoveFromPlaylist,
                    label = stringResource(R.string.remove_from_playlist),
                    accent = palette.accent,
                    onClick = it,
                )
            }
        }

        // Where the track goes next, and what is kept of it. The file rows
        // ride in this group rather than one of their own because for a
        // device file they can all be absent, and a group may not be empty.
        if (startsGroup()) IosMenuSeparator()
        ActionRow(
            icon = SfIcons.PlayNext,
            label = stringResource(R.string.play_next),
            accent = palette.accent,
            onClick = onPlayNext,
        )
        ActionRow(
            icon = SfIcons.PlayLast,
            label = stringResource(R.string.add_to_queue),
            accent = palette.accent,
            onClick = onAddToQueue,
        )
        onRemoveFromHistory?.let {
            ActionRow(
                icon = SfIcons.History,
                label = stringResource(R.string.remove_from_history),
                accent = palette.accent,
                onClick = it,
            )
        }

        if (startsGroup()) IosMenuSeparator()
        // Names where each one leads under it, as a menu's "Go to" does.
        //
        // A device row carries its release and its artist as names rather than
        // catalogue ids, so an empty id is what the menu hands over for those;
        // the host opens the device's own page for the name instead of a page
        // that cannot exist.
        val localRow = song.localUri != null ||
            song.videoId.startsWith("content://") ||
            song.videoId.startsWith("file://")
        val albumKey = song.albumId
            ?: song.albumName?.takeIf { localRow && it.isNotBlank() }?.let { "" }
        albumKey?.let { id ->
            ActionRow(
                SfIcons.Album,
                stringResource(R.string.open_album),
                accent = palette.accent,
                description = song.albumName,
            ) { onOpenAlbum(id) }
        }
        val artistKey = song.artistId
            ?: song.artist.takeIf { localRow && it.isNotBlank() }?.let { "" }
        artistKey?.let { id ->
            ActionRow(
                SfIcons.Artist,
                stringResource(R.string.open_artist),
                accent = palette.accent,
                description = song.artist.takeIf { it.isNotBlank() },
            ) { onOpenArtist(id) }
        }

        // The player's own: about the track playing right now rather than
        // about the song as a thing in a library.
        if (showSleepTimer || onLyricsOffset != null || onClearQueue != null ||
            onAutomixOverview != null || onCopyLog != null
        ) {
            if (startsGroup()) IosMenuSeparator()
            if (showSleepTimer) {
                ActionRow(
                    icon = SfIcons.SleepTimer,
                    label = stringResource(R.string.sleep_timer),
                    value = sleepTimerStatus(),
                    accent = palette.accent,
                ) { pickingSleepTimer = true }
            }
            // Keeps the playing (or paused) track and drops the rest — the
            // bounded context behind it goes too, so nothing refills the queue.
            onClearQueue?.let {
                ActionRow(
                    icon = SfIcons.DeleteSweep,
                    label = stringResource(R.string.clear_queue),
                    accent = palette.accent,
                    onClick = it,
                )
            }
            // The same popup "Mixing" opens mid-blend, on the transition
            // still to come.
            onAutomixOverview?.let {
                ActionRow(
                    icon = SfIcons.GraphicEq,
                    label = stringResource(R.string.automix_overview),
                    accent = palette.accent,
                    onClick = it,
                )
            }
            // Local files and completed downloads use the same player lyric
            // clock (including embedded synced lyrics), so they need this
            // control just as much as streamed songs do.
            onLyricsOffset?.let {
                ActionRow(
                    icon = SfIcons.Sliders,
                    label = stringResource(R.string.lyrics_offset),
                    accent = palette.accent,
                    onClick = it,
                )
            }
            // Last: the one row here nobody reaches for by accident.
            onCopyLog?.let {
                ActionRow(SfIcons.Bug, stringResource(R.string.copy_log), accent = palette.accent, onClick = it)
            }
        }
    }

    CompositionLocalProvider(LocalActionRowStyle provides ActionRowStyle.Menu) {
        Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            // The player's menu has the sleep timer, and swaps its rows for
            // the picker in place.
            if (pickingSleepTimer) {
                SleepTimerPicker(palette = palette, onBack = { pickingSleepTimer = false })
            } else {
                rows()
            }
        }
    }
}

/**
 * Row density and layout, set by whichever surface the rows are drawn on: the
 * playlist picker's sheet, or a menu panel.
 */
internal enum class ActionRowStyle { Sheet, Menu }

internal val LocalActionRowStyle = staticCompositionLocalOf { ActionRowStyle.Sheet }

/**
 * The popup's colours: the iOS menu panel's own, so the menu reads the same in
 * light and dark whatever the artwork is. Shaped as an [ArtworkPalette] so every
 * row below takes it exactly as it takes a sleeve's.
 */
@Composable
private fun neutralMenuPalette(): ArtworkPalette {
    val accent = MaterialTheme.colorScheme.primary
    val colors = iosMenuColors()
    return remember(accent, colors) {
        ArtworkPalette(
            background = colors.fill,
            wash = colors.fill,
            elevated = colors.fill,
            accent = accent,
            onBackground = colors.label,
            onBackgroundVariant = colors.secondaryLabel,
            divider = colors.separator,
        )
    }
}


/** End of track or a duration, plus a way out once one is running. */
@Composable
private fun SleepTimerPicker(palette: ArtworkPalette, onBack: () -> Unit) {
    val chosen by SleepTimer.minutes.collectAsStateWithLifecycle()
    val afterTrack by SleepTimer.afterTrack.collectAsStateWithLifecycle()
    val countdown = sleepTimerCountdown()
    // A running timer no preset matches is a custom one: the row ticks and names it.
    val custom = chosen?.takeIf { it !in SleepTimer.PRESETS }
    var pickingCustom by remember { mutableStateOf(false) }

    if (pickingCustom) {
        CustomSleepTimerPage(
            palette = palette,
            initialMinutes = custom ?: 90,
            onBack = { pickingCustom = false },
            onStart = { minutes ->
                SleepTimer.start(minutes)
                onBack()
            },
        )
        return
    }

    Column(Modifier.fillMaxWidth()) {
        SleepTimerHeader(
            title = stringResource(R.string.sleep_timer),
            subtitle = when {
                countdown != null -> stringResource(R.string.pause_countdown, countdown)
                afterTrack -> stringResource(R.string.pause_after_song)
                else -> stringResource(R.string.pause_after_a_while)
            },
            onBack = onBack,
        )
        HorizontalDivider(thickness = 0.5.dp, color = palette.divider)

        // Finishing the song is the one people reach for at the end of a
        // listen, so it leads rather than sitting under the durations.
        SleepOption(
            label = stringResource(R.string.after_this_song),
            selected = afterTrack,
            accent = palette.accent,
        ) {
            SleepTimer.startAfterTrack()
            onBack()
        }
        SleepTimer.PRESETS.forEach { minutes ->
            SleepOption(
                label = if (minutes == 60) {
                    stringResource(R.string.one_hour)
                } else {
                    pluralStringResource(R.plurals.minute_count, minutes, minutes)
                },
                selected = minutes == chosen,
                accent = palette.accent,
            ) {
                SleepTimer.start(minutes)
                onBack()
            }
        }
        SleepOption(
            label = custom?.let {
                stringResource(R.string.sleep_timer_custom) + " · " + sleepDuration(it)
            } ?: stringResource(R.string.sleep_timer_custom),
            selected = custom != null,
            accent = palette.accent,
        ) { pickingCustom = true }
        if (chosen != null || afterTrack) {
            ActionRow(SfIcons.Close, stringResource(R.string.turn_off_timer), accent = palette.accent) {
                SleepTimer.cancel()
                onBack()
            }
        }
    }
}

/**
 * Back arrow, title and a line under it — the top of both sleep timer pages.
 * The title at the size of a menu row's label, as the heading of a menu's own
 * sub-page, not a page's.
 */
@Composable
private fun SleepTimerHeader(title: String, subtitle: String, onBack: () -> Unit) {
    val colors = iosMenuColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                SfIcons.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = colors.label,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 17.sp,
                    lineHeight = 21.sp,
                    letterSpacing = (-0.3).sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = colors.label,
            )
            Text(
                text = subtitle,
                fontSize = 14.sp,
                lineHeight = 17.sp,
                color = colors.secondaryLabel,
            )
        }
    }
}

/**
 * Any length up to twelve hours, set the way the player's lyrics offset is:
 * a large readout over the player's own thin slider, with − and + for a
 * single step either way. A page of this menu rather than an alert on top
 * of it, so it stays on the panel like the presets it sits with.
 */
@Composable
private fun CustomSleepTimerPage(
    palette: ArtworkPalette,
    initialMinutes: Int,
    onBack: () -> Unit,
    onStart: (Int) -> Unit,
) {
    var minutes by remember { mutableIntStateOf(initialMinutes) }
    val foreground = MaterialTheme.colorScheme.onBackground
    val readout = iosMenuColors().label

    Column(Modifier.fillMaxWidth()) {
        SleepTimerHeader(
            title = stringResource(R.string.sleep_timer_custom),
            subtitle = stringResource(R.string.pause_after_a_while),
            onBack = onBack,
        )
        HorizontalDivider(thickness = 0.5.dp, color = palette.divider)

        Text(
            text = sleepDuration(minutes),
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
            color = readout,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StepButton(
                label = "−",
                contentDescription = stringResource(R.string.less),
                enabled = minutes > SleepTimer.STEP_MINUTES,
            ) { minutes -= SleepTimer.STEP_MINUTES }
            ThinSlider(
                value = SleepTimer.fractionOf(minutes),
                onValueChange = { minutes = SleepTimer.minutesAt(it) },
                idleHeight = 6.dp,
                activeHeight = 10.dp,
                activeColor = foreground.copy(alpha = 0.92f),
                inactiveColor = foreground.copy(alpha = 0.26f),
                modifier = Modifier.weight(1f),
            )
            StepButton(
                label = "+",
                contentDescription = stringResource(R.string.more),
                enabled = minutes < SleepTimer.MAX_MINUTES,
            ) { minutes += SleepTimer.STEP_MINUTES }
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(thickness = 0.5.dp, color = palette.divider)
        ActionRow(SfIcons.SleepTimer, stringResource(R.string.start_sleep_timer), accent = palette.accent) {
            onStart(minutes)
        }
    }
}

@Composable
private fun StepButton(
    label: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { this.contentDescription = contentDescription },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            color = if (enabled) iosMenuColors().label else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f),
        )
    }
}

/** "45 min", or "1h 25m" from an hour up. */
@Composable
private fun sleepDuration(minutes: Int): String = if (minutes >= 60) {
    stringResource(R.string.hours_minutes_short, minutes / 60, minutes % 60)
} else {
    stringResource(R.string.minutes_short, minutes)
}

@Composable
private fun SleepOption(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    // The tick leads, in the glyph column, as a menu's picker marks its choice.
    IosMenuRow(
        label = label,
        icon = if (selected) SfIcons.Check else null,
        onClick = onClick,
        iconTint = accent,
    )
}

/** What the sleep timer row shows on the right, or null when none is armed. */
@Composable
private fun sleepTimerStatus(): String? {
    val afterTrack by SleepTimer.afterTrack.collectAsStateWithLifecycle()
    return sleepTimerCountdown() ?: stringResource(R.string.after_this_song).takeIf { afterTrack }
}

/** Live "m:ss" until the sleep timer fires, or null when none is running. */
@Composable
private fun sleepTimerCountdown(): String? {
    val deadline by SleepTimer.deadline.collectAsStateWithLifecycle()
    val remaining by produceState<Long?>(initialValue = SleepTimer.remainingMs(), deadline) {
        while (deadline != null) {
            value = SleepTimer.remainingMs()
            delay(1_000)
        }
        value = null
    }
    return remaining?.let {
        val seconds = it / 1000
        "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
    }
}

/**
 * One line of an actions list: an iOS menu row on a menu panel, a full-width
 * row on the playlist picker's sheet — so the two read as the same control
 * rather than as two lists that happen to look alike.
 *
 * [tint] is for rows whose icon carries state — a filled heart on a liked
 * track — and is otherwise the ordinary foreground. [accent] colours the
 * trailing [value], and defaults to the app's own red; a surface with an
 * accent of its own passes that instead, so the row belongs to what it is
 * drawn on.
 */
@Composable
internal fun ActionRow(
    icon: ImageVector,
    label: String,
    value: String? = null,
    tint: Color? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    /** A dimmer second line — only the popup draws it, as iOS menus do. */
    description: String? = null,
    onClick: () -> Unit,
) {
    if (LocalActionRowStyle.current == ActionRowStyle.Menu) {
        IosMenuRow(
            label = label,
            icon = icon,
            onClick = onClick,
            description = description,
            value = value,
            iconTint = tint,
            valueColor = accent,
            enabled = enabled,
        )
        return
    }
    val foreground = MaterialTheme.colorScheme.onBackground
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = (tint ?: foreground).copy(alpha = alpha),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(18.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = foreground.copy(alpha = alpha),
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = accent.copy(alpha = alpha),
                maxLines = 1,
            )
        }
    }
}

/**
 * The track the playlist picker is adding, drawn at its head — the same track
 * the menu it was opened from was about.
 *
 * [subtitleColor] is for ground other than the flat theme background, where
 * the usual dim grey may not hold.
 */
@Composable
internal fun SheetTrackHeader(
    song: Song,
    modifier: Modifier = Modifier,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = song.artworkAt(ROW_ART_PX),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .trackArtSize(52.dp, song.isVideo)
                .clip(RoundedCornerShape(8.dp))
                .thumbnailBorder(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            ExplicitSongTitle(
                song = song,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = subtitleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The heading over a sheet's second half — "Add to playlist". */
@Composable
internal fun SheetHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 4.dp),
    )
}
