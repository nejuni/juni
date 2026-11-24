package com.nejuni.juni.playback

import android.content.Context
import android.content.SharedPreferences
import com.nejuni.juni.data.model.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A small on-device history of local files that have actually started playing. */
object LocalListeningHistory {
    private lateinit var preferences: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }

    fun init(context: Context) {
        preferences = context.getSharedPreferences("juni_local_listening_history", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun record(song: Song) {
        if (song.localUri == null) return
        val updated = (songs() + song).takeLast(MAX_ENTRIES)
        val encoded = runCatching {
            json.encodeToString(ListSerializer(StoredTrack.serializer()), updated.map(StoredTrack::from))
        }.getOrNull() ?: return
        preferences.edit().putString(KEY_HISTORY, encoded).apply()
    }

    /** Drops every entry for [song]'s file — the History page's own removal. */
    @Synchronized
    fun remove(song: Song) {
        val uri = song.localUri ?: return
        val remaining = songs().filterNot { it.localUri == uri }
        val encoded = runCatching {
            json.encodeToString(ListSerializer(StoredTrack.serializer()), remaining.map(StoredTrack::from))
        }.getOrNull() ?: return
        preferences.edit().putString(KEY_HISTORY, encoded).apply()
    }
    @Synchronized
    fun songs(): List<Song> {
        val stored = preferences.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(StoredTrack.serializer()), stored).map(StoredTrack::toSong)
        }
            .getOrDefault(emptyList())
    }

    @Serializable
    private data class StoredTrack(
        val id: String,
        val title: String,
        val artist: String,
        val artwork: String? = null,
        val duration: String? = null,
        val album: String? = null,
        val localUri: String,
        val localPath: String? = null,
    ) {
        fun toSong() = Song(
            videoId = id,
            title = title,
            artist = artist,
            thumbnailUrl = artwork,
            durationText = duration,
            albumName = album,
            localUri = localUri,
            localPath = localPath,
        )

        companion object {
            fun from(song: Song) = StoredTrack(
                id = song.videoId,
                title = song.title,
                artist = song.artist,
                artwork = song.thumbnailUrl,
                duration = song.durationText,
                album = song.albumName,
                localUri = requireNotNull(song.localUri),
                localPath = song.localPath,
            )
        }
    }

    private const val KEY_HISTORY = "tracks"
    private const val MAX_ENTRIES = 100
}
