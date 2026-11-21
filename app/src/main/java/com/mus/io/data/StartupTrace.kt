package com.nejuni.juni.data

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.nejuni.juni.BuildConfig
import java.io.File
import java.util.concurrent.Executors

/**
 * Temporary launch timings, written to a file the Settings page can share.
 *
 * Every line is milliseconds since this process's [StartupTrace] was first
 * touched — the first statement of `JuniApplication.onCreate` — so the file reads
 * as one launch: process start, the app's own initialisers, the first drawn
 * frame, the playback service's construction and the first library page.
 *
 * Writes go to one background thread, so a line costs the caller a queue offer
 * rather than a file operation. The file is truncated once per process, so it
 * always describes the launch that is running, not an older one.
 *
 * This exists to find a specific hot spot and is meant to be deleted once it
 * has: see the "Share startup trace" row in Settings.
 */
object StartupTrace {

    private val startedAt = SystemClock.elapsedRealtime()
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "startup-trace").apply { isDaemon = true }
    }

    @Volatile
    private var target: File? = null

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val file = File(context.filesDir, FILE_NAME)
        target = file
        runCatching { file.writeText("") }
        log(
            "app",
            "process start — ${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}, " +
                "version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        )
    }

    /** Records one step. Safe to call from any thread; cheap enough for launch. */
    fun log(area: String, message: String = "") {
        val file = target ?: return
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val suffix = if (message.isBlank()) "" else " — $message"
        val line = "+${elapsed}ms  $area$suffix\n"
        writer.execute { runCatching { file.appendText(line) } }
    }

    /** Whether a trace has been started in this process. */
    fun exists(context: Context): Boolean = initialized && File(context.filesDir, FILE_NAME).exists()

    /**
     * A copy of the trace in `cache/shared/`, which is the one directory the
     * app's FileProvider hands out, for the share sheet to attach.
     */
    fun shareCopy(context: Context): File? {
        val source = File(context.filesDir, FILE_NAME)
        if (!source.exists()) return null
        val directory = File(context.cacheDir, "shared").apply { mkdirs() }
        val copy = File(directory, "startup_trace.txt")
        return runCatching {
            source.copyTo(copy, overwrite = true)
            copy
        }.getOrNull()
    }

    private const val FILE_NAME = "startup_trace.txt"
}
