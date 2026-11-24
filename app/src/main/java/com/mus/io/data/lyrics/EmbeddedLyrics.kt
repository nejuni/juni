package com.nejuni.juni.data.lyrics

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.nejuni.juni.data.DebugLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Locale

/**
 * The lyrics already sitting inside a downloaded file.
 *
 * The read side of what the download path wrote — see `MediaTagger`, and the
 * three taggers under it. A track that was downloaded had its lyrics fetched
 * once, at download time, and written into the file; asking four servers for
 * them again every time it is played is a network round trip to arrive at a
 * string that is already on disk, and it is the reason a downloaded song showed
 * nothing at all with the connection off.
 *
 * Embedded tags are checked before any network lookup:
 *
 *  - `bit_LYRICS`, this app's own, holding the "enhanced" A2 form with the
 *    word timings intact — see [toEnhancedLrc].
 *  - standard `LYRICS` Vorbis comments, ID3 `USLT`/`SYLT` and `TXXX`, APE and
 *    Lyrics3 tags, WAV ID3 chunks, and the existing MP4/Matroska lyric fields.
 *
 * Timestamped LRC keeps its timing; plain embedded text is displayed in order
 * without made-up timestamps.
 *
 * Never throws. A file that has no supported lyric tag, or has no lyrics in it,
 * is a null — the caller falls back to the network, which
 * is exactly what it did before this existed.
 *
 * ### Offline packages
 *
 * One kind of download has no container to have been written into: an HLS or
 * DASH stream is saved as a directory of fMP4 segments behind a playlist, and
 * its `localUri` points at that playlist. `OfflineHls` and `OfflineDash` write
 * the lyrics beside it as a `lyrics.lrc` sidecar for exactly that reason — so
 * [sidecar] is tried first, and a package's lyrics come back the same shape as
 * a tagged file's. Without it those tracks fetched their lyrics from the
 * network on every play and showed none at all offline, which is the failure
 * this whole object exists to prevent.
 */
object EmbeddedLyrics {

    private const val TAG = "juni"

    /**
     * Most bytes worth pulling to find a tag.
     *
     * A cap rather than a size: this reads whatever region of the file holds
     * the metadata, and that region is small in normal audio files — but its
     * length is stated *by the file*, so a corrupt or hostile one could claim
     * any number at all. `LyricsTag` caps what it writes at 64k, so anything
     * past this is not a tag this app produced.
     */
    private const val MAX_TAG_BYTES = 16 * 1024 * 1024
    private const val MAX_PLAIN_LYRIC_LINES = 2_000
    private val LRC_METADATA_LINE = Regex(
        "\\[(ar|al|ti|length|re|ve|by|offset|kana|la)\\s*:[^]]*]",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The lyrics inside [uriString], or null when it has none worth showing.
     *
     * Touches the filesystem, so it runs on [Dispatchers.IO] regardless of
     * where it is called from.
     */
    suspend fun forUri(context: Context, uriString: String): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            val raw = runCatching { read(context, Uri.parse(uriString)) }
                .onFailure { Log.d(TAG, "no embedded lyrics in $uriString: ${it.message}") }
                .getOrNull()
                ?: return@withContext null
            // The same last pass the network sources get, so a downloaded track
            // and a streamed one draw their backing vocals the same way.
            parseEmbeddedText(raw)
        }

    /** Keeps plain USLT/APE/Vorbis text visible without fabricating word timing. */
    private fun parseEmbeddedText(raw: String): List<LyricLine>? {
        val timed = LrcLib.parseLrc(raw).takeIf { lines -> lines.any { it.text.isNotBlank() } }
        if (timed != null) return timed.withBackgroundVocals()

        val plain = raw.lineSequence()
            .map { it.trim().removePrefix("\uFEFF") }
            .filter { it.isNotBlank() && !it.matches(LRC_METADATA_LINE) }
            .take(MAX_PLAIN_LYRIC_LINES + 1)
            .toList()
            .take(MAX_PLAIN_LYRIC_LINES)
        if (plain.isEmpty()) return null
        // Unsynced tags have no timestamps. Keep their lines in order rather
        // than inventing sync positions the file does not contain.
        return plain.map { LyricLine(timeMs = 0L, text = it) }
    }

    /** The raw LRC text for the file, preferring this app's word-timed field. */
    private fun read(context: Context, uri: Uri): String? =
        sidecar(uri.path.takeIf { uri.scheme == "file" })
            ?: open(context, uri)?.use { fromBytes(it.readAtMost(MAX_TAG_BYTES)) }
            ?: readTailTags(context, uri)

    /**
     * The `lyrics.lrc` written next to the offline package playlist at [path].
     *
     * Recognised by the playlist rather than by the directory it sits in: the
     * package lives under a path this module has no business knowing, and a
     * local `.m3u8` is only ever one of these packages — nothing else in the
     * app saves a playlist to disk. What the sidecar holds is the enhanced A2
     * form where the download found one, which is why it is preferred over a
     * container search rather than used as a fallback to it.
     *
     * Takes the path rather than the `Uri` it came out of so the pairing with
     * the writers can be tested on a real directory, without a device.
     */
    internal fun sidecar(path: String?): String? {
        val playlist = path?.let(::File)?.takeIf { it.name.endsWith(".m3u8", ignoreCase = true) }
            ?: return null
        val file = File(playlist.parentFile ?: return null, "lyrics.lrc")
        // Bounded for the same reason [MAX_TAG_BYTES] is: this is read into a
        // string, and the size on disk is the only thing that says how big one.
        if (!file.isFile || file.length() !in 1..MAX_TAG_BYTES.toLong()) return null
        return runCatching { file.readText() }
            .onFailure { Log.d(TAG, "could not read $file: ${it.message}") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * The raw LRC text in [head], whichever of the three containers it is.
     *
     * Split from [read] so the parsing can be tested against bytes a tagger
     * just produced, without a device or a `Context` in the way — the round
     * trip is the only thing that proves a reader and a writer agree.
     */
    internal fun fromBytes(head: ByteArray): String? {
        val found = when {
            head.startsWith(ID3_MAGIC) -> id3(head)
            head.startsWith(FLAC_MAGIC) -> flac(head)
            head.startsWith(OGG_MAGIC) -> ogg(head)
            head.startsWith(RIFF_MAGIC) -> riff(head)
            head.startsWith(MATROSKA_MAGIC) -> matroska(head)
            head.isMp4() -> mp4(head)
            else -> null
        }
        return found?.takeIf { it.isNotBlank() }
    }

    private fun open(context: Context, uri: Uri): InputStream? =
        if (uri.scheme == "file") {
            uri.path?.let { File(it).takeIf(File::exists)?.inputStream() }
        } else {
            context.contentResolver.openInputStream(uri)
        }

    // ---- MP4 / M4A ----------------------------------------------------------

    /**
     * The `©lyr` atom's text, or this app's freeform one where it is present.
     *
     * Both live under `moov/udta/meta/ilst`, and the search is scoped to `moov`
     * rather than run over the file: a four-byte pattern turns up in audio data
     * often enough that scanning the whole thing would eventually read a frame
     * as a tag. Inside `moov` the same pattern is a tag or it is nothing.
     */
    private fun mp4(bytes: ByteArray): String? {
        val moov = topLevelBox(bytes, "moov") ?: return null
        // Exclusive, and deliberately so: the lyrics are the last item written
        // into `ilst`, so their value ends exactly on `moov`'s own end — an
        // inclusive bound here rejects the one atom this is looking for.
        val end = moov.last + 1
        return ilstText(bytes, moov.first, end, freeform = true)
            ?: ilstText(bytes, moov.first, end, freeform = false)
    }

    /**
     * The bounds of a top-level box, without walking into it.
     *
     * `moov` is not required to come before the audio — a file written without
     * the faststart pass puts it after `mdat` — so this steps box to box rather
     * than assuming a position.
     */
    private fun topLevelBox(bytes: ByteArray, type: String): IntRange? {
        var pos = 0
        while (pos + 8 <= bytes.size) {
            val declared = readU32(bytes, pos)
            var headerLen = 8
            var size = declared
            if (declared == 1L) {
                if (pos + 16 > bytes.size) return null
                size = readU64(bytes, pos + 8)
                headerLen = 16
            } else if (declared == 0L) {
                size = (bytes.size - pos).toLong()
            }
            if (size < headerLen || size > Int.MAX_VALUE) return null
            val end = (pos + size).toInt().coerceAtMost(bytes.size)
            if (String(bytes, pos + 4, 4, Charsets.ISO_8859_1) == type) return pos until end
            pos += size.toInt()
        }
        return null
    }

    /**
     * The text of the lyrics item inside [within].
     *
     * [freeform] picks which of the two: this app's `----` item, whose name is
     * carried in a `name` box beside the value, or the standard `©lyr`, whose
     * four-byte type *is* the name. They are stored differently enough that one
     * search cannot find both.
     */
    private fun ilstText(bytes: ByteArray, from: Int, endExclusive: Int, freeform: Boolean): String? {
        val marker = if (freeform) WORD_LYRICS_FIELD.toByteArray(Charsets.UTF_8) else LYR_ATOM
        var at = from
        while (true) {
            val found = bytes.indexOf(marker, at, endExclusive) ?: return null
            // The value is the first `data` box after the name, in both layouts:
            // a freeform item is mean/name/data, a standard one is type/data.
            val data = bytes.indexOf(DATA_ATOM, found, endExclusive) ?: return null
            dataText(bytes, data, endExclusive)?.let { return it }
            at = found + marker.size
        }
    }

    /**
     * An iTunes `data` box's payload as text.
     *
     * The box is version/flags(4) + locale(4) + the value, and the length in
     * front of it is what says where the value stops — a lyric sheet has
     * newlines in it and nothing else terminates it.
     */
    private fun dataText(bytes: ByteArray, dataAt: Int, endExclusive: Int): String? {
        val start = dataAt - 4
        if (start < 0 || dataAt + 12 > endExclusive) return null
        val size = readU32(bytes, start).toInt()
        if (size <= 16 || start + size > endExclusive) return null
        // Type indicator 1 is UTF-8 text; a cover's 13/14 is the other thing a
        // `data` box holds, and decoding a JPEG as a string is not a lyric.
        if (readU32(bytes, dataAt + 4).toInt() != 1) return null
        return String(bytes, dataAt + 12, start + size - (dataAt + 12), Charsets.UTF_8)
    }

    // ---- FLAC ---------------------------------------------------------------

    /**
     * The `LYRICS` (or [WORD_LYRICS_FIELD]) comment out of the `VORBIS_COMMENT` block.
     *
     * Every length in the block is **little-endian** — it reuses Ogg Vorbis'
     * layout, which is the one part of FLAC that isn't big-endian.
     */
    private fun flac(bytes: ByteArray): String? {
        var pos = FLAC_MAGIC.size
        while (pos + 4 <= bytes.size) {
            val flags = bytes[pos].toInt() and 0xFF
            val length = ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
            val start = pos + 4
            if (start + length > bytes.size) return null
            if (flags and 0x7F == FLAC_VORBIS_COMMENT) {
                return vorbisComment(bytes, start, start + length)
            }
            if (flags and 0x80 != 0) return null
            pos = start + length
        }
        return null
    }

    private fun vorbisComment(bytes: ByteArray, start: Int, end: Int): String? {
        var pos = start
        fun u32(): Int? {
            if (pos + 4 > end) return null
            val v = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 16) or ((bytes[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }
        val vendor = u32() ?: return null
        pos += vendor
        val count = u32() ?: return null
        var plain: String? = null
        repeat(count.coerceAtMost(4_096)) {
            val length = u32() ?: return plain
            if (length < 0 || pos + length > end) return plain
            val entry = String(bytes, pos, length, Charsets.UTF_8)
            pos += length
            val name = entry.substringBefore('=').uppercase()
            val value = entry.substringAfter('=', "")
            // This app's own field wins outright; the standard one is held in
            // case it turns out to be the only one there.
            if (name == WORD_LYRICS_FIELD.uppercase() && value.isNotBlank()) return value
            if (name.isLyricsTag() && plain == null && value.isNotBlank()) plain = value
        }
        return plain
    }

    // ---- ID3 (MP3, WAV, DSF and other ID3-tagged files) --------------------

    /** ID3v2 unsynchronised or synchronised lyrics, plus common TXXX lyric fields. */
    private fun id3(bytes: ByteArray): String? {
        if (bytes.size < 10) return null
        val version = bytes[3].toInt() and 0xFF
        if (version !in 2..4) return null
        val flags = bytes[5].toInt() and 0xFF
        var position = 10
        val tagSize = readSynchsafe(bytes, 6) ?: return null
        val end = minOf(bytes.size, 10 + tagSize)
        if (flags and 0x40 != 0) {
            val extendedSize = when (version) {
                3 -> if (position + 4 <= end) readU32(bytes, position).toInt() else return null
                4 -> readSynchsafe(bytes, position) ?: return null
                else -> 0
            }
            position += if (version == 3) 4 + extendedSize else extendedSize
        }
        while (position < end) {
            val headerSize = if (version == 2) 6 else 10
            if (position + headerSize > end) break
            val idLength = if (version == 2) 3 else 4
            val frameId = String(bytes, position, idLength, Charsets.ISO_8859_1)
            if (frameId.all { it == '\u0000' }) break
            val frameSize = when (version) {
                2 -> ((bytes[position + 3].toInt() and 0xFF) shl 16) or
                    ((bytes[position + 4].toInt() and 0xFF) shl 8) or (bytes[position + 5].toInt() and 0xFF)
                4 -> readSynchsafe(bytes, position + 4) ?: break
                else -> readU32(bytes, position + 4).toInt()
            }
            if (frameSize <= 0 || position + headerSize + frameSize > end) break
            val frameFlags = if (version == 2) 0 else
                ((bytes[position + 8].toInt() and 0xFF) shl 8) or (bytes[position + 9].toInt() and 0xFF)
            val payloadStart = position + headerSize
            var payload = bytes.copyOfRange(payloadStart, payloadStart + frameSize)
            if ((flags and 0x80 != 0) || (version == 4 && frameFlags and 0x02 != 0)) {
                payload = payload.removeUnsynchronization()
            }
            val lyric = when (frameId) {
                "USLT", "ULT" -> id3Text(payload, hasLanguage = true)
                "SYLT", "SLT" -> synchronizedId3Text(payload)
                "TXXX", "TXX" -> userTextLyrics(payload)
                else -> null
            }
            if (!lyric.isNullOrBlank()) return lyric
            position += headerSize + frameSize
        }
        return null
    }

    private fun id3Text(payload: ByteArray, hasLanguage: Boolean): String? {
        if (payload.isEmpty()) return null
        val encoding = payload[0].toInt() and 0xFF
        val descriptionStart = if (hasLanguage) 4 else 1
        if (payload.size < descriptionStart) return null
        val terminator = findTextTerminator(payload, descriptionStart, encoding)
        val textStart = if (terminator >= 0) terminator + terminatorWidth(encoding) else descriptionStart
        return decodeId3Text(payload, textStart, payload.size, encoding).trim()
            .takeIf { it.isNotBlank() }
    }

    private fun userTextLyrics(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val encoding = payload[0].toInt() and 0xFF
        val start = 1
        val terminator = findTextTerminator(payload, start, encoding)
        if (terminator < 0) return null
        val field = decodeId3Text(payload, start, terminator, encoding).trim()
        if (!field.isLyricsTag()) return null
        return decodeId3Text(payload, terminator + terminatorWidth(encoding), payload.size, encoding)
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun synchronizedId3Text(payload: ByteArray): String? {
        if (payload.size < 7) return null
        val encoding = payload[0].toInt() and 0xFF
        val timestampFormat = payload[4].toInt() and 0xFF
        var position = findTextTerminator(payload, 6, encoding)
        if (position < 0) return null
        position += terminatorWidth(encoding)
        val rows = ArrayList<String>()
        while (position < payload.size) {
            val end = findTextTerminator(payload, position, encoding)
            if (end < 0) break
            val text = decodeId3Text(payload, position, end, encoding).trim()
            position = end + terminatorWidth(encoding)
            if (position + 4 > payload.size) break
            val stamp = readU32(payload, position).toInt().toLong()
            position += 4
            if (text.isNotBlank()) {
                val millis = if (timestampFormat == 1) stamp * 1_000L / 75L else stamp
                val minutes = millis / 60_000L
                val seconds = millis / 1_000L % 60L
                val fraction = millis % 1_000L
                rows += "[%02d:%02d.%03d]%s".format(Locale.ROOT, minutes, seconds, fraction, text)
            }
        }
        return rows.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun findTextTerminator(bytes: ByteArray, from: Int, encoding: Int): Int {
        val width = terminatorWidth(encoding)
        var position = from
        while (position + width <= bytes.size) {
            if (bytes[position] == 0.toByte() &&
                (width == 1 || bytes[position + 1] == 0.toByte())
            ) return position
            position += width
        }
        return -1
    }

    private fun terminatorWidth(encoding: Int) = if (encoding == 1 || encoding == 2) 2 else 1

    private fun decodeId3Text(bytes: ByteArray, start: Int, end: Int, encoding: Int): String {
        if (start >= end) return ""
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.UTF_8
        }
        return String(bytes, start, end - start, charset).trim('\u0000', '\uFEFF')
    }

    private fun ByteArray.removeUnsynchronization(): ByteArray {
        // Remove an inserted 00 only when it immediately follows FF.
        val compact = ByteArray(size)
        var write = 0
        var read = 0
        while (read < size) {
            compact[write++] = this[read]
            if (this[read] == 0xFF.toByte() && read + 1 < size && this[read + 1] == 0.toByte()) read++
            read++
        }
        return compact.copyOf(write)
    }

    private fun readSynchsafe(bytes: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 4 > bytes.size) return null
        var value = 0
        repeat(4) { index ->
            val byte = bytes[offset + index].toInt() and 0xFF
            if (byte and 0x80 != 0) return null
            value = (value shl 7) or byte
        }
        return value
    }

    // ---- Ogg Vorbis / Opus comments ----------------------------------------

    private fun ogg(bytes: ByteArray): String? {
        val packets = listOf("OpusTags".toByteArray(Charsets.US_ASCII),
            byteArrayOf(3) + "vorbis".toByteArray(Charsets.US_ASCII))
        for (packet in packets) {
            var from = 0
            while (true) {
                val marker = bytes.indexOf(packet, from, bytes.size) ?: break
                val start = marker + packet.size
                vorbisComment(bytes, start, bytes.size)?.let { return it }
                from = start
            }
        }
        return null
    }

    private fun String.isLyricsTag(): Boolean {
        val key = uppercase(Locale.ROOT).replace('_', ' ').replace('-', ' ').trim()
        return key == "LYRICS" || key == "UNSYNCED LYRICS" || key.startsWith("LYRICS ") ||
            key.startsWith("UNSYNCED LYRICS ") || key.startsWith("UNSYNCEDLYRICS") ||
            key == WORD_LYRICS_FIELD.uppercase(Locale.ROOT)
    }

    // ---- RIFF/WAVE ---------------------------------------------------------

    /** WAV files can carry the same ID3 lyric frames as MP3 inside an ID3 chunk. */
    private fun riff(bytes: ByteArray): String? {
        if (bytes.size < 12 || !bytes.copyOfRange(8, 12).contentEquals(WAVE_MAGIC)) return null
        var position = 12
        while (position + 8 <= bytes.size) {
            val chunkId = String(bytes, position, 4, Charsets.ISO_8859_1)
            val length = readU32(bytes, position + 4).toInt()
            val dataStart = position + 8
            if (length < 0 || dataStart.toLong() + length > bytes.size) return null
            if (chunkId.equals("id3 ", ignoreCase = true)) {
                id3(bytes.copyOfRange(dataStart, dataStart + length))?.let { return it }
            }
            position = dataStart + length + (length and 1)
        }
        return null
    }

    /** Reads trailing tags used by DSF/DFF, APE and MP4 files whose metadata is at EOF. */
    private fun readTailTags(context: Context, uri: Uri): String? = runCatching {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return@runCatching null
        descriptor.use { pfd ->
            val length = pfd.statSize
            if (length <= 0L) return@use null
            val count = minOf(length, MAX_TAG_BYTES.toLong()).toInt()
            FileInputStream(pfd.fileDescriptor).use { stream ->
                val channel = stream.channel
                channel.position((length - count).coerceAtLeast(0L))
                val buffer = ByteBuffer.allocate(count)
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) break
                }
                readTrailingTags(buffer.array().copyOf(buffer.position()))
            }
        }
    }.onFailure { Log.d(TAG, "could not read trailing lyric tags: ${it.message}") }.getOrNull()

    private fun readTrailingTags(bytes: ByteArray): String? {
        // DSD and several WAV/MP3 tools append an ID3 tag after their audio payload.
        var marker = bytes.size - ID3_MAGIC.size
        while (marker >= 0) {
            if (bytes[marker] == ID3_MAGIC[0] && bytes[marker + 1] == ID3_MAGIC[1] &&
                bytes[marker + 2] == ID3_MAGIC[2]
            ) {
                id3(bytes.copyOfRange(marker, bytes.size))?.let { return it }
            }
            marker--
        }

        parseApeLyrics(bytes)?.let { return it }
        parseLyrics3(bytes)?.let { return it }

        // A non-faststart MP4 puts moov after mdat. Parse its complete box from
        // the tail window instead of requiring it to fit in the head read.
        val moov = "moov".toByteArray(Charsets.ISO_8859_1)
        marker = bytes.indexOf(moov, 0, bytes.size) ?: return null
        while (marker >= 4) {
            val start = marker - 4
            val size = readU32(bytes, start).toInt()
            if (size >= 8 && start.toLong() + size <= bytes.size) {
                mp4(bytes.copyOfRange(start, start + size))?.let { return it }
            }
            marker = bytes.indexOf(moov, marker + moov.size, bytes.size) ?: break
        }
        return null
    }

    private fun parseApeLyrics(bytes: ByteArray): String? {
        val signature = "APETAGEX".toByteArray(Charsets.US_ASCII)
        var footer = bytes.size - signature.size
        while (footer >= 0) {
            footer = bytes.indexOf(signature, footer, bytes.size) ?: return null
            if (footer + 32 <= bytes.size) {
                val tagSize = readU32LittleEndian(bytes, footer + 12)
                val itemCount = readU32LittleEndian(bytes, footer + 16)
                val start = footer + 32 - tagSize
                if (tagSize >= 32 && start >= 0 && itemCount in 1..100_000) {
                    var position = start
                    var fallback: String? = null
                    repeat(itemCount) {
                        if (position + 8 > footer) return fallback
                        val valueSize = readU32LittleEndian(bytes, position)
                        position += 8 // value length and item flags
                        val keyStart = position
                        while (position < footer && bytes[position] != 0.toByte()) position++
                        if (position >= footer || valueSize < 0 || valueSize > footer - position - 1) return fallback
                        val key = String(bytes, keyStart, position - keyStart, Charsets.ISO_8859_1)
                        position++
                        val value = String(bytes, position, valueSize, Charsets.UTF_8).trim('\u0000', '\uFEFF')
                        position += valueSize
                        if (key.equals(WORD_LYRICS_FIELD, ignoreCase = true) && value.isNotBlank()) return value
                        if (key.isLyricsTag() && fallback == null && value.isNotBlank()) fallback = value
                    }
                    fallback?.let { return it }
                }
            }
            footer += signature.size
        }
        return null
    }

    private fun parseLyrics3(bytes: ByteArray): String? {
        val footer = "LYRICS200".toByteArray(Charsets.US_ASCII)
        val at = bytes.lastIndexOf(footer) ?: return null
        if (at < 6) return null
        val tagLength = String(bytes, at - 6, 6, Charsets.US_ASCII).toIntOrNull() ?: return null
        val start = at - 6 - tagLength
        if (start < 11 || !bytes.copyOfRange(start - 11, start).contentEquals("LYRICSBEGIN".toByteArray())) return null
        var position = start
        val end = at - 6
        while (position + 8 <= end) {
            val id = String(bytes, position, 3, Charsets.US_ASCII)
            val length = String(bytes, position + 3, 5, Charsets.US_ASCII).toIntOrNull() ?: return null
            position += 8
            if (position + length > end) return null
            if (id == "LYR") {
                return String(bytes, position, length, Charsets.ISO_8859_1).trim().takeIf { it.isNotBlank() }
            }
            position += length
        }
        return null
    }

    private fun readU32LittleEndian(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    // ---- Matroska / WebM ----------------------------------------------------

    /**
     * The `LYRICS` SimpleTag's string.
     *
     * Scanned for rather than walked down to: the tags a download writes are
     * appended after everything else (see `WebmTagger`), so reaching them
     * properly would mean parsing the whole Segment — every cluster of audio —
     * to arrive at the last few hundred bytes. The name is matched inside a
     * `TagName` element and the value read out of the `TagString` that follows,
     * so this is looking at tag structure rather than guessing at loose bytes.
     */
    private fun matroska(bytes: ByteArray): String? {
        var plain: String? = null
        for (name in listOf(WORD_LYRICS_FIELD, "LYRICS")) {
            val needle = name.toByteArray(Charsets.US_ASCII)
            var from = 0
            while (true) {
                val at = bytes.indexOf(needle, from, bytes.size) ?: break
                from = at + needle.size
                // The name element's own header sits immediately in front of it:
                // id(2) + a one-byte length for a name this short.
                if (at < 3 || bytes[at - 3] != ID_TAGNAME[0] || bytes[at - 2] != ID_TAGNAME[1]) continue
                if ((bytes[at - 1].toInt() and 0x7F) != needle.size) continue
                val string = bytes.indexOf(ID_TAGSTRING, from, bytes.size) ?: continue
                val size = readVint(bytes, string + 2) ?: continue
                val valueAt = string + 2 + size.width
                if (size.value <= 0 || valueAt + size.value > bytes.size) continue
                val value = String(bytes, valueAt, size.value.toInt(), Charsets.UTF_8)
                if (value.isBlank()) continue
                if (name == WORD_LYRICS_FIELD) return value
                if (plain == null) plain = value
            }
        }
        return plain
    }

    private class Vint(val value: Long, val width: Int)

    /**
     * An EBML variable-length integer: the highest set bit of the first byte
     * gives the width, and the bits after it are the value.
     */
    private fun readVint(bytes: ByteArray, offset: Int): Vint? {
        if (offset >= bytes.size) return null
        val first = bytes[offset].toInt() and 0xFF
        if (first == 0) return null
        var width = 1
        var mask = 0x80
        while (first and mask == 0) {
            mask = mask shr 1
            width++
        }
        if (offset + width > bytes.size) return null
        var value = (first and mask.inv() and 0xFF).toLong()
        for (i in 1 until width) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return Vint(value, width)
    }

    // ---- Bytes --------------------------------------------------------------

    /** Reads up to [max] bytes, which is all of a normal file and a prefix of a huge one. */
    private fun InputStream.readAtMost(max: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(max, 1 shl 16))
        val buffer = ByteArray(1 shl 16)
        var total = 0
        while (total < max) {
            val read = read(buffer, 0, minOf(buffer.size, max - total))
            if (read <= 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }

    /** `ftyp` at offset 4 is what says "this is an MP4" — there is no leading magic. */
    private fun ByteArray.isMp4(): Boolean =
        size > 12 && this[4] == 'f'.code.toByte() && this[5] == 't'.code.toByte() &&
            this[6] == 'y'.code.toByte() && this[7] == 'p'.code.toByte()

    private fun ByteArray.indexOf(needle: ByteArray, from: Int, until: Int): Int? {
        if (needle.isEmpty()) return null
        val last = minOf(until, size) - needle.size
        var i = from.coerceAtLeast(0)
        outer@ while (i <= last) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return null
    }

    private fun ByteArray.lastIndexOf(needle: ByteArray): Int? {
        if (needle.isEmpty() || needle.size > size) return null
        for (start in size - needle.size downTo 0) {
            if (needle.indices.all { this[start + it] == needle[it] }) return start
        }
        return null
    }

    private fun readU32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    private fun readU64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private const val FLAC_VORBIS_COMMENT = 4
    private val ID3_MAGIC = "ID3".toByteArray(Charsets.US_ASCII)
    private val FLAC_MAGIC = "fLaC".toByteArray(Charsets.US_ASCII)
    private val OGG_MAGIC = "OggS".toByteArray(Charsets.US_ASCII)
    private val RIFF_MAGIC = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WAVE_MAGIC = "WAVE".toByteArray(Charsets.US_ASCII)
    private val MATROSKA_MAGIC = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
    private val DATA_ATOM = "data".toByteArray(Charsets.ISO_8859_1)
    private val LYR_ATOM = byteArrayOf(0xA9.toByte()) + "lyr".toByteArray(Charsets.ISO_8859_1)
    private val ID_TAGNAME = byteArrayOf(0x45, 0xA3.toByte())
    private val ID_TAGSTRING = byteArrayOf(0x44, 0x87.toByte())
}
