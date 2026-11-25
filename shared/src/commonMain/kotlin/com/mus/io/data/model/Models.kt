package com.nejuni.juni.data.model

/** Tier of an item in the playback queue. */
enum class QueueTier {
    /** Explicitly queued by user ("Play Next", "Add to Queue", or preserved user queue). */
    USER_QUEUE,
    /** Part of the active Album, Playlist, or Artist collection context. */
    CONTEXT,
    /** Dynamically generated radio recommendations appended when context/user queue ends. */
    AUTOPLAY,
}

/**
 * One credited artist, as YouTube Music itself states them.
 *
 * @property browseId The artist's own channel, so their page is the one that
 * opens. Null for the rare run that names an artist without linking one.
 */
data class ArtistRef(
    val name: String,
    val browseId: String? = null,
)

/** A playable YouTube Music track. */
data class Song(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val durationText: String? = null,
    /** Browse ids lifted from the row, used by the long-press actions. */
    val artistId: String? = null,
    /**
     * Every credited artist, each with their own channel, where the row said so.
     *
     * [artistId] is the lead's and nothing else, which is why a collaboration
     * could only ever be opened at its first name. A track's byline carries one
     * navigation endpoint per artist, so the rest of them are here rather than
     * left in the response — and because a name is not an identity, this is the
     * only thing that can open the right page for the second and third credit.
     *
     * Empty on rows that state no per-artist links, which is most home cards
     * and anything read before its watch-queue entry has been looked up.
     */
    val artists: List<ArtistRef> = emptyList(),
    val albumId: String? = null,
    /** Names the album page header, which [albumId] alone can't. */
    val albumName: String? = null,
    /** A music-video upload rather than the catalogue track. */
    val isVideo: Boolean = false,
    /**
     * True for a video upload and for its manually selected catalogue match.
     * The latter remains video-origin so playback policies such as playback policies do
     * not mistake a converted video for a normal music track.
     */
    val isVideoOrigin: Boolean = isVideo,
    /**
     * This track's identity *within one playlist*, which is not its [videoId]:
     * the same song added twice is two entries with two set-video-ids, and
     * removing one of them is only expressible in those terms. Present only on
     * rows parsed from a playlist page, which is the only place a removal can
     * be asked for from.
     */
    val setVideoId: String? = null,
    /** Which queue tier this track belongs to in the playback timeline. */
    val queueTier: QueueTier = QueueTier.CONTEXT,
    /**
     * Unique, strictly immutable queue-entry identity assigned when this track
     * enters the player's queue in QueueCoordinator. Preserved across all stream transformations
     * and persistence.
     */
    val queueEntryId: String? = null,
    /**
     * The seed title of an explicitly started radio queue. Every item in that
     * queue carries the same value, so the player can keep naming the station
     * across skips and queue edits without holding UI-only session state.
     */
    val radioName: String? = null,
    /**
     * Explicit content or file URI for local device tracks or downloaded audio.
     */
    val localUri: String? = null,
    /** A premium rendition marker recorded by bit for its Downloads list. */
    val downloadFormat: String? = null,
    /**
     * Real filesystem path backing [localUri], when MediaStore exposes one.
     * Lets playback swap a content:// row for a raw file:// path on formats
     * that need it — see [com.nejuni.juni.playback.toMediaItem].
     */
    val localPath: String? = null,
    /** MediaStore timestamps used only to sort device and downloaded libraries. */
    val localDateAddedSeconds: Long? = null,
    val localDateModifiedSeconds: Long? = null,
    /**
     * Track number within its album, from the file's own tags (`TRACK` in
     * MediaStore, `CD_TRACK_NUMBER` via retriever). Null when the tags don't
     * say — remote catalogue rows never carry one.
     *
     * Lets a local album list in release order instead of whatever the library
     * sort left standing, and lets its rows show the release's own numbers
     * rather than their position in that list.
     */
    val trackNumber: Int? = null,
    /**
     * What a non-YouTube source says it can serve this recording at, as one of
     * `LOSSLESS`, `HIGH` or `LOW` — null for every row that didn't come from
     * one.
     *
     * Carried on the row rather than discovered at stream time because it is
     * the only thing that distinguishes two catalogues holding the same track,
     * and the choice between them has to be made *before* either is asked for
     * a URL. Without it the picker was blind: a Deezer row and a 16-bit FLAC
     * row looked identical, the FLAC lost a tie-break on artist spelling, and
     * the track played as a 128kbps MP3.
     */
    val sourceQuality: String? = null,
    /**
     * Whether a `LOSSLESS` [sourceQuality] row said it was the hi-res tier
     * (`HI_RES_LOSSLESS`, `hires-96`, `24-bit`). Display only — the resolver
     * ranks on [sourceQuality], where 16-bit and 24-bit are one tier.
     */
    val sourceHiRes: Boolean = false,
    /** Explicit-content state from the catalogue; null when that source does not say. */
    val isExplicit: Boolean? = null,
    /**
     * The page, collection, or feed section that put this track in the queue.
     *
     * This is deliberately separate from [albumName]: a song can belong to an
     * album while it was actually played from Search, History, a playlist, or
     * a recommendation shelf. Kept on every queue item so Now Playing can name
     * that origin after skips and after the playback service restores a queue.
     */
    val playbackSource: String? = null,
    /** Where tapping [playbackSource] should return in the app. */
    val playbackSourceType: PlaybackSourceType? = null,
    /** Browse id for an album, playlist, or other source page. */
    val playbackSourceId: String? = null,
    /** File size in bytes, populated for audio rows available on this device. */
    val localFileSizeBytes: Long? = null,
    /** Original embedded cover image cached privately for the Now Playing screen. */
    val highResArtworkUrl: String? = null,
) {
    /** Legacy derived property: true if and only if [queueTier] is [QueueTier.AUTOPLAY]. */
    val fromAutoplay: Boolean get() = queueTier == QueueTier.AUTOPLAY

    /** Backward-compatibility constructor for callers passing legacy [fromAutoplay]. */
    constructor(
        videoId: String,
        title: String,
        artist: String,
        thumbnailUrl: String?,
        durationText: String? = null,
        artistId: String? = null,
        artists: List<ArtistRef> = emptyList(),
        albumId: String? = null,
        albumName: String? = null,
        isVideo: Boolean = false,
        isVideoOrigin: Boolean = isVideo,
        setVideoId: String? = null,
        fromAutoplay: Boolean,
        radioName: String? = null,
        localUri: String? = null,
        downloadFormat: String? = null,
        localPath: String? = null,
        localDateAddedSeconds: Long? = null,
        localDateModifiedSeconds: Long? = null,
        trackNumber: Int? = null,
        sourceQuality: String? = null,
        isExplicit: Boolean? = null,
        playbackSource: String? = null,
        playbackSourceType: PlaybackSourceType? = null,
        playbackSourceId: String? = null,
        localFileSizeBytes: Long? = null,
    ) : this(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationText = durationText,
        artistId = artistId,
        artists = artists,
        albumId = albumId,
        albumName = albumName,
        isVideo = isVideo,
        isVideoOrigin = isVideoOrigin,
        setVideoId = setVideoId,
        queueTier = if (fromAutoplay) QueueTier.AUTOPLAY else QueueTier.CONTEXT,
        queueEntryId = null,
        radioName = radioName,
        localUri = localUri,
        downloadFormat = downloadFormat,
        localPath = localPath,
        localDateAddedSeconds = localDateAddedSeconds,
        localDateModifiedSeconds = localDateModifiedSeconds,
        localFileSizeBytes = localFileSizeBytes,
        trackNumber = trackNumber,
        sourceQuality = sourceQuality,
        isExplicit = isExplicit,
        playbackSource = playbackSource,
        playbackSourceType = playbackSourceType,
        playbackSourceId = playbackSourceId,
    )
}

/**
 * Artwork at a given pixel size.
 *
 * YouTube serves every size from one URL via a `w<n>-h<n>` hint, so the size
 * an image is fetched at is the caller's to choose, and worth choosing in both
 * directions. Up: the size YouTube advertises is far short of what a
 * full-screen player draws, and the source images run to about 1400px, so
 * asking for more is free and sharper. Down: a row thumbnail left at the
 * advertised size costs an order of magnitude more bytes than the square it
 * fills — 84kB against 7.8kB, measured on the same cover.
 *
 * Video thumbnails carry no hint and are returned unchanged.
 */
fun Song.artworkAt(px: Int): String? = thumbnailUrl.artworkAt(px)

/**
 * Whether a row is the track the player is on, for the now-playing highlight.
 *
 * Title and credit, matched exactly, and nothing else. Every id a row could be
 * matched on instead is scoped to where the row came from and so lies when
 * asked across that boundary: a set-video-id names a slot in one playlist, and
 * two playlists hand the same one to unrelated tracks, which is what used to
 * light up a stranger's row while something else played. A video id is no
 * better across catalogues — the same recording arrives with a different id
 * from a local file, a download and a module source, and the highlight would
 * simply go missing.
 *
 * The cost is that name and credit are not unique: an album track and its
 * appearance on a compilation are one and the same to this, and both light up.
 * That is the trade the highlight is meant to make — it says "this is the song
 * you are hearing", not "this is the queue entry you are hearing".
 *
 * The one place that must not use this is the player's own queue, where the
 * entry, not the song, is what the row stands for — that list matches on
 * position. Any other list asks [playingRowMatcher], which tries identity
 * before this.
 */
fun Song.isSameTrackAs(other: Song?): Boolean {
    other ?: return false
    return title == other.title && artist == other.artist
}

/**
 * Which rows of one list get the now-playing highlight: the row that was
 * played, not every row that shares its name.
 *
 * [isSameTrackAs] alone lit up both a song and its music video in a search,
 * and both an addon's stereo and Atmos rows, whichever was tapped. So
 * identity is tried first, list-wide: when any row *is* the playing track —
 * same id, and the same playlist slot when the player knows it — only that
 * row lights. Only when none is does the name rule apply, for the
 * cross-catalogue case it exists for (a local file playing over a YouTube
 * album page), and then only the first row it matches.
 */
fun playingRowMatcher(rows: List<Song>, current: Song?): (Song) -> Boolean {
    current ?: return { false }
    val sameId = rows.filter { it.videoId == current.videoId }
    if (sameId.isNotEmpty()) {
        // A playlist holding the song twice: its slot says which one.
        val slot = current.setVideoId
        if (slot != null && sameId.any { it.setVideoId == slot }) {
            return { it.videoId == current.videoId && it.setVideoId == slot }
        }
        return { it.videoId == current.videoId }
    }
    val byName = rows.firstOrNull { it.isSameTrackAs(current) } ?: return { false }
    return { it == byName }
}

/**
 * [Song.durationText] in milliseconds, or 0 when the row didn't state one.
 *
 * A row's duration is a display string — YouTube sends `"3:45"`, not a
 * number — and anything that has to *reason* about the length rather than draw
 * it needs it back as a quantity. Lyrics matching is the case that forced this
 * out into the open: LRCLIB keys its exact lookup on the track's length, and
 * falls back to whichever fuzzy hit is closest to it, so a duration of zero
 * doesn't miss — it silently matches the shortest edit of the song in the
 * database and hands back timings for a different recording.
 *
 * Zero is the answer for anything that isn't a duration, including null, so a
 * caller has one thing to check rather than a nullable *and* a range.
 */
fun Song.durationMillis(): Long = durationText.durationMillis()

/** As [Song.durationMillis], for a `M:SS` or `H:MM:SS` string on its own. */
fun String?.durationMillis(): Long {
    val parts = this?.trim()?.takeIf { it.isNotEmpty() }?.split(":") ?: return 0L
    val numbers = parts.map { it.trim().toLongOrNull() ?: return 0L }
    val seconds = when (numbers.size) {
        2 -> numbers[0] * 60 + numbers[1]
        3 -> numbers[0] * 3_600 + numbers[1] * 60 + numbers[2]
        else -> return 0L
    }
    return (seconds * 1_000).coerceAtLeast(0L)
}

/** As [Song.artworkAt], for artwork that isn't a track's. */
fun String?.artworkAt(px: Int): String? {
    if (this == null) return null
    val sized = this.replace(SIZE_HINT, "w$px-h$px")
    return if (isGoogleImageHost(this)) {
        sized.replace(PARAM_SIZE_HINT, "=s$px")
    } else {
        sized
    }
}

private fun isGoogleImageHost(url: String): Boolean {
    val host = url.substringAfter("://", "")
        .ifEmpty { url.substringAfter("//", "") }
        .substringBefore('/')
        .substringBefore(':')
        .substringBefore('?')
        .lowercase()
    return host == "ggpht.com" || host.endsWith(".ggpht.com") ||
        host == "googleusercontent.com" || host.endsWith(".googleusercontent.com")
}

private val SIZE_HINT = Regex("""w\d+-h\d+""")
private val PARAM_SIZE_HINT = Regex("""=s\d+""")

/**
 * Artwork for a list row — 52dp at most, so about 140px on a 3x screen.
 * Rounded up, and one value for every row in the app rather than one per
 * row height, so they share a cache entry instead of each fetching its own.
 */
const val ROW_ART_PX = 160

/** Artwork for a shelf card: 166dp wide, so a little under 450px at 3x. */
const val CARD_ART_PX = 480

/** Artwork for a page header, drawn near enough full width. */
const val HEADER_ART_PX = 720

/**
 * Artwork handed to the media session — the lock screen, the notification,
 * Android Auto. Generous because those surfaces draw it large and take one
 * copy: unlike a list row, nothing goes back for a better one later.
 */
const val NOTIFICATION_ART_PX = 544

/**
 * Artwork for the full player — the sleeve and the full-bleed banner both, and
 * the largest rung on the ladder.
 *
 * Named here rather than left as a private constant in the player because the
 * home-screen widget picks its own size off this ladder, and a size only one
 * surface asks for is a cache entry only that surface fills. A large widget and
 * an open player were fetching the same cover twice at two sizes, and either
 * fetch could fail on its own — so the two could disagree about whether the
 * track had artwork at all.
 */
const val PLAYER_ART_PX = 1200

enum class BrowseType { ALBUM, ARTIST, PLAYLIST, OTHER }

/** A queue-level origin shown above Now Playing, in the style of Spotify. */
enum class PlaybackSourceType {
    HOME,
    SEARCH,
    HISTORY,
    REPLAY,
    EXPLORE,
    BROWSE,
    SHARED_LINK,
    QUEUE,
}




/** A card in a home-feed carousel: either a track (videoId) or an album/playlist (browseId). */
data class ShelfItem(
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val videoId: String?,
    val browseId: String?,
    /**
     * A music-video upload rather than a catalogue track: widescreen art, and a
     * picture the player can show. Drawn as a 16:9 card, as the web player does.
     */
    val isVideo: Boolean = false,
)

/** How a shelf's items are laid out — the web player's `ShelfLayout`, by the same rules. */










/** A browsed album / artist / playlist page. */
data class DetailPage(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val songs: UiState<List<Song>>,
    val type: BrowseType = BrowseType.OTHER,
    /** Continuation behind the currently visible songs, if the listing can grow. */
    val continuation: String? = null,
)


/** How a shelf's items are laid out — the web player's `ShelfLayout`, by the same rules. */
enum class ShelfLayout {
    /** Rows of tracks. */
    LIST,

    /** Square album/playlist cards. */
    CARDS,

    /** Widescreen cards for music videos. */
    VIDEO,
}

/** A shelf of cards on an artist page: the albums, singles and videos it links. */
data class HomeShelf(
    val title: String,
    val items: List<ShelfItem>,
    val subtitle: String = "",
    val layout: ShelfLayout = ShelfLayout.CARDS,
)

/** Parsed artist landing page. */
data class ArtistPage(
    val songs: List<Song>,
    val sections: List<HomeShelf>,
    /** The artist's own picture, off the page header. */
    val thumbnailUrl: String? = null,
    /** The single artist this page is for, as the header bills them. */
    val name: String? = null,
)

/**
 * A track's thumbs rating on the signed-in account.
 *
 * [INDIFFERENT] is YouTube's own word for "neither", and is a real state
 * rather than the absence of one — clearing a like is a request in its own
 * right (`like/removelike`), not the omission of one.
 */
enum class LikeStatus { LIKE, DISLIKE, INDIFFERENT }

/** Who can see a playlist. YouTube's own three values, sent verbatim. */
enum class PlaylistPrivacy(val label: String, val apiValue: String) {
    PRIVATE("Private", "PRIVATE"),
    UNLISTED("Unlisted", "UNLISTED"),
    PUBLIC("Public", "PUBLIC"),
}

/**
 * One of the account's own playlists, as the picker lists them.
 *
 * [playlistId] is the raw id (no `VL`), because that is what the edit endpoint
 * takes; [browseId] is the same playlist addressed as a page. Keeping both
 * spares every caller from remembering which prefix each side wants.
 */
data class UserPlaylist(
    val playlistId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
) {
    val browseId: String get() = "VL$playlistId"
}


sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
