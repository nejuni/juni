package com.nejuni.juni.ui.components

import com.nejuni.juni.ui.icons.SfIcons
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nejuni.juni.R
import com.nejuni.juni.data.model.BrowseType
import com.nejuni.juni.data.model.Song
import com.nejuni.juni.data.model.UserPlaylist

/**
 * The album or playlist a long-press is acting on.
 *
 * Deliberately not one of the browse models: every surface in the app describes
 * a release with a different type — a card on the Local Music tab, a
 * [com.nejuni.juni.data.model.DetailPage] on the page itself, a plain album name in
 * the queue — and the menu needs the same five things from all of them.
 */
data class BrowseTarget(
    /**
     * What to fetch the track list with, or null when there is nothing to
     * fetch: a Local Music album is a grouping of rows already on screen, not
     * a page anything can be asked for.
     */
    val browseId: String?,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String? = null,
    val type: BrowseType = BrowseType.OTHER,
    /**
     * The tracks already in hand. Empty for a card whose page was never
     * opened — the queue actions then fetch the listing themselves, which is
     * why they are offered either way.
     */
    val songs: List<Song> = emptyList(),
    /**
     * Set when this is one of the account's own playlists, which is the only
     * case where renaming and deleting are things that can be done to it.
     *
     * Filled in where the menu is raised rather than by whatever built the
     * target: only the playlist's own page states who made it, so for a card
     * this has to be sent for and arrives after the menu is already up. Left
     * null by every caller, and null is also the honest answer while the
     * question is still out.
     */
    val playlist: UserPlaylist? = null,
    /**
     * False when the menu was opened from the page it would otherwise navigate
     * to — the release page's own overflow. Play, Shuffle and Open are already
     * on that page, so there they are left off the menu.
     */
    val fromCard: Boolean = true,
)

/**
 * Long-press menu for an album or playlist — the collection-level counterpart
 * to [SongActionsMenu], and like it only the rows: the frame is the host's,
 * [IosMenuPopover] off a ⋯ or [HeldContextMenu] under a held card.
 *
 * Every action is optional, and which ones a caller passes is how the same
 * menu serves a card on the home feed and the page that card opens. A card
 * has no other way to play its album without navigating to it, so it gets Play
 * and Shuffle; the page's own header already carries both, so there they are
 * null and the menu is the queue rows and the download.
 *
 * The queue rows are always offered. They are the reason this menu exists: a
 * release is exactly the kind of thing someone wants *after* what is playing
 * rather than instead of it, and until now the only way to queue one was to
 * open it and long-press its tracks one at a time.
 *
 * Deleting asks a second time, in place. A playlist is the only thing in this
 * app whose loss can't be undone by tapping the same row again, and a
 * mis-tapped card in a shelf is exactly how it would happen.
 */
@Composable
fun BrowseActionsMenu(
    target: BrowseTarget,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (() -> Unit)? = null,
    onShuffle: (() -> Unit)? = null,
    /** Null where the menu was opened from the page it would navigate to. */
    onOpen: (() -> Unit)? = null,
    /**
     * Shares the release's own YouTube Music link — the album/playlist
     * counterpart to a track's own Share tile in [SongActionsMenu]. Null for
     * anything with no such link to share: an artist card, or a page built
     * from something other than a real browse id (a Local Music grouping).
     */
    onShare: (() -> Unit)? = null,
    /**
     * Set whenever [target] is a playlist, regardless of who owns it — pinning
     * doesn't touch the account, only what sits at the top of this device's
     * Library tab. Null everywhere else (albums, artists), where "pin" has
     * nothing to mean.
     */
    isPinned: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    onRename: ((String) -> Unit)? = null,
    /**
     * Opens the reorder page for one of the account's own playlists — set
     * under the same rule as [onRename], since YouTube refuses to rearrange a
     * playlist the account only saved.
     */
    onReorder: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    /**
     * Rename: a text field and its keyboard have no place on a menu floating
     * over the page, so the row hands the job to the host, which raises a
     * sheet holding only the form — this, composed again with [startRenaming].
     */
    onRenameInSheet: (() -> Unit)? = null,
    startRenaming: Boolean = false,
) {
    var renaming by remember { mutableStateOf(startRenaming) }
    var confirmingDelete by remember { mutableStateOf(false) }

    val playlist = target.playlist
    if (renaming && playlist != null && onRename != null) {
        RenamePlaylistForm(
            playlist = playlist,
            onBack = { renaming = false },
            onRename = onRename,
            modifier = modifier,
        )
        return
    }

    CompositionLocalProvider(LocalActionRowStyle provides ActionRowStyle.Menu) {
        Column(
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            onPlay?.let { ActionRow(SfIcons.Play, stringResource(R.string.play), onClick = it) }
            onShuffle?.let { ActionRow(SfIcons.Shuffle, stringResource(R.string.shuffle), onClick = it) }
            ActionRow(
                SfIcons.PlayNext,
                stringResource(R.string.play_next),
                onClick = onPlayNext,
            )
            ActionRow(
                SfIcons.PlayLast,
                stringResource(R.string.add_to_queue),
                onClick = onAddToQueue,
            )
            onOpen?.let {
                ActionRow(
                    SfIcons.Open,
                    target.type.localizedOpenLabel(),
                    onClick = it,
                )
            }
            onShare?.let {
                ActionRow(SfIcons.Share, stringResource(R.string.share), onClick = it)
            }
            onTogglePin?.let {
                ActionRow(
                    SfIcons.Pin,
                    stringResource(if (isPinned) R.string.unpin else R.string.pin),
                    onClick = it,
                )
            }
            if (onRename != null) {
                ActionRow(SfIcons.Pencil, stringResource(R.string.rename)) {
                    if (onRenameInSheet != null) {
                        onRenameInSheet()
                    } else {
                        renaming = true
                    }
                }
            }
            onReorder?.let {
                ActionRow(SfIcons.Reorder, stringResource(R.string.reorder_songs), onClick = it)
            }
            if (onDelete != null) {
                if (confirmingDelete) {
                    ActionRow(
                        icon = SfIcons.Trash,
                        label = stringResource(R.string.delete_playlist_confirmation, target.title),
                        tint = MaterialTheme.colorScheme.error,
                        onClick = onDelete,
                    )
                } else {
                    ActionRow(SfIcons.Trash, stringResource(R.string.delete_playlist)) { confirmingDelete = true }
                }
            }
        }
    }
}

@Composable
private fun BrowseType.localizedOpenLabel(): String = when (this) {
    BrowseType.ALBUM -> stringResource(R.string.open_album)
    BrowseType.ARTIST -> stringResource(R.string.open_artist)
    BrowseType.PLAYLIST -> stringResource(R.string.open_playlist)
    BrowseType.OTHER -> stringResource(R.string.open)
}
