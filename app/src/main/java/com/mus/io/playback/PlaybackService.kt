@file:Suppress("DEPRECATION") // Retain Media3 legacy browser and device-volume callbacks for controller compatibility.

package com.juni.io.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import com.juni.io.data.TelemetryProvenance
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.MediaMetadata
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.guava.future
import com.juni.io.playback.audio.DspChain
import com.juni.io.playback.audio.PrecisionAudioSink
import com.juni.io.playback.dsd.DsdExtractorsFactory
import com.juni.io.playback.audio.DirectAudioProbe
import com.juni.io.playback.audio.OutputNegotiator
import com.juni.io.playback.audio.PcmEncoding
import com.juni.io.playback.audio.SourceDescriptor
import com.juni.io.playback.audio.bitperfect.BitPerfectAudioSink
import com.juni.io.playback.audio.bitperfect.BitPerfectOutput
import com.juni.io.playback.audio.bitperfect.BitPerfectRoutingSink
import com.juni.io.playback.audio.bitperfect.DacVolume
import com.juni.io.playback.audio.bitperfect.usb.UsbDirectDevices
import com.juni.io.playback.audio.bluetooth.BluetoothAudioTracker
import com.juni.io.MainActivity
import com.juni.io.R
import com.juni.io.data.LocalMediaRepository
import com.juni.io.data.OfflinePlaylists
import com.juni.io.data.lyrics.EmbeddedLyrics
import com.juni.io.data.lyrics.LyricLine
import com.juni.io.data.lyrics.LyricsRepository
import com.juni.io.data.model.NOTIFICATION_ART_PX
import com.juni.io.data.model.artworkAt
import com.juni.io.data.model.QueueTier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import com.juni.io.data.NerdStats
import com.juni.io.data.TrackLog
import com.juni.io.data.stats.ListeningRecorder
import com.juni.io.data.model.Song
import com.juni.io.data.scrobbling.LastFM
import com.juni.io.data.scrobbling.ScrobbleManager
import com.juni.io.data.settings.AppSettings
import com.juni.io.data.StartupTrace
import com.juni.io.data.settings.BitPerfectMode
import com.juni.io.data.settings.EqualizerMode
import com.juni.io.widget.MediaWidget
import com.juni.io.widget.MediaWidgetSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlin.random.Random
import java.util.Locale
import java.util.UUID


/** Session command used by both the player UI and the media notification. */

/** Session command used by album/playlist playback to install a bounded context. */
const val ACTION_START_BOUNDED_CONTEXT = "com.juni.io.action.START_BOUNDED_CONTEXT"
const val ACTION_CLEAR_BOUNDED_CONTEXT = "com.juni.io.action.CLEAR_BOUNDED_CONTEXT"
const val EXTRA_BOUNDED_CONTEXT_SONGS = "umesie.boundedContext.songs"

/** Drops every queue row but the one playing (or paused) now. */
const val ACTION_CLEAR_QUEUE = "com.juni.io.action.CLEAR_QUEUE"

/** Session command used to smoothly swap the current track's version (film vs release). */

/** Session command used by the media notification's and the 4×1 widget's Favorite button. */

const val ACTION_TOGGLE_FAVORITE = "com.juni.io.action.TOGGLE_FAVORITE"

/** Session command used by the media notification's and the 4×1 widget's Shuffle button. */
const val ACTION_TOGGLE_SHUFFLE = "com.juni.io.action.TOGGLE_SHUFFLE"

/** Session commands bracketing an explicit radio queue replacement. */
const val ACTION_BEGIN_RADIO_QUEUE = "com.juni.io.action.BEGIN_RADIO_QUEUE"
const val ACTION_COMMIT_RADIO_QUEUE = "com.juni.io.action.COMMIT_RADIO_QUEUE"


/**
 * Background playback via Media3. A [MediaLibraryService] gives us the media
 * notification, lockscreen/Bluetooth controls, and Android Auto surface for
 * free; UI processes attach with a MediaController.
 *
 * Queue items carry the URI of the file they play: `content://` for a track
 * MediaStore knows, `file://` for one read off the user's own folder.
 *
 * A single ExoPlayer owns the queue and backs the session for the service's
 * whole life; [CrossfadeController] rides on top of it as volume automation.
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private val songCache = ConcurrentHashMap<String, Song>()

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val bluetoothTracker by lazy { BluetoothAudioTracker(this) }
    private var currentAudioInputFormat: Format? = null
    private val outputDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            // Plugging something in moves the music to it. Always — a choice
            // made an hour ago about the speaker is not a standing instruction
            // to ignore the headphones now going in, and there is no row in the
            // picker for "go back to following the system", so a stuck choice
            // would be a picker that had quietly broken the obvious behaviour.
            AudioRouting.forget()
            requestOutputReconfiguration()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            // A chosen output that has been unplugged is no longer a choice.
            // Left set, its id matches nothing — and ids are reused, so it
            // would eventually match whatever device the framework hands that
            // number to next. See [AudioRouting].
            if (removedDevices.any { it.id == AudioRouting.selectedId.value }) {
                AudioRouting.forget()
            }
            requestOutputReconfiguration()
        }
    }

    /**
     * The player the session is on. Swaps with [spare] at every crossfade — see
     * [adoptPlayer] — so anything reading it must read it *now* rather than
     * capturing it.
     */
    private var player: ExoPlayer? = null

    /**
     * The idle player. Between transitions it holds nothing; to arm one,
     * [CrossfadeController] loads it with the queue positioned on the incoming
     * track.
     */
    private var spare: ExoPlayer? = null

    private var crossfade: CrossfadeController? = null

    /**
     * What the players currently in hand were built for.
     *
     * Read at construction rather than per buffer because it decides whether
     * Media3 may open a float AudioTrack at all, which cannot be changed under
     * a running renderer. Moving the setting therefore rebuilds both players.
     */
    private var configuredFloatOutput = false
    private var outputReconfigureJob: Job? = null

    /**
     * One audio-processor set per player, because both carry per-sink state — a
     * delay line, filter memory — that two sinks cannot share.
     *
     * The `A`/`B` pair is fixed to the players that own them; [activeFilter] and
     * [spareFilter] are the *roles*, and they trade places at every handoff
     * along with the players. Everything downstream talks in roles.
     */
    private val spatialAudioProcessorA = SpatialAudioProcessor()
    private val spatialAudioProcessorB = SpatialAudioProcessor()
    private val equalizerProcessorA = EqualizerProcessor()
    private val equalizerProcessorB = EqualizerProcessor()
    private val transitionFilterA = TransitionFilterProcessor()
    private val transitionFilterB = TransitionFilterProcessor()

    /**
     * Loudness normalization, one per player like the other stages — see
     * [LoudnessProcessor] for why it stopped being a single session effect.
     * Paired to the players through [activeFilter]'s role rather than tracked
     * as roles of their own: see [activeLoudness].
     */
    private val loudnessA = LoudnessProcessor().apply { gainFor = ::loudnessGainFor }
    private val loudnessB = LoudnessProcessor().apply { gainFor = ::loudnessGainFor }
    private var loudnessRetryJob: Job? = null

    /** The platform audio session currently advertised to system audio tools. */
    private var advertisedAudioEffectSessionId: Int = C.AUDIO_SESSION_ID_UNSET

    /**
     * Whether the format currently arriving at the active player's decoder
     * is Dolby Atmos (E-AC-3 JOC). Widening a JOC stream would fight the
     * object-based mix Dolby already spatializes, so the effect is forced
     * off for as long as this is true — see [applySpatialAudioEnabled].
     */
    private var activeTrackIsDolbyAtmos = false

    private var activeFilter: TransitionFilterProcessor = transitionFilterA
    private var spareFilter: TransitionFilterProcessor = transitionFilterB

    /**
     * The loudness stage on the session player. Read off [activeFilter]'s role,
     * which every handoff, version swap and rebuild already keeps right, so the
     * two can never disagree about which sink is which.
     */
    private fun activeLoudness(): LoudnessProcessor =
        if (activeFilter === transitionFilterA) loudnessA else loudnessB

    private fun spareLoudness(): LoudnessProcessor =
        if (activeFilter === transitionFilterA) loudnessB else loudnessA

    /** Automix's DSP analyzer — see [com.juni.io.playback.smart.TrackAnalyzer]. */
    private val trackAnalyzer = com.juni.io.playback.smart.TrackAnalyzer(this)

    /** Shared with the crossfade's tail player, so both read the same disk cache. */
    private var mediaSourceFactory: DefaultMediaSourceFactory? = null

    /** Last sampled position of the playing track, in seconds. */
    private var lastPositionSeconds = 0L

    /** When the current track was chosen, for the time-to-first-audio log. */
    private var trackSelectedAt: Long? = null

    /**
     * The last track that actually made a sound, which is how [recoverFrom]
     * tells "this never started" from "this died in the middle".
     *
     * Deliberately a mediaId rather than a flag that gets cleared on every
     * transition. A retry *is* a seek, so a transition fires for it — the same
     * trap [recoveries] fell into — and a flag cleared there would make a track
     * that failed twenty seconds in look like a track that never began, which is
     * precisely the case that must not be skipped past. Holding the id instead
     * means the queue moving to a different track invalidates it for free, and
     * nothing has to be cleared anywhere.
     *
     * The one thing it is deliberately wrong about: a track played earlier in
     * the session and returned to — by repeat-one, by the previous button —
     * still counts as audible, so a later failure to start it is only retried
     * and not skipped. That errs towards leaving the listener where they are,
     * which is the safe direction for a queue.
     */
    private var audibleMediaId: String? = null

    /**
     * How many tracks in a row have been skipped for a plain playback error,
     * reset the moment anything actually plays — see [MAX_CONSECUTIVE_SKIPS].
     */
    private var consecutiveErrorSkips = 0

    private var scrobbleManager: ScrobbleManager? = null


    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** Canonical context owner for the active local bounded album/playlist. */
    private val boundedContextRegistry = ContextRegistry { snapshot ->
        scope.launch {
            val window = boundedContextWindow ?: return@launch
            if (snapshot.contextToken != boundedContextToken) return@launch
            window.extendTo(snapshot.songs.size)
            boundedContextRequest?.let { BoundedContextRequests.publish(it, snapshot) }
            player?.let(::saveBoundedContextSnapshot)
            scheduleBoundedContextTopUp()
        }
    }

    private var boundedContextWindow: QueueWindow? = null
    private var boundedContextToken: String? = null
    private var boundedContextSource: QueueSource? = null
    private var boundedContextRequest: BoundedContextRequests.Request? = null
    private var boundedCanonicalJob: Job? = null
    private var boundedContinuationJob: Job? = null
    private var boundedTopUpJob: Job? = null
    /** Coalesced bounded edits waiting for CrossfadeController settlement. */
    private var boundedEditState = BoundedQueueEditState()

    private val localBitrateCache = ConcurrentHashMap<String, Int>()

    /** [measuredStreamKbps]'s answers, keyed by rendition URI, and the ones still being read. */
    private val measuredBitrateCache = ConcurrentHashMap<String, Int>()
    private val measuringBitrate: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * The stream resolver the player's own data source runs. Null until
     * [onCreate] has built it.
     */

    /** Commands exposed as the secondary buttons on the media notification. */
    private val boundedContextCommand = SessionCommand(ACTION_START_BOUNDED_CONTEXT, Bundle.EMPTY)
    private val clearBoundedContextCommand = SessionCommand(ACTION_CLEAR_BOUNDED_CONTEXT, Bundle.EMPTY)
    private val clearQueueCommand = SessionCommand(ACTION_CLEAR_QUEUE, Bundle.EMPTY)
    private val favoriteCommand = SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY)
    private val shuffleCommand = SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY)
    private val beginRadioQueueCommand = SessionCommand(ACTION_BEGIN_RADIO_QUEUE, Bundle.EMPTY)
    private val commitRadioQueueCommand = SessionCommand(ACTION_COMMIT_RADIO_QUEUE, Bundle.EMPTY)

    /** Rate limit for listener-requested retries; automatic upgrades do not touch it. */
    /** The upcoming ids read-ahead was last asked to warm, so an unchanged queue isn't asked again. */

    /** Index in the live queue represented by entry zero of the persisted window. */
    private var persistedQueueStart = 0



    /**
     * The last repeat mode seen, so [onRepeatModeChanged] can tell which
     * direction the change went in: the callback reports where the player has
     * arrived, and leaving repeat-all is the half that has to restore.
     */
    private var lastRepeatMode = Player.REPEAT_MODE_OFF


    private var serviceLyricsJob: Job? = null
    private var serviceLyrics: List<LyricLine>? = null
    private var lastPublishedSubtitle: String? = null
    private var lyricsTickerJob: Job? = null

    /**
     * Everything the service books against the player it is currently on.
     *
     * A field rather than an anonymous object registered once, because the
     * session moves between two players at every crossfade — see [adoptPlayer]
     * — and this has to move with it. It is attached to exactly one player at a
     * time: the one [player] names.
     */
    private val playbackListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val activePlayer = this@PlaybackService.player ?: return
            if (player === activePlayer) updateAudioEffectSession(activePlayer)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // The player this fired on, which is by definition the one the
            // session is currently pointed at.
            val exoPlayer = player ?: return
            // The only number that describes what a listener actually
            // waits through. Every other timing in this app measures one
            // leg of getting a track started — a resolve, a client walk, an
            // extraction — and a leg being fast has repeatedly turned out
            // to say nothing about whether sound arrived quickly, because
            // the legs that were measured were the ones running in the
            // background for tracks nobody was waiting on.
            if (isPlaying) {
                trackSelectedAt?.let {
                    TrackLog.d(
                        "umesie",
                        "TIMING first audio: ${SystemClock.elapsedRealtime() - it}ms since track selected",
                        about = exoPlayer.currentMediaItem?.mediaId,
                    )
                    trackSelectedAt = null
                }
                // Sound is out of the speaker, so whatever happens to this track
                // from here is a failure mid-song. Also the one thing that can
                // say the queue is not simply unplayable end to end.
                audibleMediaId = exoPlayer.currentMediaItem?.mediaId
                consecutiveErrorSkips = 0
            }
            savePlaybackState(exoPlayer)
            // Not strictly needed for the glyph — onPlayWhenReadyChanged has
            // already flipped that — but this is where hasNext/hasPrevious and
            // the artwork are known to be settled.
            publishWidgetState()

            val song = exoPlayer.currentMediaItem?.toSong()
            val durationMs = exoPlayer.duration.takeIf { it > 0 }
            scrobbleManager?.onPlayerStateChanged(isPlaying, song, durationMs)

            // The listening record has to be told a pause happened, not merely
            // stop being told about play: its sampler measures the gap between
            // ticks, and an unclosed one across a pause is an afternoon on the
            // lock screen arriving as an afternoon of listening.
            if (!isPlaying) ListeningRecorder.onStopped()

            if (isPlaying) {
                startLyricsTicker()
            } else {
                stopLyricsTicker()
            }
        }

        /**
         * The one callback the home-screen widget can be driven from.
         *
         * `onIsPlayingChanged` is too late by seconds: a tap on play is serviced
         * before any audio comes out, all of which happens with `isPlaying` still
         * false.
         * A widget keyed on that answers a tap on play by leaving the play glyph
         * exactly where it was — the control reads as broken, and the obvious
         * response is to tap it again. `playWhenReady` flips on the command, not
         * on the audio, which is what the media notification shows too.
         */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            publishWidgetState(playing = playWhenReady)
        }

        /**
         * A seek is the one change to a playing track that no other callback
         * reports, so the lyric subtitle is refreshed from here.
         */
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            val exoPlayer = player ?: return
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                updateLyricSubtitle()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // The player this fired on, which is by definition the one the
            // session is currently pointed at.
            val exoPlayer = player ?: return
            // A quality swap replaces the playing item, which Media3
            // reports here as a playlist change — indistinguishable, from
            // this callback's point of view, from the queue moving on. It
            // is not the queue moving on: it is the same song, at the same
            // position, from a better source. Letting the bookkeeping below
            // run for it scrobbled the track twice and wrote a second history
            // entry, closing out its
            // play count mid-play — all of which happened, and all of which
            // are invisible until someone reads their listening history.


            // No crossfade case to allow for here any more. A blended advance
            // never reaches this callback — the incoming track starts as the
            // *first* item of the other player — so it is booked by
            // [adoptPlayer] instead, and what is left arriving here is only ever
            // ExoPlayer moving the queue on by itself, a repeat, or a skip.
            onTrackBecameCurrent(
                mediaItem,
                previousEnded = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
                reason = reason,
            )
            // Prune consumed USER_QUEUE entries when playback enters CONTEXT,
            // keeping the timeline clean and ensuring REPEAT_MODE_ALL loops only CONTEXT items.
            QueueCoordinator.consumePlayedUserQueue(exoPlayer)
            loadLyricsForCurrentTrack()
            if (exoPlayer.isPlaying) startLyricsTicker()
            refreshCustomLayouts()
        }

        /**
         * A failed stream is not a failed track: nothing else in this
         * service ever calls [Player.prepare] again, so before this
         * existed a single read error left the player in `STATE_IDLE` for
         * good. The notification kept the song on it, the play button kept
         * being pressed, and nothing happened — which is exactly what a
         * broken app looks like from the outside.
         */
        override fun onPlayerError(error: PlaybackException) {
            // The player this fired on, which is by definition the one the
            // session is currently pointed at.
            val exoPlayer = player ?: return
            recoverFrom(error, exoPlayer)
        }

        // Nothing follows the last track, so there is no transition to
        // pause on — the queue simply runs out and the timer is spent.
        override fun onPlaybackStateChanged(state: Int) {
            // The player this fired on, which is by definition the one the
            // session is currently pointed at.
            val exoPlayer = player ?: return
            if (state == Player.STATE_ENDED) {
                SleepTimer.cancel()
                lastPositionSeconds = 0
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            val previous = lastRepeatMode
            lastRepeatMode = repeatMode
            // Repeat-one makes the "next" track this one again.
            player?.let { activeLoudness().nextMediaId = nextMediaIdOf(it) }
            // Repeat-all loops the queue as it stands; AutoPlay's tracks are the
            // opposite of that — an endless supply of new ones — so they come
            // back out first, and native REPEAT_MODE_ALL then wraps a plain
            // queue exactly as it should. [loadAutoplayForCurrentTrack] leaves
            // it alone for as long as repeat-all stays on.
            //
            // Done here rather than in the UI that used to do it because this is
            // the only place that sees the *previous* mode, and taking the
            // tracks back is only half the job: they have to go in again when
            // the loop ends, or a listener who cycles repeat on and straight
            // back off is left with a queue that simply stops after the playing
            // track.
            if (repeatMode == Player.REPEAT_MODE_ALL && boundedContextWindow != null) {
                // A bounded context may retain physical history before its
                // final current item. Materialise the logical next cycle
                // before native repeat-all can wrap into that history.
                // The scheduler defers this append while crossfade owns
                // the players and applies it at settlement.
                scheduleBoundedContextTopUp()
            }
        }

        /**
         * AutoPlay appends to the queue after the transition that ran it
         * dry, so the track to read ahead for often only exists once the
         * timeline has changed.
         */
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // The player this fired on, which is by definition the one the
            // session is currently pointed at.
            val exoPlayer = player ?: return
            // Whether or not it is playing: a held video buffers until this answers.
            // A queue edit can change what follows gaplessly, and the loudness
            // stage switches to that track on its own at the boundary.
            activeLoudness().nextMediaId = nextMediaIdOf(exoPlayer)
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                saveQueueSnapshot(exoPlayer)
                refreshCustomLayouts()
            }
        }
    }

    /** Registered alongside [playbackListener], and moved with it. */
    private val formatListener = object : AnalyticsListener {
        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            // Taken off the event's own window rather than off the player,
            // so it names the track this format arrived for even if the
            // queue has moved on again since. See [audioFormatFor].
            audioFormatFor = eventTime.mediaId()
            // Ground truth for a real-device listening test: this is the
            // renderer's own Format, straight off the decoder with none of
            // the app's caching/upgrade logic in between, so it's the one
            // line that can prove a "hi-res" session never quietly slid
            // onto a lower-rate stream mid-track. `adb logcat -s DECODE:I`.
            val measured = format.measure()
            val khz = measured.sampleRateHz
                ?.let { "%.1fkHz".format(Locale.ROOT, it / 1000.0) } ?: "?kHz"
            val kbps = format.bitrate.takeIf { it > 0 }
                ?.let { "${it / 1000}kbps" } ?: "bitrate n/a"
            val depth = measured.bitDepth?.let { "${it}-bit" } ?: "?-bit"
            val channels = measured.channels?.let { "${it}ch" } ?: "?ch"
            TrackLog.i(
                "DECODE",
                "$audioFormatFor <- ${format.sampleMimeType} $khz $kbps $depth $channels",
                about = audioFormatFor,
            )
            activeTrackIsDolbyAtmos = NerdStats.isDolbyAtmosMime(format.sampleMimeType)
            applySpatialAudioEnabled()
            publishNerdStats()
            currentAudioInputFormat = format
            applyOutputRoute()
        }

        /**
         * The seam, measured rather than described. This fires when the
         * audio track starts putting samples out again after the sink was
         * flushed, which for a quality swap is the exact instant the music
         * comes back — and the gap between it and the swap is the only
         * number that says whether any of the work above paid off. Every
         * other timing here brackets a fetch, and a fetch being fast has
         * repeatedly said nothing about whether the listener heard a hole.
         */
        override fun onAudioPositionAdvancing(
            eventTime: AnalyticsListener.EventTime,
            playoutStartSystemTimeMs: Long,
        ) {
            val cutAt = swapCutAt ?: return
            swapCutAt = null
            TrackLog.d(
                "umesie",
                "swap seam: ${SystemClock.elapsedRealtime() - cutAt}ms of silence",
                about = eventTime.mediaId(),
            )
        }

        /**
         * The three legs the seam breaks into, logged separately because
         * they have entirely different fixes: getting the new source
         * loaded and past the load control's gate, standing a decoder up,
         * and opening an audio track. Only the first is ours to shorten.
         */
        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
            val cutAt = swapCutAt ?: return
            if (state == Player.STATE_READY) {
                TrackLog.d(
                    "umesie",
                    "swap leg: ready ${SystemClock.elapsedRealtime() - cutAt}ms after the cut",
                    about = eventTime.mediaId(),
                )
            }
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            AudioOutputStatus.publishDecoder(decoderName)
            TrackLog.i(
                "AUDIO_OUT",
                "decoder=$decoderName initialized in ${initializationDurationMs}ms",
                about = eventTime.mediaId(),
            )
            val cutAt = swapCutAt ?: return
            TrackLog.d(
                "umesie",
                "swap leg: $decoderName stood up in ${initializationDurationMs}ms, " +
                    "${SystemClock.elapsedRealtime() - cutAt}ms after the cut",
                about = eventTime.mediaId(),
            )
        }

        override fun onAudioTrackInitialized(
            eventTime: AnalyticsListener.EventTime,
            audioTrackConfig: AudioSink.AudioTrackConfig,
        ) {
            AudioOutputStatus.publishAudioTrack(
                encoding = audioTrackConfig.encoding,
                sampleRateHz = audioTrackConfig.sampleRate,
                bufferSize = audioTrackConfig.bufferSize,
            )
            TrackLog.i(
                "AUDIO_OUT",
                "AudioTrack ${audioTrackConfig.sampleRate}Hz encoding=${audioTrackConfig.encoding}",
                about = eventTime.mediaId(),
            )
        }

    }

    /**
     * Which track an analytics event is about, taken off the event's own window.
     *
     * The player has moved on by the time some of these arrive — a format
     * change for the outgoing track lands after the transition — so its
     * `currentMediaItem` names the wrong one. The event carries the timeline it
     * was raised against, which does not.
     */
    private fun AnalyticsListener.EventTime.mediaId(): String? = timeline
        .takeIf { windowIndex < it.windowCount }
        ?.getWindow(windowIndex, Timeline.Window())
        ?.mediaItem
        ?.mediaId

    override fun onCreate() {
        super.onCreate()
        StartupTrace.log("service", "onCreate")

        // First, because everything below assumes it is standing up fresh and
        // one of the two ways this service starts does not give it that.
        //
        // A cold start runs in a new process, where the session-scoped state
        // these two hold is empty anyway. A *warm* one doesn't: closing the app
        // destroys the service while Android keeps the process to reuse, so
        // without this a second service inherits the first one's idea of what
        // was playing and what has already been asked about. That cost the
        // reported bug all three of its symptoms — a badge reading "Lossless"
        // over a player holding no bytes, and a track that had been upgraded to
        // FLAC playing its cached Opus with no second look, permanently, because
        // its id was still recorded as answered. Both are documented where the
        // state lives.
        NerdStats.forgetLastSession()
        localBitrateCache.clear()
        measuredBitrateCache.clear()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.playback_channel_name)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification_logo) },
        )

        // Both the player screen and notification route their Shuffle command
        // here. Observe its state so either surface's icon follows the queue
        // edit once that single service-side transaction has completed.
        scope.launch {
            QueueShuffle.enabled
                .collectLatest {
                    refreshCustomLayouts()
                    publishWidgetState()
                }
        }
        scope.launch {
            OfflinePlaylists.liked
                .map { liked -> player?.currentMediaItem?.toSong()?.localUri?.let { it in liked } }
                .distinctUntilChanged()
                .collectLatest {
                    refreshCustomLayouts()
                    publishWidgetState()
                }
        }

        bluetoothTracker.start()
        scope.launch {
            // A codec change (LDAC 24-bit ↔ SBC) can flip float output on or off,
            // which needs a sink rebuild, not just a route refresh. Any other real
            // change — codec name, sample rate — still refreshes the route, so the
            // readout follows it; only the update timestamp is ignored.
            bluetoothTracker.telemetry
                .map { it.copy(lastUpdatedMs = 0L) }
                .distinctUntilChanged()
                .collect { requestOutputReconfiguration() }
        }
        // Device files, played straight. MediaStore and the user's folder
        // hand over `content://` or `file://` URIs, so the platform's own data
        // source opens them with nothing in between — no stream to resolve and
        // no cache to fill.
        //
        // DsdExtractorsFactory: the stock extractors plus DSF/DFF, which Media3
        // cannot open at all. A DSD file leaves the extractor as float PCM.
        mediaSourceFactory = DefaultMediaSourceFactory(
            DefaultDataSource.Factory(this),
            DsdExtractorsFactory(),
        )

        configuredFloatOutput = shouldEnableFloatOutput()
        val exoPlayer = buildPlayer(
            spatialAudioProcessorA,
            equalizerProcessorA,
            transitionFilterA,
            loudnessA,
            ownsSession = true,
        )
        val sparePlayer = buildPlayer(
            spatialAudioProcessorB,
            equalizerProcessorB,
            transitionFilterB,
            loudnessB,
            ownsSession = false,
        )
        player = exoPlayer
        spare = sparePlayer
        // Both sinks feed the same session id, so the system equalizer and any
        // other effect attached to the app applies to whichever player happens
        // to be audible. Without it a crossfade would audibly change EQ halfway
        // through, and again at every handoff.
        sparePlayer.audioSessionId = exoPlayer.audioSessionId
        audioManager?.registerAudioDeviceCallback(outputDeviceCallback, null)
        applyOutputRoute()
        StartupTrace.log("service", "players built")

        AppSettings.audioSessionId.value = exoPlayer.audioSessionId
        applySettings(exoPlayer)
        applySettings(sparePlayer)
        observeSettings()
        observeScrobbling()
        watchSleepTimer()
        // The saved queue is read after the session exists and off the main
        // thread: on a large library its JSON snapshot is megabytes, and
        // decoding it inline used to hold onCreate (and with it the UI's
        // controller connection) for seconds.
        // History pings fire once a track is actually audible — both when
        // playback starts and when the queue moves on while already playing.
        lastRepeatMode = exoPlayer.repeatMode
        exoPlayer.addListener(playbackListener)

        // Only the analytics listener reports the format the audio renderer was
        // configured with. Treated as a trigger rather than a source: the
        // publisher reads the format off the player, so it can't go stale
        // against the track the bitrate is looked up for.
        exoPlayer.addAnalyticsListener(formatListener)

        reportProgress()

        val controller = createCrossfadeController()
        crossfade = controller
        QueueShuffle.isCrossfadeTransitioning = { crossfade?.isTransitioning() == true }
        controller.start()

        mediaSession = MediaLibrarySession.Builder(
            this,
            newSessionPlayer(exoPlayer, controller),
            MediaLibraryCallback(),
        )
            .setId(SESSION_ID)
            .setSessionActivity(sessionActivity())
            .build()
        refreshCustomLayouts()
        StartupTrace.log("service", "session built — onCreate done")
        scope.launch {
            val restored = restoreQueue(exoPlayer)
            StartupTrace.log("service", if (restored) "queue restored" else "no queue to restore")
            if (restored) {
                publishWidgetState()
            } else {
                MediaWidgetSnapshot.save(this@PlaybackService, MediaWidgetSnapshot.EMPTY)
                MediaWidget.refresh(this@PlaybackService)
            }
        }

    }

    private fun newSessionPlayer(player: Player, crossfade: CrossfadeController) = SessionPlayer(
        player,
        crossfade,
    ) { lastPublishedSubtitle }


    private fun createCrossfadeController() = CrossfadeController(
            scope,
            active = { requireNotNull(player) },
            standby = { requireNotNull(spare) },
            onHandoff = ::adoptPlayer,
            analysisFor = { item -> trackAnalyzer.analysisFor(item.mediaId) },
            requestAnalysis = { item, durationMs ->
                item.localConfiguration?.uri?.let { uri ->
                    trackAnalyzer.request(item.mediaId, uri, durationMs / 1000.0)
                }
            },
            // "Incoming" and "outgoing" here are session roles, not players:
            // the session player's sink and the spare's. The controller maps
            // its two tracks onto them according to which side of the
            // mid-blend handoff it is on — so these read the role fields fresh
            // on every call rather than closing over an instance that will
            // have changed hands.
            filters = object : TransitionFilters {
                override fun incoming(lowPassHz: Float, highPassHz: Float) =
                    activeFilter.setCutoffs(lowPassHz, highPassHz)

                override fun outgoing(lowPassHz: Float, highPassHz: Float) =
                    spareFilter.setCutoffs(lowPassHz, highPassHz)

                override fun incomingLeadMs(): Long = activeFilter.leadUs / 1000

                override fun outgoingLeadMs(): Long = spareFilter.leadUs / 1000

                override fun incomingEcho(delaySeconds: Float, send: Float, dry: Float) =
                    activeFilter.setEcho(delaySeconds, send, dry)

                override fun outgoingEcho(delaySeconds: Float, send: Float, dry: Float) =
                    spareFilter.setEcho(delaySeconds, send, dry)

                override fun parkEchoes() {
                    transitionFilterA.parkEcho()
                    transitionFilterB.parkEcho()
                }
            },
            analysisRunningFor = { item -> trackAnalyzer.isAnalysing(item.mediaId) },
            // The standby is still the spare when this runs — it only becomes
            // the session player at the handoff — so the incoming track is
            // levelled on its own sink before it renders a frame, and the
            // outgoing track keeps its own gain for the rest of the blend.
            onArmIncoming = { item, nextId -> spareLoudness().track(item.mediaId, nextId) },
            // Both sinks, not a role: the trim is about what the pair sums to.
            onBlendHeadroom = { trim ->
                loudnessA.setBlendTrim(trim)
                loudnessB.setBlendTrim(trim)
            },
            onSettled = {
                if (boundedEditState.hasPendingEdits) {
                    scope.launch {
                        applyDeferredBoundedQueueEdit()
                    }
                }
            },
            // A next track still waiting to become its audio version is not
            // ready to blend into yet.
            mixWaveform = { item, durationMs, from, to, bins ->
                item.localConfiguration?.uri?.let { uri ->
                    trackAnalyzer.mixWaveform(item.mediaId, uri, durationMs / 1000.0, from, to, bins)
                }
            },
            // Each sink's loop-mix port rides on its transition filter, so the
            // player and the port trade roles together at every handoff.
            loopPortOf = { target ->
                when {
                    target === player -> activeFilter.loop
                    target === spare -> spareFilter.loop
                    else -> null
                }
            },
            loopRegion = { item, durationMs, from, to, rate ->
                item.localConfiguration?.uri?.let { uri ->
                    trackAnalyzer.loopRegion(item.mediaId, uri, durationMs / 1000.0, from, to, rate)
                }
            },
            syncedLines = ::loopMixLyrics,
            loudnessGain = ::loudnessGainFor,
            onMixPositionJump = { wrapped -> (mediaSession?.player as? SessionPlayer)?.announceMixPosition(wrapped) },
        )

    /**
     * A track's synced lyrics for the loop mix, which lets a loop be vouched
     * instrumental by when its lines are sung rather than by the vocal model.
     * Only with synced lyrics switched on, and never for longer than
     * [LOOP_MIX_LYRICS_TIMEOUT_MS]: the mask stands in for whatever doesn't come.
     */
    private suspend fun loopMixLyrics(item: MediaItem, durationMs: Long): List<LyricLine>? {
        if (!AppSettings.syncedLyrics.value) return null
        val song = item.toSong()
        return kotlinx.coroutines.withTimeoutOrNull(LOOP_MIX_LYRICS_TIMEOUT_MS) {
            song.localUri?.let { EmbeddedLyrics.forUri(this@PlaybackService, it) }
                ?: LyricsRepository.lyrics(
                    videoId = song.videoId,
                    title = song.title,
                    artist = song.artist,
                    durationMs = durationMs,
                    album = song.albumName,
                    sources = AppSettings.lyricsSources.value,
                    order = AppSettings.lyricsSourceOrder.value,
                )?.lines
        }
    }

    /**
     * Whether Like is offered for [item] — the player's own rule, so no surface
     * shows a heart the player doesn't: there has to be an account to record it
     * against, unless it is a local file or a download, which is liked into the
     * device's own Liked playlist instead (see [OfflinePlaylists]).
     */
    private fun canLike(item: MediaItem?): Boolean =
        item != null && item.toSong().localUri != null

    /** Whether [item] is liked, by whichever of the two records owns it. */
    private fun isLiked(item: MediaItem?): Boolean {
        val song = item?.toSong() ?: return false
        return OfflinePlaylists.isLiked(song)
    }

    /** Favorite and Shuffle: the only actions shown on the phone notification. */
    private fun notificationButtons(): List<CommandButton> {
        val liked = isLiked(player?.currentMediaItem)
        // The icon constant tells controllers like Android Auto what the button
        // means; the custom drawable swaps Material's sharp star for the
        // player's rounded one on the notification itself.
        val favorite = if (!canLike(player?.currentMediaItem)) null else CommandButton.Builder(
            if (liked) CommandButton.ICON_STAR_FILLED else CommandButton.ICON_STAR_UNFILLED,
        )
            .setCustomIconResId(if (liked) R.drawable.ic_notification_star_filled else R.drawable.ic_notification_star)
            .setSessionCommand(favoriteCommand)
            .setDisplayName("Favorite")
            .build()
        val shuffleEnabled = QueueShuffle.enabled.value
        val shuffle = CommandButton.Builder(
            if (shuffleEnabled) {
                CommandButton.ICON_SHUFFLE_ON
            } else {
                CommandButton.ICON_SHUFFLE_OFF
            },
        )
            .setSessionCommand(shuffleCommand)
            .setDisplayName(if (shuffleEnabled) "Shuffle off" else "Shuffle on")
            .build()
        return listOfNotNull(favorite, shuffle)
    }


    /** Android Auto / Automotive controllers currently connected to the session. */
    private val carControllers = mutableSetOf<MediaSession.ControllerInfo>()

    /**
     * Pushes the current button state to every surface. The phone/lock-screen
     * notification is driven by Media3's own internal controller, so it gets
     * an explicit override limited to Favorite/Shuffle; every other connected
     * controller falls back to the broadcast layout, which also carries
     * Radio/Revert.
     *
     * Media3 mirrors the notification controller's layout into the platform
     * session's custom actions, and Android Auto reads the platform session,
     * not the broadcast layout. So while a car is connected the notification
     * override carries the car buttons too, or Radio/Revert vanish from Auto.
     */
    private fun refreshCustomLayouts() {
        val session = mediaSession ?: return
        val car = notificationButtons()
        session.setCustomLayout(car)
        session.getMediaNotificationControllerInfo()?.let {
            session.setCustomLayout(it, if (carControllers.isEmpty()) notificationButtons() else car)
        }
    }


    /**
     * Credits [incoming]'s configured audio format to its own current track,
     * for a player taken over mid-play.
     *
     * [formatListener] reports a format once, when the renderer is configured,
     * and the incoming player configured its renderer during the blend, while
     * the listener was still on the outgoing one. So [audioFormatFor] went on
     * naming the outgoing track for the whole of the incoming one, and
     * [adoptCachedTrack] — which refuses any format not credited to the track
     * asking — never got past it: a track crossfaded into from the disk cache
     * never had its second look, and never showed "Upgrading quality".
     *
     * A renderer that has no format yet credits nothing; its callback is still
     * to come, and now there is a listener to hear it.
     */
    private fun adoptFormatOf(incoming: ExoPlayer) {
        if (incoming.audioFormat == null) return
        audioFormatFor = incoming.currentMediaItem?.mediaId
    }


    private fun toggleShuffleFromSession() {
        // Nothing to shuffle yet: the queue is still being restored.
        if ((player?.mediaItemCount ?: 0) == 0) return
        val window = boundedContextWindow
        if (window != null) {
            val target = !(boundedEditState.desiredShuffle ?: window.isShuffleEnabled)
            boundedEditState = BoundedQueueEdits.requestShuffle(
                boundedEditState,
                desiredShuffle = target,
            )
            if (crossfade?.isTransitioning() != true) applyDeferredBoundedQueueEdit()
            return
        }
        player?.let(QueueShuffle::toggle)
        refreshCustomLayouts()
    }

    /**
     * Latest desired shuffle state wins. Nothing logical is mutated until the
     * actual crossfade settlement callback; thus the outgoing and standby
     * queues cannot be based on different generations of the logical order.
     */
    private fun applyDeferredBoundedQueueEdit() {
        val exoPlayer = player ?: return
        val window = boundedContextWindow ?: return
        if (crossfade?.isTransitioning() == true) return
        val settlement = BoundedQueueEdits.settle(
            state = boundedEditState,
            settledGeneration = boundedEditState.contextGeneration,
        )
        val edit = settlement.edit ?: return
        boundedEditState = settlement.state
        boundedContextAnchor(exoPlayer)?.let { anchor ->
            if (window.currentCanonicalIndex != anchor) window.jumpTo(anchor)
        }
        if (edit.desiredShuffle != null) {
            val shuffleStarted = SystemClock.elapsedRealtime()
            window.setShuffle(edit.desiredShuffle, shuffleSeed = Random.nextLong())
            QueueShuffle.setEnabled(edit.desiredShuffle)
            AppSettings.setShuffleEnabled(edit.desiredShuffle)
            replaceBoundedUpcoming(exoPlayer, window)
            StartupTrace.log(
                "queue",
                "shuffle ${if (edit.desiredShuffle) "on" else "off"} — " +
                    "${SystemClock.elapsedRealtime() - shuffleStarted}ms",
            )
        }
        if (edit.refresh) replaceBoundedUpcoming(exoPlayer, window)
        if (edit.topUp) {
            applyBoundedContextTopUp()
        }
        refreshCustomLayouts()
    }

    /** Replace only the bounded upcoming tail, retaining current/history and tiers. */
    private fun replaceBoundedUpcoming(exoPlayer: ExoPlayer, window: QueueWindow) {
        val snapshot = boundedContextRegistry.snapshot() ?: return
        if (snapshot.contextToken != boundedContextToken) return
        window.extendTo(snapshot.songs.size)
        val from = exoPlayer.currentMediaItemIndex + 1
        if (from < 0) return
        val existing = (from until exoPlayer.mediaItemCount).map(exoPlayer::getMediaItemAt)
        val users = existing.filter { it.queueTier == QueueTier.USER_QUEUE }
        val autoplay = existing.filter { it.queueTier == QueueTier.AUTOPLAY }
        val existingContext = existing.filter { it.queueTier == QueueTier.CONTEXT }
            .associateBy { QueueWindowEntry(it.canonicalIndex ?: -1, it.contextCycleId ?: window.cycleId) }
        val context = BoundedQueuePlanner.physicalUpcoming(
            window = window,
            repeatAll = exoPlayer.repeatMode == Player.REPEAT_MODE_ALL &&
                BoundedQueuePlanner.canRepeat(snapshot.continuationState),
        ).map { entry ->
            existingContext[entry] ?: boundedMediaItem(
                snapshot.songs[entry.canonicalIndex], entry.canonicalIndex, entry.cycleId,
            )
        }
        QueueShuffle.replaceChanged(exoPlayer, from, exoPlayer.mediaItemCount, users + context + autoplay)
    }

    /**
     * Installs the first bounded window for an album/playlist request.  The
     * complete list stays in [boundedContextRegistry]; only the bounded plan is
     * converted to Media3 items.  This command is intentionally handled by the
     * service so the physical edit uses the raw ExoPlayer.
     */
    private fun startBoundedContext(args: Bundle) {
        val exoPlayer = player ?: return
        val requestToken = args.getString(EXTRA_BOUNDED_CONTEXT_SONGS) ?: return
        val request = BoundedContextRequests.take(requestToken) ?: return
        BoundedContextPersistence.clearImmediately()
        boundedEditState = BoundedQueueEdits.invalidateContext(boundedEditState)

        val source = request.source
        val requestedSongs = request.songs
            .map { it.copy(
                queueTier = QueueTier.CONTEXT,
                playbackSource = source.title,
                playbackSourceType = source.type,
                playbackSourceId = source.id,
            ) }
        if (requestedSongs.isEmpty()) return

        val requestedIndex = request.startIndex
            .coerceIn(requestedSongs.indices)
        val selectedKey = requestedSongs[requestedIndex].contextEntryKey()
        val contextToken = "${source.type.name}:${source.id.orEmpty()}:${UUID.randomUUID()}"
        val registered = boundedContextRegistry.register(
            contextToken = contextToken,
            sourceKey = "${source.type.name}:${source.id.orEmpty()}",
            browseId = source.id,
            songs = requestedSongs,
            continuationToken = request.continuationToken,
        )
        val selectedIndex = registered.songs.indexOfFirst { it.contextEntryKey() == selectedKey }
            .takeIf { it >= 0 } ?: 0
        val shuffle = request.shuffle
        // A fresh seed per start: the default one is fixed, which gave the
        // same album the same "shuffled" order every time it was played.
        val window = QueueWindow(registered.songs.size, shuffleSeed = Random.nextLong())
            .startAt(selectedIndex, shuffle)

        boundedContinuationJob?.cancel()
        boundedContextWindow = window
        boundedContextToken = contextToken
        boundedContextSource = source
        boundedContextRequest = request
        BoundedContextRequests.publish(request, registered)

        val currentIndex = exoPlayer.currentMediaItemIndex
        val upcoming = if (currentIndex in 0 until exoPlayer.mediaItemCount) {
            (currentIndex + 1 until exoPlayer.mediaItemCount).map { exoPlayer.getMediaItemAt(it) }
        } else {
            emptyList()
        }
        val userQueue = upcoming.filter { it.queueTier == QueueTier.USER_QUEUE }.map(MediaItem::toSong)
        val repeatAll = exoPlayer.repeatMode == Player.REPEAT_MODE_ALL
        val plan = BoundedQueuePlanner.buildInitialPlan(
            contextSnapshot = registered,
            selectedIndex = selectedIndex,
            queueWindow = window,
            preservedUserQueue = userQueue,
            repeatAll = repeatAll,
        )

        // Off the main thread: this is the one step here that scales with the
        // whole list, and [toMediaItem] stats every downloaded track. Shuffle
        // toggled before it lands reads the player's own rows, then this replaces them.
        boundedCanonicalJob?.cancel()
        QueueShuffle.clearCanonicalContext()
        boundedCanonicalJob = scope.launch(Dispatchers.Default) {
            val canonicalItems = registered.songs.mapIndexed { index, song ->
                val canonical = song.copy(queueEntryId = "context:$contextToken:$index")
                QueueShuffle.withQueueMetadata(
                    canonical.toMediaItem(),
                    newEntryId = canonical.queueEntryId,
                    newCanonicalIndex = index,
                    newTier = QueueTier.CONTEXT,
                )
            }
            if (isActive) QueueShuffle.setCanonicalContext(canonicalItems)
        }

        val items = boundedMediaItems(plan, window)
        val start = plan.history.size
        exoPlayer.setMediaItems(items, start.coerceIn(items.indices), 0L)
        saveBoundedContextSnapshot(exoPlayer)
        exoPlayer.prepare()
        exoPlayer.play()
    }

    /** Clears local bounded state before a non-context queue replaces the player. */
    private fun clearBoundedContext() {
        boundedEditState = BoundedQueueEdits.invalidateContext(boundedEditState)
        boundedContextRequest?.handoffToken?.let(BoundedContextRequests::discard)
        boundedCanonicalJob?.cancel()
        boundedCanonicalJob = null
        boundedContinuationJob?.cancel()
        boundedContinuationJob = null
        boundedTopUpJob?.cancel()
        boundedTopUpJob = null
        boundedContextRegistry.register(
            contextToken = "cleared:${UUID.randomUUID()}",
            sourceKey = "cleared",
            browseId = null,
            songs = emptyList(),
            continuationToken = null,
        )
        BoundedContextPersistence.clearImmediately()
        boundedContextWindow = null
        boundedContextToken = null
        boundedContextSource = null
        boundedContextRequest = null
        QueueShuffle.clearCanonicalContext()
    }

    /**
     * Leaves only the row that is playing (or paused/stopped) in the queue.
     *
     * The bounded context goes first: it is what tops the upcoming window back
     * up from the album or folder that started playback, so without dropping it
     * the cleared queue would refill on the next track change. Everything
     * before and after the current row is then removed in place, which keeps
     * the current item — its position, buffer and playback state — untouched.
     * The shortened queue is persisted so the next launch restores it.
     */
    private fun clearQueueExceptCurrent() {
        val exoPlayer = player ?: return
        val count = exoPlayer.mediaItemCount
        if (count == 0) return
        val current = exoPlayer.currentMediaItemIndex
        if (current !in 0 until count) return

        clearBoundedContext()
        if (current > 0) exoPlayer.removeMediaItems(0, current)
        if (exoPlayer.mediaItemCount > 1) {
            exoPlayer.removeMediaItems(1, exoPlayer.mediaItemCount)
        }
        saveQueueSnapshotImmediately(exoPlayer)
        refreshCustomLayouts()
    }

    /** Follows the continuation independently of the detail-page lifecycle. */

    private fun scheduleBoundedContextTopUp() {
        if (boundedContextWindow == null || boundedContextToken == null) return
        boundedEditState = BoundedQueueEdits.requestTopUp(boundedEditState)
        if (boundedTopUpJob?.isActive == true) return
        boundedTopUpJob = scope.launch {
            if (crossfade?.isTransitioning() == true) {
                return@launch
            }
            applyBoundedContextTopUp()
            boundedEditState = boundedEditState.copy(topUpPending = false)
        }
    }

    private fun applyBoundedContextTopUp() {
        val exoPlayer = player ?: return
        val window = boundedContextWindow ?: return
        val snapshot = boundedContextRegistry.snapshot() ?: return
        if (snapshot.contextToken != boundedContextToken) return
        window.extendTo(snapshot.songs.size)
        val currentIndex = boundedContextAnchor(exoPlayer) ?: window.currentCanonicalIndex ?: return
        if (window.currentCanonicalIndex != currentIndex) {
            window.jumpTo(currentIndex)
        }
        val now = exoPlayer.currentMediaItemIndex
        val afterCurrent = if (now in 0 until exoPlayer.mediaItemCount) {
            (now + 1 until exoPlayer.mediaItemCount).map { exoPlayer.getMediaItemAt(it) }
        } else emptyList()
        val existingEntries = afterCurrent
            .filter { it.queueTier == QueueTier.CONTEXT }
            .mapNotNull { item ->
                val canonicalIndex = QueueShuffle.getCanonicalIndex(item) ?: return@mapNotNull null
                QueueWindowEntry(canonicalIndex, item.contextCycleId ?: window.cycleId)
            }
        val repeatAll = exoPlayer.repeatMode == Player.REPEAT_MODE_ALL &&
            BoundedQueuePlanner.canRepeat(snapshot.continuationState)
        val additions = BoundedQueuePlanner.topUpEntries(
            window = window,
            materialized = existingEntries,
            repeatAll = repeatAll,
        )
        if (additions.isEmpty()) return
        val autoplayIndex = (now + 1 until exoPlayer.mediaItemCount)
            .firstOrNull { exoPlayer.getMediaItemAt(it).queueTier == QueueTier.AUTOPLAY }
            ?: exoPlayer.mediaItemCount
        val items = additions.map { entry ->
            boundedMediaItem(snapshot.songs[entry.canonicalIndex], entry.canonicalIndex, entry.cycleId)
        }
        exoPlayer.addMediaItems(autoplayIndex, items)
    }

    private fun boundedContextAnchor(exoPlayer: ExoPlayer): Int? {
        val current = exoPlayer.currentMediaItem
        if (current?.queueTier == QueueTier.CONTEXT) {
            QueueShuffle.getCanonicalIndex(current)?.let { return it }
        }
        val currentIndex = exoPlayer.currentMediaItemIndex
        for (index in currentIndex - 1 downTo 0) {
            val item = exoPlayer.getMediaItemAt(index)
            if (item.queueTier == QueueTier.CONTEXT) {
                QueueShuffle.getCanonicalIndex(item)?.let { return it }
            }
        }
        return null
    }

    private fun syncBoundedContextCursor(exoPlayer: ExoPlayer, mediaItem: MediaItem?) {
        val window = boundedContextWindow ?: return
        if (mediaItem?.queueTier != QueueTier.CONTEXT) return
        val canonicalIndex = QueueShuffle.getCanonicalIndex(mediaItem) ?: return
        val cycle = mediaItem.contextCycleId
        if (cycle != null) {
            window.commit(QueueWindowEntry(canonicalIndex, cycle))
        } else if (window.currentCanonicalIndex != canonicalIndex) {
            window.jumpTo(canonicalIndex)
        }
    }

    private fun boundedMediaItems(plan: BoundedContextPlan, window: QueueWindow): List<MediaItem> {
        val canonical = plan.canonicalContext
        val history = plan.history.map { song ->
            boundedMediaItem(song, canonical.indexOfFirst { it.contextEntryKey() == song.contextEntryKey() }, window.cycleId)
        }
        val current = boundedMediaItem(plan.current, plan.currentCanonicalIndex, window.cycleId)
        val user = plan.userQueue.map { it.copy(queueEntryId = it.queueEntryId ?: UUID.randomUUID().toString()).toMediaItem() }
        val context = plan.contextUpcoming.map { entry ->
            boundedMediaItem(canonical[entry.canonicalIndex], entry.canonicalIndex, entry.cycleId)
        }
        val autoplay = plan.autoplay.map { it.copy(queueEntryId = it.queueEntryId ?: UUID.randomUUID().toString()).toMediaItem() }
        return history + listOf(current) + user + context + autoplay
    }

    private fun boundedMediaItem(song: Song, canonicalIndex: Int, cycleId: Long): MediaItem {
        val entry = song.copy(
            queueTier = QueueTier.CONTEXT,
            queueEntryId = "window:${UUID.randomUUID()}",
        )
        return QueueShuffle.withQueueMetadata(
            entry.toMediaItem(),
            newEntryId = entry.queueEntryId,
            newCanonicalIndex = canonicalIndex,
            newTier = QueueTier.CONTEXT,
            newContextCycleId = cycleId,
        )
    }


    /**
     * Both players, built identically. Only [ownsSession] differs, and only at
     * construction — it moves at every handoff, see [setSessionOwner].
     *
     * They share the media source factory, so whichever one is arming reads from
     * the same on-disk cache the other is playing out of rather than
     * re-resolving a stream URL for audio that is already local.
     */
    private fun buildPlayer(
        spatial: SpatialAudioProcessor,
        equalizer: EqualizerProcessor,
        filter: TransitionFilterProcessor,
        loudness: LoudnessProcessor,
        ownsSession: Boolean,
    ): ExoPlayer = ExoPlayer.Builder(this)
        .setRenderersFactory(silenceSkippingRenderers(spatial, equalizer, filter, loudness))
        .setMediaSourceFactory(requireNotNull(mediaSourceFactory))
        .setLoadControl(farBufferingLoadControl())
        .setAudioAttributes(AUDIO_ATTRIBUTES, /* handleAudioFocus = */ ownsSession)
        .setHandleAudioBecomingNoisy(ownsSession)
        // Keeps the CPU awake while this player is playing. Normal output gets
        // that from Android's audio server, but direct USB and AAudio exclusive
        // feed the DAC from this process, and without it a screen-off phone
        // suspends mid-track and the stream underruns.
        .setWakeMode(C.WAKE_MODE_LOCAL)
        // Back restarts the track once you're this far into it; only a
        // press before that steps to the previous one.
        .setMaxSeekToPreviousPositionMs(BACK_RESTARTS_AFTER_MS)
        .build()

    /**
     * Moves the session onto the player the crossfade has just started the
     * incoming track on. This is the whole of the handoff: no seek, no
     * re-buffer, and no audio rendered twice.
     *
     * Order matters in one place — focus is released on the outgoing player
     * *before* the incoming one asks for it, so the app never holds two focus
     * requests at once and never briefly holds none.
     */
    private fun adoptPlayer(outgoing: ExoPlayer, incoming: ExoPlayer) {
        setSessionOwner(outgoing, owns = false)
        setSessionOwner(incoming, owns = true)

        outgoing.removeListener(playbackListener)
        outgoing.removeAnalyticsListener(formatListener)
        // The fields move before the listeners are attached, so anything the
        // first callback reads already describes the new arrangement.
        player = incoming
        spare = outgoing
        val heldFilter = activeFilter
        activeFilter = spareFilter
        spareFilter = heldFilter
        incoming.addListener(playbackListener)
        incoming.addAnalyticsListener(formatListener)
        adoptFormatOf(incoming)

        mediaSession?.player = newSessionPlayer(incoming, requireNotNull(crossfade))

        // The queue moving on used to arrive here as an item transition on the
        // one player that owned the queue. It cannot any more — the incoming
        // track started as its own player's *first* item, which fires on a
        // player nothing was listening to yet — so the bookkeeping that hung off
        // that callback is driven explicitly instead. Without this the crossfade
        // would silently stop scrobbling, stop writing history, stop honouring
        // "sleep after this song" and stop reading ahead.
        onTrackBecameCurrent(
            incoming.currentMediaItem,
            previousEnded = true,
            reason = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
            alreadyAudible = true,
        )

    }

    /**
     * Only one player may handle audio focus at a time.
     *
     * Two focus-handling players in one process fight each other: the standby
     * taking focus as it starts would have Media3 pause the player that lost it,
     * cutting the outgoing track dead instead of fading it. Focus follows the
     * session, and so does "becoming noisy" — unplugging headphones should pause
     * the song you are listening to, which is whichever one the session is on.
     */
    private fun setSessionOwner(target: ExoPlayer, owns: Boolean) {
        target.setAudioAttributes(AUDIO_ATTRIBUTES, /* handleAudioFocus = */ owns)
        target.setHandleAudioBecomingNoisy(owns)
    }

    /**
     * Publishes the standard music-session lifecycle used by system and
     * third-party audio controllers. Keep the session open while playback is
     * preparing as well as while it is audible, so an effect can attach before
     * the first decoded buffer reaches the output device.
     */
    private fun updateAudioEffectSession(activePlayer: ExoPlayer) {
        val sessionId = activePlayer.audioSessionId
        val shouldAdvertise = activePlayer.playWhenReady &&
            (activePlayer.playbackState == Player.STATE_BUFFERING ||
                activePlayer.playbackState == Player.STATE_READY) &&
            sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId > 0

        if (!shouldAdvertise) {
            closeAudioEffectSession()
            return
        }
        if (advertisedAudioEffectSessionId == sessionId) return

        closeAudioEffectSession()
        advertisedAudioEffectSessionId = sessionId
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            },
        )
    }

    private fun closeAudioEffectSession() {
        val sessionId = advertisedAudioEffectSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return

        advertisedAudioEffectSessionId = C.AUDIO_SESSION_ID_UNSET
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            },
        )
    }

    /**
     * Where a tap on the session lands. Media3 uses this both as the media
     * notification's contentIntent and as the session activity handed to the
     * platform MediaSession.
     *
     * This is not cosmetic on One UI: Samsung's Now Bar / Live Notification
     * chip is a launcher for the session, so a session that advertises nowhere
     * to go is skipped and only the plain shade notification survives. Same
     * reason the notification itself was previously un-tappable.
     */
    private fun sessionActivity(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            // MainActivity is singleTask, so this resumes the existing task
            // rather than stacking a second copy of the UI.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )


    /**
     * Everything that has to happen when a different song becomes the one
     * playing: history, scrobbles, the sleep timer, read-ahead
     * and the second look for a better copy.
     *
     * Called from two places, and it has to be, because there are now two ways
     * for the current song to change. ExoPlayer's own item transition covers
     * the ordinary ones — the queue advancing, a skip, a repeat. A crossfade
     * covers none of them: the incoming track starts life as the *first* item
     * of the other player, which fires a transition on a player nothing is
     * listening to yet, so [adoptPlayer] calls this by hand at the handoff.
     * Before that split existed this logic lived inside the callback, and
     * moving to two players would have silently stopped every crossfaded track
     * from being scrobbled, recorded, or read ahead for.
     *
     * @param previousEnded whether the song being replaced ran to its end, as
     *   opposed to being skipped past. Only an ended song is a listen.
     * @param alreadyAudible whether the track was already sounding when it
     *   became current, which is only true of a crossfade handoff.
     */
    private fun onTrackBecameCurrent(
        mediaItem: MediaItem?,
        previousEnded: Boolean,
        reason: Int,
        alreadyAudible: Boolean = false,
    ) {
        val exoPlayer = player ?: return
        syncBoundedContextCursor(exoPlayer, mediaItem)
        if (boundedContextWindow != null) {
            saveBoundedContextSnapshot(exoPlayer)
        }
        currentAudioInputFormat = null
        NerdStats.onTrackTransition(mediaItem?.mediaId)
        // A rendition discarded and fetched again comes back under the same
        // URI, so a size measured for the last track isn't kept past it.
        measuredBitrateCache.clear()
        AudioOutputStatus.onTrackTransition()
        mediaItem?.mediaId?.let { id ->
            resolveLocalBitrate(id, mediaItem)
        }

        // A crossfade handoff never fires [formatListener] for the entering
        // track — [CrossfadeController] starts its decoder during ARMING,
        // while the listener is still on the outgoing player, and it isn't
        // reattached until this handoff, by which point the format has
        // already been reported once and won't be again. The claim from
        // source resolution stands in until the decoder itself confirms or
        // corrects it — see [formatListener]'s onAudioInputFormatChanged.
        activeTrackIsDolbyAtmos = NerdStats.declaredFormat(mediaItem?.mediaId)?.isDolbyAtmos == true
        applySpatialAudioEnabled()

        setupLoudness(mediaItem?.mediaId)

        // Keep a real, bounded history in the player rather than merely hiding
        // old rows in Compose. MediaController mirrors the playlist across the
        // session boundary, persistence snapshots it, and crossfade duplicates
        // it onto the standby player, so letting completed entries accumulate
        // made every one of those paths progressively more expensive.
        val expiredHistory = queueHistoryTrimCount(exoPlayer.currentMediaItemIndex)
        if (expiredHistory > 0) {
            if (boundedContextWindow != null) {
                // In bounded mode the logical QueueWindow owns repeat/cycle
                // state. The player timeline only retains the recent history;
                // rotating expired rows would reintroduce them ahead of the
                // logical cursor and make a cycle look unconsumed.
                exoPlayer.removeMediaItems(0, expiredHistory)
            } else if (exoPlayer.repeatMode == Player.REPEAT_MODE_ALL) {
                // Repeat-all still means the whole queue. Rotate history that
                // has fallen out of the visible 25-song window to the end
                // instead of deleting it; it becomes upcoming again in the
                // same order and the loop can continue indefinitely.
                exoPlayer.rotateExpiredHistory(expiredHistory)
            } else {
                exoPlayer.removeMediaItems(0, expiredHistory)
            }
        }
        scheduleBoundedContextTopUp()
        // A *different* track is a clean slate for [recoverFrom], and so is the
        // same track becoming current for any reason other than that method's
        // own retry. The distinction is the whole of the reported loading loop.
        //
        // This was an unconditional clear, on the reasoning that the count exists
        // to stop one broken stream looping rather than to hold a grudge for the
        // session — and that reasoning is right about the listener pressing play
        // again, which is why it is kept below. What it missed is that "a track
        // became current" is not the same event as "something other than the
        // retry happened": ExoPlayer fires a transition for PLAYLIST_CHANGED and
        // for SEEK, and [recoverFrom]'s recovery *is* a seek — so the counter was
        // reset by the very retries it was counting. The report shows four resets
        // in 2m41s, each followed by a fresh "attempt 1", eight failures against
        // a budget of two, and roughly twenty-seven full resolve walks for one
        // unplayable track. [retryingMediaId] is the one case that must not
        // reset; everything else still does.
        val becameCurrent = mediaItem?.mediaId
        if (becameCurrent == null || becameCurrent != retryingMediaId) {
            recoveries.clear()
        }
        retryingMediaId = null

        // Where the wait starts, for the log in onIsPlayingChanged — unless
        // there was no wait. A crossfaded track has been audible for as long as
        // it has been current, so `onIsPlayingChanged` will never fire for it
        // and an armed timer would sit there until some unrelated buffering
        // blip tripped it, reporting a wait of seconds for a track that started
        // instantly. Measured one at 16871ms.
        trackSelectedAt = if (alreadyAudible) null else SystemClock.elapsedRealtime()
        if (alreadyAudible) {
            TrackLog.d(
                "umesie",
                "TIMING first audio: 0ms, the crossfade covered it",
                about = mediaItem?.mediaId,
            )
            // The other way a track becomes audible. `onIsPlayingChanged` never
            // fires across a crossfade handoff — the incoming track has been
            // sounding since before it was current — so without this a blended
            // advance would leave every track looking like it never started.
            audibleMediaId = mediaItem?.mediaId
            consecutiveErrorSkips = 0
        }
        // And the same instant on the wall clock, which is the one
        // logcat stamps its lines with — see [TrackLog].
        mediaItem?.mediaId?.let(TrackLog::onTrackStarted)

        TrackLog.d(
            "umesie",
            "TIMING track selected: ${mediaItem?.mediaId} (reason=$reason)",
            about = mediaItem?.mediaId,
        )

        lastPositionSeconds = 0

        // Scrobbling: stop old song, start new song
        scrobbleManager?.onSongStop()
        // And the local record, which needs the transition even when the track
        // id doesn't change: repeat-one plays the same song again, and without
        // this the second play through is a continuation of the first and is
        // never counted.
        ListeningRecorder.onStopped()
        val newSong = mediaItem?.toSong()
        newSong?.let(LocalListeningHistory::record)
        val durationMs = exoPlayer.duration.takeIf { it > 0 }
        if (exoPlayer.isPlaying) {
            scrobbleManager?.onSongStart(newSong, durationMs)
        }

        NerdStats.current.value = null

        // "Sleep after this song": the queue moving on by itself is the
        // moment the track the user meant has finished. REPEAT counts
        // too, or the timer would never fire with repeat-one on.
        if (previousEnded && SleepTimer.afterTrack.value) {
            exoPlayer.pause()
            SleepTimer.cancel()
        }
        savePlaybackState(exoPlayer)
        // Covers crossfades too: a blended advance never reaches
        // onMediaItemTransition, and [adoptPlayer] calls this handler by hand.
        publishWidgetState()
        loadLyricsForCurrentTrack()
        if (exoPlayer.isPlaying) startLyricsTicker()
    }

    /**
     * How many times each track has been picked up off the floor, so a stream
     * that fails the same way every time stops rather than loops. Reset when
     * the queue genuinely moves on, not when a track merely re-prepares.
     */
    private val recoveries = mutableMapOf<String, Int>()

    /**
     * The track [recoverFrom] is about to seek-and-prepare, so that the
     * transition its own retry may fire can be told from the queue moving on or
     * the listener asking again — see [onTrackBecameCurrent]. Cleared by the
     * transition it describes.
     */
    private var retryingMediaId: String? = null


    /**
     * Puts a track that died mid-read back on its feet.
     *
     * Two things get thrown away before trying again, because both have been
     * seen to be the actual fault and neither is visible from the exception:
     *
     * The position is kept: this should look like a hiccup, not like the song
     * starting over.
     */
    private fun recoverFrom(error: PlaybackException, player: ExoPlayer) {
        val item = player.currentMediaItem ?: return
        val mediaId = item.mediaId
        val uri = item.localConfiguration?.uri
        val position = player.currentPosition.coerceAtLeast(0L)
        val attempts = recoveries.getOrDefault(mediaId, 0) + 1
        recoveries[mediaId] = attempts
        TrackLog.w(
            "umesie",
            "playback failed for $mediaId at ${position}ms (${error.errorCodeName}), attempt $attempts",
            error,
            about = mediaId,
        )
        // A local file that is not there is the one failure retrying cannot
        // touch, and the only one where the fix is to stop asking for the file.
        //
        // Everything below this point recovers a *stream*: it discards cached
        // bytes, releases the choice of who serves the track, and prepares the
        // same item again. Against `file:///…/Drake - Janice STFU.m4a` that is
        // all wasted — the uri is baked into the item already in the timeline,
        // so `prepare()` reopens the identical dead path and fails identically.
        // Observed as eight `ERROR_CODE_IO_FILE_NOT_FOUND`s in three seconds
        // against a download whose file had been deleted from a file manager,
        // ending in a track that simply refused to play.
        //
        // Rebuilding the item without its local uri is what turns that into a
        // stream, and dropping the record is what stops the next play walking
        // into the same hole. Deliberately ahead of the verdict and the attempt
        // budget below: this is not an attempt spent, it is a different source.
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND &&
            restreamMissingLocalFile(player, item, uri, position)
        ) {
            return
        }

        // Giving up on the *retry*, not on everything below it.
        //
        // A track that has exhausted its attempts is not finished with.
        // [recoveries] is cleared the moment any track becomes current, so the
        // listener who presses play again gets a fresh count — and used to get,
        // along with it, the exact URL that had just failed three times.
        // A pinned stream URL outlives this method, and anything that reads one
        // *before* resolving would replay the dead URL instantly and in
        // silence: no resolve logged, no lookup attempted, and none of the
        // refusals recorded below ever consulted, because reaching them means
        // getting as far as resolving. That is a track that cannot be played
        // and does not even try, which to the listener is a track that is
        // permanently broken.
        //
        // So everything from here to the discard runs either way, and only the
        // seek-and-prepare at the end is skipped.
        //
        // A track that has never made a sound has its own answer below; giving
        // up on the retry is what stops the reuse of a URL that just failed.
        val givingUp = attempts > MAX_RECOVERIES
        // A track that has never made a sound has *failed to start*, whatever
        // the error was, and the queue should move past it once the attempts are
        // spent — see [skipReason]. Read here rather than after the delay below,
        // because the seek-and-prepare of an unrelated recovery could move the
        // player on in between.
        val neverStarted = audibleMediaId != mediaId
        if (givingUp) {
        }
        // Whatever failed took its claimed format with it. The stream that
        // recovers is a different one and has not promised anything yet, so
        // leaving the old claim behind is how a badge earned by a FLAC ends up
        // sitting over the Opus that replaced it.
        NerdStats.clearDeclared(mediaId)
        scope.launch(TrackLog.about(mediaId)) {
            // Long enough for the released source to let go of the cache keys
            // about to be removed, short enough to read as a stutter.
            delay(RECOVERY_DELAY_MS)
            if (givingUp) {
                // A track nobody can play must not leave the player parked in
                // IDLE on it. Nothing else in this service calls prepare() again,
                // so before this the queue simply stopped: the notification kept
                // showing the song, the play button kept doing nothing, and from
                // the outside that is indistinguishable from a hung app — which
                // is what the report describes and what "it was stuck on my
                // phone too" means. Moving on is the only honest answer.
                skipReason(error, neverStarted, attempts)?.let { reason ->
                    withContext(Dispatchers.Main) { skipPastUnplayable(mediaId, reason) }
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                val player = this@PlaybackService.player ?: return@withContext
                if (player.currentMediaItem?.mediaId != mediaId) return@withContext
                TrackLog.d("umesie", "retrying $mediaId from ${position}ms")
                // Claimed before the seek, so the transition it may fire is
                // recognised as this retry rather than read as a fresh start and
                // handed a fresh attempt budget.
                retryingMediaId = mediaId
                player.seekTo(player.currentMediaItemIndex, position)
                player.prepare()
            }
        }
    }

    /**
     * Swaps a track whose downloaded file has gone missing back onto a stream,
     * in place and at the same position.
     *
     * The file was downloaded and then deleted from under the app — a file
     * manager, a cleaner, a wiped SD card — leaving [Downloads]' record pointing
     * at nothing. [Song.toMediaItem] checks that record before it builds an
     * item, but only at build time: an item already sitting in the timeline was
     * built when the file was still there can retain that stale URI while it
     * waits in the live queue. This is the other end of that, and the only one
     * that can see the file is gone rather than guess.
     *
     * The record goes first, then the item is rebuilt from its own metadata with
     * the local uri stripped, which sends [Song.toMediaItem] down its streaming
     * branch. Position is kept, so this reads as the hiccup [recoverFrom] is
     * written around rather than the song starting over.
     *
     * @return false when this is not that situation and the caller should carry
     *   on with its ordinary stream recovery — including the case where stripping
     *   the local uri changes nothing, which is a device-library track whose
     *   mediaId *is* the missing file and for which there is no stream to fall
     *   back to. Returning true there would be a swap that fixes nothing, on a
     *   loop.
     */
    private fun restreamMissingLocalFile(
        player: ExoPlayer,
        item: MediaItem,
        uri: Uri?,
        position: Long,
    ): Boolean {
        val scheme = uri?.scheme
        if (scheme != "file" && scheme != "content") return false
        val mediaId = item.mediaId

        val restreamed = item.toSong().copy(localUri = null, localPath = null).toMediaItem()
        if (restreamed.localConfiguration?.uri == uri) {
            TrackLog.w(
                "umesie",
                "$mediaId is a local file that is gone and has no stream behind it",
                about = mediaId,
            )
            return false
        }

        TrackLog.w(
            "umesie",
            "$mediaId was downloaded but $uri is gone; streaming it instead",
            about = mediaId,
        )
        // Not claimed as [retryingMediaId], unlike the seek-and-prepare retry
        // below: that flag exists to stop a retry against the *same* stream
        // refilling its own attempt budget, and this is a different source
        // entirely. Letting the transition clear the count is the right answer
        // here — a stream that has never been tried deserves the full budget.
        recoveries.remove(mediaId)
        player.replaceMediaItem(player.currentMediaItemIndex, restreamed)
        player.seekTo(player.currentMediaItemIndex, position)
        player.prepare()
        return true
    }


    /**
     * Why a track that has run out of attempts should be left behind, or null
     * to park on it as the queue used to.
     *
     * Two things get past here.
     *
     * A [verdict] always does, unconditionally and exactly as it did before this
     * function existed: the resolver has established the track cannot be served
     * by anyone, and a playlist with a run of age-gated or region-locked tracks
     * in it should walk straight through them.
     *
     * Everything else gets past only when the track never made a sound. The
     * error itself is not consulted — a 403, a dead socket, a codec that will
     * not initialise and a cache entry that will not open are all, from the
     * listener's side, the same event: they pressed play and nothing happened.
     * Waiting for a *classification* of that is what left the queue parked on
     * tracks whose only sin was an error nobody had taught the service to name.
     * What is consulted is [neverStarted], because the one failure that must not
     * move the queue is the one in the middle of a song someone is listening to:
     * the attempts are spent, the recovery has already put them back where they
     * were twice, and jumping to the next track at that point would take the
     * song away rather than rescue it.
     *
     * [MAX_CONSECUTIVE_SKIPS] is the floor under the rest. Nothing here can tell
     * a broken track from a broken network, and offline every track in the queue
     * fails identically — so without a limit a dropped connection would quietly
     * walk to the end of the queue, spending a full attempt budget per track,
     * and hand back a queue the listener no longer recognises. Stopping instead
     * leaves the error on the player, which is what puts a message on screen,
     * and leaves the queue where it was. The count only resets when something
     * actually plays, so a working track anywhere in a bad patch restores the
     * full allowance. Verdicts are exempt: they are a statement about one track
     * rather than a guess, and their skipping is behaviour that already works.
     */
    private fun skipReason(
        error: PlaybackException,
        neverStarted: Boolean,
        attempts: Int,
    ): String? {
        if (!neverStarted) return null
        if (consecutiveErrorSkips >= MAX_CONSECUTIVE_SKIPS) {
            TrackLog.w(
                "umesie",
                "$MAX_CONSECUTIVE_SKIPS tracks in a row failed to start; " +
                    "this is the queue or the connection, not the track — stopping here",
            )
            return null
        }
        consecutiveErrorSkips++
        return "failed to start after $attempts attempts (${error.errorCodeName})"
    }

    /**
     * Leave a track the resolver has ruled out and carry on down the queue.
     *
     * The item is left in place rather than removed: the listener queued it, and
     * the reason it cannot be played is usually temporary in a way this service
     * cannot see the end of — signing in clears an age gate, travelling clears a
     * region block, and a stream that 403s now resolves again in an hour.
     * Removing it would quietly rewrite a queue on the strength of a ten-minute
     * verdict.
     *
     * Whether a failure has earned this at all is [skipReason]'s decision, not
     * this method's; by here the answer is yes.
     *
     * With nothing after it there is nowhere to go, and stopping is then the
     * correct end state rather than a failure to recover: the error stays on the
     * player, which is what puts a message in front of the listener — see
     * `rememberPlayerState` in
     * [PlayerConnection][com.juni.io.playback.PlayerConnection].
     */
    private fun skipPastUnplayable(mediaId: String, reason: String) {
        val exoPlayer = player ?: return
        if (exoPlayer.currentMediaItem?.mediaId != mediaId) return
        if (!exoPlayer.hasNextMediaItem()) {
            TrackLog.w("umesie", "$reason — and nothing after it in the queue", about = mediaId)
            return
        }
        TrackLog.w("umesie", "$reason — skipping to the next track", about = mediaId)
        exoPlayer.seekToNextMediaItem()
        // No play() here: an error does not clear playWhenReady, so prepare()
        // resumes exactly as far as the listener had asked for. Calling play()
        // would un-pause a queue they had paused.
        exoPlayer.prepare()
    }

    /**
     * Why [error] means "never", or null if it only means "not just now".
     *
     * Unwrapped by hand because the classification is the resolver's and the
     * exception has been through Media3's loader by the time it arrives:
     * `ExoPlaybackException` wrapping `Loader.UnexpectedLoaderException` wrapping
     * what the resolver actually threw. A shallow `is` check sees only the
     * outermost of those three.
     */


    /**
     * When the audio was last cut for a quality swap, so the analytics listener
     * can say how long it stayed cut. Null except across a swap.
     */
    private var swapCutAt: Long? = null

    /**
     * The track [ExoPlayer.getAudioFormat] is currently describing.
     *
     * `audioFormat` is a property of the *renderer*, not of the queue item, and
     * it keeps naming the outgoing track's codec until the renderer has read a
     * sample of the incoming one. Anything that asks "what is playing right
     * now" in the moments after a transition is therefore told about the track
     * before it, and [adoptCachedTrack] is asked exactly there — a queue
     * advance is one of the places [lookForBetterCopy] runs from.
     *
     * Observed: 'Harleys In Hawaii' came up fifteen milliseconds after the
     * queue moved onto it, twenty seconds after the previous track had been
     * upgraded to FLAC. The renderer still said `audio/flac`, so a WebM Opus
     * stream — verified by the `1A 45 DF A3` on its cache entry — was written
     * off as "already lossless from cache" and, because that verdict is
     * recorded once and for good, never offered an upgrade again for the rest
     * of the session.
     */
    private var audioFormatFor: String? = null

    private class Measured(
        val sampleRateHz: Int?,
        val channels: Int?,
        val bitDepth: Int?,
    ) {
        /**
         * What the samples cost per second once decoded, in kbps.
         *
         * `16-bit · 44.1 kHz · stereo` is 1411, `24-bit · 96 kHz · stereo` is
         * 4608 — the figures Tidal, Qobuz and Apple Music all put next to a
         * lossless track, and the only bitrate that means anything for one.
         * A FLAC's *compressed* rate is a property of how compressible that
         * particular recording was, so two copies of the same master at the
         * same quality report different numbers and the comparison the reader
         * is trying to make doesn't survive it.
         *
         * Null unless all three are known: two thirds of this product is not
         * a bitrate.
         */
        val pcmBitrateKbps: Int?
            get() {
                val depth = bitDepth ?: return null
                val rate = sampleRateHz ?: return null
                val ch = channels ?: return null
                return (depth.toLong() * rate * ch / 1000).toInt().takeIf { it > 0 }
            }
    }

    /**
     * What the renderer was handed, with the gaps its container left filled in
     * from the stream's own header.
     *
     * A container is free not to state any of this, and one in use here
     * doesn't: Tidal's FLAC arrives inside MP4, whose audio sample entry
     * carries the sample rate as a 16.16 fixed-point field and is written
     * zero, the escape the FLAC-in-ISOBMFF spec allows precisely because the
     * real figure lives in the `dfLa` box below it. Media3 passes that box
     * through as initialization data without folding it back into the
     * `Format`, so `sampleRate` reached the stats line as 0 and `pcmEncoding`
     * as unset — `audio/flac 0.0kHz ?-bit`, on a track that is plain
     * 16-bit/44.1kHz and displayed as such everywhere else.
     *
     * Every figure here still comes from the bytes being decoded. This is not
     * a fallback to what a source claimed; it is the same stream read at the
     * one layer that always states it, because a FLAC frame cannot be decoded
     * without it.
     */
    private fun Format.measure(): Measured {
        val streamInfo = flacStreamInfo()
        // The renderer's PCM encoding first: it is the one that would show a
        // 24-bit master being truncated on the way to the sink, which is the
        // whole reason the figure is on screen. STREAMINFO describes the file,
        // so it can only answer what the file holds.
        //
        // Float is the exception, and has to be, because it is a carrier
        // rather than a measurement. `MediaCodecAudioRenderer.getMediaFormat`
        // asks the decoder for float output whenever the sink reports it can
        // take one, so a plain 16-bit/44.1kHz file comes back through this
        // path reading 32-bit exactly as readily as a master does — and then
        // gets called Hi-Res Lossless, and wears the shine under the
        // transport, on the strength of the container it happened to travel
        // in. Nothing about a float buffer says how many bits were in the
        // samples poured into it, so it is not asked.
        val rendererDepth = if (pcmEncoding == C.ENCODING_PCM_FLOAT) null else bitDepthOf(pcmEncoding)
        return Measured(
            sampleRateHz = sampleRate.takeIf { it > 0 } ?: streamInfo?.sampleRateHz,
            channels = channelCount.takeIf { it > 0 } ?: streamInfo?.channels,
            bitDepth = rendererDepth ?: streamInfo?.bitDepth,
        )
    }

    /** Sample rate, channel count and bit depth as a FLAC STREAMINFO block states them. */
    private class FlacStreamInfo(val sampleRateHz: Int, val channels: Int, val bitDepth: Int)

    /**
     * The STREAMINFO block carried in this format's initialization data, for a
     * FLAC stream that has one.
     *
     * The offset is found rather than assumed. Media3 hands the block over
     * with a `fLaC` marker in front of it from both the MP4 and the raw path,
     * but that is its business and not something worth depending on, so all
     * three shapes — marker, bare block header, bare body — are recognised by
     * looking for the header itself: a metadata block type of 0 (STREAMINFO)
     * followed by a 24-bit length of 34.
     */
    private fun Format.flacStreamInfo(): FlacStreamInfo? {
        if (sampleMimeType != MimeTypes.AUDIO_FLAC) return null
        val data = initializationData.firstOrNull() ?: return null
        fun byteAt(i: Int) = data[i].toInt() and 0xFF
        fun headerAt(i: Int) = data.size > i + 3 &&
            (byteAt(i) and 0x7F) == 0 &&
            byteAt(i + 1) == 0 && byteAt(i + 2) == 0 && byteAt(i + 3) == STREAM_INFO_BYTES
        val body = when {
            data.size >= 4 && String(data, 0, 4, Charsets.US_ASCII) == "fLaC" ->
                if (headerAt(4)) 8 else 4
            headerAt(0) -> 4
            else -> 0
        }
        if (data.size < body + STREAM_INFO_BYTES) return null
        fun at(i: Int) = byteAt(body + i)
        // STREAMINFO opens with two 16-bit block sizes and two 24-bit frame
        // sizes — ten bytes — and then packs, without alignment, a 20-bit
        // sample rate, a 3-bit channel count and a 5-bit sample depth, the
        // last two both stored one less than they mean.
        val sampleRate = (at(10) shl 12) or (at(11) shl 4) or (at(12) shr 4)
        val channels = ((at(12) shr 1) and 0x07) + 1
        val bitDepth = (((at(12) and 0x01) shl 4) or (at(13) shr 4)) + 1
        return FlacStreamInfo(sampleRate, channels, bitDepth).takeIf { sampleRate > 0 }
    }

    /**
     * Publishes what the decoder is really being fed, for "stats for nerds"
     * and for the quality badge above it.
     *
     * Everything the badge is decided on is measured off the stream in hand —
     * see [Measured] and [NerdStats.Snapshot.isLossless]. What a source said
     * it was about to send is carried alongside as [NerdStats.Snapshot.claimed]
     * and used for nothing but the mismatch note, because a claim is the one
     * thing that stays true after the stream it described has stopped playing.
     *
     * Bitrate is the awkward one. A lossless stream gets the rate its samples
     * decode to, which is a product of three figures already measured and is
     * the number a listener can compare between tracks. A lossy one gets the
     * container's, when the container states it — YouTube's WebM and MP4 do
     * not, so [Format.bitrate] arrives as `NO_VALUE` and the honest figure is
     * whatever named this stream instead. The source's own figure comes ahead
     * of YouTube's because a track can have both: one resolved through YouTube
     * and then upgraded to a module stream mid-song has a stale 160 sitting in
     * [NerdStats.pickedBitrateKbps] describing audio that stopped playing
     * several seconds ago. Anything still unknown is left null for the UI to
     * omit — better a shorter line than a made-up number.
     */
    private fun isLocalPlayback(mediaId: String, mediaItem: MediaItem?): Boolean {
        if (mediaId.startsWith("content://") || mediaId.startsWith("file://") || mediaId.startsWith("/")) {
            return true
        }
        val uri = mediaItem?.localConfiguration?.uri
        if (uri != null && (uri.scheme == "file" || uri.scheme == "content")) {
            return true
        }
        val requestUri = mediaItem?.requestMetadata?.mediaUri
        if (requestUri != null && (requestUri.scheme == "file" || requestUri.scheme == "content")) {
            return true
        }
        val extras = mediaItem?.mediaMetadata?.extras
        val localUri = extras?.getString(EXTRA_LOCAL_URI)
        if (!localUri.isNullOrBlank() && (localUri.startsWith("content://") || localUri.startsWith("file://"))) {
            return true
        }
        val localPath = extras?.getString(EXTRA_LOCAL_PATH)
        if (!localPath.isNullOrBlank()) {
            return true
        }
        return false
    }

    private fun currentSourceName(mediaId: String?, mediaItem: MediaItem?): String? {
        val id = mediaId ?: return null
        if (isLocalPlayback(id, mediaItem)) {
            return "Local Storage"
        }
        val exactRecorded = NerdStats.exactSourceFor(id)
        if (!exactRecorded.isNullOrBlank()) return exactRecorded

        val recorded = NerdStats.sourceFor(id)
        if (!recorded.isNullOrBlank()) return recorded

        // A YouTube video id is the only other thing a media id can be.
        if (id.length == 11) return "YouTube"

        return "Unknown"
    }

    private fun resolveLocalUri(mediaId: String, mediaItem: MediaItem?): Uri? {
        val directUriStr = when {
            mediaId.startsWith("content://") || mediaId.startsWith("file://") -> mediaId
            mediaId.startsWith("/") -> "file://$mediaId"
            else -> null
        }
        if (directUriStr != null) return Uri.parse(directUriStr)

        val localConfigUri = mediaItem?.localConfiguration?.uri
        if (localConfigUri != null && (localConfigUri.scheme == "file" || localConfigUri.scheme == "content")) {
            return localConfigUri
        }

        val requestUri = mediaItem?.requestMetadata?.mediaUri
        if (requestUri != null && (requestUri.scheme == "file" || requestUri.scheme == "content")) {
            return requestUri
        }

        val extras = mediaItem?.mediaMetadata?.extras
        val localUriStr = extras?.getString(EXTRA_LOCAL_URI)
        if (!localUriStr.isNullOrBlank()) {
            return Uri.parse(localUriStr)
        }

        val localPath = extras?.getString(EXTRA_LOCAL_PATH)
        if (!localPath.isNullOrBlank()) {
            return Uri.fromFile(java.io.File(localPath))
        }

        return null
    }

    private fun resolveLocalBitrate(mediaId: String, mediaItem: MediaItem?) {
        if (!isLocalPlayback(mediaId, mediaItem)) return
        if (localBitrateCache.containsKey(mediaId)) return

        val uri = resolveLocalUri(mediaId, mediaItem) ?: return
        scope.launch(Dispatchers.IO) {
            val kbps = runCatching {
                val retriever = MediaMetadataRetriever()
                var bps: Long? = null
                var durMs: Long? = null
                try {
                    retriever.setDataSource(this@PlaybackService, uri)
                    bps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
                    durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                } finally {
                    retriever.release()
                }

                if (bps != null && bps > 0) {
                    ((bps + 500L) / 1000L).toInt()
                } else if (durMs != null && durMs > 0) {
                    val sizeBytes = when (uri.scheme) {
                        "file" -> uri.path?.let { java.io.File(it).length() }
                        "content" -> contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
                        else -> null
                    }
                    if (sizeBytes != null && sizeBytes > 0) {
                        ((sizeBytes * 8000L) / durMs / 1000L).toInt().takeIf { it > 0 }
                    } else null
                } else null
            }.getOrNull()

            if (kbps != null && kbps > 0) {
                localBitrateCache[mediaId] = kbps
                withContext(Dispatchers.Main) {
                    if (player?.currentMediaItem?.mediaId == mediaId) {
                        publishNerdStats()
                    }
                }
            }
        }
    }

    private fun publishNerdStats() {
        val player = player ?: return
        val format = player.audioFormat
        val mediaId = player.currentMediaItem?.mediaId
        val generation = NerdStats.currentGeneration
        val measured = format?.measure()
        // DSD reaches the renderer already decimated to PCM (DsdExtractor), so
        // its sample mime is `audio/raw`; the extractor leaves `dsd64` and the
        // like in `codecs`, and that is the name worth showing — with the DSD
        // stream's own bitrate and rate (2.8224 MHz for DSD64) rather than the
        // 176.4 kHz PCM it became.
        val dsd = format?.codecs?.takeIf { it.startsWith("dsd") }
        val dsdRateHz = dsd?.removePrefix("dsd")?.toIntOrNull()?.times(44_100)
        val isLossless = dsd != null || NerdStats.isLosslessMime(format?.sampleMimeType)
        val isRawPcm = NerdStats.isRawPcm(format?.sampleMimeType)
        val pcmDataRate = measured?.pcmBitrateKbps

        val (bitrate, provenance) = when {
            format?.averageBitrate != null && format.averageBitrate > 0 ->
                Pair((format.averageBitrate + 500) / 1000, TelemetryProvenance.AUTHORITATIVE)
            format?.bitrate != null && format.bitrate > 0 ->
                Pair((format.bitrate + 500) / 1000, TelemetryProvenance.AUTHORITATIVE)
            mediaId != null && localBitrateCache.containsKey(mediaId) ->
                Pair(localBitrateCache[mediaId], TelemetryProvenance.AUTHORITATIVE)
            dsd == null && isRawPcm && pcmDataRate != null ->
                Pair(pcmDataRate, TelemetryProvenance.DERIVED)
            else ->
                NerdStats.sourceBitrate(mediaId) { measuredStreamKbps(player) }
                    ?: Pair(null, TelemetryProvenance.UNKNOWN)
        }

        NerdStats.publish(NerdStats.Snapshot(
            mimeType = dsd?.let { "audio/$it" } ?: format?.sampleMimeType,
            bitrateKbps = bitrate,
            bitrateProvenance = provenance,
            pcmDataRateKbps = if (isLossless) pcmDataRate else null,
            sampleRateHz = dsdRateHz ?: measured?.sampleRateHz,
            channels = measured?.channels,
            bitDepth = if (dsd != null) 1 else if (isLossless) measured?.bitDepth else null,
            claimed = NerdStats.declaredFormat(mediaId),
            sourceName = currentSourceName(mediaId, player.currentMediaItem),
            container = when (format?.containerMimeType) {
                "audio/x-dsf" -> "DSF"
                "audio/x-dff" -> "DFF"
                else -> null
            },
            mediaId = mediaId,
        ), expectedGeneration = generation)
    }

    /**
     * The playing stream's real bitrate: its full size over its length, or
     * null until both are known.
     *
     * For an addon that didn't state a bitrate and got one guessed from the
     * tier it was asked for — see [NerdStats.sourceBitrate]. The size is what
     * the server's response said, recorded in the cache index the first time
     * the rendition was opened, so this works on a stream still downloading.
     * Slightly high, because container overhead counts toward the bytes and
     * not toward the audio. Read off the main thread, once per rendition: the
     * cache index lookup takes the cache's lock, which a loader may be holding.
     */
    private fun measuredStreamKbps(player: Player): Int? {
        val uri = player.currentMediaItem?.localConfiguration?.uri ?: return null
        val key = uri.toString()
        measuredBitrateCache[key]?.let { return it }
        val durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return null
        if (measuringBitrate.add(key)) {
            scope.launch(Dispatchers.IO) {
                try {
                    // The file's own length, off the platform — nothing to ask a
                    // cache about any more.
                    val bytes = runCatching {
                        contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                    }.getOrNull() ?: -1L
                    if (bytes > 0) {
                        val kbps = (bytes * 8 / durationMs).toInt()
                        if (kbps > 0) measuredBitrateCache[key] = kbps
                    }
                } finally {
                    measuringBitrate.remove(key)
                }
            }
        }
        return null
    }

    /** Serialize only the bounded window needed for a future cold-start resume. */
    private fun saveQueueSnapshot(player: ExoPlayer) {
        if (player.mediaItemCount == 0) {
            persistedQueueStart = 0
            LastPlayed.clear()
            BoundedContextPersistence.clear()
            return
        }
        val range = LastPlayed.window(player.mediaItemCount, player.currentMediaItemIndex)
        if (range.isEmpty()) return
        persistedQueueStart = range.first
        LastPlayed.saveQueue(
            songs = range.map { player.getMediaItemAt(it).toSong() },
            index = player.currentMediaItemIndex - range.first,
        )
        saveBoundedContextSnapshot(player)
        savePlaybackState(player)
    }

    /** Make the newly installed radio queue the durable cold-start boundary. */
    private fun saveQueueSnapshotImmediately(player: ExoPlayer) {
        if (player.mediaItemCount == 0) {
            persistedQueueStart = 0
            LastPlayed.clearImmediately()
            BoundedContextPersistence.clearImmediately()
            return
        }
        val range = LastPlayed.window(player.mediaItemCount, player.currentMediaItemIndex)
        persistedQueueStart = range.first
        LastPlayed.saveQueueImmediately(
            songs = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toSong() },
            index = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
        )
        saveBoundedContextSnapshot(player, immediately = true)
    }

    /** Capture logical state and only the materialized bounded projection. */
    private fun saveBoundedContextSnapshot(player: ExoPlayer, immediately: Boolean = false) {
        val window = boundedContextWindow ?: return
        val context = boundedContextRegistry.snapshot() ?: return
        val source = boundedContextSource ?: return
        if (context.contextToken != boundedContextToken || context.songs.isEmpty()) return
        val currentIndex = player.currentMediaItemIndex.coerceIn(0, player.mediaItemCount - 1)
        val range = LastPlayed.window(player.mediaItemCount, currentIndex)
        if (range.isEmpty()) return
        val entries = range.map { index ->
            val item = player.getMediaItemAt(index)
            BoundedContextPersistence.Entry(
                song = item.toSong(),
                canonicalIndex = if (item.queueTier == QueueTier.CONTEXT) item.canonicalIndex else null,
                cycleId = if (item.queueTier == QueueTier.CONTEXT) item.contextCycleId else null,
            )
        }
        val state = BoundedContextPersistence.State(
            context = context,
            source = source,
            window = window.snapshot(),
            entries = entries,
            currentIndex = currentIndex - range.first,
            positionMs = player.currentPosition,
        )
        if (immediately) BoundedContextPersistence.saveImmediately(state) else BoundedContextPersistence.save(state)
    }

    /** Drop every service-side reference that could resurrect the former queue. */
    private fun beginRadioQueue() {
        crossfade?.onSkipRequested()
        clearBoundedContext()
        persistedQueueStart = 0
        LastPlayed.clearImmediately()
    }

    /** Persist index and position without touching or serializing queue contents. */
    private fun savePlaybackState(player: ExoPlayer) {
        if (player.mediaItemCount == 0) return
        if (boundedContextWindow != null) {
            BoundedContextPersistence.savePlaybackState(
                currentEntryId = player.currentMediaItem?.queueEntryId,
                positionMs = player.currentPosition,
            )
        }
        LastPlayed.savePlaybackState(
            index = player.currentMediaItemIndex - persistedQueueStart,
            positionMs = player.currentPosition,
        )
    }

    /**
     * Restore the logical bounded context first, falling back to the unchanged
     * LastPlayed physical snapshot when the new record is absent or unsafe.
     *
     * The reads happen on [Dispatchers.Default] — the snapshot is a large JSON
     * string and SharedPreferences parses its whole file on first access — and
     * only the player updates come back to the main thread. Called from a
     * coroutine after the session is built, so the controller can connect while
     * this runs.
     */
    private suspend fun restoreQueue(player: ExoPlayer): Boolean {
        val persisted = withContext(Dispatchers.Default) { BoundedContextPersistence.load() }
        StartupTrace.log("service", "queue snapshot read — ${persisted != null}")
        // The controller is already connected while this runs; a track started
        // in that window owns the player now, and restoring must not replace it.
        if (player.mediaItemCount > 0) {
            StartupTrace.log("service", "queue restore skipped — something is already playing")
            return false
        }
        if (persisted != null && restoreBoundedQueue(player, persisted)) return true
        if (persisted != null) BoundedContextPersistence.clearImmediately()
        val last = withContext(Dispatchers.Default) { LastPlayed.load() } ?: return false
        StartupTrace.log("service", "last queue read — ${last.songs.size} songs")
        if (player.mediaItemCount > 0) return false
        persistedQueueStart = 0
        player.setMediaItems(last.songs.map { it.toMediaItem() }, last.index, last.positionMs)
        return true
    }

    private fun restoreBoundedQueue(
        exoPlayer: ExoPlayer,
        persisted: BoundedContextPersistence.State,
    ): Boolean {
        val started = SystemClock.elapsedRealtime()
        val projected = BoundedContextPersistence.restoreProjection(
            persisted,
            repeatAll = exoPlayer.repeatMode == Player.REPEAT_MODE_ALL,
        ) ?: return false
        StartupTrace.log("restore", "projection — ${SystemClock.elapsedRealtime() - started}ms")
        val restoredWindow = runCatching { QueueWindow.restore(projected.window) }.getOrNull()
            ?: return false
        if (restoredWindow.canonicalSize != projected.context.songs.size) return false
        val restoredCurrent = restoredWindow.currentCanonicalIndex ?: return false
        if (projected.currentIndex !in projected.entries.indices) return false

        val restoredContext = runCatching {
            boundedContextRegistry.restore(projected.context)
        }.getOrNull() ?: return false
        if (restoredContext.songs.isEmpty()) return false
        StartupTrace.log(
            "restore",
            "registry — ${restoredContext.songs.size} songs, ${SystemClock.elapsedRealtime() - started}ms",
        )

        val contextToken = restoredContext.contextToken
        // Window-first restore: the canonical songs stay in
        // [boundedContextRegistry] (restoring those is a few milliseconds), but
        // their MediaItems are not built here. Only the physical window becomes
        // player items; shuffle and the queue edits rebuild the upcoming window
        // from the registry songs when they need it. Building a MediaItem per
        // song — 1,313 on the reported library — took seconds on the main thread
        // and was the launch freeze.
        QueueShuffle.clearCanonicalContext()
        val windowStarted = SystemClock.elapsedRealtime()
        val items = projected.entries.map { entry ->
            val canonicalIndex = entry.canonicalIndex
            if (canonicalIndex == null) {
                entry.song.toMediaItem()
            } else {
                boundedMediaItem(
                    entry.song.copy(queueTier = QueueTier.CONTEXT),
                    canonicalIndex,
                    entry.cycleId ?: restoredWindow.cycleId,
                )
            }
        }
        if (items.size != projected.entries.size) return false
        StartupTrace.log(
            "restore",
            "window items ${items.size} — ${SystemClock.elapsedRealtime() - windowStarted}ms",
        )

        // Checked before any service field is touched: the controller connects
        // while restore is still reading, so a track started in that window owns
        // the player and must not be replaced by the restored one.
        if (exoPlayer.mediaItemCount > 0) return false

        boundedContextWindow = restoredWindow
        boundedContextToken = contextToken
        boundedContextSource = persisted.source
        boundedContextRequest = BoundedContextRequests.restore(
            BoundedContextRequests.Request(
                songs = restoredContext.songs,
                startIndex = restoredCurrent,
                source = persisted.source,
                continuationToken = restoredContext.continuationToken,
                shuffle = restoredWindow.isShuffleEnabled,
            ),
        )
        boundedEditState = BoundedQueueEdits.invalidateContext(boundedEditState)
        persistedQueueStart = 0
        exoPlayer.setMediaItems(items, projected.currentIndex, projected.positionMs)
        StartupTrace.log("restore", "media items set — total ${SystemClock.elapsedRealtime() - started}ms")
        return true
    }

    /**
     * PCM sample depth the renderer settled on, in bits.
     *
     * This is the figure that decides whether a hi-res file is being played as
     * one. A 24-bit FLAC whose renderer reports 16-bit PCM has been truncated
     * somewhere between the decoder and the sink, and no other number on the
     * stats line would show it — the sample rate and the codec both survive
     * that unharmed.
     *
     * `ENCODING_INVALID` and `NO_VALUE` mean the renderer hasn't said, which is
     * common for pass-through and for formats decoded straight to float, and
     * is reported as unknown rather than as a failure.
     */
    private fun bitDepthOf(pcmEncoding: Int): Int? = when (pcmEncoding) {
        C.ENCODING_PCM_8BIT -> 8
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> 32
        C.ENCODING_PCM_FLOAT -> 32
        else -> null
    }

    /**
     * Tell the home-screen widgets what is playing.
     *
     * Kept out of [saveQueue] even though every caller does both: that one also
     * runs from the per-second sampler in [reportProgress], and pushing a bitmap
     * to the launcher once a second would be a lot of work to redraw the same
     * picture.
     *
     * [playing] overrides what the player reports, for the one caller that knows
     * better than it does — teardown, where the player is still nominally set to
     * play right up to the moment it is released.
     */
    private fun publishWidgetState(playing: Boolean? = null) {
        val exoPlayer = player ?: return
        val song = exoPlayer.currentMediaItem?.toSong() ?: return
        val liked = (if (song.localUri != null) OfflinePlaylists.isLiked(song) else null)
            ?: MediaWidgetSnapshot.load(this).takeIf { it.mediaId == song.videoId }?.isLiked
            ?: false
        val reported: Player = mediaSession?.player ?: exoPlayer
        val snapshot = MediaWidgetSnapshot(
            mediaId = song.videoId,
            title = song.title,
            artist = song.artist,
            artworkUrl = song.thumbnailUrl,
            // playWhenReady, not isPlaying — see MediaWidgetSnapshot.isPlaying.
            // Through the session player, which answers for Cast or the watch
            // while one of them is the speaker.
            isPlaying = playing ?: reported.playWhenReady,
            hasPrevious = exoPlayer.hasPreviousMediaItem(),
            hasNext = exoPlayer.hasNextMediaItem(),
            isLiked = liked,
            canLike = canLike(exoPlayer.currentMediaItem),
            shuffleEnabled = QueueShuffle.enabled.value,
        )
        MediaWidgetSnapshot.save(this, snapshot)
        MediaWidget.refresh(this)
    }


    private fun reportProgress() {
        scope.launch {
            while (isActive) {
                // Re-read every tick rather than captured once: the session
                // moves between two players, and a sampler pinned to the one
                // that happened to be first would go on reporting a player that
                // has been silent since the last crossfade.
                val player = this@PlaybackService.player
                if (player != null && player.isPlaying) {
                    lastPositionSeconds = player.currentPosition / 1000
                    // The device's own listening record — see [ListeningRecorder],
                    // which counts wall-clock time between ticks rather than
                    // reading the position. This loop is the only place in the app
                    // that ticks exactly while audio is coming out, which is what
                    // makes it the right place to count from.
                    player.currentMediaItem?.toSong()?.let {
                        ListeningRecorder.onSample(it, player.duration)
                    }
                    // Only two primitive preference values. Queue JSON is
                    // written from onTimelineChanged, never from this loop.
                    savePlaybackState(player)
                    // The renderer can settle on its format a moment after the
                    // track change, which no callback of ours follows up on.
                    publishNerdStats()

                }
                delay(PROGRESS_SAMPLE_MS)
            }
        }
    }

    /**
     * Pause when the sleep timer runs out.
     *
     * `collectLatest` is what makes re-setting the timer work: the pending wait
     * is cancelled and restarted on the new deadline instead of both firing.
     */
    private fun watchSleepTimer() {
        scope.launch {
            SleepTimer.deadline.collectLatest { deadline ->
                if (deadline == null) return@collectLatest
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining > 0) delay(remaining)
                player?.pause()
                SleepTimer.cancel()
            }
        }
    }

    /**
     * Buffers as far ahead as a whole track rather than a rolling window.
     *
     * Media3's audio default stops loading at 13 buffer segments, so everything
     * past that is read only as playback consumes it, and a seek past the
     * buffered part waits on a file read.
     *
     * This matters for the track playback *starts* on. Everything after it is
     * read ahead before it is reached; a first track has had no such chance.
     *
     * The byte ceiling is what governs; the duration is set past any song so
     * that it never becomes the binding constraint.
     *
     * Two further departures from the defaults, both about how long the
     * listener waits for sound:
     *
     *  - **Back buffer.** Media3 keeps nothing behind the playhead, so a seek
     *    *backwards* drops the buffer and reloads, while a seek forwards lands
     *    in samples already held. Half a minute of history closes that gap for
     *    the seek people actually make — nudging back a few seconds to catch a
     *    lyric — and it is deliberately no longer than that.
     *  - **Thresholds to (re)start playback.** The defaults — 2.5s of audio
     *    before starting, 5s before resuming after a rebuffer — are sized for
     *    streaming video over a network that might stall again.
     *  - **Thresholds to (re)start playback.** The defaults — 2.5s of audio
     *    before starting, 5s before resuming after a rebuffer — are sized for
     *    streaming video over a network that might stall again. Here the bytes
     *    are usually already on disk, so those seconds are spent waiting on a
     *    buffer that fills instantly and are simply dead air after a seek.
     *    Resuming is given more room than starting: a stall means the network
     *    is genuinely struggling, and coming back with a second of audio in
     *    hand only buys the next stall.
     */
    private fun farBufferingLoadControl() = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            /* maxBufferMs = */ FAR_BUFFER_MS,
            /* bufferForPlaybackMs = */ START_PLAYBACK_MS,
            /* bufferForPlaybackAfterRebufferMs = */ RESUME_PLAYBACK_MS,
        )
        .setTargetBufferBytes(FAR_BUFFER_BYTES)
        .setBackBuffer(/* backBufferDurationMs = */ BACK_BUFFER_MS, /* retainBackBufferFromKeyframe = */ true)
        .build()

    /**
     * Renderers whose audio sink only skips silence worth skipping.
     *
     * Media3's stock threshold is 100ms, which eats the breaths, rests and
     * pre-chorus beats *inside* a song — the setting reads as "make the music
     * sound rushed" rather than "trim dead air". A second-long floor leaves
     * musical pauses alone and still collapses the run-in and run-out of a
     * track. Everything else about the chain stays default, so
     * `skipSilenceEnabled` keeps driving it as before.
     */
    private fun silenceSkippingRenderers(
        spatial: SpatialAudioProcessor,
        equalizer: EqualizerProcessor,
        transition: TransitionFilterProcessor,
        loudness: LoudnessProcessor,
    ) = object : FfmpegFallbackRenderersFactory(this) {
        init {
            // Do not force PCM_FLOAT onto an OEM speaker mixer merely because
            // the preference asks for it. The selected route must advertise
            // the format; otherwise Media3 uses its stable PCM16 path.
            //
            // Which way this cuts is worth stating, because it reads backwards:
            // float is what *preserves* a hi-res source rather than what
            // embellishes it, since it is the only AudioTrack encoding Media3
            // will open that carries a 24-bit sample intact.
            setEnableAudioFloatOutput(configuredFloatOutput)
            // c2.sec.flac.decoder produces one extra 232.2ms timestamp
            // advance per decoded buffer when Media3 requests float PCM.
            // Decode FLAC through the platform software codec on affected Samsung devices.
            setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val selector = if (mimeType == MimeTypes.AUDIO_FLAC) {
                    MediaCodecSelector.PREFER_SOFTWARE
                } else {
                    MediaCodecSelector.DEFAULT
                }
                val candidates = selector.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder,
                )
                if (mimeType == MimeTypes.AUDIO_FLAC) {
                    candidates.filterNot { AudioOutputPolicy.isUnsafeFloatFlacDecoder(it.name) }
                } else {
                    candidates
                }
            }
        }

        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink {
            val defaultSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                // Speed handled by the AudioTrack itself on a float-capable
                // route, rather than by Sonic. `DefaultAudioSink.configure`
                // appends `audioProcessorChain.getAudioProcessors()` — which is
                // where Sonic lives — only on its 16-bit branch; the float
                // branch gets the format converter and nothing else. With the
                // stock flag, changing speed on a float route therefore left
                // the audio playing at 1x while `getMediaDuration` went on
                // scaling, so the position clock drifted away from the sound.
                // AudioTrack's own playback params work on either branch.
                //
                // Silence skipping is in that same dropped chain and has no
                // such escape hatch; the equaliser screen says so, and the
                // equaliser itself is unaffected because it runs upstream in
                // [PrecisionAudioSink], not here.
                .setEnableAudioTrackPlaybackParams(
                    enableAudioTrackPlaybackParams || enableFloatOutput,
                )
                .setAudioProcessorChain(
                    DefaultAudioSink.DefaultAudioProcessorChain(
                        emptyArray<AudioProcessor>(),
                        SilenceSkippingAudioProcessor(
                            MIN_SILENCE_US,
                            SilenceSkippingAudioProcessor.DEFAULT_SILENCE_RETENTION_RATIO,
                            SilenceSkippingAudioProcessor.DEFAULT_MAX_SILENCE_TO_KEEP_DURATION_US,
                            SilenceSkippingAudioProcessor.DEFAULT_MIN_VOLUME_TO_KEEP_PERCENTAGE,
                            SilenceSkippingAudioProcessor.DEFAULT_SILENCE_THRESHOLD_LEVEL,
                        ),
                        SonicAudioProcessor(),
                    ),
                )
                .build()

            // Transition filtering last of the three: widening is a
            // property of the track, and a bass swap that ran before it
            // would have its own low end fed back in by the crossfeed —
            // or, once the equaliser is in the chain, by whatever the
            // listener's low band was set to. The equaliser sits between
            // them for the same reason: it belongs to the listener and
            // the whole session, while the transition filter belongs to
            // one handoff and has to have the last word on it.
            val dspChain = DspChain(spatial, equalizer, transition, loudness)
            val precision = PrecisionAudioSink(
                delegate = defaultSink,
                dspChain = dspChain,
                enableFloatOutput = enableFloatOutput,
                preferredOutputEncodingProvider = { format -> resolvePreferredOutputEncoding(format) },
                // Which of the two sinks the listener can hear, told by the
                // role its transition filter currently holds — [activeFilter]
                // and [spareFilter] trade places at every handoff. Only the
                // audible one writes telemetry, so the idle player's reset
                // cannot publish "not exact" over the playing track's verdict.
                isAudible = { transition === activeFilter },
            )
            // In front of everything above: a track the bit-perfect output can
            // carry skips the DSP chain and Media3's sink altogether.
            return BitPerfectRoutingSink(precision, BitPerfectAudioSink(BitPerfectOutput.forContext(context)))
        }
    }

    /**
     * Push current settings onto a player. Called for both: whichever one is
     * idle right now is the one the next transition will start a song on, so it
     * cannot be left on stale settings.
     */
    private fun applySettings(player: ExoPlayer) {
        player.skipSilenceEnabled = AppSettings.skipSilence.value
        player.setPlaybackSpeed(AppSettings.playbackSpeed.value)
        // Restore persisted shuffle and repeat states on startup. Assign both
        // values: Android can destroy and recreate the service while retaining
        // this process, so QueueShuffle's process-wide object may still hold
        // the previous service instance's value. Only assigning `true` made a
        // stale enabled state consume the first tap meant to enable Shuffle.
        QueueShuffle.setEnabled(AppSettings.shuffleEnabled.value)
        player.repeatMode = AppSettings.repeatMode.value
    }

    /**
     * What the pipeline last reported, for when the live [Format] carries no
     * usable rate or channel count of its own. Guessing 48 kHz here is what made
     * a 176.4 kHz stream negotiate itself down, so the real last-known value is
     * worth reaching for before giving up.
     */
    private fun lastReportedSampleRateHz(): Int? {
        val status = AudioOutputStatus.current.value
        return NerdStats.current.value?.sampleRateHz?.takeIf { it > 0 }
            ?: status.actualSampleRateHz?.takeIf { it > 0 }
            ?: status.negotiationResult?.output?.sampleRateHz?.takeIf { it > 0 }
    }

    private fun lastReportedChannelCount(): Int? {
        val status = AudioOutputStatus.current.value
        return NerdStats.current.value?.channels?.takeIf { it > 0 }
            ?: status.negotiationResult?.output?.channelCount?.takeIf { it > 0 }
    }

    /**
     * Applies the user's USB-DAC preference through the public Android routing
     * API. This does not bypass the system USB driver: it intentionally lets
     * Android negotiate only formats the connected DAC actually advertises.
     */
    private fun applyOutputRoute() {
        val manager = audioManager ?: return
        val usb = preferredUsbDevice()
        val chosen = AudioRouting.infoFor(manager, AudioRouting.selectedId.value)
        val preferred = chosen ?: usb.takeIf { AppSettings.preferUsbDac.value }
        eachPlayer { it.setPreferredAudioDevice(preferred) }

        val activeDevice = resolveActiveOutputDevice()
        val routeKind = resolveActiveRouteKind(activeDevice)

        val format = currentAudioInputFormat
        val measured by lazy(LazyThreadSafetyMode.NONE) { format?.measure() }
        val sampleRate = format?.sampleRate?.takeIf { it > 0 }
            ?: measured?.sampleRateHz?.takeIf { it > 0 }
            ?: lastReportedSampleRateHz()
        val channels = format?.channelCount?.takeIf { it > 0 }
            ?: measured?.channels?.takeIf { it > 0 }
            ?: lastReportedChannelCount()
            ?: 2
        val directSupport = if (sampleRate != null) {
            DirectAudioProbe.probeDirectSupport(
                audioManager = manager,
                sampleRateHz = sampleRate,
                channelCount = channels,
                activeDevice = activeDevice,
            )
        } else {
            DirectAudioProbe.DirectSupport.NONE
        }
        val btTelemetry = if (routeKind == AudioRouting.Kind.BLUETOOTH) bluetoothTracker.telemetry.value else null

        AudioOutputStatus.publish(
            manager = manager,
            requestedPcmMode = AppSettings.outputPcmMode.value,
            preferred = activeDevice,
            floatEnabled = shouldEnableFloatOutput(),
            routeKind = routeKind,
            directSupport = directSupport,
            bluetoothTelemetry = btTelemetry,
            systemMixerRateHz = if (routeKind == AudioRouting.Kind.USB) 48000 else null,
        )

        if (format != null) {
            resolvePreferredOutputEncoding(format)
        }
    }

    private fun resolvePreferredOutputEncoding(format: Format): PcmEncoding? {
        val manager = audioManager ?: return null
        val activeDevice = resolveActiveOutputDevice()
        val routeKind = resolveActiveRouteKind(activeDevice)

        if (routeKind == AudioRouting.Kind.PHONE) {
            return PcmEncoding.PCM_16BIT
        }

        val inputFormat = currentAudioInputFormat ?: format
        val measuredFormat = format.measure()
        val measuredInput by lazy(LazyThreadSafetyMode.NONE) {
            if (inputFormat === format) measuredFormat else inputFormat.measure()
        }

        // No rate from the stream and none ever reported means nothing is known
        // yet: negotiating against a guessed rate would publish that guess as
        // fact. Leave the encoding to the sink's own fallback instead.
        val sampleRate = format.sampleRate.takeIf { it > 0 }
            ?: inputFormat.sampleRate.takeIf { it > 0 }
            ?: measuredFormat.sampleRateHz?.takeIf { it > 0 }
            ?: measuredInput.sampleRateHz?.takeIf { it > 0 }
            ?: lastReportedSampleRateHz()
            ?: return null

        val channels = format.channelCount.takeIf { it > 0 }
            ?: inputFormat.channelCount.takeIf { it > 0 }
            ?: measuredFormat.channels?.takeIf { it > 0 }
            ?: measuredInput.channels?.takeIf { it > 0 }
            ?: lastReportedChannelCount()
            ?: 2
        val bitDepth = measuredFormat.bitDepth
            ?: measuredInput.bitDepth
            ?: NerdStats.current.value?.bitDepth ?: when (inputFormat.pcmEncoding) {
            C.ENCODING_PCM_32BIT -> 32
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_FLOAT -> 32
            else -> when (format.pcmEncoding) {
                C.ENCODING_PCM_32BIT -> 32
                C.ENCODING_PCM_24BIT -> 24
                C.ENCODING_PCM_FLOAT -> 32
                else -> 16
            }
        }

        val sourceEncoding = if (inputFormat.sampleMimeType != null && inputFormat.sampleMimeType != "audio/raw") {
            inputFormat.sampleMimeType!!
        } else {
            format.sampleMimeType ?: "audio/raw"
        }

        val source = SourceDescriptor(
            encoding = sourceEncoding,
            sampleRateHz = sampleRate,
            channelCount = channels,
            bitDepth = bitDepth,
        )

        val directSupport = DirectAudioProbe.probeDirectSupport(
            audioManager = manager,
            sampleRateHz = sampleRate,
            channelCount = channels,
            activeDevice = activeDevice,
        )
        val btTelemetry = if (routeKind == AudioRouting.Kind.BLUETOOTH) bluetoothTracker.telemetry.value else null

        val advertisedEncodings = activeDevice?.encodings?.toList() ?: emptyList()
        val advertisedSampleRates = activeDevice?.sampleRates?.toList() ?: emptyList()

        val negotiation = OutputNegotiator.negotiate(
            source = source,
            decoderName = AudioOutputStatus.current.value.decoderName,
            sampleRateHz = sampleRate,
            channelCount = channels,
            routeKind = routeKind,
            deviceName = activeDevice?.productName?.toString()?.ifBlank { null } ?: "System default",
            advertisedEncodings = advertisedEncodings,
            advertisedSampleRates = advertisedSampleRates,
            requestedMode = AppSettings.outputPcmMode.value,
            directSupport = directSupport,
            bluetoothTelemetry = btTelemetry,
            delegateSupportsFloat = shouldEnableFloatOutput(),
            // Never, on any route. `DefaultAudioSink.configure` inserts either
            // `ToFloatPcmAudioProcessor` or `ToInt16PcmAudioProcessor` for every
            // linear-PCM input encoding, so there is no packed-24-bit AudioTrack
            // to be had through it however loudly the device advertises one.
            // Claiming otherwise here selected a 24-bit path that Media3 then
            // silently re-converted, costing an extra quantization and leaving
            // this readout naming a bit depth that was never written.
            delegateSupportsPcm24 = false,
            knownSystemMixerRateHz = if (routeKind == AudioRouting.Kind.USB) 48000 else null,
        )

        AudioOutputStatus.publishNegotiation(negotiation)

        return negotiation.output.encoding
    }

    private fun requestOutputReconfiguration(rebuild: Boolean = false) {
        outputReconfigureJob?.cancel()
        outputReconfigureJob = scope.launch {
            // Replacing two renderers during a blend would cut one half of it.
            // Wait for the short transition to settle, then swap the engine.
            while (crossfade?.isTransitioning() == true) delay(50)
            val requestedFloat = shouldEnableFloatOutput()
            if (requestedFloat == configuredFloatOutput && !rebuild) {
                applyOutputRoute()
            } else {
                rebuildPlayersForOutput(requestedFloat)
            }
        }
    }

    /**
     * Rebuilds Media3's immutable AudioSink configuration without restarting
     * the app or service. Queue, item, position and play state are transferred
     * to the replacement player; the old AudioTrack is released only after the
     * MediaSession points at the new one.
     */
    private fun rebuildPlayersForOutput(enableFloat: Boolean) {
        val oldActive = player ?: return
        val oldSpare = spare ?: return
        val items = List(oldActive.mediaItemCount) { oldActive.getMediaItemAt(it) }
        val index = oldActive.currentMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: 0
        val position = oldActive.currentPosition.coerceAtLeast(0L)
        val playWhenReady = oldActive.playWhenReady
        val repeatMode = oldActive.repeatMode
        val shuffleMode = oldActive.shuffleModeEnabled

        oldActive.playWhenReady = false
        crossfade?.release()
        oldActive.removeListener(playbackListener)
        oldActive.removeAnalyticsListener(formatListener)

        configuredFloatOutput = enableFloat
        activeFilter = transitionFilterA
        spareFilter = transitionFilterB
        val newActive = buildPlayer(
            spatialAudioProcessorA,
            equalizerProcessorA,
            transitionFilterA,
            loudnessA,
            ownsSession = true,
        )
        val newSpare = buildPlayer(
            spatialAudioProcessorB,
            equalizerProcessorB,
            transitionFilterB,
            loudnessB,
            ownsSession = false,
        )
        player = newActive
        spare = newSpare
        newSpare.audioSessionId = newActive.audioSessionId
        AppSettings.audioSessionId.value = newActive.audioSessionId
        applySettings(newActive)
        applySettings(newSpare)
        newActive.repeatMode = repeatMode
        newActive.shuffleModeEnabled = shuffleMode
        applyEqualizer()
        applySpatialAudioEnabled()
        if (items.isNotEmpty()) {
            newActive.setMediaItems(items, index.coerceIn(items.indices), position)
            // By hand, because the listener is attached below this: the
            // transition `setMediaItems` fires lands before anything is
            // listening, so the ordinary setup path in [onTrackBecameCurrent]
            // never runs for the track the rebuild resumes on.
            setupLoudness(items.getOrNull(index.coerceIn(items.indices))?.mediaId)
        }
        newActive.addListener(playbackListener)
        newActive.addAnalyticsListener(formatListener)

        val newCrossfade = createCrossfadeController()
        crossfade = newCrossfade
        newCrossfade.start()
        mediaSession?.player = newSessionPlayer(newActive, newCrossfade)
        applyOutputRoute()
        if (items.isNotEmpty()) newActive.prepare()
        newActive.playWhenReady = playWhenReady

        oldActive.release()
        oldSpare.release()
        TrackLog.i(
            "AUDIO_OUT",
            "live output switch: ${if (enableFloat) "PCM_FLOAT" else "PCM_16BIT"}",
            about = newActive.currentMediaItem?.mediaId,
        )
    }

    private fun preferredUsbDevice(): AudioDeviceInfo? = audioManager
        ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        ?.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }

    private fun resolveActiveOutputDevice(): AudioDeviceInfo? {
        val manager = audioManager ?: return null
        val infos = runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull() ?: return null
        val chosen = AudioRouting.infoFor(manager, AudioRouting.selectedId.value)
        val preferred = chosen ?: preferredUsbDevice().takeIf { AppSettings.preferUsbDac.value }
        return preferred ?: AudioRouting.activeOf(infos, null)
    }

    private fun resolveActiveRouteKind(device: AudioDeviceInfo?): AudioRouting.Kind {
        if (device == null) return AudioRouting.Kind.PHONE
        return AudioRouting.kindOf(device.type) ?: AudioRouting.Kind.PHONE
    }

    private fun shouldEnableFloatOutput(): Boolean {
        val activeDevice = resolveActiveOutputDevice()
        val routeKind = resolveActiveRouteKind(activeDevice)
        val advertisesFloat = activeDevice?.encodings?.contains(AudioFormat.ENCODING_PCM_FLOAT) == true
        val directFloatSupported = audioManager?.let { mgr ->
            DirectAudioProbe.probeDirectSupport(mgr, 48000, 2, activeDevice).supportsFloat
        } ?: false
        // Worth knowing which way this cuts for a hi-res stream: with float
        // off, Media3 downconverts *every* input encoding to 16-bit, because
        // float and 16-bit are the only two linear-PCM tracks its sink can
        // open. Float is what carries a 24-bit sample to the hardware intact,
        // so "32-bit float" in Output precision is the setting that preserves
        // a hi-res source, not the one that embellishes it.
        return AudioOutputPolicy.shouldUseFloatOutput(
            requestedMode = AppSettings.outputPcmMode.value,
            routeKind = routeKind,
            advertisesPcmFloat = advertisesFloat || directFloatSupported,
            bluetoothCodecBitDepth = bluetoothCodecBitDepth(routeKind),
        )
    }

    /** The negotiated A2DP bit depth, only when Android actually reported it. */
    private fun bluetoothCodecBitDepth(routeKind: AudioRouting.Kind): Int? {
        if (routeKind != AudioRouting.Kind.BLUETOOTH) return null
        val telemetry = bluetoothTracker.telemetry.value
        return telemetry.bitDepth.takeIf { telemetry.isConnected && telemetry.isAuthoritative }
    }

    /** Runs [body] against both players, in whichever roles they currently hold. */
    private inline fun eachPlayer(body: (ExoPlayer) -> Unit) {
        player?.let(body)
        spare?.let(body)
    }

    private fun observeSettings() {
        scope.launch {
            AppSettings.skipSilence.collect { on -> eachPlayer { it.skipSilenceEnabled = on } }
        }
        scope.launch {
            AppSettings.preferUsbDac.drop(1).collect { requestOutputReconfiguration() }
        }
        scope.launch {
            AudioRouting.selectedId.drop(1).collect { requestOutputReconfiguration() }
        }
        scope.launch {
            AppSettings.outputPcmMode.drop(1).collect { requestOutputReconfiguration() }
        }
        // Direct USB needs Android's permission to the DAC, asked for when the
        // mode is chosen and again whenever a DAC is attached; an answer, an
        // attach or a detach all send the playing track back to be re-routed.
        UsbDirectDevices.init(this)
        BitPerfectOutput.watch(this)
        scope.launch {
            var available = false
            DacVolume.level.collect { level ->
                val now = level != null
                (mediaSession?.player as? SessionPlayer)?.onDacVolumeChanged(routeChanged = now != available)
                available = now
            }
        }
        UsbDirectDevices.onChanged = {
            BitPerfectOutput.refreshReady(this)
            scope.launch {
                if (AppSettings.bitPerfectMode.value == BitPerfectMode.USB_DIRECT) {
                    UsbDirectDevices.requestPermission(this@PlaybackService)
                    requestOutputReconfiguration(rebuild = true)
                }
            }
        }
        scope.launch {
            // The sinks decide per format, so the playing track only moves on
            // or off the exclusive output when its sink is configured again.
            AppSettings.bitPerfectMode.collect { mode ->
                BitPerfectOutput.refreshReady(this@PlaybackService)
                if (mode == BitPerfectMode.USB_DIRECT) UsbDirectDevices.requestPermission(this@PlaybackService)
            }
        }
        scope.launch {
            AppSettings.bitPerfectMode.drop(1).collect { requestOutputReconfiguration(rebuild = true) }
        }
        scope.launch {
            AppSettings.usbAllowFixedVolume.drop(1).collect {
                if (AppSettings.bitPerfectMode.value == BitPerfectMode.USB_DIRECT) {
                    requestOutputReconfiguration(rebuild = true)
                }
            }
        }
        scope.launch {
            AppSettings.convertForLimitedDac.drop(1).collect {
                if (AppSettings.bitPerfectMode.value != BitPerfectMode.OFF) {
                    requestOutputReconfiguration(rebuild = true)
                }
            }
        }
        scope.launch {
            // Not applied to a player mid-transition: [CrossfadeController]
            // stacks a beatmatch stretch on top of this setting, and writing the
            // raw value over it would drop the incoming track back to its own
            // tempo halfway through a blend. The controller re-reads the setting
            // when it restores the rate, so the change still lands.
            AppSettings.playbackSpeed.collect { speed ->
                if (crossfade?.isTransitioning() == true) return@collect
                crossfade?.applyPlaybackSpeed(speed) ?: eachPlayer { it.setPlaybackSpeed(speed) }
            }
        }
        scope.launch {
            combine(
                AppSettings.syncedLyrics,
                AppSettings.lyricsSources,
                AppSettings.lyricsSourceOrder,
                AppSettings.prioritizeSyllableSync,
            ) { synced, sources, order, prio ->
                synced to sources
            }.distinctUntilChanged().collect {
                loadLyricsForCurrentTrack()
            }
        }
        scope.launch {
            AppSettings.spatialAudio.collect { applySpatialAudioEnabled() }
        }
        scope.launch {
            AppSettings.loudnessNormalization.collect {
                setupLoudness(player?.currentMediaItem?.mediaId)
            }
        }
        scope.launch {
            // Same route the player's output caption reads, so "on speaker"
            // means the same thing here as there.
            combine(
                AppSettings.loudnessOffOnSpeaker,
                AudioOutputStatus.current.map { it.routeKind == AudioRouting.Kind.PHONE }.distinctUntilChanged(),
            ) { off, onSpeaker -> off && onSpeaker }
                .distinctUntilChanged()
                .drop(1)
                .collect { setupLoudness(player?.currentMediaItem?.mediaId) }
        }
        scope.launch {
            // Explicit <Any, _>: these flows have mixed element types, and
            // letting the reified vararg combine() infer T lands on an
            // intersection type. Nothing is read out of the array — the
            // equaliser reads the settings it wants directly — because seven
            // sources of one curve is seven chances to destructure them in the
            // wrong order.
            combine<Any, Unit>(
                AppSettings.equalizerEnabled,
                AppSettings.equalizerMode,
                AppSettings.equalizerToneX,
                AppSettings.equalizerToneY,
                AppSettings.equalizerFocused,
                AppSettings.equalizerBalance,
                AppSettings.equalizerBands,
            ) { }.collect { applyEqualizer() }
        }
    }

    /**
     * Renders the equaliser settings into a curve and hands it to both
     * processors.
     *
     * Both, and not just the audible one, for the reason [applySettings] gives:
     * the idle player is the one the next transition starts a song on, and a
     * blend whose two halves were equalised differently would sweep the curve in
     * over the crossfade.
     *
     * The curve is built here rather than in the processor because working out
     * the make-up attenuation walks the whole response ([EqCurve]), and the
     * audio thread is the one place that must not do that.
     */
    private fun applyEqualizer() {
        val enabled = AppSettings.equalizerEnabled.value
        val curve = when (AppSettings.equalizerMode.value) {
            EqualizerMode.DYNAMIC -> toneCurve(
                x = AppSettings.equalizerToneX.value,
                y = AppSettings.equalizerToneY.value,
                focused = AppSettings.equalizerFocused.value,
            )
            EqualizerMode.MANUAL -> manualCurve(AppSettings.equalizerBands.value)
        }
        val balance = AppSettings.equalizerBalance.value
        equalizerProcessorA.setTuning(enabled, curve, balance)
        equalizerProcessorB.setTuning(enabled, curve, balance)
    }

    /**
     * The effective spatial-audio state: the user's toggle, unless the
     * active player's decoder is currently on a Dolby Atmos (E-AC-3 JOC)
     * stream, in which case it's forced off. [activeTrackIsDolbyAtmos] only
     * ever describes the active player — see [formatListener] — so both
     * processors are kept in lockstep rather than tracking a role swap.
     */
    private fun applySpatialAudioEnabled() {
        val enabled = AppSettings.spatialAudio.value && !activeTrackIsDolbyAtmos
        spatialAudioProcessorA.enabled = enabled
        spatialAudioProcessorB.enabled = enabled
    }

    /**
     * The linear gain [LoudnessProcessor] applies to [mediaId].
     *
     * A file on this device carries no normalization figure, so this is exactly
     * 1: the stage stays in the chain — and in the pipeline readout — but
     * nothing is turned down or up.
     */
    private fun loudnessGainFor(mediaId: String): Float = 1f

    /**
     * Points the session player's loudness stage at [mediaId] and the track
     * queued after it, and publishes the figure for the pipeline readout.
     *
     * Only the session player's: the spare is pointed at its own track when a
     * transition arms it — see [CrossfadeController]'s `onArmIncoming` — which
     * is the whole point of the stage being per player.
     */
    private fun setupLoudness(mediaId: String?) {
        val exoPlayer = player ?: return
        activeLoudness().track(mediaId, nextMediaIdOf(exoPlayer))
    }

    /** The track [exoPlayer] will move onto gaplessly, if any. */
    private fun nextMediaIdOf(exoPlayer: ExoPlayer): String? {
        val next = exoPlayer.nextMediaItemIndex
        if (next == C.INDEX_UNSET || next !in 0 until exoPlayer.mediaItemCount) return null
        return exoPlayer.getMediaItemAt(next).mediaId
    }


    /**
     * One re-read of the readout, a few seconds after a transition, for the
     * track whose YouTube figure had not resolved yet when [setupLoudness]
     * first ran — the resolve is often slower than the transition that needed
     * it.
     *
     * Only the readout: the gain itself is read lazily by [LoudnessProcessor]
     * and glides in on its own the moment the figure lands.
     */

    private fun observeScrobbling() {
        // Keep the manager alive while its timing settings change, so updating
        // a preference does not cancel the current track's scrobble timer.
        scope.launch {
            // Explicit <Any, _>: these flows have mixed element types, and letting
            // the reified vararg combine() infer T lands on an intersection type.
            combine<Any, ScrobblingSnapshot>(
                AppSettings.lastfmEnabled,
                AppSettings.lastfmScrobbleEnabled,
                AppSettings.lastfmNowPlaying,
                AppSettings.lastfmPrimaryArtistOnly,
                AppSettings.lastfmSessionKey,
                AppSettings.lastfmApiKey,
                AppSettings.lastfmSecret,
                AppSettings.scrobbleMinDuration,
                AppSettings.scrobbleDelayPercent,
                AppSettings.scrobbleDelaySeconds,
            ) { values ->
                ScrobblingSnapshot(
                    lastfmEnabled = values[0] as Boolean,
                    scrobbleEnabled = values[1] as Boolean,
                    nowPlaying = values[2] as Boolean,
                    primaryArtistOnly = values[3] as Boolean,
                    sessionKey = values[4] as String,
                    apiKey = values[5] as String,
                    secret = values[6] as String,
                    minDuration = values[7] as Int,
                    delayPercent = values[8] as Float,
                    delaySeconds = values[9] as Int,
                )
            }.collectLatest { snapshot ->
                val shouldEnable = snapshot.lastfmEnabled &&
                    snapshot.scrobbleEnabled &&
                    snapshot.sessionKey.isNotBlank() &&
                    snapshot.apiKey.isNotBlank() &&
                    snapshot.secret.isNotBlank()

                if (!shouldEnable) {
                    scrobbleManager?.destroy()
                    scrobbleManager = null
                    return@collectLatest
                }

                LastFM.configure(
                    apiKey = snapshot.apiKey,
                    secret = snapshot.secret,
                    sessionKey = snapshot.sessionKey,
                )
                val manager = scrobbleManager ?: ScrobbleManager(scope).also {
                    scrobbleManager = it
                }
                manager.minSongDuration = snapshot.minDuration
                manager.scrobbleDelayPercent = snapshot.delayPercent
                manager.scrobbleDelaySeconds = snapshot.delaySeconds
                manager.useNowPlaying = snapshot.nowPlaying
                manager.usePrimaryArtistOnly = snapshot.primaryArtistOnly

                player?.let { exoPlayer ->
                    if (exoPlayer.isPlaying) {
                        manager.onPlayerStateChanged(
                            isPlaying = true,
                            song = exoPlayer.currentMediaItem?.toSong(),
                            durationMs = exoPlayer.duration.takeIf { it > 0 },
                        )
                    }
                }
            }
        }
    }

    private data class ScrobblingSnapshot(
        val lastfmEnabled: Boolean,
        val scrobbleEnabled: Boolean,
        val nowPlaying: Boolean,
        val primaryArtistOnly: Boolean,
        val sessionKey: String,
        val apiKey: String,
        val secret: String,
        val minDuration: Int,
        val delayPercent: Float,
        val delaySeconds: Int,
    )

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    /**
     * Called by Android when the user swipes this app's task away from the
     * recent apps screen.
     *
     * When [AppSettings.stopOnTaskRemoved] is on we stop the player and let the
     * service die naturally; otherwise we leave it running in the background so
     * music continues past the swipe, which is the default Android behaviour for
     * a foreground-service-backed media session.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (AppSettings.stopOnTaskRemoved.value) {
            // Both, or a swipe-away mid-crossfade leaves the outgoing track
            // playing on its own out of a service that is on its way out.
            eachPlayer { it.stop() }
            stopSelf()
        }
    }
    override fun onDestroy() {
        closeAudioEffectSession()
        bluetoothTracker.stop()
        audioManager?.unregisterAudioDeviceCallback(outputDeviceCallback)
        player?.let {
            saveQueueSnapshot(it)
            savePlaybackState(it)
        }
        // And to leave the widgets showing a play button. Nothing else reports a
        // swipe-away, so a widget left on the home screen would sit there with a
        // pause glyph on a service that no longer exists.
        publishWidgetState(playing = false)
        lyricsTickerJob?.cancel()
        lyricsTickerJob = null
        serviceLyricsJob?.cancel()
        serviceLyricsJob = null
        serviceLyrics = null
        trackAnalyzer.release()
        loudnessRetryJob?.cancel()
        scrobbleManager?.destroy()
        scrobbleManager = null
        // Last chance to get the current track's minutes onto disk before the
        // service scope is cancelled and the sampler goes with it.
        ListeningRecorder.onStopped()
        scope.cancel()
        QueueShuffle.isCrossfadeTransitioning = null
        crossfade?.release()
        crossfade = null
        mediaSession?.release()
        mediaSession = null
        player?.removeListener(playbackListener)
        player?.removeAnalyticsListener(formatListener)
        player?.release()
        player = null
        // Released too, and not conditionally: mid-crossfade it is holding a
        // decoder and an open audio track of its own, and the service going away
        // is not a reason to leave either behind.
        spare?.release()
        spare = null
        AudioOutputStatus.reset()
        NerdStats.forgetLastSession()
        localBitrateCache.clear()
        measuredBitrateCache.clear()
        super.onDestroy()
    }

    private fun loadLyricsForCurrentTrack() {
        val currentSong = player?.currentMediaItem?.toSong() ?: run {
            serviceLyrics = null
            stopLyricsTicker()
            return
        }

        if (!AppSettings.syncedLyrics.value) {
            serviceLyrics = null
            stopLyricsTicker()
            return
        }

        val trackDurationMs = (player?.duration ?: 0L).takeIf { it > 0 } ?: 0L

        serviceLyricsJob?.cancel()
        serviceLyricsJob = scope.launch(Dispatchers.IO) {
            val localUri = currentSong.localUri
            var lines: List<LyricLine>? = null
            if (localUri != null) {
                lines = EmbeddedLyrics.forUri(this@PlaybackService, localUri)
            }
            if (lines == null) {
                val found = LyricsRepository.lyrics(
                    videoId = currentSong.videoId,
                    title = currentSong.title,
                    artist = currentSong.artist,
                    durationMs = trackDurationMs,
                    album = currentSong.albumName,
                    sources = AppSettings.lyricsSources.value,
                    order = AppSettings.lyricsSourceOrder.value,
                    prioritizeSyllableSync = AppSettings.prioritizeSyllableSync.value,
                )
                lines = found?.lines
            }
            withContext(Dispatchers.Main) {
                serviceLyrics = lines
                if (player?.isPlaying == true) {
                    updateLyricSubtitle()
                }
            }
        }
    }

    private fun startLyricsTicker() {
        if (lyricsTickerJob?.isActive == true) return
        lyricsTickerJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                updateLyricSubtitle()
                delay(500L)
            }
        }
    }

    private fun stopLyricsTicker() {
        lyricsTickerJob?.cancel()
        lyricsTickerJob = null
        updateLyricSubtitle()
    }

    private fun updateLyricSubtitle() {
        val exoPlayer = player ?: return
        val currentSong = exoPlayer.currentMediaItem?.toSong() ?: return
        val lines = serviceLyrics
        val pos = crossfade?.loopPositionMs() ?: exoPlayer.currentPosition
        val subtitleText = if (lines != null && lines.isNotEmpty() && AppSettings.syncedLyrics.value) {
            val idx = lines.indexOfLast { it.timeMs <= pos }
            val currentLine = lines.getOrNull(idx)
            if (currentLine != null && !currentLine.isGap && currentLine.text.isNotBlank()) {
                "♪ ${currentLine.text}"
            } else {
                currentSong.artist
            }
        } else {
            currentSong.artist
        }

        if (subtitleText != lastPublishedSubtitle) {
            lastPublishedSubtitle = subtitleText
            // buildUpon() off the metadata that's already playing, not a fresh
            // Builder() — a synced lyric line can advance every second or two,
            // and re-stating setArtworkUri() on every tick made media3 treat
            // the cover as newly changed that often, racing its own artwork
            // decode/cache against the legacy MediaSession broadcast and
            // crashing with "cannot use a recycled source in createBitmap".
            // Carrying the existing artwork field forward keeps that field
            // untouched while still firing onMediaMetadataChanged for the
            // subtitle itself.
            exoPlayer.playlistMetadata = exoPlayer.mediaMetadata
                .buildUpon()
                .setSubtitle(subtitleText)
                .build()
        }
    }

    /**
     * What the MediaSession, and so every control surface, actually talks to.
     *
     * Two behaviours are grafted onto the player here rather than left to
     * ExoPlayer's defaults:
     *
     * **Back restarts the track.** ExoPlayer already implements
     * restart-then-skip in [Player.seekToPrevious], gated on
     * `maxSeekToPreviousPosition`. External surfaces don't use it:
     * [DefaultMediaNotificationProvider] binds its previous button to
     * `COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM`, which skips unconditionally. So
     * that command is redirected rather than left to behave differently
     * depending on which back button was pressed.
     *
     * **A skip cancels the crossfade.** Blending is for a track running out,
     * not for one being changed: told to move on, the listener wants the song
     * they were on to stop, not to keep playing over the one they asked for.
     * So every skip tells [CrossfadeController] to drop whatever is in flight
     * and then moves the queue plainly.
     *
     * Command availability is deliberately untouched — mutating it through a
     * [ForwardingPlayer] means intercepting listener callbacks too. The one
     * consequence is the first track of a queue, where ExoPlayer withholds
     * `COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM` for want of a previous item: back
     * stays inert on those surfaces, exactly as it already was. In the app it
     * restarts, since that path asks for `COMMAND_SEEK_TO_PREVIOUS`.
     */
    private class SessionPlayer(
        player: Player,
        private val crossfade: CrossfadeController,
        /**
         * Reports that what just came through here was the *user's* doing.
         *
         * This wrapper is the door every external surface knocks on — the
         * app, the notification, a headset button, Android Auto — while the
         * service's own programmatic moves go straight to the ExoPlayer
         * underneath it.
         */
        private val getSubtitle: () -> String?,
    ) : ForwardingPlayer(player) {

        // The listeners registered through this wrapper, kept so the receiver's
        // changes can be announced to them. The phone's own player never
        // announces them — it is paused, and as far as it knows nothing is
        // happening.
        private val listeners = CopyOnWriteArraySet<Player.Listener>()
        private companion object {
            /**
             * A DAC on direct USB output, whose volume is its own hardware
             * attenuator: remote, as far as Android's volume keys are
             * concerned, since the music stream no longer reaches it.
             */
            val DAC_DEVICE: DeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                .setMinVolume(0)
                .setMaxVolume(100)
                .build()

            val DEVICE_VOLUME_COMMANDS = intArrayOf(
                Player.COMMAND_GET_DEVICE_VOLUME,
                Player.COMMAND_SET_DEVICE_VOLUME,
                Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                Player.COMMAND_ADJUST_DEVICE_VOLUME,
                Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
            )
        }

        /** The DAC's own volume stands in for the device's, unless a receiver is the speaker. */
        private val dac: Boolean get() = DacVolume.level.value != null

        private fun dacVolume(): Int = ((DacVolume.level.value ?: 0f) * 100).roundToInt()

        override fun getAvailableCommands(): Player.Commands =
            if (dac) super.getAvailableCommands().buildUpon().addAll(*DEVICE_VOLUME_COMMANDS).build()
            else super.getAvailableCommands()

        override fun isCommandAvailable(command: Int): Boolean =
            (dac && command in DEVICE_VOLUME_COMMANDS) || super.isCommandAvailable(command)

        /**
         * The DAC volume came or went ([routeChanged]), or moved. Told by the
         * service, since this wrapper is replaced at every handoff.
         */
        fun onDacVolumeChanged(routeChanged: Boolean) {
            if (routeChanged) {
                val commands = availableCommands
                listeners.forEach { it.onAvailableCommandsChanged(commands) }
                val events = Player.Events(FlagSet.Builder().add(Player.EVENT_AVAILABLE_COMMANDS_CHANGED).build())
                listeners.forEach { it.onEvents(this, events) }
                onRemoteRouteChanged()
            } else {
                onRemoteVolumeChanged()
            }
        }

        override fun addListener(listener: Player.Listener) {
            listeners += listener
            super.addListener(listener)
        }

        override fun removeListener(listener: Player.Listener) {
            listeners -= listener
            super.removeListener(listener)
        }

        // While a receiver is the speaker the phone's player is paused and its
        // clock is stale, so everything that describes playback is the
        // receiver's. The queue, the metadata and the commands stay the
        // phone's.
        override fun getPlayWhenReady() = super.getPlayWhenReady()

        override fun isPlaying() = super.isPlaying()

        override fun getPlaybackState() = super.getPlaybackState()

        // During a loop mix the song loops and follows the tempo ramp, so its
        // playhead is the mix's own, not the player's: the seek bar, the
        // lyrics and every controller read it here.
        override fun getCurrentPosition() =
            crossfade.loopPositionMs() ?: super.getCurrentPosition()

        override fun getContentPosition() =
            crossfade.loopPositionMs() ?: super.getContentPosition()

        private var lastMixPositionMs = C.TIME_UNSET

        /**
         * Re-anchors every controller on the loop mix's playhead. A wrap is a
         * deliberate jump back, announced as one (the lyrics follow it); a
         * re-anchor through the ramp is reported moving forward, so nothing
         * reads it as a seek.
         */
        fun announceMixPosition(wrapped: Boolean) {
            val now = currentPosition
            val before = if (wrapped && lastMixPositionMs != C.TIME_UNSET) lastMixPositionMs else (now - 1).coerceAtLeast(0L)
            lastMixPositionMs = now
            fun info(position: Long) = Player.PositionInfo(
                null,
                currentMediaItemIndex,
                currentMediaItem,
                null,
                currentPeriodIndex,
                position,
                position,
                C.INDEX_UNSET,
                C.INDEX_UNSET,
            )
            val old = info(before)
            val new = info(now)
            listeners.forEach { it.onPositionDiscontinuity(old, new, Player.DISCONTINUITY_REASON_INTERNAL) }
            val events = Player.Events(FlagSet.Builder().add(Player.EVENT_POSITION_DISCONTINUITY).build())
            listeners.forEach { it.onEvents(this, events) }
        }

        override fun getBufferedPosition() = super.getBufferedPosition()

        override fun getContentBufferedPosition() =
            super.getContentBufferedPosition()

        override fun getDuration(): Long =
            super.getDuration()

        override fun getContentDuration(): Long =
            super.getContentDuration()

        // The receiver is a remote output with a volume of its own, which is
        // what makes the hardware keys move it instead of the phone's.
        override fun getDeviceInfo(): DeviceInfo = when {
            dac -> DAC_DEVICE
            else -> super.getDeviceInfo()
        }

        override fun getDeviceVolume(): Int = when {
            dac -> dacVolume()
            else -> super.getDeviceVolume()
        }

        override fun isDeviceMuted(): Boolean = if (dac) false else super.isDeviceMuted()

        override fun setDeviceVolume(volume: Int, flags: Int) {
            when {
                dac -> DacVolume.set(volume / 100f)
                else -> super.setDeviceVolume(volume, flags)
            }
        }

        @Suppress("DEPRECATION")
        @Deprecated("Retained for older Media3 volume controllers")
        override fun setDeviceVolume(volume: Int) {
            when {
                dac -> DacVolume.set(volume / 100f)
                else -> super.setDeviceVolume(volume)
            }
        }

        override fun increaseDeviceVolume(flags: Int) {
            when {
                dac -> DacVolume.step(+1)
                else -> super.increaseDeviceVolume(flags)
            }
        }

        @Suppress("DEPRECATION")
        @Deprecated("Retained for older Media3 volume controllers")
        override fun increaseDeviceVolume() {
            when {
                dac -> DacVolume.step(+1)
                else -> super.increaseDeviceVolume()
            }
        }

        override fun decreaseDeviceVolume(flags: Int) {
            when {
                dac -> DacVolume.step(-1)
                else -> super.decreaseDeviceVolume(flags)
            }
        }

        @Suppress("DEPRECATION")
        @Deprecated("Retained for older Media3 volume controllers")
        override fun decreaseDeviceVolume() {
            when {
                dac -> DacVolume.step(-1)
                else -> super.decreaseDeviceVolume()
            }
        }

        override fun setDeviceMuted(muted: Boolean, flags: Int) {
            if (!dac) super.setDeviceMuted(muted, flags)
        }

        @Suppress("DEPRECATION")
        @Deprecated("Retained for older Media3 volume controllers")
        override fun setDeviceMuted(muted: Boolean) {
            if (!dac) super.setDeviceMuted(muted)
        }

        private fun onRemoteRouteChanged() {
            val info = getDeviceInfo()
            listeners.forEach { it.onDeviceInfoChanged(info) }
            val events = Player.Events(FlagSet.Builder().add(Player.EVENT_DEVICE_INFO_CHANGED).build())
            listeners.forEach { it.onEvents(this, events) }
            onRemoteVolumeChanged()
        }

        private fun onRemoteVolumeChanged() {
            val volume = getDeviceVolume()
            val muted = isDeviceMuted()
            listeners.forEach { it.onDeviceVolumeChanged(volume, muted) }
            val events = Player.Events(FlagSet.Builder().add(Player.EVENT_DEVICE_VOLUME_CHANGED).build())
            listeners.forEach { it.onEvents(this, events) }
        }

        override fun getMediaMetadata(): MediaMetadata {
            val base = wrappedPlayer.mediaMetadata
            val subtitle = getSubtitle()
            return if (!subtitle.isNullOrBlank()) {
                base.buildUpon().setSubtitle(subtitle).build()
            } else {
                base
            }
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            crossfade.onSkipRequested()
            if (mediaItemIndex !in 0 until wrappedPlayer.mediaItemCount) return
            if (mediaItemIndex <= wrappedPlayer.currentMediaItemIndex) {
                wrappedPlayer.seekTo(mediaItemIndex, positionMs)
                return
            }
            // A direct choice of a later queue row bypasses items between
            // current and target (and an AutoPlay row, even the next one,
            // becomes the new context). The in-app queue tap lands here too (as a
            // controller seek), so the notification, Auto, Wear and the app
            // all follow the one plan in [QueueTimeline.computeJumpOrder] —
            // in particular, bypassed USER_QUEUE rows are re-pinned after the
            // target rather than left behind the playhead, where
            // [QueueCoordinator.consumePlayedUserQueue] would delete them.
            QueueCoordinator.jumpToQueueItem(
                wrappedPlayer,
                mediaItemIndex,
                positionMs = positionMs,
                play = false,
            )
        }

        override fun seekToPreviousMediaItem() {
            crossfade.onSkipRequested()
            wrappedPlayer.seekToPrevious()
        }

        override fun seekToNextMediaItem() {
            crossfade.onSkipRequested()
            wrappedPlayer.seekToNextMediaItem()
        }

        override fun seekToNext() {
            crossfade.onSkipRequested()
            wrappedPlayer.seekToNext()
        }
    }

    /**
     * Handles browsing and content resolution for Android Auto and other MediaBrowser clients.
     */
    private inner class MediaLibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(favoriteCommand)
                .add(boundedContextCommand)
                .add(clearBoundedContextCommand)
                .add(clearQueueCommand)
                .add(shuffleCommand)
                .add(beginRadioQueueCommand)
                .add(commitRadioQueueCommand)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (session.isAutoCompanionController(controller) || session.isAutomotiveController(controller)) {
                carControllers += controller
                refreshCustomLayouts()
            }
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (carControllers.remove(controller)) refreshCustomLayouts()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_START_BOUNDED_CONTEXT -> startBoundedContext(args)
                ACTION_CLEAR_BOUNDED_CONTEXT -> clearBoundedContext()
                ACTION_CLEAR_QUEUE -> clearQueueExceptCurrent()
                ACTION_TOGGLE_SHUFFLE -> toggleShuffleFromSession()
                ACTION_BEGIN_RADIO_QUEUE -> beginRadioQueue()
                ACTION_COMMIT_RADIO_QUEUE -> player?.let(::saveQueueSnapshotImmediately)
                // A widget or watch drawn before the track changed can still
                // send this for one the player offers no Like on.
                ACTION_TOGGLE_FAVORITE -> session.player.currentMediaItem
                    ?.takeIf(::canLike)
                    ?.let { item ->
                        // On the device and nowhere else: nothing to
                        // reconcile, and the button follows [OfflinePlaylists.liked].
                        OfflinePlaylists.toggleLike(item.toSong())
                    }
                else -> return Futures.immediateFuture(
                    SessionResult(SessionError.ERROR_NOT_SUPPORTED),
                )
            }
            // The actual YouTube rating is asynchronous. The command itself has been accepted;
            // the notification is refreshed when the network write completes.
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val isRecent = params?.isRecent == true ||
                params?.extras?.getBoolean("android.service.media.extra.RECENT") == true ||
                params?.extras?.getBoolean("androidx.media.MediaBrowserCompat.EXTRA_RECENT") == true

            val rootId = if (isRecent) MEDIA_RECENTS_ID else MEDIA_ROOT_ID
            val rootExtras = buildContentStyleBundle(
                CONTENT_STYLE_GRID_ITEM_HINT_VALUE,
                CONTENT_STYLE_LIST_ITEM_HINT_VALUE,
            )
            val rootItem = MediaItem.Builder()
                .setMediaId(rootId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(getString(R.string.app_name))
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                        .setExtras(rootExtras)
                        .build(),
                )
                .build()
            val rootParams = LibraryParams.Builder()
                .setExtras(rootExtras)
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, rootParams))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
            val isGridFolder = parentId == MEDIA_ROOT_ID || parentId == MEDIA_PLAYLISTS_ID
            val items: List<MediaItem> = when (parentId) {
                MEDIA_ROOT_ID, MEDIA_MORE_ID -> listOf(
                    createFolderItem(MEDIA_LOCAL_MUSIC_ID, getString(R.string.local_music), folderType = MediaMetadata.FOLDER_TYPE_MIXED),
                    createFolderItem(MEDIA_RECENTS_ID, getString(R.string.auto_recents), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS),
                    createFolderItem(MEDIA_LIKED_ID, getString(R.string.auto_liked), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS),
                    createFolderItem(MEDIA_PLAYLISTS_ID, getString(R.string.playlists), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS, isGrid = true),
                )
                MEDIA_RECENTS_ID -> {
                    val songs = LocalListeningHistory.songs().asReversed()
                    songs.forEach { songCache[it.videoId] = it }
                    if (songs.isNotEmpty()) songs.map { it.toMediaItem() } else listOf(
                        createInfoItem(
                            "msg:empty_recents",
                            getString(R.string.auto_empty_recents_title),
                            getString(R.string.auto_empty_recents_subtitle),
                        ),
                    )
                }
                MEDIA_LIKED_ID -> {
                    val songs = OfflinePlaylists
                        .get(OfflinePlaylists.PREFIX + OfflinePlaylists.LIKED_ID)
                        ?.songs
                        .orEmpty()
                    songs.forEach { songCache[it.videoId] = it }
                    if (songs.isNotEmpty()) songs.map { it.toMediaItem() } else listOf(
                        createInfoItem(
                            "msg:empty_liked",
                            getString(R.string.auto_empty_liked_title),
                            getString(R.string.auto_empty_liked_subtitle),
                        ),
                    )
                }
                MEDIA_PLAYLISTS_ID -> {
                    val playlists = OfflinePlaylists.playlists.value.filterNot { it.isLiked }
                    if (playlists.isNotEmpty()) {
                        playlists.map { playlist ->
                            createGridPlaylistItem(
                                mediaId = "oplaylist:${playlist.id}",
                                title = playlist.title,
                                subtitle = resources.getQuantityString(
                                    R.plurals.song_count_plural,
                                    playlist.songs.size,
                                    playlist.songs.size,
                                ),
                                artworkUrl = playlist.songs.firstNotNullOfOrNull { it.thumbnailUrl },
                            )
                        }
                    } else {
                        listOf(
                            createInfoItem(
                                "msg:empty_playlists",
                                getString(R.string.auto_empty_playlists_title),
                                getString(R.string.auto_empty_playlists_subtitle),
                            ),
                        )
                    }
                }
                MEDIA_LOCAL_MUSIC_ID -> {
                    val local = LocalMediaRepository.getLocalMusic(this@PlaybackService)
                    local.forEach { songCache[it.videoId] = it }
                    if (local.isNotEmpty()) local.map { it.toMediaItem() } else listOf(
                        createInfoItem(
                            "msg:empty_local",
                            getString(R.string.auto_empty_offline_title),
                            getString(R.string.auto_empty_offline_subtitle),
                        ),
                    )
                }
                else -> {
                    if (parentId.startsWith("oplaylist:")) {
                        val songs = OfflinePlaylists.get(parentId.removePrefix("oplaylist:"))?.songs.orEmpty()
                        songs.forEach { songCache[it.videoId] = it }
                        songs.map { it.toMediaItem() }
                    } else {
                        val cachedSong = songCache[parentId]
                        if (cachedSong != null) {
                            listOf(cachedSong.toMediaItem())
                        } else {
                            emptyList()
                        }
                    }
                }
            }
            val returnParams = LibraryParams.Builder().setExtras(
                buildContentStyleBundle(
                    if (isGridFolder) CONTENT_STYLE_GRID_ITEM_HINT_VALUE else CONTENT_STYLE_LIST_ITEM_HINT_VALUE,
                    CONTENT_STYLE_LIST_ITEM_HINT_VALUE,
                ),
            ).build()
            LibraryResult.ofItemList(ImmutableList.copyOf(paginate(items, page, pageSize)), returnParams)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future(Dispatchers.IO) {
            val item = when (mediaId) {
                MEDIA_ROOT_ID -> MediaItem.Builder()
                    .setMediaId(MEDIA_ROOT_ID)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(getString(R.string.app_name))
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                            .build(),
                    ).build()
                MEDIA_RECENTS_ID -> createFolderItem(MEDIA_RECENTS_ID, getString(R.string.auto_recents), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS)
                MEDIA_PLAYLISTS_ID -> createFolderItem(MEDIA_PLAYLISTS_ID, getString(R.string.playlists), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS, isGrid = true)
                MEDIA_LIKED_ID -> createFolderItem(MEDIA_LIKED_ID, getString(R.string.auto_liked), folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS)
                MEDIA_LOCAL_MUSIC_ID -> createFolderItem(MEDIA_LOCAL_MUSIC_ID, getString(R.string.local_music))
                else -> {
                    if (mediaId.startsWith("msg:")) {
                        createInfoItem(
                            mediaId,
                            getString(R.string.auto_unavailable_title),
                            getString(R.string.auto_unavailable_subtitle),
                        )
                    } else {
                        val cached = songCache[mediaId]
                        if (cached != null) {
                            cached.toMediaItem()
                        } else if (mediaId.startsWith("oplaylist:")) {
                            createGridPlaylistItem(
                                mediaId = mediaId,
                                title = getString(R.string.playlists),
                            )
                        } else {
                            createInfoItem(
                                "msg:unavailable|$mediaId",
                                getString(R.string.auto_unavailable_title),
                                getString(R.string.auto_unavailable_subtitle),
                            )
                        }
                    }
                }
            }
            LibraryResult.ofItem(item, null)
        }

        @Deprecated("Retained for media controllers using the older resumption callback")
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
            // 1. If player currently holds items in its queue, resume from it
            val activePlayer = player
            if (activePlayer != null && activePlayer.mediaItemCount > 0) {
                val currentItems = (0 until activePlayer.mediaItemCount).map { activePlayer.getMediaItemAt(it) }
                val startIndex = activePlayer.currentMediaItemIndex.coerceIn(0, currentItems.size - 1)
                val startPositionMs = activePlayer.currentPosition.coerceAtLeast(0L)
                return@future MediaSession.MediaItemsWithStartPosition(
                    ImmutableList.copyOf(currentItems),
                    startIndex,
                    startPositionMs,
                )
            }


            // 3. Fallback to device local music
            val localSongs = LocalMediaRepository.getLocalMusic(this@PlaybackService)
            if (localSongs.isNotEmpty()) {
                val items = localSongs.take(BoundedQueuePlanner.MAX_PHYSICAL_CONTEXT).map { it.toMediaItem() }
                return@future MediaSession.MediaItemsWithStartPosition(
                    ImmutableList.copyOf(items),
                    0,
                    0L,
                )
            }

            // Return empty instead of throwing an unhandled exception to prevent crash
            MediaSession.MediaItemsWithStartPosition(
                ImmutableList.of(),
                0,
                0L,
            )
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future(Dispatchers.IO) {
            session.notifySearchResultChanged(browser, query, 0, params)
            LibraryResult.ofVoid(params)
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
            LibraryResult.ofItemList(ImmutableList.of(), params)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
            val resolved = resolveMediaItems(mediaItems)
            val validIndex = startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0))
            MediaSession.MediaItemsWithStartPosition(
                ImmutableList.copyOf(resolved),
                validIndex,
                startPositionMs.coerceAtLeast(0L),
            )
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = scope.future(Dispatchers.IO) {
            resolveMediaItems(mediaItems)
        }

        private suspend fun resolveMediaItems(mediaItems: List<MediaItem>): List<MediaItem> {
            val resolved = mutableListOf<MediaItem>()
            fun isLocalUri(uri: Uri): Boolean =
                uri.scheme == "file" || uri.scheme == "content" || uri.scheme == "android.resource"
            for (item in mediaItems) {
                when (item.mediaId) {
                    MEDIA_LOCAL_MUSIC_ID -> {
                        val local = LocalMediaRepository.getLocalMusic(this@PlaybackService)
                        local.forEach { songCache[it.videoId] = it }
                        resolved.addAll(local.map { it.toMediaItem() })
                    }
                    else -> {
                        val uri = (item.localConfiguration?.uri ?: item.requestMetadata.mediaUri)
                            ?.takeIf(::isLocalUri)
                        val cached = songCache[item.mediaId]
                        val cachedUriIsLocal = cached?.localUri
                            ?.let(Uri::parse)
                            ?.let(::isLocalUri) == true
                        when {
                            cached?.localPath != null || cachedUriIsLocal -> resolved.add(cached.toMediaItem())
                            uri != null -> resolved.add(item.buildUpon().setUri(uri).build())
                        }
                    }
                }
            }
            return resolved
        }
    }

    private fun buildContentStyleBundle(browsableHint: Int, playableHint: Int): Bundle =
        Bundle().apply {
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT, browsableHint)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT, playableHint)
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT_AX, browsableHint)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT_AX, playableHint)
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT_LEGACY, browsableHint)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT_LEGACY, playableHint)
            putInt("android.media.extras.CONTENT_STYLE_BROWSABLE_HINT", browsableHint)
            putInt("android.media.extras.CONTENT_STYLE_PLAYABLE_HINT", playableHint)
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", browsableHint)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", playableHint)
            putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED, true)
            putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED_AX, true)
        }

    private fun <T> paginate(items: List<T>, page: Int, pageSize: Int): List<T> {
        if (page < 0 || pageSize <= 0 || items.isEmpty()) return emptyList()
        val safePageSize = pageSize.coerceAtMost(MAX_AUTO_PAGE_SIZE)
        val fromLong = page.toLong() * safePageSize.toLong()
        if (fromLong >= items.size.toLong()) return emptyList()
        val from = fromLong.toInt()
        val to = (fromLong + safePageSize).coerceAtMost(items.size.toLong()).toInt()
        return items.subList(from, to)
    }

    private fun createGridPlaylistItem(
        mediaId: String,
        title: String,
        subtitle: String? = null,
        artworkUrl: String? = null,
        folderType: Int = MediaMetadata.FOLDER_TYPE_PLAYLISTS,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(artworkUrl?.artworkAt(NOTIFICATION_ART_PX)?.let(Uri::parse))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setFolderType(folderType)
                .setExtras(buildContentStyleBundle(CONTENT_STYLE_GRID_ITEM_HINT_VALUE, CONTENT_STYLE_LIST_ITEM_HINT_VALUE))
                .build(),
        )
        .build()




    private fun createFolderItem(
        mediaId: String,
        title: String,
        subtitle: String? = null,
        folderType: Int = MediaMetadata.FOLDER_TYPE_MIXED,
        isGrid: Boolean = false,
    ): MediaItem {
        val style = if (isGrid) CONTENT_STYLE_GRID_ITEM_HINT_VALUE else CONTENT_STYLE_LIST_ITEM_HINT_VALUE
        val extras = buildContentStyleBundle(style, CONTENT_STYLE_LIST_ITEM_HINT_VALUE)
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setFolderType(if (isGrid && folderType == MediaMetadata.FOLDER_TYPE_MIXED) MediaMetadata.FOLDER_TYPE_ALBUMS else folderType)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }




    private fun createInfoItem(mediaId: String, title: String, subtitle: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(false)
                    .setIsPlayable(false)
                    .setExtras(buildContentStyleBundle(CONTENT_STYLE_LIST_ITEM_HINT_VALUE, CONTENT_STYLE_LIST_ITEM_HINT_VALUE))
                    .build(),
            )
            .build()

    private companion object {
        const val MEDIA_ROOT_ID = "root"
        const val MEDIA_RECENTS_ID = "recents"
        const val MEDIA_PLAYLISTS_ID = "playlists"
        const val MEDIA_MORE_ID = "more"
        const val MEDIA_LIKED_ID = "liked"
        const val MEDIA_LOCAL_MUSIC_ID = "local_music"
        const val MAX_AUTO_PAGE_SIZE = 50

        // Android Auto Content Style Hints
        const val EXTRA_CONTENT_STYLE_SUPPORTED = "android.media.browse.extra.CONTENT_STYLE_SUPPORTED"
        const val EXTRA_CONTENT_STYLE_SUPPORTED_AX = "androidx.media.contentstyle.CONTENT_STYLE_SUPPORTED"
        const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.extra.CONTENT_STYLE_BROWSABLE_HINT"
        const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.extra.CONTENT_STYLE_PLAYABLE_HINT"
        const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT_AX = "androidx.media.contentstyle.CONTENT_STYLE_BROWSABLE_HINT"
        const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT_AX = "androidx.media.contentstyle.CONTENT_STYLE_PLAYABLE_HINT"
        const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT_LEGACY = "androidx.media.utils.CONTENT_STYLE_BROWSABLE_HINT"
        const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT_LEGACY = "androidx.media.utils.CONTENT_STYLE_PLAYABLE_HINT"
        const val CONTENT_STYLE_LIST_ITEM_HINT_VALUE = 1
        const val CONTENT_STYLE_GRID_ITEM_HINT_VALUE = 4
        /**
         * Shared by both players. Identical on purpose: they take turns being
         * the session, and a difference here would be an audible change of
         * routing at the handoff.
         */
        val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        /** Length of a FLAC STREAMINFO block, which the format fixes at 34 bytes. */
        const val STREAM_INFO_BYTES = 34

        const val CHANNEL_ID = "umesie_playback"
        const val SESSION_ID = "umesiePlayback"

        /** How often played-seconds are sampled off the player. */
        const val PROGRESS_SAMPLE_MS = 5_000L


        /** Shortest gap "skip silence" is allowed to touch. */
        const val MIN_SILENCE_US = 1_000_000L

        /** How long the loop mix waits for a song's synced lyrics before planning on the vocal mask. */
        const val LOOP_MIX_LYRICS_TIMEOUT_MS = 10_000L

        /** Past any song, so the byte ceiling is what stops loading. */
        const val FAR_BUFFER_MS = 15 * 60 * 1000

        /** ~6 minutes at 160kbps: a whole track, for all but the longest. */
        const val FAR_BUFFER_BYTES = 8 * 1024 * 1024

        /**
         * A short nudge backwards, and no more: this shares the byte ceiling
         * above with the read-ahead it would otherwise starve.
         */
        const val BACK_BUFFER_MS = 30 * 1000

        /** Enough to cover the decoder's own latency, not seconds of dead air. */
        const val START_PLAYBACK_MS = 500

        /** More room after a stall than at the start — see the load control. */
        const val RESUME_PLAYBACK_MS = 2_000


        /** How many times one track is picked up off the floor — see [recoverFrom]. */
        const val MAX_RECOVERIES = 2

        /**
         * How many tracks in a row may be skipped for a plain playback error
         * before the queue is left alone — see [skipReason]. Sized to walk a
         * short run of broken tracks without walking an entire queue that is
         * only failing because there is no connection behind it.
         */
        const val MAX_CONSECUTIVE_SKIPS = 4

        /**
         * The pause before a retry. Media3 refuses to remove a cache entry a
         * reader still holds, and the reader is let go asynchronously as the
         * failed source is released, so the discard needs a moment to land
         * before the same track is asked for again.
         */
        const val RECOVERY_DELAY_MS = 350L


    }
}

/**
 * The stock renderers, plus FFmpeg after the MediaCodec one.
 *
 * FFmpeg comes last so it only gets a track no platform decoder takes: AC-4
 * and Dolby Digital (Plus, Atmos) on phones without Dolby's own decoder. Atmos
 * then plays as its 5.1 bed, folded down to stereo — the object layer needs
 * Dolby's decoder, which is the only thing that can read it. Same sink as the
 * MediaCodec renderer, so the DSP chain and the precision path see no
 * difference between the two.
 *
 * ALAC goes to FFmpeg on every device, because MediaCodec is never offered it
 * ([withoutPlatformAlac]). Samsung ships no ALAC codec, which is where FFmpeg
 * was proved; Qualcomm vendor images ship `c2.qti.alac.sw.decoder`, which
 * released itself on its first input buffer (realme RMX3842, Android 16) and
 * misreports position on the tracks it does play. A codec that fails mid-
 * playback is never retried on the next renderer — the track just falls to
 * YouTube — so the only fix is not to start there. Nothing is given up: the
 * vendor codec is software as well, and lossless decoders agree to the bit.
 *
 * Shared by the real players and the upgrade audition, which must be able to
 * decode whatever it is auditioning or it can never prove it — and with the
 * same decoder, or the proof is of something else.
 */
private open class FfmpegFallbackRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            withoutPlatformAlac(mediaCodecSelector),
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )
        out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
    }
}

/**
 * [selector] with no decoders for ALAC, so `MediaCodecAudioRenderer` reports
 * it unsupported and the track selector hands it to FFmpeg — unless this
 * build's FFmpeg cannot decode it (an ABI it was not built for), where a
 * vendor codec is still better than none. Wraps rather than replaces, so a
 * subclass's own selector (the FLAC one) keeps working underneath.
 */
private fun withoutPlatformAlac(selector: MediaCodecSelector) =
    MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
        if (mimeType == MimeTypes.AUDIO_ALAC && ffmpegDecodesAlac) {
            emptyList()
        } else {
            selector.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        }
    }

/**
 * Asked once per process, and only when an ALAC track is first looked at: the
 * native library then loads on the playback thread, as it did before, rather
 * than on the main thread while a player is built.
 */
private val ffmpegDecodesAlac by lazy { FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_ALAC) }
