package com.nejuni.juni

import com.nejuni.juni.data.LocalLibraryCache
import com.nejuni.juni.data.model.ArtistRef
import com.nejuni.juni.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * The disk snapshot has to read back exactly what was written. A field-order
 * mismatch between the writer and the reader makes every launch treat the
 * snapshot as corrupt and scan again — silently, because the in-memory list
 * answers first within the session that wrote it.
 */
class LocalLibraryCacheTest {

    @Test
    fun `snapshot round-trips every field`() {
        val songs = listOf(
            Song(
                videoId = "content://media/external/audio/media/1",
                title = "A Love That's Real",
                artist = "The Intruders",
                thumbnailUrl = "content://media/external/audio/albumart/9",
                durationText = "3:05",
                artists = listOf(ArtistRef(name = "The Intruders", browseId = null)),
                albumName = "Come Home Soon",
                localUri = "content://media/external/audio/media/1",
                localPath = "/storage/emulated/0/Music/a.flac",
                localDateAddedSeconds = 1_700_000_000L,
                localDateModifiedSeconds = 1_700_000_500L,
                localFileSizeBytes = 12_345_678L,
                trackNumber = 3,
            ),
            Song(
                videoId = "content://media/external/audio/media/2",
                title = "Bare",
                artist = "",
                thumbnailUrl = null,
                artists = emptyList(),
            ),
        )

        val bytes = ByteArrayOutputStream().also { raw ->
            DataOutputStream(raw).use { output ->
                LocalLibraryCache.writeTo(output, songs, at = 42L)
            }
        }.toByteArray()

        val read = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            LocalLibraryCache.readFrom(input)
        }

        assertEquals(songs, read)
    }

    @Test
    fun `empty snapshot reads back empty, not null`() {
        val bytes = ByteArrayOutputStream().also { raw ->
            DataOutputStream(raw).use { output ->
                LocalLibraryCache.writeTo(output, emptyList(), at = 0L)
            }
        }.toByteArray()

        val read = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            LocalLibraryCache.readFrom(input)
        }

        assertEquals(emptyList<Song>(), read)
    }
}
