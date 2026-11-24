package com.nejuni.juni.data

import android.content.Context
import android.net.Uri
import com.nejuni.juni.data.model.Song
import com.nejuni.juni.data.model.durationMillis
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/** Writes the device library as a UTF-8 Extended M3U playlist. */
object M3uExporter {
    fun write(context: Context, destination: Uri, songs: List<Song>): Int {
        val playable = songs.filter { !it.localUri.isNullOrBlank() || !it.localPath.isNullOrBlank() }
        val stream = requireNotNull(context.contentResolver.openOutputStream(destination, "wt")) {
            "Could not open playlist destination"
        }
        OutputStreamWriter(stream, StandardCharsets.UTF_8).buffered().use { writer ->
            writer.appendLine("#EXTM3U")
            playable.forEach { song ->
                val seconds = (song.durationMillis() / 1_000L).coerceAtLeast(-1L)
                val label = listOf(song.artist, song.title)
                    .filter(String::isNotBlank)
                    .joinToString(" - ")
                    .replace('\r', ' ')
                    .replace('\n', ' ')
                writer.appendLine("#EXTINF:$seconds,$label")
                // Use a real path when MediaStore provides it. For SAF-backed
                // folders, retain the document URI as the only valid locator.
                writer.appendLine(song.localPath?.takeIf(String::isNotBlank) ?: song.localUri.orEmpty())
            }
        }
        return playable.size
    }
}
