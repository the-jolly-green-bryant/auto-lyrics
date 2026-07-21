package com.autolyrics.media

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.autolyrics.lyrics.LrcLibClient
import com.autolyrics.lyrics.LrcParser
import com.autolyrics.lyrics.LyricsCache
import com.autolyrics.lyrics.LyricsTranslator
import com.autolyrics.lyrics.MetadataCleaner
import com.autolyrics.lyrics.SyncLrcClient
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus
import com.autolyrics.model.TrackInfo
import com.autolyrics.util.AlbumColorExtractor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

class MediaTracker private constructor(context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences =
        context.getSharedPreferences("auto_lyrics_prefs", Context.MODE_PRIVATE)
    private val lyricsCache = LyricsCache(context)

    private val _state = MutableStateFlow(LyricsState())
    val state: StateFlow<LyricsState> = _state.asStateFlow()

    private var activeController: MediaController? = null
    private var lastPositionMs: Long = 0
    private var lastPositionUpdateTime: Long = 0
    private var playbackSpeed: Float = 1.0f
    private var fetchJob: Job? = null
    private var prefetchJob: Job? = null
    private var artJob: Job? = null
    private var translationJob: Job? = null
    private var pendingTrack: TrackInfo? = null
    private var pendingArt: Bitmap? = null
    private var lyricsOffsetMs: Long = 0L

    init {
        _state.value = _state.value.copy(offsetMs = lyricsOffsetMs)
    }

    private val positionChecker = object : Runnable {
        override fun run() {
            updateCurrentPosition()
            if (_state.value.isPlaying) {
                handler.postDelayed(this, 150)
            }
        }
    }

    private val trackChangeRunnable = Runnable {
        val track = pendingTrack ?: return@Runnable
        val art = pendingArt
        val current = _state.value.track
        if (sameTrack(track, current)) return@Runnable

        translationJob?.cancel()
        lyricsOffsetMs = savedOffsetFor(track)
        _state.value = _state.value.copy(
            track = track,
            lines = emptyList(),
            currentIndex = -1,
            currentWordIndex = -1,
            status = LyricsStatus.LOADING,
            source = "",
            albumArt = art,
            albumColors = null,
            translatedLines = null,
            detectedLanguage = null,
            offsetMs = lyricsOffsetMs
        )
        fetchLyrics(track)
        extractAlbumColors(art)
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            handleMetadataChanged(metadata)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            handlePlaybackStateChanged(state)
        }

        override fun onSessionDestroyed() {
            onMediaSessionChanged(null)
        }
    }

    fun adjustOffset(deltaMs: Long) {
        lyricsOffsetMs += deltaMs
        saveOffsetForCurrentTrack()
        _state.value = _state.value.copy(offsetMs = lyricsOffsetMs)
        updateCurrentPosition()
    }

    fun resetOffset() {
        lyricsOffsetMs = 0L
        saveOffsetForCurrentTrack()
        _state.value = _state.value.copy(offsetMs = lyricsOffsetMs)
        updateCurrentPosition()
    }

    fun retryCurrentLyrics() {
        val track = _state.value.track ?: return
        lyricsCache.remove(track.title, track.artist, track.spotifyUri)
        _state.value = _state.value.copy(
            status = LyricsStatus.LOADING,
            source = "",
            lines = emptyList(),
            currentIndex = -1,
            currentWordIndex = -1
        )
        fetchLyrics(track)
    }

    fun resumePlayback() {
        activeController?.transportControls?.play()
    }

    fun setOffset(ms: Long) {
        lyricsOffsetMs = ms
        saveOffsetForCurrentTrack()
        _state.value = _state.value.copy(offsetMs = lyricsOffsetMs)
        updateCurrentPosition()
    }

    fun getCurrentPositionMs(): Long {
        val basePos = if (!_state.value.isPlaying) {
            lastPositionMs
        } else {
            val elapsed = SystemClock.elapsedRealtime() - lastPositionUpdateTime
            lastPositionMs + (elapsed * playbackSpeed).toLong()
        }
        return basePos + lyricsOffsetMs
    }

    /**
     * Accepts low-frequency state snapshots from Spotify App Remote. The regular
     * position checker extrapolates between snapshots, so Spotify is never polled
     * at karaoke refresh frequency.
     */
    fun onSpotifyPlayerState(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        spotifyUri: String,
        positionMs: Long,
        isPaused: Boolean
    ) {
        val track = TrackInfo(
            MetadataCleaner.cleanTitle(title),
            MetadataCleaner.cleanArtist(artist),
            MetadataCleaner.cleanAlbum(album),
            durationMs,
            spotifyUri
        )
        val current = _state.value.track
        if (!sameTrack(track, current)) {
            pendingTrack = track
            pendingArt = _state.value.albumArt
            handler.removeCallbacks(trackChangeRunnable)
            handler.post(trackChangeRunnable)
        }

        lastPositionMs = positionMs
        lastPositionUpdateTime = SystemClock.elapsedRealtime()
        playbackSpeed = 1.0f
        _state.value = _state.value.copy(isPlaying = !isPaused)
        handler.removeCallbacks(positionChecker)
        if (!isPaused) handler.post(positionChecker)
    }

    private fun sameTrack(first: TrackInfo?, second: TrackInfo?): Boolean {
        if (first == null || second == null) return false
        if (!first.spotifyUri.isNullOrBlank() || !second.spotifyUri.isNullOrBlank()) {
            return !first.spotifyUri.isNullOrBlank() &&
                !second.spotifyUri.isNullOrBlank() &&
                first.spotifyUri == second.spotifyUri
        }
        return first.title.equals(second.title, ignoreCase = true) &&
            first.artist.equals(second.artist, ignoreCase = true) &&
            first.album.equals(second.album, ignoreCase = true) &&
            durationsMatch(first.durationMs, second.durationMs)
    }

    private fun durationsMatch(first: Long, second: Long): Boolean {
        if (first <= 0L || second <= 0L) return true
        return kotlin.math.abs(first - second) < 2_000L
    }

    private fun savedOffsetFor(track: TrackInfo): Long {
        val spotifyKey = track.spotifyUri?.takeIf { it.isNotBlank() }?.let(::hashedOffsetKey)
        return if (spotifyKey != null && prefs.contains(spotifyKey)) {
            prefs.getLong(spotifyKey, 0L)
        } else {
            prefs.getLong(metadataOffsetKey(track), 0L)
        }
    }

    private fun saveOffsetForCurrentTrack() {
        val track = _state.value.track ?: return
        val editor = prefs.edit().putLong(metadataOffsetKey(track), lyricsOffsetMs)
        track.spotifyUri?.takeIf { it.isNotBlank() }?.let {
            editor.putLong(hashedOffsetKey(it), lyricsOffsetMs)
        }
        editor.apply()
    }

    private fun metadataOffsetKey(track: TrackInfo): String = hashedOffsetKey(
        listOf(
            track.title.lowercase().trim(),
            track.artist.lowercase().trim(),
            track.album.lowercase().trim(),
            track.durationMs.toString()
        ).joinToString("|")
    )

    private fun hashedOffsetKey(identity: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "lyrics_offset_$digest"
    }

    private fun updateCurrentPosition() {
        val currentState = _state.value
        val lines = currentState.lines
        if (lines.isEmpty() || currentState.status != LyricsStatus.FOUND) return

        val posMs = getCurrentPositionMs()

        var newLineIndex = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= posMs) {
                newLineIndex = i
            } else {
                break
            }
        }

        var newWordIndex = -1
        if (newLineIndex >= 0) {
            val words = lines[newLineIndex].words
            if (words.isNotEmpty()) {
                for (i in words.indices) {
                    if (words[i].timeMs <= posMs) {
                        newWordIndex = i
                    } else {
                        break
                    }
                }
            }
        }

        if (newLineIndex != currentState.currentIndex || newWordIndex != currentState.currentWordIndex) {
            _state.value = currentState.copy(
                currentIndex = newLineIndex,
                currentWordIndex = newWordIndex
            )
        }
    }

    fun onMediaSessionChanged(controller: MediaController?) {
        activeController?.unregisterCallback(mediaCallback)
        activeController = controller

        if (controller == null) {
            handler.removeCallbacks(positionChecker)
            handler.removeCallbacks(trackChangeRunnable)
            artJob?.cancel()
            lyricsOffsetMs = 0L
            _state.value = LyricsState(offsetMs = 0L)
            return
        }

        controller.registerCallback(mediaCallback)
        handleMetadataChanged(controller.metadata)
        handlePlaybackStateChanged(controller.playbackState)
    }

    private fun handleMetadataChanged(metadata: MediaMetadata?) {
        if (metadata == null) return

        val rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return
        val rawArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val rawAlbum = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val title = MetadataCleaner.cleanTitle(rawTitle)
        val artist = MetadataCleaner.cleanArtist(rawArtist)
        val album = MetadataCleaner.cleanAlbum(rawAlbum)

        val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

        val newTrack = TrackInfo(title, artist, album, duration)
        val current = _state.value.track

        if (sameTrack(newTrack, current)) {
            if (art != null && _state.value.albumArt == null) {
                _state.value = _state.value.copy(albumArt = art)
                extractAlbumColors(art)
            }
            return
        }

        pendingTrack = newTrack
        pendingArt = art
        handler.removeCallbacks(trackChangeRunnable)
        handler.postDelayed(trackChangeRunnable, 600)
    }

    private fun extractAlbumColors(bitmap: Bitmap?) {
        artJob?.cancel()
        if (bitmap == null) return
        artJob = scope.launch(Dispatchers.Default) {
            val colors = AlbumColorExtractor.extract(bitmap)
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(albumColors = colors)
            }
        }
    }

    private fun handlePlaybackStateChanged(pbState: PlaybackState?) {
        if (pbState == null) return

        val isPlaying = pbState.state == PlaybackState.STATE_PLAYING
        lastPositionMs = pbState.position
        lastPositionUpdateTime = pbState.lastPositionUpdateTime
        if (lastPositionUpdateTime == 0L) {
            lastPositionUpdateTime = SystemClock.elapsedRealtime()
        }
        playbackSpeed = if (pbState.playbackSpeed > 0) pbState.playbackSpeed else 1.0f

        _state.value = _state.value.copy(isPlaying = isPlaying)

        handler.removeCallbacks(positionChecker)
        if (isPlaying) {
            handler.post(positionChecker)
        }
    }

    private fun fetchLyrics(track: TrackInfo) {
        fetchJob?.cancel()
        fetchJob = scope.launch(Dispatchers.IO) {
            try {
                val cached = lyricsCache.get(track.title, track.artist, track.spotifyUri)
                if (cached != null) {
                    val (lines, status, source) = cached
                    withContext(Dispatchers.Main) {
                        if (_state.value.track != track) return@withContext
                        _state.value = _state.value.copy(
                            lines = lines,
                            currentIndex = -1,
                            currentWordIndex = -1,
                            status = status,
                            source = "$source (cached)"
                        )
                        if (status == LyricsStatus.FOUND) {
                            updateCurrentPosition()
                        }
                        translateIfNeeded(lines, track)
                    }

                    prefetchNextSong()
                    return@launch
                }

                val result = fetchBestLyricsWithRetry(track)

                withContext(Dispatchers.Main) {
                    if (_state.value.track != track) return@withContext

                    if (result != null) {
                        lyricsCache.put(
                            track.title,
                            track.artist,
                            result.lines,
                            result.status,
                            result.source,
                            track.spotifyUri
                        )
                        _state.value = _state.value.copy(
                            lines = result.lines,
                            currentIndex = -1,
                            currentWordIndex = -1,
                            status = result.status,
                            source = result.source
                        )
                        if (result.status == LyricsStatus.FOUND) {
                            updateCurrentPosition()
                        }
                        translateIfNeeded(result.lines, track)
                    } else {
                        lyricsCache.putMissing(track.title, track.artist, track.spotifyUri)
                        _state.value = _state.value.copy(
                            status = LyricsStatus.NOT_FOUND,
                            source = ""
                        )
                    }
                }

                prefetchNextSong()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    if (_state.value.track == track && _state.value.status == LyricsStatus.LOADING) {
                        _state.value = _state.value.copy(
                            status = LyricsStatus.ERROR,
                            source = ""
                        )
                    }
                }
            }
        }
    }

    private fun prefetchNextSong() {
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            try {
                val controller = activeController ?: return@launch
                val queue = controller.queue ?: return@launch
                val currentTitle = _state.value.track?.title ?: return@launch
                val currentArtist = _state.value.track?.artist ?: return@launch

                var foundCurrent = false
                for (item in queue) {
                    val desc = item.description
                    val title = MetadataCleaner.cleanTitle(desc.title?.toString() ?: continue)
                    val artist = MetadataCleaner.cleanArtist(desc.subtitle?.toString() ?: "")

                    if (!foundCurrent) {
                        if (title.equals(currentTitle, ignoreCase = true) &&
                            artist.equals(currentArtist, ignoreCase = true)
                        ) {
                            foundCurrent = true
                        }
                        continue
                    }

                    if (lyricsCache.get(title, artist) != null) return@launch

                    val nextTrack = TrackInfo(title, artist, "", 0)
                    val result = fetchBestLyricsWithRetry(nextTrack)
                    if (result != null) {
                        lyricsCache.put(title, artist, result.lines, result.status, result.source)
                    }
                    return@launch
                }
            } catch (_: Exception) {
                // prefetch failures are non-fatal
            }
        }
    }

    private data class FetchResult(
        val lines: List<LyricLine>,
        val status: LyricsStatus,
        val source: String
    )

    private fun fetchBestLyrics(track: TrackInfo): FetchResult? {
        val syncLrc = fetchFromSyncLrc(track)
        // Karaoke and line-synced lyrics are already the best useful outcome.
        if (syncLrc?.status == LyricsStatus.FOUND) return syncLrc

        // A later provider's synced result beats an earlier provider's plain text.
        val lrcLib = fetchFromLrcLib(track)
        if (lrcLib?.status == LyricsStatus.FOUND) return lrcLib
        return syncLrc ?: lrcLib
    }

    private suspend fun fetchBestLyricsWithRetry(track: TrackInfo): FetchResult? {
        repeat(LYRICS_FETCH_ATTEMPTS) { attempt ->
            val result = fetchBestLyrics(track)
            if (result != null) return result
            if (attempt < LYRICS_FETCH_ATTEMPTS - 1) {
                delay(LYRICS_RETRY_DELAYS_MS[attempt])
            }
        }
        return null
    }

    private fun fetchFromSyncLrc(track: TrackInfo): FetchResult? {
        val result = try {
            SyncLrcClient.getLyrics(track.title, track.artist)
        } catch (_: Exception) {
            null
        } ?: return null

        return when (result.type) {
            SyncLrcClient.LyricsType.KARAOKE -> {
                val lines = LrcParser.parseKaraoke(result.lyrics)
                val hasRealText = lines.any { it.text != "♪" && it.text.isNotBlank() }
                if (hasRealText) FetchResult(lines, LyricsStatus.FOUND, "SyncLRC · Karaoke")
                else null
            }
            SyncLrcClient.LyricsType.SYNCED -> {
                val lines = LrcParser.parse(result.lyrics)
                val hasRealText = lines.any { it.text != "♪" && it.text.isNotBlank() }
                if (hasRealText) FetchResult(lines, LyricsStatus.FOUND, "SyncLRC · Synced")
                else null
            }
            SyncLrcClient.LyricsType.PLAIN -> {
                val lines = result.lyrics.lines()
                    .filter { it.isNotBlank() }
                    .map { text -> LyricLine(0L, text) }
                if (lines.isNotEmpty()) FetchResult(lines, LyricsStatus.PLAIN_ONLY, "SyncLRC · Plain")
                else null
            }
        }
    }

    private fun fetchFromLrcLib(track: TrackInfo): FetchResult? {
        val durationSec = if (track.durationMs > 0) (track.durationMs / 1000).toInt() else 0

        val result = try {
            LrcLibClient.getLyrics(
                trackName = track.title,
                artistName = track.artist,
                albumName = track.album,
                durationSec = durationSec
            )
        } catch (_: Exception) {
            null
        } ?: return null

        if (result.syncedLyrics != null) {
            val lines = LrcParser.parse(result.syncedLyrics)
            val hasRealText = lines.any { it.text != "♪" && it.text.isNotBlank() }
            if (hasRealText) {
                val source = if (result.matchedAlternateArtist) {
                    "LRCLIB · Synced · Alternate recording"
                } else {
                    "LRCLIB · Synced"
                }
                return FetchResult(lines, LyricsStatus.FOUND, source)
            }
        }

        if (result.plainLyrics != null) {
            val lines = result.plainLyrics.lines()
                .filter { it.isNotBlank() }
                .map { text -> LyricLine(0L, text) }
            if (lines.isNotEmpty()) {
                val source = if (result.matchedAlternateArtist) {
                    "LRCLIB · Plain · Alternate recording"
                } else {
                    "LRCLIB · Plain"
                }
                return FetchResult(lines, LyricsStatus.PLAIN_ONLY, source)
            }
        }

        return null
    }

    private fun translateIfNeeded(lines: List<LyricLine>, track: TrackInfo) {
        if (!prefs.getBoolean("translation_enabled", true)) return
        translationJob?.cancel()
        translationJob = scope.launch(Dispatchers.IO) {
            try {
                val result = LyricsTranslator.translateLines(lines) ?: return@launch
                withContext(Dispatchers.Main) {
                    if (_state.value.track == track) {
                        _state.value = _state.value.copy(
                            translatedLines = result.translatedLines,
                            detectedLanguage = result.detectedLanguage
                        )
                    }
                }
            } catch (_: Exception) { }
        }
    }

    companion object {
        private const val LYRICS_FETCH_ATTEMPTS = 3
        private val LYRICS_RETRY_DELAYS_MS = longArrayOf(750L, 2_000L)

        @Volatile
        private var instance: MediaTracker? = null

        fun init(context: Context) {
            getInstance(context)
        }

        fun getInstance(context: Context): MediaTracker {
            return instance ?: synchronized(this) {
                instance ?: MediaTracker(context.applicationContext).also { instance = it }
            }
        }
    }
}
