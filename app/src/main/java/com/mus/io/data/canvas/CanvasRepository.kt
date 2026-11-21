package com.nejuni.juni.data.canvas

import com.nejuni.juni.data.model.Song

/** Motion artwork lookup is disabled in the local only build. */
object CanvasRepository {
    suspend fun canvasFor(song: Song): CanvasArtwork? = null

    fun cached(song: Song): CanvasArtwork? = null

}
