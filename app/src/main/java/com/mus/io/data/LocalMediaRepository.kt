package com.nejuni.juni.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.nejuni.juni.data.DebugLog as Log
import androidx.core.content.ContextCompat
import com.nejuni.juni.data.model.Song
import com.nejuni.juni.data.StartupTrace
import com.nejuni.juni.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.ArrayDeque

object LocalMediaRepository {

    private const val TAG = "juni"
    private const val MIN_LOCAL_MUSIC_DURATION_MS = 30_000L

    data class ScanProgress(val discovered: Int = 0, val scanned: Int = 0, val phase: String = "")
    private val _scanProgress = MutableStateFlow(ScanProgress())
    val scanProgress: StateFlow<ScanProgress> = _scanProgress.asStateFlow()

    /** Serialises scans: the launch page and the Search tab can ask at once. */
    private val scanMutex = Mutex()

    /** The last scan in this process, so asks a moment apart share one answer. */
    @Volatile
    private var lastScanSongs: List<Song>? = null

    @Volatile
    private var lastScanAt = 0L

    /** How long a finished scan answers a repeat ask without querying again. */
    private const val SCAN_REUSE_MS = 4_000L

    // `mp4` only ever reaches this list as an audio row — MediaStore files a
    // video .mp4 under Video, not Audio — so it is the audio-only MP4s Dolby
    // and Apple music downloads come as (AC-4, E-AC-3, ALAC). `dsf`/`dff` are
    // DSD, which DsdExtractor plays.
    private val localMusicExtensions = setOf(
        "mp3", "m4a", "mp4", "flac", "ogg", "opus", "oga", "aac",
        "wav", "wave", "aif", "aiff", "alac", "webm", "dsf", "dff", "ape", "wv",
        "mpc", "mka", "ac3", "ec3", "amr", "3gp", "dts", "tta", "tak", "thd",
        "truehd", "caf", "au", "snd",
    )

    private val nonMusicPathSegments = listOf(
        "/alarms/",
        "/notifications/",
        "/ringtones/",
        "/podcasts/",
        "/audiobooks/",
        "/recordings/",
        "/voice recorder/",
        "/sound_recorder/",
        "/call_rec/",
        "/whatsapp voice notes/",
    )
    private val nonMusicFolderNames = setOf(
        "alarms", "notifications", "ringtones", "podcasts", "audiobooks", "recordings",
        "voice recorder", "sound_recorder", "call_rec", "whatsapp voice notes",
    )

    /** Check if storage/audio permission is granted to query device local music. */
    fun hasStoragePermission(context: Context): Boolean {
        val treeUri = AppSettings.localMusicFolderUri.value
        if (treeUri.isNotBlank()) {
            val uri = Uri.parse(treeUri)
            val hasPersistedGrant = context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }
            if (hasPersistedGrant) return true
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }


    /**
     * The parts of a scanner row worth reading back — everything else about a
     * download is better known from the record that made it.
     */
    private class ScannedTags(
        val albumName: String?,
        val artworkUrl: String?,
        val dateAddedSeconds: Long?,
        val dateModifiedSeconds: Long?,
        val sizeBytes: Long? = null,
    )

    /** What MediaStore writes into a column it has nothing for. */
    private fun String?.cleanTag(): String? =
        takeUnless { it.isNullOrBlank() || it == "<unknown>" }

    /**
     * A way for a build to look beyond MediaStore — the Android TV app reads USB
     * drives MediaStore does not index. Handed what MediaStore found (nothing,
     * without the storage permission) and returns the list to use. Null on the
     * phone, where MediaStore is the whole story.
     */
    @Volatile
    var extraLocalMusic: (suspend (Context, List<Song>) -> List<Song>)? = null

    /**
     * The device's music.
     *
     * Without [fresh] this answers from the last scan â€” the one in memory, then
     * the launch snapshot â€” and only queries MediaStore on the very first run,
     * when there is no snapshot yet. An explicit scan ([fresh]) is what the
     * "Scan library" action runs, and what a change to the folder or filter
     * settings asks for.
     */
    suspend fun getLocalMusic(context: Context, fresh: Boolean = false): List<Song> =
        withContext(Dispatchers.IO) {
            if (!hasStoragePermission(context)) {
                return@withContext extraLocalMusic?.invoke(context, emptyList()) ?: emptyList()
            }
            if (!fresh) {
                recentScan()?.let { return@withContext it }
                LocalLibraryCache.cached(context)?.let {
                    StartupTrace.log("library", "snapshot hit — ${it.size} songs, no scan")
                    return@withContext it
                }
            }
            scanMutex.withLock {
                if (!fresh) recentScan()?.let { return@withLock it }
                // Two settings changes landing together (or a double tap) ask
                // for the same scan; the second waits here and takes the first
                // one's answer instead of running it again.
                lastScanSongs?.takeIf {
                    SystemClock.elapsedRealtime() - lastScanAt < SCAN_REUSE_MS
                }?.let { return@withLock it }
                val scanStarted = SystemClock.elapsedRealtime()
                StartupTrace.log("library", "scan start")
                val songs = scanLocalMusic(context)
                _scanProgress.value = ScanProgress()
                lastScanAt = SystemClock.elapsedRealtime()
                StartupTrace.log(
                    "library",
                    if (songs == null) {
                        "scan failed after ${lastScanAt - scanStarted}ms"
                    } else {
                        "scan done — ${songs.size} songs in ${lastScanAt - scanStarted}ms"
                    },
                )
                if (songs != null) {
                    lastScanSongs = songs
                    // However empty: "there is no music here" is an answer worth
                    // remembering, and only a failed query returns null.
                    LocalLibraryCache.update(context, songs)
                    return@withLock songs
                }
                // A failed query leaves the last known answer standing.
                lastScanSongs ?: LocalLibraryCache.cached(context) ?: emptyList()
            }
        }

    /** The launch snapshot, without touching MediaStore. */
    suspend fun cachedLocalMusic(context: Context): List<Song>? = withContext(Dispatchers.IO) {
        if (!hasStoragePermission(context)) {
            null
        } else {
            val cached = LocalLibraryCache.cached(context)
            StartupTrace.log("library", "snapshot read — ${cached?.size ?: 0} songs")
            cached
        }
    }

    /** When the snapshot behind [cachedLocalMusic] was taken; 0 if none. */
    suspend fun lastScanAt(context: Context): Long = withContext(Dispatchers.IO) {
        if (!hasStoragePermission(context)) 0L else LocalLibraryCache.savedAt(context)
    }

    private fun recentScan(): List<Song>? =
        lastScanSongs?.takeIf { SystemClock.elapsedRealtime() - lastScanAt < SCAN_REUSE_MS }

    /**
     * The query itself, or null when it could not be run. Null is not "no
     * music": it is the one answer that must not replace the snapshot.
     */
    private suspend fun scanLocalMusic(context: Context): List<Song>? {
        val treeUri = AppSettings.localMusicFolderUri.value
        if (treeUri.isNotBlank()) {
            val folderScan = runCatching { scanGrantedTree(context, Uri.parse(treeUri)) }
            folderScan.onFailure { Log.w(TAG, "SAF folder scan failed: ${it.message}") }
            if (folderScan.isSuccess) {
                LocalLibraryCache.clearScanCheckpoint(context)
                _scanProgress.value = ScanProgress()
                return extraLocalMusic?.invoke(context, folderScan.getOrThrow()) ?: folderScan.getOrThrow()
            }
        }
        _scanProgress.value = ScanProgress(phase = "MediaStore fallback")
        val songs = mutableListOf<Song>()
        // This scan runs over every audio file on the device, which includes
        // whatever this app has downloaded into Music/bit alongside
        // everything else — but by content URI, the only thing MediaStore
        // offers here, that download is indistinguishable from a file the
        // user copied on by hand. Reversing [Downloads.saved] hands a
        // downloaded track its real YouTube id back, which is what lets
        // PlaybackTracker recognise it as a video worth registering a play
        // for — a content URI fails its id check on purpose, since most rows
        // here really are just local files with nothing to sync.

        val filterNonMusic = AppSettings.filterNonMusicAudio.value
        val selectedTree = AppSettings.localMusicFolderUri.value
        val selectedPath = if (selectedTree.isBlank()) null else selectedFolder(selectedTree)
        val selection = if (filterNonMusic) {
            buildString {
                append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
                // A duration of 0 or NULL is the scanner failing to read one,
                // not a short clip — fragmented MP4 (how ALAC downloads often
                // come) reads as 0, high-rate DSD as NULL — so it is let
                // through to the extension and path checks rather than
                // dropped as too short.
                append(
                    " AND (${MediaStore.Audio.Media.DURATION} >= ?" +
                        " OR ${MediaStore.Audio.Media.DURATION} <= 0" +
                        " OR ${MediaStore.Audio.Media.DURATION} IS NULL)",
                )
                append(" AND ${MediaStore.Audio.Media.IS_ALARM} = 0")
                append(" AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0")
                append(" AND ${MediaStore.Audio.Media.IS_RINGTONE} = 0")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    append(" AND ${MediaStore.Audio.Media.IS_PODCAST} = 0")
                }
            }
        } else {
            "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        }
        val selectionArgs = if (filterNonMusic) {
            arrayOf(MIN_LOCAL_MUSIC_DURATION_MS.toString())
        } else {
            null
        }
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        val queried = runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                AUDIO_PROJECTION,
                selection,
                selectionArgs,
                sortOrder,
            )?.use { cursor ->
                val columns = AudioColumns(cursor)
                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(columns.displayName).orEmpty()
                    val durationMs = cursor.getLong(columns.duration)
                    val path = cursor.getString(columns.data)

                    if (filterNonMusic && !isEligibleLocalMusic(durationMs, displayName, path)) continue
                    if (selectedTree.isNotBlank() && !isInFolder(path, selectedPath)) continue
                    val song = columns.song(cursor)
                    songs.add(
                        if (AppSettings.highResCoverArt.value) {
                            song.copy(
                                highResArtworkUrl = extractHighResArtwork(
                                    context = context,
                                    sourceUri = song.localUri.orEmpty(),
                                ),
                            )
                        } else song,
                    )
                    if (songs.size % 100 == 0) _scanProgress.value = ScanProgress(songs.size, songs.size, "MediaStore fallback")
                }
            }
        }
        queried.onFailure { Log.w(TAG, "Failed scanning device local music: ${it.message}") }
        if (queried.isFailure) return null

        return extraLocalMusic?.invoke(context, songs) ?: songs
    }

    /** Recursively walks the exact folder grant. It reads metadata only, never audio contents. */
    private suspend fun scanGrantedTree(context: Context, treeUri: Uri): List<Song> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val dirs = ArrayDeque<String>()
        dirs.add(rootId)
        val candidates = ArrayList<Triple<String, String, ScannedTags>>()
        val filterNonMusic = AppSettings.filterNonMusicAudio.value
        val visited = HashSet<String>()
        _scanProgress.value = ScanProgress(phase = "Finding audio files")
        while (dirs.isNotEmpty()) {
            val parent = dirs.removeFirst()
            if (!visited.add(parent)) continue
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent)
            resolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null, null, null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idCol) ?: continue
                    val name = cursor.getString(nameCol).orEmpty()
                    val mime = cursor.getString(mimeCol).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!filterNonMusic || name.lowercase(Locale.ROOT) !in nonMusicFolderNames) dirs.addLast(id)
                    } else if (isAudioCandidate(name)) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString()
                        val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L
                        val modified = if (modifiedCol >= 0 && !cursor.isNull(modifiedCol)) cursor.getLong(modifiedCol) else 0L
                        candidates += Triple(uri, name, ScannedTags(
                            albumName = null,
                            artworkUrl = null,
                            dateAddedSeconds = null,
                            dateModifiedSeconds = modified.takeIf { it > 0L }?.div(1000L),
                            sizeBytes = size.takeIf { it > 0L },
                        ))
                    }
                }
            } ?: throw IllegalStateException("Folder provider returned no listing")
            if (visited.size % 25 == 0) _scanProgress.value = ScanProgress(candidates.size, 0, "Finding audio files")
        }

        val result = ArrayList<Song>(candidates.size)
        val highResEnabled = AppSettings.highResCoverArt.value
        val checkpointKey = "$treeUri|filterNonMusic=$filterNonMusic|highResArt=$highResEnabled"
        val validUris = candidates.asSequence().map { it.first }.toHashSet()
        val resumed = LocalLibraryCache.scanCheckpoint(context, checkpointKey)
            .orEmpty()
            .filter { it.localUri in validUris }
        result.addAll(resumed)
        val completedUris = resumed.mapNotNullTo(HashSet()) { it.localUri }
        _scanProgress.value = ScanProgress(candidates.size, result.size, "Reading audio metadata")
        var sinceCheckpoint = 0
        var attemptedCount = 0
        candidates.forEachIndexed { index, (uri, name, tags) ->
            if (uri !in completedUris) {
                runCatching { buildSongFromUri(context, uri, name, tags) }
                    .onSuccess { song ->
                        val durationMs = song.durationText?.let(::durationTextToMillis)
                        val inFilteredFolder = !filterNonMusic || durationMs == null || durationMs >= MIN_LOCAL_MUSIC_DURATION_MS
                        if (inFilteredFolder) result += song
                        completedUris += uri
                        sinceCheckpoint++
                    }
                attemptedCount++
                if (sinceCheckpoint >= 32) {
                    LocalLibraryCache.saveScanCheckpoint(context, checkpointKey, result)
                    sinceCheckpoint = 0
                }
            }
            if ((index + 1) % 16 == 0 || index == candidates.lastIndex) {
                _scanProgress.value = ScanProgress(candidates.size, (resumed.size + attemptedCount).coerceAtMost(candidates.size), "Reading audio metadata")
                kotlinx.coroutines.yield()
            }
        }
        result.sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    private fun isAudioCandidate(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in localMusicExtensions

    private fun durationTextToMillis(value: String): Long? {
        val parts = value.split(':').mapNotNull { it.toLongOrNull() }
        if (parts.size != 2) return null
        return (parts[0] * 60L + parts[1]) * 1_000L
    }

    private val AUDIO_PROJECTION = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.DATE_ADDED,
        MediaStore.Audio.Media.DATE_MODIFIED,
        MediaStore.Audio.Media.SIZE,
        MediaStore.Audio.Media.TRACK,
    )

    /**
     * Where [AUDIO_PROJECTION]'s columns landed in one cursor, looked up once
     * rather than per row — and the one reading of a row into a [Song], so a
     * file opened from a file manager comes out exactly as the same file does
     * in Local Music. That sameness is what lets a like or a playlist entry
     * made from either be found from the other.
     */
    private class AudioColumns(cursor: Cursor) {
        val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val displayName = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val data = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
        val dateAdded = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
        val dateModified = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
        val size = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
        val track = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)

        fun song(cursor: Cursor): Song {
            val rowId = cursor.getLong(id)
            val name = cursor.getString(displayName).orEmpty()
            val artId = cursor.getLong(albumId)
            val durationMs = cursor.getLong(duration)
            val path = cursor.getString(data)
            val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, rowId).toString()
            val songTitle = cursor.getString(title).cleanTag()?.removeAudioExtension()
                ?: name.substringBeforeLast('.').takeIf { it.isNotBlank() }
                ?: "Track $rowId"
            val trackNumber = if (track >= 0 && !cursor.isNull(track)) {
                trackNumberFromMediaStore(cursor.getInt(track))
            } else {
                null
            }
            return Song(
                videoId = contentUri,
                title = songTitle,
                artist = cursor.getString(artist).cleanTag() ?: "Unknown Artist",
                thumbnailUrl = if (artId > 0) ContentUris.withAppendedId(ALBUM_ART_URI, artId).toString() else null,
                durationText = durationMs.takeIf { it > 0 }?.let { formatDuration(it) },
                albumName = cursor.getString(album).cleanTag(),
                localUri = contentUri,
                localPath = path,
                localDateAddedSeconds = cursor.getLong(dateAdded),
                localDateModifiedSeconds = cursor.getLong(dateModified),
                localFileSizeBytes = cursor.getLong(size).takeIf { it > 0L }
                    ?: path?.let { File(it).length().takeIf { bytes -> bytes > 0L } },
                trackNumber = trackNumber,
            )
        }
    }

    // Lazy so loading this object doesn't touch the framework: unit tests of
    // the pure helpers here run against a stub Uri.parse that returns null.
    private val ALBUM_ART_URI: Uri by lazy { Uri.parse("content://media/external/audio/albumart") }

    /**
     * Which of [songs] still have a file behind them, in their order — for an
     * offline playlist, whose record outlives a file deleted from under it.
     *
     * One MediaStore query for every row it indexes, by id, rather than a
     * listing of the whole device; a `file://` row is asked of the disk. Rows
     * this can't check — another provider's, or every row while the storage
     * permission is missing — are kept: hiding a track because it couldn't be
     * looked at would empty a playlist over a permission prompt.
     */
    suspend fun present(context: Context, songs: List<Song>): List<Song> = withContext(Dispatchers.IO) {
        val mediaIds = songs.mapNotNull { it.localUri?.mediaStoreAudioId() }
        val existing = HashSet<Long>()
        val canAsk = mediaIds.isNotEmpty() && runCatching {
            mediaIds.distinct().chunked(500).forEach { chunk ->
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID),
                    "${MediaStore.Audio.Media._ID} IN (${chunk.joinToString(",")})",
                    null,
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) existing += cursor.getLong(0)
                }
            }
        }.isSuccess
        // MediaStore only answers for files this app may see. Without the
        // permission its silence is not an answer, except for this app's own
        // downloads, which it always sees.
        val trustMissing = canAsk && hasStoragePermission(context)
        songs.filter { song ->
            val uri = song.localUri ?: return@filter false
            val id = uri.mediaStoreAudioId()
            when {
                id != null -> id in existing || !trustMissing
                uri.startsWith("file://") -> Uri.parse(uri).path?.let { File(it).exists() } != false
                else -> true
            }
        }
    }

    /** The row id of a `content://media/…/audio/media/<id>` uri, else null. */
    private fun String.mediaStoreAudioId(): Long? {
        if (!startsWith("content://media/")) return null
        val uri = Uri.parse(this)
        val segments = uri.pathSegments
        if (segments.size < 2 || segments[segments.size - 2] != "media" || "audio" !in segments) return null
        return segments.last().toLongOrNull()
    }

    /**
     * A file another app handed over to be played — "Open with" from a file
     * manager, a tap on an attachment.
     *
     * Read as Local Music would read it whenever MediaStore indexes it, so a
     * heart or a playlist entry made from here is the same entry the Local
     * Music folder shows; otherwise off the file's own tags, under the name
     * the sending app gave it.
     */
    suspend fun songFromUri(context: Context, uri: Uri): Song = withContext(Dispatchers.IO) {
        mediaStoreRow(context, uri)?.let { return@withContext it }
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Audio"
        buildSongFromUri(context, uri.toString(), name)
    }

    /** The MediaStore row behind [uri], when there is one this app may read. */
    private fun mediaStoreRow(context: Context, uri: Uri): Song? = runCatching {
        val resolver = context.contentResolver
        val (target, selection, args) = when {
            uri.scheme == "file" -> {
                val path = uri.path ?: return@runCatching null
                Triple(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "${MediaStore.Audio.Media.DATA} = ?", arrayOf(path))
            }
            uri.authority == MediaStore.AUTHORITY -> Triple(uri, null, null)
            // A documents-provider uri (most file managers) names the same
            // file MediaStore does under another id; API 29 can translate.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && DocumentsContract.isDocumentUri(context, uri) ->
                Triple(MediaStore.getMediaUri(context, uri) ?: return@runCatching null, null, null)
            else -> return@runCatching null
        }
        resolver.query(target, AUDIO_PROJECTION, selection, args, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            AudioColumns(cursor).song(cursor)
        }
    }.getOrNull()

    /** Human-readable path for the folder setting without exposing provider internals. */
    fun selectedFolderLabel(treeUri: String): String? = selectedFolder(treeUri)?.label

    private fun isInFolder(path: String?, folder: SelectedFolder?): Boolean {
        if (folder == null) return false
        val candidate = path?.normalizedPath() ?: return false
        return candidate == folder.absolutePath || candidate.startsWith("${folder.absolutePath}/")
    }

    private data class SelectedFolder(val absolutePath: String, val label: String)

    private fun selectedFolder(treeUri: String): SelectedFolder? = runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
        if (documentId.startsWith("raw:")) {
            val path = documentId.removePrefix("raw:").normalizedPath()
            return@runCatching SelectedFolder(path, path.substringAfterLast('/'))
        }
        val volume = documentId.substringBefore(':')
        val relative = documentId.substringAfter(':', "").trim('/')
        val volumeRoot = if (volume.equals("primary", ignoreCase = true)) {
            "/storage/emulated/0"
        } else {
            "/storage/$volume"
        }
        SelectedFolder(
            absolutePath = listOf(volumeRoot, relative).filter { it.isNotBlank() }.joinToString("/").normalizedPath(),
            label = relative.ifBlank { volume },
        )
    }.getOrNull()

    private fun String.normalizedPath(): String =
        replace('\\', '/').trimEnd('/').lowercase(Locale.ROOT)

    /**
     * MediaStore's `IS_MUSIC` flag is advisory and often includes notification
     * sounds, voice notes and recorder output. Keep this second gate independent
     * of scanner metadata so the same bad rows stay out across Android vendors.
     */
    internal fun isEligibleLocalMusic(durationMs: Long, displayName: String, path: String?): Boolean {
        if (durationMs in 1 until MIN_LOCAL_MUSIC_DURATION_MS) return false

        val fileName = path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: displayName
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in localMusicExtensions) return false

        val normalizedPath = path
            ?.replace('\\', '/')
            ?.lowercase(Locale.ROOT)
            ?: return true
        return nonMusicPathSegments.none(normalizedPath::contains)
    }

    private fun String.removeAudioExtension(): String {
        val extension = substringAfterLast('.', "").lowercase(Locale.ROOT)
        return if (extension in localMusicExtensions || extension == "wav") {
            substringBeforeLast('.').ifBlank { this }
        } else {
            this
        }
    }

    /**
     * A song built from a file in the downloads folder the app has no record of
     * — one copied in by hand, or left behind by an install whose record is
     * gone. The file's own tags are the only thing there is to go on; [scanned]
     * fills in what the retriever couldn't read, since the media scanner and
     * `MediaMetadataRetriever` do not agree on every container.
     */
    private fun buildSongFromUri(
        context: Context,
        uriStr: String,
        fileName: String,
        scanned: ScannedTags? = null,
    ): Song {
        var title = fileName.substringBeforeLast(".")
        var artist = "Unknown Artist"
        var albumName: String? = null
        var durationText: String? = null
        var trackNumber: Int? = null
        var embeddedPicture: ByteArray? = null
        var highResPicture: ByteArray? = null

        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(uriStr))
                val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                val metaAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                val metaDur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                val metaTrack = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                val picture = retriever.embeddedPicture?.takeIf { it.isNotEmpty() }
                embeddedPicture = picture?.takeIf { it.size <= MAX_EMBEDDED_ART_BYTES }
                if (AppSettings.highResCoverArt.value) {
                    highResPicture = picture?.takeIf { it.size <= MAX_HIGH_RES_ART_BYTES }
                }

                if (!metaTitle.isNullOrBlank()) title = metaTitle
                if (!metaArtist.isNullOrBlank()) artist = metaArtist
                albumName = metaAlbum.cleanTag()
                if (metaDur != null && metaDur > 0) durationText = formatDuration(metaDur)
                trackNumber = trackNumberFromTag(metaTrack)
            } finally {
                retriever.release()
            }
        }

        val artwork = scanned?.artworkUrl ?: embeddedPicture?.let {
            persistEmbeddedArtwork(context, it, artist, albumName, uriStr)
        }
        val highResArtwork = highResPicture?.let {
            persistHighResEmbeddedArtwork(context, it, uriStr)
        }

        return Song(
            videoId = uriStr,
            title = title,
            artist = artist,
            thumbnailUrl = artwork,
            highResArtworkUrl = highResArtwork,
            durationText = durationText,
            albumName = albumName ?: scanned?.albumName,
            localUri = uriStr,
            localDateAddedSeconds = scanned?.dateAddedSeconds,
            localDateModifiedSeconds = scanned?.dateModifiedSeconds,
            localFileSizeBytes = scanned?.sizeBytes ?: runCatching {
                Uri.parse(uriStr).path?.let { File(it).length().takeIf { size -> size > 0L } }
            }.getOrNull(),
            trackNumber = trackNumber,
        )
    }

    /** Stores a small, app-owned cover image so SAF tracks keep art after the scan and process restart. */
    private fun persistEmbeddedArtwork(
        context: Context,
        encoded: ByteArray,
        artist: String,
        album: String?,
        sourceUri: String,
    ): String? = runCatching {
        val identity = if (!album.isNullOrBlank()) "$artist\u0000$album" else sourceUri
        val key = MessageDigest.getInstance("SHA-256")
            .digest(identity.lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
        val directory = File(context.filesDir, "local-art").apply { mkdirs() }
        val output = File(directory, "$key.jpg")
        if (output.isFile && output.length() > 0L) return@runCatching Uri.fromFile(output).toString()

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > ARTWORK_MAX_EDGE_PX * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            encoded,
            0,
            encoded.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return@runCatching null
        val scale = minOf(1f, ARTWORK_MAX_EDGE_PX.toFloat() / maxOf(decoded.width, decoded.height))
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            ).also { if (it !== decoded) decoded.recycle() }
        } else decoded
        val temporary = File(directory, "$key.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, ARTWORK_JPEG_QUALITY, stream))
                stream.fd.sync()
            }
        } finally {
            bitmap.recycle()
        }
        if (!temporary.renameTo(output)) {
            temporary.delete()
            if (!output.isFile) error("could not save cover image")
        }
        Uri.fromFile(output).toString()
    }.onFailure { Log.w(TAG, "Could not cache embedded cover art: ${it.message}") }.getOrNull()

    /** Keeps the encoded source image intact; the normal thumbnail remains a separate small JPEG. */
    private fun persistHighResEmbeddedArtwork(
        context: Context,
        encoded: ByteArray,
        sourceUri: String,
    ): String? = runCatching {
        val key = MessageDigest.getInstance("SHA-256")
            .digest(encoded)
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
        val extension = when {
            encoded.size >= 8 && encoded[0] == 0x89.toByte() && encoded[1] == 0x50.toByte() -> "png"
            encoded.size >= 12 && encoded[0] == 'R'.code.toByte() && encoded[1] == 'I'.code.toByte() &&
                encoded[8] == 'W'.code.toByte() && encoded[9] == 'E'.code.toByte() -> "webp"
            encoded.size >= 6 && encoded[0] == 'G'.code.toByte() && encoded[1] == 'I'.code.toByte() -> "gif"
            encoded.size >= 2 && encoded[0] == 0xFF.toByte() && encoded[1] == 0xD8.toByte() -> "jpg"
            else -> "img"
        }
        val directory = File(context.filesDir, HIGH_RES_ART_DIRECTORY).apply { mkdirs() }
        val output = File(directory, "$key.$extension")
        if (!output.isFile || output.length() != encoded.size.toLong()) {
            val temporary = File(directory, "$key.$extension.tmp")
            FileOutputStream(temporary).use { stream ->
                stream.write(encoded)
                stream.fd.sync()
            }
            if (!temporary.renameTo(output)) {
                temporary.delete()
                if (!output.isFile) error("could not save full-resolution cover image")
            }
        }
        Uri.fromFile(output).toString()
    }.onFailure { Log.w(TAG, "Could not cache high-res cover art for $sourceUri: ${it.message}") }.getOrNull()

    /** Extracts original metadata art for MediaStore rows, whose regular art is only an album thumbnail URI. */
    private fun extractHighResArtwork(
        context: Context,
        sourceUri: String,
    ): String? {
        if (sourceUri.isBlank()) return null
        var encoded: ByteArray? = null
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(sourceUri))
                encoded = retriever.embeddedPicture?.takeIf {
                    it.isNotEmpty() && it.size <= MAX_HIGH_RES_ART_BYTES
                }
            } finally {
                retriever.release()
            }
        }.onFailure { Log.w(TAG, "Could not read embedded cover art for $sourceUri: ${it.message}") }
        return encoded?.let { persistHighResEmbeddedArtwork(context, it, sourceUri) }
    }

    private const val MAX_EMBEDDED_ART_BYTES = 20 * 1024 * 1024
    private const val MAX_HIGH_RES_ART_BYTES = 64 * 1024 * 1024
    private const val HIGH_RES_ART_DIRECTORY = "high-res-art"
    private const val ARTWORK_MAX_EDGE_PX = 512
    private const val ARTWORK_JPEG_QUALITY = 88

    /**
     * MediaStore packs the disc into the same int (`disc * 1000 + track`), so
     * 1003 is disc 1 track 3 rather than track 1003. Unpacks it; null when the
     * scanner wrote nothing usable.
     */
    internal fun trackNumberFromMediaStore(raw: Int?): Int? {
        if (raw == null || raw <= 0) return null
        val track = if (raw >= 1000) raw % 1000 else raw
        return track.takeIf { it in 1..999 }
    }

    /**
     * `CD_TRACK_NUMBER` arrives as `"3"` or `"3/12"`. Takes the head; null when
     * it isn't a number.
     */
    internal fun trackNumberFromTag(raw: String?): Int? {
        val head = raw?.substringBefore('/')?.trim() ?: return null
        return head.toIntOrNull()?.takeIf { it in 1..999 }
    }

    private fun formatDuration(ms: Long): String {
        val totalSecs = ms / 1000
        val minutes = totalSecs / 60
        val secs = totalSecs % 60
        return String.format(Locale.ROOT, "%d:%02d", minutes, secs)
    }
}
