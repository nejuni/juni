package com.nejuni.juni.ui.screens

import com.nejuni.juni.ui.components.trackArtSize
import androidx.compose.ui.layout.ContentScale
import com.nejuni.juni.data.model.toSong
import com.nejuni.juni.ui.components.SwipeToQueueBox
import com.nejuni.juni.ui.components.contextClick
import androidx.compose.ui.unit.Dp
import com.nejuni.juni.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.nejuni.juni.ui.components.PillSegmentedControl
import com.nejuni.juni.ui.components.longPressMenuClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import coil3.compose.AsyncImage
import com.nejuni.juni.data.model.BrowseType
import com.nejuni.juni.data.model.ROW_ART_PX
import com.nejuni.juni.data.model.artworkAt
import com.nejuni.juni.data.model.Song
import com.nejuni.juni.data.model.playingRowMatcher
import com.nejuni.juni.data.model.UiState
import com.nejuni.juni.data.model.SearchHistoryEntity
import com.nejuni.juni.data.model.EntityType
import com.nejuni.juni.ui.components.MessageState
import com.nejuni.juni.ui.components.PAGE_GUTTER
import com.nejuni.juni.ui.components.RowMoreButton
import com.nejuni.juni.ui.components.ROW_DIVIDER_INSET
import com.nejuni.juni.ui.components.SearchField
import com.nejuni.juni.ui.components.SongRow
import com.nejuni.juni.ui.components.SearchPlayingBars
import com.nejuni.juni.ui.components.thumbnailBorder
import com.nejuni.juni.ui.components.songListSkeleton
import java.util.Locale

@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    currentSong: Song?,
    isPlaying: Boolean,
    listState: LazyListState,
    scrollResetTrigger: Int,
    focusRequested: Boolean,
    onFocusHandled: () -> Unit,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    history: List<SearchHistoryEntity>,
    onSubmit: () -> Unit,
    onHistoryClick: (SearchHistoryEntity) -> Unit,
    onHistoryRemove: (String) -> Unit,
    onHistoryClear: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    /**
     * Room above the search field. The phone's frosted bar floats over the top
     * of the tab, so the field has to clear it; the desktop has no such bar.
     */
    topPadding: Dp,
    /**
     * Whether the page draws its own search field. The desktop's sidebar has
     * the one search box the window needs, so the page there is results only.
     */
    showField: Boolean = true,
    /**
     * Whether the page is searching the device's Local Music folder instead of
     * YouTube. The phone's top bar flips this; the desktop never does.
     */
    searchingLibrary: Boolean = false,

    /** The Library source's tracks for [query]; null while nothing is typed. */
    libraryResults: UiState<List<Song>>? = null,
    /** Files on the device are a running order to play through, not a station seed. */
    onLibrarySongClick: (List<Song>, Int) -> Unit = onSongClick,
    /**
     * The YouTube / Library switcher, full width at the head of the page. Up
     * only while the field is empty: once something is typed the page is
     * about the results, and the switcher would just push them down.
     */
    sourceSwitcher: (@Composable () -> Unit)? = null,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    // Tapping the search tab from the nav bar sets focusRequested;
    // respond by focusing the field and opening the keyboard.
    LaunchedEffect(focusRequested) {
        if (focusRequested && showField) {
            focusRequester.requestFocus()
            keyboardController?.show()
            onFocusHandled()
        }
    }
    // Search keeps one list state while its contents change. Reset it for each
    // new request so choosing a recent search cannot inherit the history's
    // previous scroll position (or a previous result page's position).
    LaunchedEffect(scrollResetTrigger) {
        if (scrollResetTrigger > 0) listState.scrollToItem(0)
    }
    Column(modifier = modifier.fillMaxSize()) {
        // Search field and filter tabs stay fixed at the top, outside the
        // scrolling list, so they're always reachable rather than scrolling
        // away with the results or recent searches beneath them.
        // The FrostedTopBar is visible on this tab (showing "Search"), so we
        // clear it fully — status bar inset + bar height + breathing gap — so
        // the search field sits cleanly below the bar instead of overlapping it.
        Column(modifier = Modifier.padding(top = topPadding)) {
            if (showField) {
                SearchField(
                    query = query,
                    onQueryChange = onQueryChange,
                    onSubmit = onSubmit,
                    focusRequester = focusRequester,
                    placeholder = if (searchingLibrary) {
                        stringResource(Res.string.search_library_hint)
                    } else {
                        stringResource(Res.string.search_hint)
                    },
                    modifier = Modifier.padding(start = PAGE_GUTTER, end = PAGE_GUTTER, bottom = 4.dp),
                )
            }
            if (sourceSwitcher != null) {
                AnimatedVisibility(
                    visible = query.isEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Box(Modifier.padding(start = PAGE_GUTTER, end = PAGE_GUTTER, bottom = 4.dp)) {
                        sourceSwitcher()
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
        ) {
            if (searchingLibrary) {
                librarySearchResults(
                    query = query.trim(),
                    results = libraryResults,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                    onSongClick = onLibrarySongClick,
                    onSongLongPress = onSongLongPress,
                    onSongSwipe = onSongSwipe,
                )
            } else if (history.isEmpty()) {
                item { MessageState(stringResource(Res.string.search_empty)) }
            } else {
                recentSearches(
                    history = history,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                    onClick = onHistoryClick,
                    onSongLongPress = onSongLongPress,
                    onRemove = onHistoryRemove,
                    onClear = onHistoryClear,
                    onSongSwipe = onSongSwipe,
                )
            }
        }
    }
}

/**
 * The Library source's page: the Local Music folder's tracks that match, as
 * plain song rows. An artist or album hit needs no row of its own — every
 * track under that name matches too, so they are already here.
 */
private fun LazyListScope.librarySearchResults(
    query: String,
    results: UiState<List<Song>>?,
    currentSong: Song?,
    isPlaying: Boolean,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
) {
    when (results) {
        null -> item(key = "library:empty") {
            MessageState(stringResource(Res.string.search_library_empty))
        }
        UiState.Loading -> songListSkeleton(keyPrefix = "skeleton:search:library")
        is UiState.Error -> item(key = "library:error") { MessageState(results.message) }
        is UiState.Success -> if (results.data.isEmpty()) {
            item(key = "library:no-match") {
                MessageState(stringResource(Res.string.search_library_no_results, query))
            }
        } else {
            val tracks = results.data
            val isCurrentRow = playingRowMatcher(tracks, currentSong)
            itemsIndexed(tracks, key = { index, song -> "library_${song.videoId}_$index" }) { index, song ->
                SongRow(
                    song = song,
                    onClick = {
                        onSongClick(tracks, tracks.indexOfFirst { it === song })
                    },
                    onLongPress = { onSongLongPress(song) },
                    onSwipeToQueue = { onSongSwipe(song) },
                    isCurrent = isCurrentRow(song),
                    isPlaying = isPlaying && isCurrentRow(song),
                    searchPlayingStyle = true,
                )
                if (index < tracks.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}




/**
 * What was searched for before, shown in place of the results while the field
 * is empty — the same spot Spotify and Apple Music put it, and the reason the
 * blank search page isn't just a sentence any more.
 */
private fun LazyListScope.recentSearches(
    history: List<SearchHistoryEntity>,
    currentSong: Song?,
    isPlaying: Boolean,
    onClick: (SearchHistoryEntity) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    onSongSwipe: (Song) -> Unit,
) {
    item(key = "recent:header") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PAGE_GUTTER, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.recent_searches),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(Res.string.clear),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
    items(history, key = { "recent:${it.id}" }) { entity ->
        // A recent track gets the same menu and swipe as a result row, so it can
        // be queued without interrupting what's playing. The ids an old build
        // saved as raw queries ("q:…") have no track behind them.
        val song = entity.toSong()
        RecentSearchEntityRow(
            entity = entity,
            isCurrent = entity.entityType == EntityType.TRACK && entity.id == currentSong?.videoId,
            isPlaying = isPlaying,
            onClick = { onClick(entity) },
            onLongPress = song?.let { { onSongLongPress(it) } },
            onRemove = { onRemove(entity.id) },
            onSwipeToQueue = song?.let { { onSongSwipe(it) } },
        )
    }
}

/**
 * Spotify-style entity row: square thumbnail, bold title, subtitle with type,
 * and a removal button. Tapping navigates to the entity or plays it directly;
 * holding a track opens its menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentSearchEntityRow(
    entity: SearchHistoryEntity,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    onRemove: () -> Unit,
    onSwipeToQueue: (() -> Unit)? = null,
) {
    SwipeToQueueBox(
        onSwipeToQueue = onSwipeToQueue,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .longPressMenuClickable(
                    onClick = onClick,
                    onLongClick = onLongPress,
                    liftPadding = PaddingValues(start = PAGE_GUTTER, end = 8.dp),
                )
                .contextClick(onLongPress)
                .padding(start = PAGE_GUTTER, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(52.dp)) {
                AsyncImage(
                    model = entity.artworkUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .thumbnailBorder(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                if (isCurrent && isPlaying) {
                    SearchPlayingBars(Modifier.align(Alignment.Center))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = entity.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = entity.subtitle.ifBlank { entity.entityType.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase(Locale.ROOT) } },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.recent_search_remove, entity.title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}



