package com.nejuni.juni

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.annotation.OptIn
import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.nejuni.juni.data.DebugLog
import com.nejuni.juni.data.StartupTrace
import com.nejuni.juni.ui.player.AndroidPlayerHost
import com.nejuni.juni.ui.player.PlayerPlatform
import com.nejuni.juni.playback.LastPlayed
import com.nejuni.juni.playback.BoundedContextPersistence
import com.nejuni.juni.data.scrobbling.LastFM
import com.nejuni.juni.data.settings.AppSettings
import com.nejuni.juni.data.settings.SearchHistory
import com.nejuni.juni.data.stats.ArtistFacts
import com.nejuni.juni.data.stats.ListeningStats
import kotlinx.coroutines.launch

class JuniApplication : Application(), SingletonImageLoader.Factory {


    @OptIn(UnstableApi::class)
    @kotlin.OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate() {
        super.onCreate()
        StartupTrace.init(this)
        StartupTrace.log("app", "onCreate")
        // The player is drawn by the shared UI module; this is what it reads
        // underneath — settings, the Canvas decoder, outputs.
        PlayerPlatform.install(AndroidPlayerHost(this))
        com.nejuni.juni.ui.AppUi.install(com.nejuni.juni.ui.AndroidAppUiHost)
        StartupTrace.log("app", "platform installed")
        // The shared data layer (lyrics, the YouTube Music client) logs to
        // logcat on debug builds only, as the app's own DebugLog always has.
        if (BuildConfig.DEBUG) {
            DebugLog.sink = DebugLog.Sink { level, tag, message, error ->
                when (level) {
                    'D' -> android.util.Log.d(tag, message, error)
                    'I' -> android.util.Log.i(tag, message, error)
                    'W' -> android.util.Log.w(tag, message, error)
                    else -> android.util.Log.e(tag, message, error)
                }
            }
        } else {
            // R8 strips android.util.Log from a release build (proguard-rules.pro);
            // this stops Media3 composing the lines it would have handed it.
            androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
            // java.util.logging reaches logcat without android.util.Log, through
            // the handler Android installs on its root logger — the Discord
            // gateway's connection chatter went out that way.
            java.util.logging.LogManager.getLogManager().reset()
            java.util.logging.Logger.getLogger("").level = java.util.logging.Level.OFF
        }
        // Compose re-votes the window's frame rate from dispatchDraw on every
        // frame (API 35+), and Samsung's View logs each vote together with a
        // stack walk of its caller: measured on an S22, 8,000 lines in two
        // minutes, two stack traces built on the main thread per frame. The app
        // asks for its refresh rate itself (MainActivity.applyPerformanceMode),
        // so those votes buy nothing — and they kept resetting that request.
        ComposeUiFlags.isAdaptiveRefreshRateEnabled = false
        StartupTrace.log("app", "logging configured")
        // The per-app language picker.
        // Full tag (zh-Hant / zh-Hans preserved).
        AppSettings.init(this)
        StartupTrace.log("app", "settings")
        // Before anything resolves a track: the decoder format gate reads "no"
        // until it has looked.
        com.nejuni.juni.playback.audio.LosslessOutput.init(this)
        SearchHistory.init(this)
        LastPlayed.init(this)
        com.nejuni.juni.playback.LocalListeningHistory.init(this)
        BoundedContextPersistence.init(this)
        StartupTrace.log("app", "session stores")
        // The device's own listening record. Opened here rather than in
        // PlaybackService because the Replay page reads it from the UI side and
        // both live in this process — one owner, one directory.
        ListeningStats.init(this)
        // After AppSettings, whose switch decides whether half of it runs.
        ArtistFacts.init(this)
        StartupTrace.log("app", "stats")
        // A sideloaded update is just a new APK over the old one, so app data —
        // including whatever the old build left in the image cache — survives
        // it untouched. Wipe that on the first launch of a higher versionCode so
        // a format change between builds can't serve stale bytes.
        //
        // The song cache is gone, so there is nothing of its own to wipe.
        if (AppSettings.consumeVersionUpdate(BuildConfig.VERSION_CODE)) {
            SingletonImageLoader.get(this).let { loader ->
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
            }
        }
        StartupTrace.log("app", "version gate")
        // Initialize LastFM with saved settings if available
        initLastfm()
        ensureLauncherEntry()
        StartupTrace.log("app", "onCreate done")
    }

    /**
     * The themed launcher aliases were retired with the single wave icon. An
     * install that had picked one has that alias name recorded as enabled, but
     * the alias no longer exists after the update; LauncherDefault is now the
     * only launcher entry, so make sure it is enabled rather than leaving the
     * home screen with no shortcut.
     */
    private fun ensureLauncherEntry() {
        val component = ComponentName(this, "$packageName.LauncherDefault")
        runCatching {
            if (packageManager.getComponentEnabledSetting(component) !=
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            ) {
                packageManager.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }

    /**
     * Artwork loading, which was previously left entirely on Coil's defaults.
     *
     * The defaults aren't unreasonable, but the disk cache is sized at 2% of
     * free space — which on a full phone is the 10MB floor, a few screens of
     * covers, and covers are exactly the thing worth still having tomorrow.
     * Naming a directory alongside it keeps that cache somewhere identifiable
     * rather than in the process's temp dir.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            // Images are read from local files only. Lyric and Last.fm HTTP
            // clients are separate and remain available for those features.
            .components { add(SvgDecoder.Factory()) }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            // Covers arriving with a hard cut read as the list flickering as
            // it scrolls; a short fade reads as them developing.
            .crossfade(200)
            .allowHardware(!emulatedGpu)
            .build()

    /**
     * Whether graphics here go through an emulator's GL translator rather than
     * a real GPU driver — see [newImageLoader].
     *
     * On BlueStacks (Android 9) uploading a hardware bitmap aborts the whole
     * process: the renderer turns a cover into a texture, the translator
     * answers GL_INVALID_ENUM, and hwui's own error check kills the app —
     * `glGenTextures error! GL_INVALID_ENUM`, from RenderThread, each time the
     * app came back to the foreground with a 640px cover to decode. Covers in
     * ordinary bitmaps never take that path.
     *
     * Asked of the driver on disk rather than of build properties, because
     * emulators spoof those — BlueStacks reports itself as a Qualcomm Galaxy
     * S22. `libEGL_emulation` is the AOSP emulator's translator, which no
     * phone ships.
     */
    private val emulatedGpu: Boolean by lazy {
        listOf("/vendor/lib64", "/vendor/lib", "/system/lib64", "/system/lib")
            .any { java.io.File("$it/egl/libEGL_emulation.so").exists() }
    }

    private fun initLastfm() {
        val sessionKey = AppSettings.lastfmSessionKey.value
        if (sessionKey.isBlank()) return
        val apiKey = AppSettings.lastfmApiKey.value.trim()
        val secret = AppSettings.lastfmSecret.value.trim()
        if (apiKey.isBlank() || secret.isBlank()) return
        LastFM.configure(
            apiKey = apiKey,
            secret = secret,
            sessionKey = sessionKey,
        )
    }

}
