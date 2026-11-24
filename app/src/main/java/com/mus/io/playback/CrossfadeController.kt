package com.nejuni.juni.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.nejuni.juni.data.settings.AppSettings
import com.nejuni.juni.playback.audio.bitperfect.BitPerfectOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Fixed-duration, equal-power crossfade between the service's two players. */
@UnstableApi
class CrossfadeController(
    private val scope: CoroutineScope,
    private val active: () -> ExoPlayer,
    private val standby: () -> ExoPlayer,
    private val onHandoff: (outgoing: ExoPlayer, incoming: ExoPlayer) -> Unit,
    private val onArmIncoming: (androidx.media3.common.MediaItem, String?) -> Unit = { _, _ -> },
    private val onBlendHeadroom: (Float) -> Unit = {},
    private val onSettled: () -> Unit = {},
) {
    private enum class Phase { IDLE, ARMING, FADING, SLEEP_FADE }

    private var phase = Phase.IDLE
    private var outgoing: ExoPlayer? = null
    private var incoming: ExoPlayer? = null
    private var handedOff = false
    private var fadeMs = 0L
    private var fadeEndMs = 0L
    private var queuedItemCount = 0
    private var outgoingItemIndex = C.INDEX_UNSET
    private var armDeadline = 0L
    private var ticker: Job? = null

    fun isTransitioning(): Boolean = phase != Phase.IDLE

    fun applyPlaybackSpeed(speed: Float) {
        if (isTransitioning()) return
        listOf(active(), standby()).distinct().forEach { it.setPlaybackSpeed(speed) }
    }

    fun start() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    fun release() {
        ticker?.cancel()
        ticker = null
        releaseHold()
        outgoing?.let { it.volume = 1f }
        incoming?.let(::retire)
        outgoing = null
        incoming = null
        phase = Phase.IDLE
        onBlendHeadroom(1f)
    }

    fun onSkipRequested() {
        if (phase == Phase.ARMING) finish(keepOutgoing = true)
        else if (phase == Phase.FADING) finish(keepOutgoing = handedOff)
    }

    private fun tick() {
        when (phase) {
            Phase.IDLE -> plan()
            Phase.ARMING -> arm()
            Phase.FADING -> fade()
            Phase.SLEEP_FADE -> sleepFade()
        }
    }

    private fun plan() {
        val player = active()
        if (!player.isPlaying || player.repeatMode == Player.REPEAT_MODE_ONE ||
            BitPerfectOutput.status.value.active || !player.hasNextMediaItem()
        ) return
        val duration = player.duration
        val fade = fadeFor(duration)
        if (duration == C.TIME_UNSET || duration <= 0L || fade <= 0L) return
        val remaining = duration - player.currentPosition
        if (com.nejuni.juni.playback.SleepTimer.afterTrack.value) {
            if (remaining <= fade) beginSleepFade(fade)
            return
        }
        if (remaining > fade + ARM_LEAD_MS) return
        armTransition(player, duration, fade)
    }

    private fun armTransition(out: ExoPlayer, duration: Long, fade: Long) {
        val into = standby()
        if (out === into) return
        val nextIndex = out.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return
        outgoing = out
        incoming = into
        handedOff = false
        fadeMs = fade
        fadeEndMs = duration
        queuedItemCount = out.mediaItemCount
        outgoingItemIndex = out.currentMediaItemIndex
        armDeadline = SystemClock.elapsedRealtime() + ARM_TIMEOUT_MS
        out.pauseAtEndOfMediaItems = true
        into.skipSilenceEnabled = out.skipSilenceEnabled
        into.repeatMode = out.repeatMode
        into.shuffleModeEnabled = out.shuffleModeEnabled
        into.setPlaybackSpeed(AppSettings.playbackSpeed.value)
        into.volume = 0f
        val items = (0 until out.mediaItemCount).map(out::getMediaItemAt)
        into.setMediaItems(items, nextIndex, 0L)
        val after = if (nextIndex + 1 < items.size) items[nextIndex + 1].mediaId else null
        items.getOrNull(nextIndex)?.let { onArmIncoming(it, after) }
        into.playWhenReady = false
        into.prepare()
        phase = Phase.ARMING
    }

    private fun arm() {
        val out = outgoing ?: return finish(keepOutgoing = true)
        val into = incoming ?: return finish(keepOutgoing = true)
        if (!out.playWhenReady) return finish(keepOutgoing = true)
        val ready = into.playbackState == Player.STATE_READY
        if (!ready && SystemClock.elapsedRealtime() >= armDeadline) return finish(keepOutgoing = true)
        if (out.duration == C.TIME_UNSET || out.duration - out.currentPosition > fadeMs) return
        if (ready) {
            into.volume = 0f
            into.playWhenReady = true
            phase = Phase.FADING
        }
    }

    private fun fade() {
        val out = outgoing ?: return finish(keepOutgoing = handedOff)
        val into = incoming ?: return finish(keepOutgoing = handedOff)
        if (!handedOff && into.playbackState == Player.STATE_IDLE) return finish(keepOutgoing = false)
        val progress = ((into.currentPosition.toFloat()) / fadeMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
        into.volume = sin(progress * Math.PI.toFloat() / 2f)
        out.volume = if (handedOff && out.currentMediaItemIndex != outgoingItemIndex) 0f
            else cos(progress * Math.PI.toFloat() / 2f)
        onBlendHeadroom((1f / kotlin.math.sqrt(into.volume * into.volume + out.volume * out.volume).coerceAtLeast(1f)))
        if (!handedOff && progress >= HANDOFF_AT) handoff(out, into)
        if (progress >= 1f || AppSettings.crossfadeSeconds.value <= 0) {
            if (!handedOff) handoff(out, into)
            finish(keepOutgoing = true)
        }
    }

    private fun handoff(out: ExoPlayer, into: ExoPlayer) {
        if (handedOff) return
        val appended = (queuedItemCount until out.mediaItemCount).map { out.getMediaItemAt(it) }
        if (appended.isNotEmpty()) into.addMediaItems(appended)
        handedOff = true
        onHandoff(out, into)
        if (out.mediaItemCount > out.currentMediaItemIndex + 1) {
            out.removeMediaItems(out.currentMediaItemIndex + 1, out.mediaItemCount)
        }
    }

    private fun beginSleepFade(fade: Long) {
        outgoing = active()
        fadeMs = fade.coerceAtLeast(1L)
        fadeEndMs = active().currentPosition + fadeMs
        phase = Phase.SLEEP_FADE
    }

    private fun sleepFade() {
        val out = outgoing ?: return finish(keepOutgoing = true)
        val progress = ((out.currentPosition - (fadeEndMs - fadeMs)).toFloat() / fadeMs).coerceIn(0f, 1f)
        out.volume = cos(progress * Math.PI.toFloat() / 2f)
        if (progress >= 1f) {
            out.pause()
            out.volume = 1f
            com.nejuni.juni.playback.SleepTimer.cancel()
            finish(keepOutgoing = true)
        }
    }

    private fun finish(keepOutgoing: Boolean) {
        val wasActive = phase != Phase.IDLE
        releaseHold()
        if (keepOutgoing) outgoing?.volume = 1f else outgoing?.let(::retire)
        if (handedOff) outgoing?.let(::retire)
        incoming?.let { if (it !== active()) retire(it) else it.volume = 1f }
        outgoing = null
        incoming = null
        handedOff = false
        outgoingItemIndex = C.INDEX_UNSET
        phase = Phase.IDLE
        onBlendHeadroom(1f)
        if (wasActive) onSettled()
    }

    private fun releaseHold() {
        outgoing?.pauseAtEndOfMediaItems = false
        active().pauseAtEndOfMediaItems = false
    }

    private fun retire(player: ExoPlayer) {
        if (player === active()) return
        runCatching { player.stop(); player.clearMediaItems() }
        player.pauseAtEndOfMediaItems = false
        player.volume = 1f
        player.setPlaybackSpeed(AppSettings.playbackSpeed.value)
    }

    private fun fadeFor(durationMs: Long): Long {
        val configured = AppSettings.crossfadeSeconds.value * 1000L
        if (configured <= 0L) return 0L
        return if (durationMs > 0L && durationMs != C.TIME_UNSET) min(configured, durationMs / 3) else configured
    }

    private companion object {
        const val TICK_MS = 30L
        const val ARM_LEAD_MS = 4_000L
        const val ARM_TIMEOUT_MS = 12_000L
        const val HANDOFF_AT = 0.5f
    }
}
