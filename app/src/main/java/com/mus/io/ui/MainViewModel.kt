package com.nejuni.juni.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nejuni.juni.R
import com.nejuni.juni.data.LocalGroup
import com.nejuni.juni.data.LocalGroups
import com.nejuni.juni.data.LocalMediaRepository
import com.nejuni.juni.data.OfflinePlaylists
import com.nejuni.juni.data.StartupTrace
import com.nejuni.juni.data.localplaylist.LocalPlaylistStore
import com.nejuni.juni.data.lyrics.EmbeddedLyrics
import com.nejuni.juni.data.lyrics.LyricLine
import com.nejuni.juni.data.matchesSearch
import com.nejuni.juni.data.model.BrowseType
import com.nejuni.juni.data.model.CreditJoiner
import com.nejuni.juni.data.model.DetailPage
import com.nejuni.juni.data.model.EntityType
import com.nejuni.juni.data.model.SearchHistoryEntity
import com.nejuni.juni.data.model.Song
import com.nejuni.juni.data.model.UiState
import com.nejuni.juni.data.settings.AppSettings
import com.nejuni.juni.data.settings.SearchHistory
import com.nejuni.juni.playback.BoundedContextRequests
import com.nejuni.juni.playback.LocalListeningHistory
import com.nejuni.juni.playback.contextEntryKey
import com.nejuni.juni.ui.navigation.AppNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State behind the app's pages.
 *
 * This build plays the device's own files and nothing else, so everything here
 * reads either the device (its folders and the playlists kept on it) or a
 * service that does — there is no account, no catalogue and no remote page.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    // ---- Embedded lyrics ----------------------------------------------------

    private val _lyrics = MutableStateFlow<List<LyricLine>?>(null)
    val lyrics: StateFlow<List<LyricLine>?> = _lyrics.asStateFlow()
    private val _lyricsChecked = MutableStateFlow(false)
    val lyricsChecked: StateFlow<Boolean> = _lyricsChecked.asStateFlow()
    private var lyricsKey: Pair<String, String?>? = null

    fun loadLyrics(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        localUri: String? = null,
    ) {
        val key = videoId to localUri
        if (lyricsKey == key) return
        lyricsKey = key
        _lyrics.value = null
        _lyricsChecked.value = false
        if (!AppSettings.syncedLyrics.value) {
            _lyricsChecked.value = true
            return
        }
        viewModelScope.launch {
            _lyrics.value = localUri?.let { EmbeddedLyrics.forUri(getApplication(), it) }
            _lyricsChecked.value = true
        }
    }

    // ---- Pages on the device -------------------------------------------------

    /**
     * The pages pushed over the tab — a device folder, an album or artist
     * grouped out of one, one of the app's own playlists — as a stack.
     */
    private val _detailStack = MutableStateFlow<List<DetailPage>>(emptyList())
    val detailStack: StateFlow<List<DetailPage>> = _detailStack.asStateFlow()

    /**
     * The app's back stack: the routes pushed over the tab, interleaved with
     * [detailStack]. Held here so it survives a configuration change along
     * with the pages it is ordered against.
     */
    val navigator = AppNavigator(
        detailDepth = { _detailStack.value.size },
        popDetail = ::closeDetail,
        clearDetails = ::clearDetail,
    )

    /** Tapping a tab should leave any pushed page behind. */
    fun clearDetail() {
        if (_detailStack.value.isNotEmpty()) _detailStack.value = emptyList()
        navigator.onDetailStackShrunk(0)
    }

    /**
     * Pushes the page [browseId] names and reads its tracks off the device.
     *
     * Every page this build can open is one of the device's own: a folder, an
     * album or artist grouped out of a folder, the app's own playlist, or the
     * offline Liked playlist. An id that answers to none of those has nothing
     * behind it and opens as an empty page.
     */
    fun openDetail(
        browseId: String,
        title: String,
        subtitle: String = "",
        thumbnailUrl: String? = null,
        type: BrowseType = BrowseType.OTHER,
    ) {
        // A fast double tap used to push two identical loading pages. The page
        // is pushed synchronously, so this closes that window without
        // suppressing a deliberate revisit after the first page loads.
        if (_detailStack.value.lastOrNull()?.let {
                it.browseId == browseId && it.songs is UiState.Loading
            } == true
        ) return
        val resolved = browseTypeOf(browseId, type)
        _detailStack.value += DetailPage(
            browseId = browseId,
            title = title,
            subtitle = subtitle,
            thumbnailUrl = thumbnailUrl,
            songs = UiState.Loading,
            type = resolved,
        )
        viewModelScope.launch {
            var name = title
            var credit: String? = subtitle
            var artwork = thumbnailUrl
            LocalPlaylistStore.getPlaylist(browseId)?.let { playlist ->
                // Named by the store rather than by whatever opened it, which
                // may be a "Playing from" label from before a rename.
                name = playlist.title
                credit = getApplication<Application>()
                    .getString(R.string.local_playlist_subtitle, playlist.songs.size)
                artwork = playlist.songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl
            }
            OfflinePlaylists.get(browseId)?.let { name = it.title }
            val local = localPageState(browseId)
            // Reopened by id alone — the player's "Playing from" — a page off
            // the device arrives with only its title, and takes the cover and
            // credit its own card had instead.
            if (local is UiState.Success) {
                val group = LocalGroups.groupOf(browseId, local.data)
                if (thumbnailUrl == null) artwork = group?.thumbnailUrl
                if (subtitle.isBlank()) credit = group?.subtitle?.ifBlank { null }
            }
            val state = local ?: UiState.Error(text(R.string.no_tracks_here))
            // Update by id — the user may have pushed another page meanwhile.
            _detailStack.value = _detailStack.value.map {
                if (it.browseId == browseId && it.songs is UiState.Loading) {
                    it.copy(
                        songs = state,
                        thumbnailUrl = artwork ?: it.thumbnailUrl,
                        title = name,
                        subtitle = credit ?: it.subtitle,
                    )
                } else {
                    it
                }
            }
        }
    }

    /**
     * Pushes one album, artist or playlist out of a device folder as a page of
     * its own, with the rows the folder already had in hand — so it opens on
     * its tracks rather than on a skeleton while the folder is read again.
     * [reloadLocalDetail] keeps it current from there, like any `local:` page.
     */
    fun openLocalGroup(group: LocalGroup) {
        if (_detailStack.value.lastOrNull()?.browseId == group.browseId) return
        _detailStack.value += DetailPage(
            browseId = group.browseId,
            title = group.title,
            subtitle = group.subtitle,
            thumbnailUrl = group.thumbnailUrl,
            songs = if (group.songs.isEmpty()) UiState.Error(text(R.string.no_tracks_here)) else UiState.Success(group.songs),
            type = group.type,
        )
    }

    /**
     * Opens the device's own page for the artist [name].
     *
     * The credit under a playing track is a string, not an identity — the
     * player splits a byline into the names it reads — so the name is matched
     * against the rows on this device first, and the page it opens is the same
     * Local Music artist page the Artists tab opens. A joint byline is matched
     * through its own separators, so tapping one name on a collaboration
     * reaches the release that names both.
     *
     * @return false when the library is loaded and holds nothing by that name.
     */
    fun openLocalArtist(name: String): Boolean {
        val wanted = name.trim()
        if (wanted.isBlank()) return false
        val songs = localSongsForLookup()
        val match = songs?.firstOrNull { song -> song.creditNames(wanted) }
        if (songs != null && match == null) return false
        val stored = match?.artist?.takeIf { it.isNotBlank() } ?: wanted
        openDetail(
            browseId = LocalGroups.artistPageId(LOCAL_FOLDER_ID, stored),
            title = stored,
            subtitle = "",
            thumbnailUrl = null,
            type = BrowseType.OTHER,
        )
        return true
    }

    /**
     * The album page for [title] among the device's own files — the counterpart
     * to [openLocalArtist] for a track's "Open album" row.
     *
     * @return false when the library is loaded and holds no such album.
     */
    fun openLocalAlbum(title: String, artist: String? = null): Boolean {
        val wanted = title.trim()
        if (wanted.isBlank()) return false
        val songs = localSongsForLookup()
        val match = songs?.firstOrNull { it.albumName.equals(wanted, ignoreCase = true) }
        if (songs != null && match == null) return false
        val stored = match?.albumName?.takeIf { it.isNotBlank() } ?: wanted
        openDetail(
            browseId = LocalGroups.albumPageId(LOCAL_FOLDER_ID, stored),
            title = stored,
            subtitle = artist.orEmpty(),
            thumbnailUrl = null,
            type = BrowseType.ALBUM,
        )
        return true
    }

    /** Whether this row's byline credits [wanted], joint bylines included. */
    private fun Song.creditNames(wanted: String): Boolean {
        if (artist.equals(wanted, ignoreCase = true)) return true
        if (artists.any { it.name.equals(wanted, ignoreCase = true) }) return true
        return CreditJoiner.split(artist).any { it.trim().equals(wanted, ignoreCase = true) }
    }

    /** The device rows a name can be resolved against, when they are in hand. */
    private fun localSongsForLookup(): List<Song>? =
        (_detailStack.value.firstOrNull { it.browseId == LOCAL_FOLDER_ID }?.songs as? UiState.Success)?.data
            ?: (_librarySongs.value as? UiState.Success)?.data

    /**
     * Scans the device library now, and repaints every open local page from the
     * result.
     *
     * This is the only read of MediaStore beyond the very first run: launch
     * answers from the snapshot, and a scan happens when the listener asks for
     * one — here, or by changing the folder/filter settings, which is the same
     * request in another form.
     */
    fun rescanLibrary() {
        if (_libraryScanning.value) return
        viewModelScope.launch {
            _libraryScanning.value = true
            try {
                val context = getApplication<Application>()
                // A failed scan answers with the last known list, so a failure
                // never wipes the pages below.
                LocalMediaRepository.getLocalMusic(context, fresh = true)
                _libraryScannedAt.value = LocalMediaRepository.lastScanAt(context)
                val state = if (!LocalMediaRepository.hasStoragePermission(context)) {
                    UiState.Error(text(R.string.storage_required_read))
                } else {
                    localPageState(LOCAL_FOLDER_ID) ?: UiState.Error(text(R.string.no_local_audio_found))
                }
                _librarySongs.value = state
                val stack = _detailStack.value
                val updated = ArrayList<DetailPage>(stack.size)
                for (page in stack) {
                    val local = page.browseId == LOCAL_FOLDER_ID ||
                        LocalGroups.folderOf(page.browseId) != null
                    updated += if (local) {
                        page.copy(songs = localPageState(page.browseId) ?: page.songs)
                    } else {
                        page
                    }
                }
                _detailStack.value = updated
            } finally {
                _libraryScanning.value = false
            }
        }
    }

    /** Renames an open page in place — a device playlist, whose title is the store's. */
    fun retitleDetail(browseId: String, title: String) {
        if (title.isBlank()) return
        _detailStack.value = _detailStack.value.map { if (it.browseId == browseId) it.copy(title = title) else it }
    }

    fun reloadLocalDetail(browseId: String) {
        viewModelScope.launch {
            val state = localPageState(browseId) ?: return@launch
            _detailStack.value = _detailStack.value.map {
                if (it.browseId == browseId) {
                    it.copy(songs = state)
                } else it
            }
        }
    }

    /**
     * The listing behind a page whose tracks come from this device — a device
     * folder, a playlist in the app's own store, the offline Liked playlist, or
     * one album or artist grouped out of a folder — or null for every other
     * page.
     *
     * The one place each of those is read, so opening a page, refreshing it
     * and queueing it from a menu can't disagree about what is on it.
     */
    private suspend fun localPageState(browseId: String): UiState<List<Song>>? {
        val context = getApplication<Application>()
        fun nonEmpty(songs: List<Song>, empty: () -> String): UiState<List<Song>> =
            if (songs.isEmpty()) UiState.Error(empty()) else UiState.Success(songs)

        if (OfflinePlaylists.isOfflinePlaylist(browseId)) {
            // A playlist deleted while its page was still reachable — from
            // "Playing from", say — is an empty page, not a lookup.
            val playlist = OfflinePlaylists.get(browseId) ?: return UiState.Error(text(R.string.no_tracks_here))
            return nonEmpty(LocalMediaRepository.present(context, playlist.songs)) {
                text(if (playlist.isLiked) R.string.offline_liked_empty else R.string.offline_playlist_empty)
            }
        }
        LocalPlaylistStore.getPlaylist(browseId)?.let { playlist ->
            return nonEmpty(playlist.songs) { text(R.string.spotify_import_empty_playlist) }
        }
        LocalGroups.folderOf(browseId)?.let { folderId ->
            // Read off a fresh listing of the folder it came from: a grouping
            // has nothing of its own to read, and whatever the folder says
            // when it can't be listed is what this page says too.
            return when (val folder = localPageState(folderId)) {
                is UiState.Success -> nonEmpty(LocalGroups.songsOf(browseId, folder.data)) { text(R.string.no_tracks_here) }
                null -> UiState.Error(text(R.string.no_tracks_here))
                else -> folder
            }
        }
        return when {
            browseId == LOCAL_FOLDER_ID -> if (!LocalMediaRepository.hasStoragePermission(context)) {
                UiState.Error(text(R.string.storage_required_read))
            } else {
                // This build scans when asked to. The launch snapshot answers
                // every open but the first, and the first has nothing to answer
                // with yet, so that is the one automatic read.
                val cached = LocalMediaRepository.cachedLocalMusic(context)
                if (cached != null) {
                    StartupTrace.log("page", "library page ready — snapshot, ${cached.size} songs")
                    nonEmpty(cached) { text(R.string.no_local_audio_found) }
                } else {
                    val songs = LocalMediaRepository.getLocalMusic(context)
                    _libraryScannedAt.value = LocalMediaRepository.lastScanAt(context)
                    StartupTrace.log("page", "library page ready — first scan, ${songs.size} songs")
                    nonEmpty(songs) { text(R.string.no_local_audio_found) }
                }
            }
            else -> null
        }
    }


    /**
     * Applies the service-owned canonical context to its matching open page.
     *
     * The service only materialises a small queue window, while its registry
     * keeps the complete canonical list. Mirroring that registry here avoids a
     * page that appears to stop growing as soon as playback starts.
     */
    private fun applyBoundedContextSnapshot(snapshot: BoundedContextRequests.Snapshot) {
        val browseId = snapshot.request.source.id ?: return
        val stack = _detailStack.value
        val index = stack.indexOfLast { it.browseId == browseId }
        if (index < 0) return
        val page = stack[index]
        if (page.type != BrowseType.ALBUM && page.type != BrowseType.PLAYLIST) return
        val existing = (page.songs as? UiState.Success)?.data ?: return
        val known = existing.mapTo(HashSet()) { it.contextEntryKey() }
        val added = snapshot.songs
            .filter { known.add(it.contextEntryKey()) }
            .withArtwork(page.thumbnailUrl)
        if (added.isEmpty()) return
        _detailStack.value = stack.toMutableList().also {
            it[index] = page.copy(songs = UiState.Success(existing + added))
        }
    }

    /**
     * Returns the newest page snapshot for a bounded playback handoff. The
     * page's own listing is already complete — a device folder is read whole —
     * so this only mirrors whatever the service has taken.
     */
    fun takePlaybackContext(browseId: String): DetailPage? {
        val stack = _detailStack.value
        val index = stack.indexOfLast { it.browseId == browseId }
        if (index < 0) return null
        val mirrored = BoundedContextRequests.snapshot.value
            ?.takeIf { it.request.source.id == browseId }
        if (mirrored != null) {
            applyBoundedContextSnapshot(mirrored)
            return _detailStack.value.getOrNull(index)
        }
        return stack[index]
    }

    /**
     * A page's rows don't always repeat its cover — the page carries it once —
     * so rows arrive with no artwork and stay blank through to the queue and
     * the notification. Fall back to the page's.
     */
    private fun List<Song>.withArtwork(fallback: String?): List<Song> {
        if (fallback == null) return this
        return map { if (it.thumbnailUrl == null) it.copy(thumbnailUrl = fallback) else it }
    }

    /** Pops one page; returns false when there was nothing to pop. */
    fun closeDetail(): Boolean {
        val stack = _detailStack.value
        if (stack.isEmpty()) return false
        _detailStack.value = stack.dropLast(1)
        navigator.onDetailStackShrunk(_detailStack.value.size)
        return true
    }

    /**
     * Every track behind a device folder, album, artist or playlist, handed to
     * [onResult] once it is all in.
     *
     * What a long-press on a card is acting on: a card has nothing but an id —
     * its page was never opened, so there is no track list anywhere to read.
     */
    fun collectSongs(
        browseId: String,
        artworkFallback: String? = null,
        onResult: (Result<List<Song>>) -> Unit,
    ) {
        viewModelScope.launch {
            val result = when (val local = localPageState(browseId)) {
                is UiState.Success -> Result.success(local.data)
                is UiState.Error -> Result.failure(IllegalStateException(local.message))
                UiState.Loading -> Result.failure(IllegalStateException(text(R.string.no_tracks_here)))
                null -> Result.failure(IllegalStateException(text(R.string.no_tracks_here)))
            }
            onResult(result.map { it.withArtwork(artworkFallback) })
        }
    }

    // ---- Listening history ---------------------------------------------------

    private val _history = MutableStateFlow<UiState<List<Song>>>(UiState.Loading)
    val history: StateFlow<UiState<List<Song>>> = _history.asStateFlow()

    /** The device's local-file listening history, most recent first. */
    fun loadHistory() {
        val songs = LocalListeningHistory.songs().asReversed()
        _history.value = if (songs.isEmpty()) {
            UiState.Error(text(R.string.history_empty))
        } else {
            UiState.Success(songs)
        }
    }

    /**
     * Forgets [song] in the device's own history — the History page's remove.
     *
     * The list on screen is updated in place rather than re-read, so a row
     * taken away does not flash the whole page back through a reload.
     */
    fun removeFromHistory(song: Song) {
        LocalListeningHistory.remove(song)
        val current = _history.value
        if (current is UiState.Success) {
            val remaining = current.data.filterNot { it.localUri != null && it.localUri == song.localUri }
            _history.value = if (remaining.isEmpty()) {
                UiState.Error(text(R.string.history_empty))
            } else {
                UiState.Success(remaining)
            }
        }
    }

    // ---- Search over the device ---------------------------------------------

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Increments once per query reset so the UI can reset its list. */
    private val _searchScrollReset = MutableStateFlow(0)
    val searchScrollReset: StateFlow<Int> = _searchScrollReset.asStateFlow()

    /** Every track in the Local Music folder, read when the tab is opened. */
    private val _librarySongs = MutableStateFlow<UiState<List<Song>>>(UiState.Loading)

    /** True while an explicit "Scan library" is running, for its settings row. */
    private val _libraryScanning = MutableStateFlow(false)
    val libraryScanning: StateFlow<Boolean> = _libraryScanning.asStateFlow()

    /** When the listing now in use was scanned; 0 until there is one. */
    private val _libraryScannedAt = MutableStateFlow(0L)
    val libraryScannedAt: StateFlow<Long> = _libraryScannedAt.asStateFlow()

    /**
     * The Search tab's answer for the current query: the Local Music folder
     * narrowed by the same match its own filter box uses, so the two places
     * never disagree about what is on the device. Null while the field is
     * empty — there is nothing asked yet.
     */
    val libraryResults: StateFlow<UiState<List<Song>>?> = combine(_librarySongs, _query) { state, query ->
        val term = query.trim()
        when {
            term.isEmpty() -> null
            state is UiState.Success -> UiState.Success(state.data.filter { it.matchesSearch(term) })
            else -> state
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Recent searches, kept on device as rich entity records. */
    val searchHistory: StateFlow<List<SearchHistoryEntity>> = SearchHistory.recent

    /**
     * Records a full entity payload in the history — used when the user taps a
     * result row so the recents list shows real artwork and metadata instead
     * of raw text.
     */
    fun recordEntity(entity: SearchHistoryEntity) = SearchHistory.record(entity)

    fun removeSearch(id: String) = SearchHistory.remove(id)

    fun clearSearchHistory() = SearchHistory.clear()

    fun onQueryChange(newValue: String) {
        _query.value = newValue
        if (newValue.isBlank()) {
            // The list is about to switch from results to recent searches (or
            // the empty state) — without this it can keep whatever scroll
            // offset the results list was left at, landing the new, much
            // shorter list somewhere other than the top.
            _searchScrollReset.value += 1
        }
    }

    /**
     * Commits the current query text: the term goes into the recent searches.
     *
     * The answer itself needs no request — [libraryResults] is already the
     * device's own list, filtered as each letter arrives.
     */
    fun submitSearch() {
        val q = _query.value.trim()
        if (q.isEmpty()) return
        recordEntity(
            SearchHistoryEntity(
                id = "q:$q",
                title = q,
                subtitle = "",
                artworkUrl = null,
                entityType = EntityType.TRACK,
            ),
        )
    }

    /**
     * Reads the Local Music folder for the Search tab. Re-read on every open,
     * so files added since — or a permission granted since — are in the next
     * answer; the last list stays up meanwhile.
     */
    fun loadLibrarySongs() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            if (!LocalMediaRepository.hasStoragePermission(context)) {
                _librarySongs.value = UiState.Error(text(R.string.storage_required_read))
                return@launch
            }
            // The launch snapshot answers the first open; the query behind it
            // replaces the list only when the library has changed.
            if (_librarySongs.value !is UiState.Success) {
                LocalMediaRepository.cachedLocalMusic(context)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { _librarySongs.value = UiState.Success(it) }
            }
            val songs = runCatching { LocalMediaRepository.getLocalMusic(context) }.getOrNull()
            _librarySongs.value = if (songs.isNullOrEmpty()) {
                UiState.Error(text(R.string.no_local_audio_found))
            } else {
                UiState.Success(songs)
            }
        }
    }

    init {
        viewModelScope.launch {
            _libraryScannedAt.value = LocalMediaRepository.lastScanAt(getApplication())
        }
        viewModelScope.launch {
            BoundedContextRequests.snapshot.collect { snapshot ->
                snapshot?.let(::applyBoundedContextSnapshot)
            }
        }
    }

    companion object {
        /** The one device-folder page this build has: the whole library. */
        private const val LOCAL_FOLDER_ID = "local:all"

        fun browseTypeOf(browseId: String, fallback: BrowseType = BrowseType.OTHER): BrowseType = when {
            browseId.startsWith(LocalGroups.PREFIX) -> LocalGroups.typeOf(browseId) ?: fallback
            browseId.startsWith(OfflinePlaylists.PREFIX) -> BrowseType.PLAYLIST
            browseId.startsWith("local:playlist:") -> BrowseType.PLAYLIST
            else -> fallback
        }
    }

    /** Public because the long-press menus ask the same question of a card. */
    fun browseTypeOf(browseId: String, fallback: BrowseType = BrowseType.OTHER): BrowseType =
        Companion.browseTypeOf(browseId, fallback)

    private fun text(id: Int): String = getApplication<Application>().getString(id)
}

