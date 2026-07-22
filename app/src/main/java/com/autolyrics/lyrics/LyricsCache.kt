package com.autolyrics.lyrics

import android.content.Context
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricWord
import com.autolyrics.model.LyricsStatus
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class LyricsCache(context: Context) {

    private val cacheDir = File(context.filesDir, "lyrics_cache")
    private val gson = Gson()

    init {
        cacheDir.mkdirs()
    }

    data class CachedResult(
        val lines: List<CachedLine>,
        val status: String,
        val source: String,
        val timestamp: Long,
        val schemaVersion: Int = 0
    )

    data class CachedLine(
        val timeMs: Long,
        val text: String,
        val words: List<CachedWord>
    )

    data class CachedWord(
        val timeMs: Long,
        val text: String
    )

    fun get(title: String, artist: String, spotifyUri: String? = null): Triple<List<LyricLine>, LyricsStatus, String>? {
        val spotifyFile = cacheFile(title, artist, spotifyUri)
        val metadataFile = cacheFile(title, artist, null)
        val file = when {
            spotifyFile.exists() -> spotifyFile
            metadataFile.exists() -> metadataFile
            else -> return null
        }

        return try {
            val json = file.readText()
            val cached = gson.fromJson(json, CachedResult::class.java)
            val status = try {
                LyricsStatus.valueOf(cached.status)
            } catch (_: Exception) {
                return null
            }
            if (status == LyricsStatus.NOT_FOUND &&
                (cached.schemaVersion < CACHE_SCHEMA_VERSION ||
                    System.currentTimeMillis() - cached.timestamp >= NEGATIVE_CACHE_MS)
            ) {
                file.delete()
                return null
            }
            val lines = cached.lines.map { cl ->
                LyricLine(
                    timeMs = cl.timeMs,
                    text = cl.text,
                    words = cl.words.map { cw -> LyricWord(cw.timeMs, cw.text) }
                )
            }
            Triple(lines, status, cached.source)
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    fun put(
        title: String,
        artist: String,
        lines: List<LyricLine>,
        status: LyricsStatus,
        source: String,
        spotifyUri: String? = null
    ) {
        try {
            val cached = CachedResult(
                lines = lines.map { line ->
                    CachedLine(
                        timeMs = line.timeMs,
                        text = line.text,
                        words = line.words.map { w -> CachedWord(w.timeMs, w.text) }
                    )
                },
                status = status.name,
                source = source,
                timestamp = System.currentTimeMillis(),
                schemaVersion = CACHE_SCHEMA_VERSION
            )
            val json = gson.toJson(cached)
            cacheFile(title, artist, spotifyUri).writeText(json)
            if (!spotifyUri.isNullOrBlank()) {
                // Also index by metadata so the same cached lyrics work when the
                // Spotify connection is unavailable.
                cacheFile(title, artist, null).writeText(json)
            }
        } catch (_: Exception) {
            // cache write failures are non-fatal
        }
    }

    fun putMissing(title: String, artist: String, spotifyUri: String? = null) {
        put(title, artist, emptyList(), LyricsStatus.NOT_FOUND, "", spotifyUri)
    }

    fun remove(title: String, artist: String, spotifyUri: String? = null) {
        cacheFile(title, artist, spotifyUri).delete()
        cacheFile(title, artist, null).delete()
    }

    private fun cacheFile(title: String, artist: String, spotifyUri: String?): File {
        val key = spotifyUri?.takeIf { it.isNotBlank() }
            ?: "${title.lowercase().trim()}|${artist.lowercase().trim()}"
        val hash = key.hashCode().toUInt().toString(16)
        return File(cacheDir, "$hash.json")
    }

    companion object {
        private const val NEGATIVE_CACHE_MS = 24L * 60 * 60 * 1000
        private const val CACHE_SCHEMA_VERSION = 2
    }
}
