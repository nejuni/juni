package com.nejuni.juni.playback

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What an intent from outside the app turned out to be asking for. */
sealed interface LinkRequest {
    /** "Play music", with nothing said about what. */
    data object Resume : LinkRequest

    /**
     * An audio file another app asked to have played — "Open with" from a file
     * manager, a tapped attachment — by the `content://` or `file://` uri it
     * handed over. See [com.nejuni.juni.data.LocalMediaRepository.songFromUri].
     */
    data class LocalFile(val uri: Uri) : LinkRequest
}

/**
 * Intents handed to bit from elsewhere on the device.
 *
 * The same relay [PlayerDeepLink] is, and for the same reason: what has to
 * happen — start a queue, resume one — is all inside `juniApp`'s composition,
 * which [MainActivity][com.nejuni.juni.MainActivity] has no handle on. The activity
 * reads the intent, this holds the answer, and the composition serves it once
 * its controller and view model exist.
 *
 * Kept apart from [PlayerDeepLink] rather than folded into it because the two
 * are answered by different things at different times: opening the player is a
 * boolean the sheet reads, while this is a request that has to reach a live
 * playback session before anything happens on screen.
 */
object MusicLink {

    /**
     * Marks an intent as already read.
     *
     * MainActivity is `singleTask` and declares no `configChanges`, so a theme
     * or font-size change destroys and recreates it — with `getIntent()` still
     * returning the link that launched it. Without this, rotating the phone an
     * hour later would replay the opened file over whatever is playing.
     */
    private const val EXTRA_CONSUMED = "juni.linkConsumed"

    private val _pending = MutableStateFlow<LinkRequest?>(null)

    /** The outstanding request, or null. Cleared by [handled]. */
    val pending: StateFlow<LinkRequest?> = _pending.asStateFlow()

    /** Reads an incoming intent, and reports whether it carried a request. */
    fun consume(intent: Intent?): Boolean {
        if (intent == null || intent.getBooleanExtra(EXTRA_CONSUMED, false)) return false
        val request = when (intent.action) {
            // Only the audio filter in the manifest takes these two schemes, so
            // either one is a file to play.
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                if (uri.scheme == "content" || uri.scheme == "file") LinkRequest.LocalFile(uri) else null
            }
            // The assistant's "play music". It names nothing, and the useful
            // answer is to carry on with what was already on.
            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> LinkRequest.Resume
            else -> null
        } ?: return false
        intent.putExtra(EXTRA_CONSUMED, true)
        _pending.value = request
        return true
    }

    /**
     * Called once the request has actually been acted on.
     *
     * By whoever acted, not by whoever set it — this object outlives the
     * composition, and a request left standing would be served again by the
     * next one, which is the opened file starting over on the first
     * configuration change.
     */
    fun handled() {
        _pending.value = null
    }
}
