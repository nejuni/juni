package com.juni.io.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.juni.io.R
import com.juni.io.data.LocalGroup
import com.juni.io.data.LocalGroups
import com.juni.io.data.LocalMediaRepository
import com.juni.io.data.OfflinePlaylists
import com.juni.io.data.StartupTrace
import com.juni.io.data.localplaylist.LocalPlaylistStore
import com.juni.io.data.lyrics.EmbeddedLyrics
import com.juni.io.data.lyrics.LyricLine
import com.juni.io.data.lyrics.LyricsRepository
import com.juni.io.data.lyrics.LyricsSource
import com.juni.io.data.matchesSearch
import com.juni.io.data.model.BrowseType
import com.juni.io.data.model.CreditJoiner
import com.juni.io.data.model.DetailPage
import com.juni.io.data.model.EntityType
import com.juni.io.data.model.SearchHistoryEntity
import com.juni.io.data.model.Song
import com.juni.io.data.model.UiState
import com.juni.io.data.settings.AppSettings
import com.juni.io.data.settings.SearchHistory
import com.juni.io.playback.BoundedContextRequests
import com.juni.io.playback.LocalListeningHistory
import com.juni.io.playback.contextEntryKey
import com.juni.io.ui.navigation.AppNavigator
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State behind the app's pages.
 *
 * This build plays the device's own files and nothing else, so everything here
 * reads either the device (its folders and the playlists kept on it) or a
 * service that does — there is no account, no catalogue and no remote page.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    // ---- Lyrics -------------------------------------------------------------

    /** Synced lyrics for whatever is playing; null while unknown or absent. */
    private val _lyrics = MutableStateFlow<List<LyricLine>?>(null)
    val lyrics: StateFlow<List<LyricLine>?> = _lyrics.asStateFlow()

    /** Which of the databases [lyrics] came from, for the panel's credit. */
    private val _lyricsSource = MutableStateFlow<LyricsSource?>(null)
    val lyricsSource: StateFlow<LyricsSource?> = _lyricsSource.asStateFlow()

    /**
     * Whether the lookup for the current track has finished. [lyrics] alone
     * can't tell "still looking" apart from "looked, found nothing" — both
     * are null — and the player needs that distinction to show "Lyrics not
     * available" only once it actually means that.
     */
    private val _lyricsChecked = MutableStateFlow(false)
    val lyricsChecked: StateFlow<Boolean> = _lyricsChecked.asStateFlow()

    private var lyricsJob: Job? = null
    private val manualLyricsJobs = mutableMapOf<LyricsSource, Job>()

    private val _lyricsProviderStates = MutableStateFlow(
        LyricsSource.entries.associateWith { LyricsProviderState.NOT_FETCHED },
    )
    val lyricsProviderStates: StateFlow<Map<LyricsSource, LyricsProviderState>> =
        _lyricsProviderStates.asStateFlow()

    /** Completed hits are retained for the playing track so choosing one is instant. */
    private val lyricsProviderResults = ConcurrentHashMap<LyricsSource, LyricsRepository.Result>()

    private data class LyricsRequest(
        val videoId: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val album: String?,
    )

    private var currentLyricsRequest: LyricsRequest? = null
    private var lyricsGeneration = 0L
    private var selectedLyricsSource: LyricsSource? = null

    /**
     * What the loaded lyrics are for. Both the track *and* the settings that
     * chose them, so switching a source on or off re-runs the lookup rather
     * than leaving the last answer sitting on a player that would now find a
     * different one.
     */
    private var lyricsFor: List<Any>? = null

    /**
     * Called as the playing track changes; cheap no-op when already loaded.
     *
     * [localUri] is the file this track plays from when it is on the device,
     * and it is tried before the network: a file that carries its own lyrics
     * has them already (see `LyricsTag`), so asking the same servers again is
     * a round trip to arrive at a string that is on disk — and one that fails
     * outright with the connection off, which is what made a downloaded song
     * show nothing offline.
     */
    fun loadLyrics(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        localUri: String? = null,
    ) {
        val sources = if (AppSettings.syncedLyrics.value) {
            AppSettings.lyricsSources.value
        } else {
            emptySet()
        }
        val key = lyricsLookupKey(
            videoId, sources, AppSettings.lyricsSourceOrder.value, AppSettings.prioritizeSyllableSync.value,
        )
        if (lyricsFor == key) return
        // The duration lands a beat after the track, and a database match needs
        // it. Turned away here rather than inside the job below: claiming the
        // lookup first and giving it up asynchronously means the very re-trigger
        // that carries the duration can arrive while the claim still stands, be
        // dropped as a duplicate, and leave the track marked as being looked up
        // by nobody — which is what left a paused track loading for ever, since
        // pausing is when the duration is most likely to arrive a frame late.
        if (localUri == null && durationMs <= 0L) return
        lyricsFor = key
        lyricsGeneration += 1
        val generation = lyricsGeneration
        currentLyricsRequest = LyricsRequest(videoId, title, artist, durationMs, album)
        selectedLyricsSource = null
        manualLyricsJobs.values.forEach(Job::cancel)
        manualLyricsJobs.clear()
        lyricsProviderResults.clear()
        _lyricsProviderStates.value =
            LyricsSource.entries.associateWith { LyricsProviderState.NOT_FETCHED }
        _lyrics.value = null
        _lyricsSource.value = null
        lyricsJob?.cancel()
        if (sources.isEmpty()) {
            // Switched off, or every source unticked. Nothing to look up, and
            // nothing to say about it — the player drops the lyric strip
            // rather than reporting a track with no lyrics.
            _lyricsChecked.value = true
            return
        }
        _lyricsChecked.value = false
        lyricsJob = viewModelScope.launch {
            // The file first, and without the duration gate below: a length is
            // only needed to *match* a track against a stranger's database, and
            // nothing is being matched here — these lyrics were written into
            // this exact file, for this exact recording.
            if (localUri != null) {
                EmbeddedLyrics.forUri(getApplication(), localUri)?.let { embedded ->
                    _lyrics.value = embedded
                    // No source to name: what the file records is the lyrics,
                    // not which of the eight services they came from months ago.
                    _lyricsSource.value = null
                    _lyricsChecked.value = true
                    return@launch
                }
            }
            if (durationMs <= 0L) {
                // Duration arrives a beat after the track does; wait for it.
                lyricsFor = null
                return@launch
            }
            val found = LyricsRepository.lyrics(
                videoId, title, artist, durationMs, album, sources,
                AppSettings.lyricsSourceOrder.value, AppSettings.prioritizeSyllableSync.value,
                onSourceStarted = { source -> providerStarted(generation, source) },
                onSourceResult = { source, result ->
                    providerFinished(generation, source, result)
                },
                onSourceCancelled = { source -> providerCancelled(generation, source) },
            )
            val selected = selectedLyricsSource?.let(lyricsProviderResults::get) ?: found
            _lyrics.value = selected?.lines
            _lyricsSource.value = selected?.source
            _lyricsChecked.value = true
        }
    }

    /**
     * Selects a provider from the player drawer. Completed hits are applied
     * from memory; misses are inert; only an untouched provider goes online.
     */
    fun selectLyricsProvider(source: LyricsSource) {
        val request = currentLyricsRequest ?: return
        when (_lyricsProviderStates.value[source]) {
            LyricsProviderState.FOUND -> {
                selectedLyricsSource = source
                lyricsProviderResults[source]?.let(::showLyricsResult)
            }
            LyricsProviderState.FETCHING -> {
                // Apply it as soon as the already-running automatic attempt completes.
                selectedLyricsSource = source
            }
            LyricsProviderState.NOT_FETCHED, null -> {
                selectedLyricsSource = source
                fetchLyricsProvider(request, lyricsGeneration, source)
            }
            LyricsProviderState.NOT_FOUND -> Unit
        }
    }

    private fun fetchLyricsProvider(
        request: LyricsRequest,
        generation: Long,
        source: LyricsSource,
    ) {
        if (manualLyricsJobs[source]?.isActive == true) return
        manualLyricsJobs[source] = viewModelScope.launch {
            LyricsRepository.lyrics(
                videoId = request.videoId,
                title = request.title,
                artist = request.artist,
                durationMs = request.durationMs,
                album = request.album,
                sources = setOf(source),
                order = listOf(source),
                prioritizeSyllableSync = false,
                onSourceStarted = { provider -> providerStarted(generation, provider) },
                onSourceResult = { provider, result ->
                    providerFinished(generation, provider, result)
                },
                onSourceCancelled = { provider -> providerCancelled(generation, provider) },
            )
        }
    }

    private fun providerStarted(generation: Long, source: LyricsSource) {
        if (generation != lyricsGeneration) return
        _lyricsProviderStates.update { it + (source to LyricsProviderState.FETCHING) }
    }

    private fun providerFinished(
        generation: Long,
        source: LyricsSource,
        result: LyricsRepository.Result?,
    ) {
        if (generation != lyricsGeneration) return
        if (result == null) {
            _lyricsProviderStates.update { it + (source to LyricsProviderState.NOT_FOUND) }
            return
        }
        lyricsProviderResults[source] = result
        _lyricsProviderStates.update { it + (source to LyricsProviderState.FOUND) }
        if (selectedLyricsSource == source) showLyricsResult(result)
    }

    private fun providerCancelled(generation: Long, source: LyricsSource) {
        if (generation != lyricsGeneration) return
        _lyricsProviderStates.update { states ->
            if (states[source] == LyricsProviderState.FETCHING) {
                states + (source to LyricsProviderState.NOT_FETCHED)
            } else {
                states
            }
        }
        // A tap may have selected a provider while the priority race was still
        // using it. If that race then cancels the loser, honour the tap with a
        // dedicated request instead of leaving the row stuck at "Fetching".
        if (selectedLyricsSource == source) {
            currentLyricsRequest?.let { fetchLyricsProvider(it, generation, source) }
        }
    }

    private fun showLyricsResult(result: LyricsRepository.Result) {
        _lyrics.value = result.lines
        _lyricsSource.value = result.source
        _lyricsChecked.value = true
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

/**
 * What one lyrics lookup for the playing track was made under. The order and
 * the syllable preference pick a different answer from the same providers, so
 * a change in either is a new lookup, as switching a source on or off is.
 */
internal fun lyricsLookupKey(
    videoId: String,
    sources: Set<LyricsSource>,
    order: List<LyricsSource>,
    prioritizeSyllableSync: Boolean,
): List<Any> = listOf(videoId, sources, order, prioritizeSyllableSync)
