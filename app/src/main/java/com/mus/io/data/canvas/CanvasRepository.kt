package com.juni.io.data.canvas

import com.juni.io.data.model.Song

/** Motion artwork lookup is disabled in the local only build. */
object CanvasRepository {
    suspend fun canvasFor(song: Song): CanvasArtwork? = null

    fun cached(song: Song): CanvasArtwork? = null

}
