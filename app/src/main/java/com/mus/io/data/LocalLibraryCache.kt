package com.nejuni.juni.data

import android.content.Context
import com.nejuni.juni.data.model.ArtistRef
import com.nejuni.juni.data.model.Song
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * The last scan of the device's music, kept as a snapshot so the first page
 * after launch is drawn without querying MediaStore at all.
 *
 * This build scans when it is asked to and not otherwise: the snapshot is what
 * launch reads, and the only thing that replaces it is the "Scan library" action
 * in Settings → Local Music. That is the whole trade — a list that is one tap
 * stale rather than a scan on every start.
 *
 * Binary rather than JSON: this is a list of possibly thousands of rows read
 * once per launch, and a row's fields are all strings and numbers. Writing it is
 * atomic (a temporary file renamed over the old one), so a process death
 * mid-write cannot leave a half-parsed cache behind.
 */
internal object LocalLibraryCache {

    private const val FILE_NAME = "local_library.bin"
    private const val MAGIC = 0x4249544C // "BITL"
    private const val VERSION = 3

    /** The snapshot read this process, if any. */
    @Volatile
    private var songs: List<Song>? = null

    /** When the snapshot was taken. */
    @Volatile
    private var savedAt: Long = 0L

    /**
     * The snapshot on disk, read once per process. Null when there has never
     * been a scan — the one case that still scans by itself.
     */
    fun cached(context: Context): List<Song>? {
        songs?.let { return it }
        return read(context)?.also { songs = it }
    }

    /** When [cached] was taken, in wall-clock millis; 0 when there is none. */
    fun savedAt(context: Context): Long {
        if (songs == null) cached(context)
        return savedAt
    }

    /** Records a just-completed scan, in memory and on disk. */
    fun update(context: Context, fresh: List<Song>, at: Long = System.currentTimeMillis()) {
        songs = fresh
        savedAt = at
        write(context, fresh, at)
    }

    private fun read(context: Context): List<Song>? = runCatching {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return@runCatching null
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            readFrom(input)
        }
    }.onFailure {
        StartupTrace.log("library", "snapshot unreadable — ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()

    private fun write(context: Context, songs: List<Song>, at: Long) {
        runCatching {
            val target = File(context.filesDir, FILE_NAME)
            val temporary = File(context.filesDir, "$FILE_NAME.tmp")
            DataOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { output ->
                writeTo(output, songs, at)
            }
            if (!temporary.renameTo(target)) {
                target.delete()
                if (!temporary.renameTo(target)) {
                    StartupTrace.log("library", "snapshot not saved — rename failed")
                }
            }
        }.onFailure {
            StartupTrace.log("library", "snapshot not saved — ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /**
     * The whole snapshot format, in one place. [readFrom] must consume exactly
     * what [writeTo] writes, field for field, in the same order — a mismatch
     * here makes every launch treat the snapshot as corrupt and scan again,
     * which is invisible while the just-scanned list is still in memory. The
     * round-trip test is the guard.
     */
    internal fun writeTo(output: DataOutputStream, songs: List<Song>, at: Long) {
        output.writeInt(MAGIC)
        output.writeInt(VERSION)
        output.writeLong(at)
        output.writeInt(songs.size)
        songs.forEach { output.writeSong(it) }
    }

    internal fun readFrom(input: DataInputStream): List<Song>? {
        if (input.readInt() != MAGIC) return null
        if (input.readInt() != VERSION) return null
        val at = input.readLong()
        val count = input.readInt()
        if (count < 0 || count > 200_000) return null
        val out = ArrayList<Song>(count)
        repeat(count) { out += input.readSong() }
        savedAt = at
        return out
    }

    private fun DataOutputStream.writeSong(song: Song) {
        writeNullable(song.videoId)
        writeNullable(song.title)
        writeNullable(song.artist)
        writeNullable(song.thumbnailUrl)
        writeNullable(song.durationText)
        writeNullable(song.albumName)
        writeNullable(song.albumId)
        writeNullable(song.artistId)
        writeNullable(song.localUri)
        writeNullable(song.localPath)
        writeLong(song.localDateAddedSeconds ?: -1L)
        writeLong(song.localDateModifiedSeconds ?: -1L)
        writeLong(song.localFileSizeBytes ?: -1L)
        writeInt(song.trackNumber ?: -1)
        writeInt(song.artists.size)
        song.artists.forEach { artist ->
            writeNullable(artist.name)
            writeNullable(artist.browseId)
        }
    }

    private fun DataInputStream.readSong(): Song = Song(
        videoId = readNullable().orEmpty(),
        title = readNullable().orEmpty(),
        artist = readNullable().orEmpty(),
        thumbnailUrl = readNullable(),
        durationText = readNullable(),
        albumName = readNullable(),
        albumId = readNullable(),
        artistId = readNullable(),
        localUri = readNullable(),
        localPath = readNullable(),
        localDateAddedSeconds = readLong().takeIf { it >= 0L },
        localDateModifiedSeconds = readLong().takeIf { it >= 0L },
        localFileSizeBytes = readLong().takeIf { it >= 0L },
        trackNumber = readInt().takeIf { it >= 0 },
        artists = buildList {
            repeat(readInt().coerceIn(0, 64)) {
                add(ArtistRef(name = readNullable().orEmpty(), browseId = readNullable()))
            }
        },
    )

    private fun DataOutputStream.writeNullable(value: String?) {
        if (value == null) {
            writeBoolean(false)
        } else {
            writeBoolean(true)
            writeUTF(value)
        }
    }

    private fun DataInputStream.readNullable(): String? =
        if (readBoolean()) readUTF() else null
}
