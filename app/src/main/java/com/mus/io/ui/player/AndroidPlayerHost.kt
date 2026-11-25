package com.nejuni.juni.ui.player

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.nejuni.juni.data.canvas.CanvasArtwork
import com.nejuni.juni.data.canvas.CanvasRepository
import com.nejuni.juni.data.lyrics.LyricLine
import com.nejuni.juni.data.model.Song
import com.nejuni.juni.data.settings.AppSettings
import com.nejuni.juni.data.settings.LastPlayerScreen
import com.nejuni.juni.playback.AudioOutputStatus
import com.nejuni.juni.playback.AudioRouting
import com.nejuni.juni.playback.audio.bitperfect.BitPerfectOutput
import com.nejuni.juni.playback.audio.bitperfect.DacVolume
import com.nejuni.juni.ui.components.backdrop.backdrops.SnapshotBackdrop
import com.nejuni.juni.ui.components.backdrop.backdrops.rememberSnapshotBackdrop
import com.nejuni.juni.ui.components.backdrop.backdrops.snapshotBackdropSource
import com.nejuni.juni.ui.components.isGlassSupported
import com.nejuni.juni.ui.components.menuBackdropFrost
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The phone's answers to [PlayerHost]: AppSettings, the Media3 Canvas decoder,
 * AudioRouting and the music stream's volume, and the lyric translation
 * endpoint — each exactly what the player read directly before it moved into
 * the shared module.
 */
class AndroidPlayerHost(context: Context) : PlayerHost {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    override val settings: PlayerSettingsSource = object : PlayerSettingsSource {
        override val animatedCanvas get() = AppSettings.animatedCanvas
        override val audioQualityCellular get() = AppSettings.audioQualityCellular
        override val audioQualityWifi get() = AppSettings.audioQualityWifi
        override val canvasOverCellular get() = AppSettings.canvasOverCellular
        override val fullBleedArtwork get() = AppSettings.fullBleedArtwork
        override val hideSongStatus get() = AppSettings.hideSongStatus
        override val showQueueHistory get() = AppSettings.showQueueHistory
        override val hideVolumeBar get() = AppSettings.hideVolumeBar
        override val hideStatusBar get() = AppSettings.hideStatusBarInPlayer
        override val lastPlayerScreen get() = AppSettings.lastPlayerScreen
        override val playerTheme get() = AppSettings.playerTheme
        override val lyricsBlur get() = AppSettings.lyricsBlur
        override val lyricsOffsetMs get() = AppSettings.lyricsOffsetMs
        override val meteredConnection get() = AppSettings.meteredConnection
        override val streamCeilingOverride get() = AppSettings.streamCeilingOverride
        override val preferUsbDac get() = AppSettings.preferUsbDac
        override val reduceAnimation get() = AppSettings.reduceAnimation
        override val reduceDynamicBlur get() = AppSettings.reduceDynamicBlur
        override val disableBlendEffects get() = AppSettings.disableBlendEffects
        override val showNerdStats get() = AppSettings.showNerdStats
        override val highResCoverArt get() = AppSettings.highResCoverArt
        override val canvasAutoHide get() = AppSettings.canvasAutoHide
        override val syncedLyrics get() = AppSettings.syncedLyrics
        override val hideLyricsPreview get() = AppSettings.hideLyricsPreview
        override val versionAlignmentInProgress get() = AppSettings.versionAlignmentInProgress

        override fun setLastPlayerScreen(value: LastPlayerScreen) = AppSettings.setLastPlayerScreen(value)
        override fun setLyricsOffsetMs(value: Int) = AppSettings.setLyricsOffsetMs(value)

    }

    override fun cachedCanvas(song: Song): CanvasArtwork? = CanvasRepository.cached(song)

    // Keep any previously cached clip available; never fetch a new one online.
    override suspend fun canvasFor(song: Song): CanvasArtwork? = CanvasRepository.cached(song)

    @Composable
    override fun CanvasVideo(spec: CanvasVideoSpec, modifier: Modifier) = Unit

    // Lazy, all three: the player is not the first thing the process does, and
    // none of these objects should be woken for a launch that never opens it.
    override val volume: SystemVolume by lazy { MusicStreamVolume(app) }

    @Composable
    override fun rememberAudioOutputs(): List<AudioOutputDevice> = rememberAndroidAudioOutputs()

    @Composable
    override fun rememberOutputPicker(onOpen: () -> Unit): () -> Unit = rememberAndroidOutputPicker(onOpen)

    override fun selectAudioOutput(id: Int?) {
        AudioRouting.select(id)
    }

    override val outputFormat: StateFlow<OutputFormatUi> by lazy {
        combine(AudioOutputStatus.current, BitPerfectOutput.status) { status, bitPerfect ->
            status.toOutputFormat(bitPerfect)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            AudioOutputStatus.current.value.toOutputFormat(BitPerfectOutput.status.value),
        )
    }

    override fun showMessage(message: String) {
        main.post { Toast.makeText(app, message, Toast.LENGTH_SHORT).show() }
    }


    @Composable
    override fun AudioPipelineDialog(hazeState: HazeState, isPlaying: Boolean, onDismiss: () -> Unit) =
        com.nejuni.juni.ui.components.AudioPipelineDialog(
            hazeState = hazeState,
            onDismiss = onDismiss,
            isPlaying = isPlaying,
        )

    // The ⋯ menu's own material: a still of the player, blurred under each
    // panel exactly as [menuBackdropFrost] blurs it under the menu. Below
    // Android 12 there is no RenderEffect to blur it with.
    @Composable
    override fun rememberPlayerFrost(): PlayerFrost? {
        if (!isGlassSupported()) return null
        val backdrop = rememberSnapshotBackdrop()
        return remember(backdrop) { BackdropPlayerFrost(backdrop) }
    }

    // The phone is the platform that can turn picked lyrics into a picture: a
    // bitmap, a canvas, the gallery and the chooser are all Android's here. The
    // desktop has no answer to it, so it never sees the pick mode at all.
    override val lyricsShareAvailable: Boolean get() = true

    @Composable
    override fun LyricsShareSheet(
        hazeState: HazeState,
        request: LyricsShareRequest,
        onDismiss: () -> Unit,
    ) = AndroidLyricsShareSheet(hazeState = hazeState, request = request, onDismiss = onDismiss)
}

/**
 * The output's format line, and whether the track is reaching the DAC
 * bit-perfect. On the exclusive output the format is the one written to
 * the DAC, not the ordinary AudioTrack's, which is idle meanwhile.
 */
private fun AudioOutputStatus.Snapshot.toOutputFormat(bitPerfect: BitPerfectOutput.Status): OutputFormatUi {
    val encoding = if (bitPerfect.active) "${bitPerfect.outputBits}-bit PCM" else AudioOutputStatus.encodingLabel(this)
    val rate = (if (bitPerfect.active) bitPerfect.sampleRateHz else actualSampleRateHz)?.takeIf { it > 0 }
    val format = if (rate == null) {
        encoding
    } else {
        "$encoding · ${"%.1f".format(Locale.ROOT, rate / 1000f).removeSuffix(".0")} kHz"
    }
    val dacLimited = bitPerfect.active && bitPerfect.dacLimited
    return OutputFormatUi(
        summary = if (dacLimited) "$format · DAC max" else format,
        bitPerfect = bitPerfect.active && bitPerfect.exact,
        dacLimited = dacLimited,
    )
}

/**
 * The music stream's volume. Hardware keys and the system panel change it
 * behind the app's back, so Settings is watched and the level follows.
 */
private class MusicStreamVolume(private val context: Context) : SystemVolume {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val _level = MutableStateFlow(read())
    override val level: StateFlow<Float> = _level.asStateFlow()

    init {
        context.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = refresh()
            },
        )
    }

    private fun max(): Int = manager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15

    private fun read(): Float =
        (manager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0).toFloat() / max()

    override fun set(level: Float) {
        manager?.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max()).roundToInt(), 0)
    }

    override fun refresh() {
        _level.value = read()
    }
}

private class BackdropPlayerFrost(private val backdrop: SnapshotBackdrop) : PlayerFrost {
    @Composable
    override fun Modifier.frostSource(): Modifier = snapshotBackdropSource(backdrop)

    override fun capture() = backdrop.capture()

    override fun release() = backdrop.release()

    override fun Modifier.frostPanel(shape: Shape, fill: Color): Modifier = menuBackdropFrost(backdrop, shape, fill)
}
