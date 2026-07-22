package com.autolyrics.auto

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import com.autolyrics.media.MediaTracker
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class LyricsBrowserService : MediaBrowserServiceCompat() {

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var mediaTracker: MediaTracker
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private var lastNotifyTime = 0L
    private var pendingNotify = false
    private var displayedWindowStart = -1
    private var displayedWindowEnd = -1
    private var displayedCurrentIdx = -1
    private var displayedStatus: LyricsStatus? = null
    private var displayedTrackTitle: String? = null
    private var lastSubtitleText: String? = null
    private var lastAlbumArt: Bitmap? = null

    private var aaKaraokeEnabled = true
    private var aaOffsetMs = 0L

    private var lastKaraokeLineIdx = -1
    private var lastKaraokeWordIdx = -1
    private var lastKaraokeText: String? = null

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            when (key) {
                "aa_karaoke_enabled" -> {
                    aaKaraokeEnabled = sp.getBoolean(key, true)
                    forceRefresh()
                }
                "aa_offset_ms" -> {
                    aaOffsetMs = sp.getLong(key, 0L)
                    forceRefresh()
                }
            }
        }

    companion object {
        private const val ROOT_ID = "root"
        private const val WINDOW_SIZE = 6
        private const val PLAIN_WINDOW_SIZE = 6
        private const val PAD_WIDTH = 60
        private const val NOTIFY_THROTTLE_MS = 500L
        private const val BROWSE_KARAOKE_WINDOW_MS = 600L
        private const val SUBTITLE_KARAOKE_WINDOW_MS = 300L
        private const val SESSION_REFRESH_MS = 1500L
        private const val PLAIN_LOOP_DELAY_MS = 2000L
    }

    override fun onCreate() {
        super.onCreate()
        mediaTracker = MediaTracker.getInstance(this)

        val prefs = getSharedPreferences("auto_lyrics_prefs", MODE_PRIVATE)
        aaKaraokeEnabled = prefs.getBoolean("aa_karaoke_enabled", true)
        aaOffsetMs = prefs.getLong("aa_offset_ms", 0L)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        mediaSession = MediaSessionCompat(this, "AutoLyrics").apply {
            setCallback(SessionCallback())
            isActive = true
        }
        sessionToken = mediaSession.sessionToken

        scope.launch {
            mediaTracker.state.collectLatest { state ->
                updateMediaSession(state)
                throttledNotifyChildren(state)
            }
        }

        scope.launch {
            while (isActive) {
                delay(200)
                updateSubtitleKaraoke()
            }
        }

        scope.launch {
            while (isActive) {
                delay(PLAIN_LOOP_DELAY_MS)
                val state = mediaTracker.state.value
                if (state.isPlaying && state.status == LyricsStatus.PLAIN_ONLY && state.lines.isNotEmpty()) {
                    throttledNotifyChildren(state)
                }
            }
        }

        scope.launch {
            while (isActive) {
                delay(SESSION_REFRESH_MS)
                val state = mediaTracker.state.value
                if (state.isPlaying && state.track != null) {
                    mediaSession.isActive = true
                    mediaSession.setPlaybackState(buildPlaybackState(state))
                }
            }
        }
    }

    override fun onDestroy() {
        getSharedPreferences("auto_lyrics_prefs", MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        scope.cancel()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        val state = mediaTracker.state.value
        val items = mutableListOf<MediaBrowserCompat.MediaItem>()

        when (state.status) {
            LyricsStatus.NO_MEDIA -> {
                items.add(buildTextItem("no_media", "Play a song to see lyrics"))
            }
            LyricsStatus.LOADING -> {
                items.add(buildTextItem("loading", "Loading lyrics…"))
            }
            LyricsStatus.NOT_FOUND -> {
                items.add(buildTextItem("not_found", "No lyrics found for this track"))
            }
            LyricsStatus.ERROR -> {
                items.add(buildTextItem("error", "Lyrics services unavailable — try again"))
            }
            LyricsStatus.FOUND -> {
                buildWindowedLyrics(state, items)
            }
            LyricsStatus.PLAIN_ONLY -> {
                if (state.lines.isEmpty()) {
                    items.add(buildTextItem("empty", "♪"))
                } else {
                    val durationMs = state.track?.durationMs ?: 0
                    val posMs = try {
                        mediaTracker.getCurrentPositionMs().coerceAtLeast(0)
                    } catch (_: Exception) { 0L }

                    val estimatedIdx = if (durationMs > 0) {
                        ((posMs.toFloat() / durationMs) * state.lines.size).toInt()
                            .coerceIn(0, state.lines.size - 1)
                    } else { 0 }

                    val winStart = estimatedIdx
                    val winEnd = minOf(state.lines.size, winStart + PLAIN_WINDOW_SIZE)

                    for (i in winStart until winEnd) {
                        val text = state.lines[i].text.ifBlank { "♪" }
                        val trans = state.translatedLines?.getOrNull(i)?.takeIf { it.isNotBlank() }
                        items.add(buildTextItem("line_$i", "    $text", pad = true, subtitle = trans))
                    }
                }
            }
        }

        result.sendResult(items)
    }

    private fun buildWindowedLyrics(
        state: LyricsState,
        items: MutableList<MediaBrowserCompat.MediaItem>
    ) {
        val lines = state.lines
        if (lines.isEmpty()) {
            items.add(buildTextItem("empty", "♪"))
            return
        }

        val posMs = getAaPositionMs()
        val aaCurrentIdx = findLineIndex(lines, posMs).coerceAtLeast(0)
        val windowStart = aaCurrentIdx
        val windowEnd = minOf(lines.size, windowStart + WINDOW_SIZE)

        for (i in windowStart until windowEnd) {
            val line = lines[i]
            val isCurrent = i == aaCurrentIdx
            val prefix = if (isCurrent) "▶  " else "    "

            val text = if (isCurrent && aaKaraokeEnabled && line.words.isNotEmpty()) {
                buildKaraokeText(line, i, posMs, BROWSE_KARAOKE_WINDOW_MS)
            } else {
                line.text.ifBlank { "♪" }
            }

            val trans = state.translatedLines?.getOrNull(i)?.takeIf { it.isNotBlank() }
            items.add(buildTextItem("line_$i", "$prefix$text", pad = true, subtitle = trans))
        }
    }

    private fun buildTextItem(id: String, text: String, pad: Boolean = false, subtitle: String? = null): MediaBrowserCompat.MediaItem {
        val title = if (pad) text.padEnd(PAD_WIDTH) else text
        val builder = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
        if (!subtitle.isNullOrBlank()) builder.setSubtitle(subtitle)
        return MediaBrowserCompat.MediaItem(
            builder.build(),
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )
    }

    // --- Karaoke helpers ---

    private fun getAaPositionMs(): Long {
        return try {
            mediaTracker.getCurrentPositionMs() + aaOffsetMs
        } catch (_: Exception) {
            0L
        }
    }

    private fun findLineIndex(lines: List<LyricLine>, posMs: Long): Int {
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= posMs) idx = i
            else break
        }
        return idx
    }

    private fun buildKaraokeText(line: LyricLine, lineIdx: Int, posMs: Long, windowMs: Long): String {
        val words = line.words
        if (words.isEmpty()) return line.text

        var currentIdx = -1
        for (i in words.indices) {
            if (words[i].timeMs <= posMs) currentIdx = i
            else break
        }
        if (currentIdx < 0) return line.text

        val sameLine = lineIdx == lastKaraokeLineIdx
        if (sameLine && currentIdx <= lastKaraokeWordIdx && lastKaraokeText != null) {
            return lastKaraokeText!!
        }

        var endIdx = currentIdx
        for (i in (currentIdx + 1) until words.size) {
            if (words[i].timeMs <= posMs + windowMs) endIdx = i
            else break
        }

        if (sameLine) {
            endIdx = maxOf(endIdx, lastKaraokeWordIdx)
        }

        lastKaraokeLineIdx = lineIdx
        lastKaraokeWordIdx = currentIdx
        val sb = StringBuilder()
        for (i in words.indices) {
            if (i == currentIdx) sb.append("【")
            sb.append(words[i].text)
            if (i == endIdx) sb.append("】")
            if (i < words.size - 1) sb.append(" ")
        }
        lastKaraokeText = sb.toString()
        return lastKaraokeText!!
    }

    private fun resetKaraokeState() {
        lastKaraokeLineIdx = -1
        lastKaraokeWordIdx = -1
        lastKaraokeText = null
    }

    private fun getSubtitleText(state: LyricsState): String {
        val posMs = getAaPositionMs()

        if (state.status == LyricsStatus.FOUND) {
            val lineIdx = findLineIndex(state.lines, posMs)
            val line = state.lines.getOrNull(lineIdx)
            if (line != null) {
                val original = if (aaKaraokeEnabled && line.words.isNotEmpty()) {
                    buildKaraokeText(line, lineIdx, posMs, SUBTITLE_KARAOKE_WINDOW_MS)
                } else {
                    line.text
                }
                val trans = state.translatedLines?.getOrNull(lineIdx)?.takeIf { it.isNotBlank() }
                return if (trans != null) "$original\n$trans" else original
            }
        }

        if (state.status == LyricsStatus.PLAIN_ONLY && state.lines.isNotEmpty()) {
            val durationMs = state.track?.durationMs ?: 0
            val idx = if (durationMs > 0) {
                ((posMs.toFloat() / durationMs) * state.lines.size).toInt()
                    .coerceIn(0, state.lines.size - 1)
            } else { 0 }
            val original = state.lines[idx].text
            val trans = state.translatedLines?.getOrNull(idx)?.takeIf { it.isNotBlank() }
            return if (trans != null) "$original\n$trans" else original
        }

        return when (state.status) {
            LyricsStatus.LOADING -> "Loading lyrics…"
            LyricsStatus.NOT_FOUND -> "No lyrics found"
            LyricsStatus.ERROR -> "Lyrics services unavailable"
            else -> ""
        }
    }

    // --- MediaSession management ---

    private fun buildBaseMetadata(state: LyricsState): MediaMetadataCompat.Builder {
        val metaBuilder = MediaMetadataCompat.Builder()

        state.track?.let { track ->
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.album)
            if (track.durationMs > 0) {
                metaBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track.durationMs)
            }

        }

        val art = state.albumArt ?: lastAlbumArt
        if (art != null) {
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
            lastAlbumArt = art
        }

        return metaBuilder
    }

    private fun updateMediaSession(state: LyricsState) {
        val trackChanged = state.track?.title != displayedTrackTitle
        if (trackChanged) {
            mediaSession.isActive = true
            lastSubtitleText = null
            lastAlbumArt = null
            resetKaraokeState()
        }

        val metaBuilder = buildBaseMetadata(state)

        val subtitleText = getSubtitleText(state)
        if (subtitleText.isNotBlank()) {
            metaBuilder.putString(
                MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
                subtitleText
            )
        }
        lastSubtitleText = subtitleText

        mediaSession.setMetadata(metaBuilder.build())
        mediaSession.setPlaybackState(buildPlaybackState(state))
    }

    private fun updateSubtitleKaraoke() {
        val state = mediaTracker.state.value
        if (!state.isPlaying) return
        if (state.status != LyricsStatus.FOUND && state.status != LyricsStatus.PLAIN_ONLY) return

        val subtitleText = getSubtitleText(state)
        if (subtitleText == lastSubtitleText) return
        lastSubtitleText = subtitleText

        val metaBuilder = buildBaseMetadata(state)
        if (subtitleText.isNotBlank()) {
            metaBuilder.putString(
                MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
                subtitleText
            )
        }
        mediaSession.setMetadata(metaBuilder.build())
        mediaSession.setPlaybackState(buildPlaybackState(state))
    }

    private fun buildPlaybackState(state: LyricsState): PlaybackStateCompat {
        val pbState = if (state.isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else if (state.track != null) {
            PlaybackStateCompat.STATE_PAUSED
        } else {
            PlaybackStateCompat.STATE_NONE
        }

        val position = try {
            mediaTracker.getCurrentPositionMs()
        } catch (_: Exception) {
            0L
        }

        val primaryAction = if (state.isPlaying) {
            PlaybackStateCompat.ACTION_PAUSE
        } else {
            PlaybackStateCompat.ACTION_PLAY
        }

        return PlaybackStateCompat.Builder()
            .setState(pbState, position, if (state.isPlaying) 1.0f else 0f)
            .setActions(
                primaryAction or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
            .build()
    }

    // --- Throttled browse tree updates ---

    private data class WindowInfo(val start: Int, val end: Int, val currentIdx: Int)

    private fun computeWindow(state: LyricsState): WindowInfo {
        val lines = state.lines
        if (lines.isEmpty()) return WindowInfo(-1, -1, -1)

        val isPlain = state.status == LyricsStatus.PLAIN_ONLY
        val currentIdx = if (isPlain) {
            val durationMs = state.track?.durationMs ?: 0
            val posMs = try {
                mediaTracker.getCurrentPositionMs().coerceAtLeast(0)
            } catch (_: Exception) { 0L }
            if (durationMs > 0) {
                ((posMs.toFloat() / durationMs) * lines.size).toInt()
                    .coerceIn(0, lines.size - 1)
            } else 0
        } else {
            val posMs = getAaPositionMs()
            findLineIndex(lines, posMs).coerceAtLeast(0)
        }

        val winSize = if (isPlain) PLAIN_WINDOW_SIZE else WINDOW_SIZE
        val windowStart = currentIdx
        val windowEnd = minOf(lines.size, windowStart + winSize)
        return WindowInfo(windowStart, windowEnd, currentIdx)
    }

    private fun forceRefresh() {
        displayedWindowStart = -1
        displayedWindowEnd = -1
        displayedCurrentIdx = -1
        lastNotifyTime = 0L
        resetKaraokeState()
        notifyChildrenChanged(ROOT_ID)
    }

    private fun throttledNotifyChildren(state: LyricsState) {
        val statusChanged = state.status != displayedStatus
        val trackChanged = state.track?.title != displayedTrackTitle

        if (trackChanged) {
            displayedWindowStart = -1
            displayedWindowEnd = -1
            displayedCurrentIdx = -1
            displayedStatus = state.status
            displayedTrackTitle = state.track?.title
            lastNotifyTime = System.currentTimeMillis()
            handler.removeCallbacksAndMessages(null)
            pendingNotify = false
            resetKaraokeState()
            notifyChildrenChanged(ROOT_ID)
            return
        }

        val win = computeWindow(state)
        val windowChanged = win.start != displayedWindowStart || win.end != displayedWindowEnd
        val lineChanged = win.currentIdx != displayedCurrentIdx
        val karaokeActive = aaKaraokeEnabled && state.status == LyricsStatus.FOUND
            && state.lines.getOrNull(state.currentIndex)?.words?.isNotEmpty() == true

        if (!windowChanged && !lineChanged && !statusChanged && !karaokeActive) return

        displayedWindowStart = win.start
        displayedWindowEnd = win.end
        displayedCurrentIdx = win.currentIdx
        displayedStatus = state.status

        val now = System.currentTimeMillis()
        val elapsed = now - lastNotifyTime

        if (elapsed >= NOTIFY_THROTTLE_MS) {
            lastNotifyTime = now
            notifyChildrenChanged(ROOT_ID)
        } else if (!pendingNotify) {
            pendingNotify = true
            handler.postDelayed({
                pendingNotify = false
                lastNotifyTime = System.currentTimeMillis()
                notifyChildrenChanged(ROOT_ID)
            }, NOTIFY_THROTTLE_MS - elapsed)
        }
    }

    private inner class SessionCallback : MediaSessionCompat.Callback() {
        override fun onPlay() {
            mediaTracker.resumePlayback()
        }

        override fun onPause() {
            mediaTracker.pausePlayback()
        }

        override fun onSkipToNext() {
            mediaTracker.skipToNext()
        }

        override fun onSkipToPrevious() {
            mediaTracker.skipToPrevious()
        }

        override fun onStop() {
            mediaTracker.stopPlayback()
        }

        override fun onSeekTo(pos: Long) {
            mediaTracker.seekTo(pos)
        }

        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            // Auto Lyrics mirrors the active player's catalog rather than
            // owning one, so voice play requests resume that active session.
            mediaTracker.resumePlayback()
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            if (mediaId == null) return

            if (!mediaId.startsWith("line_")) return
            val index = mediaId.removePrefix("line_").toIntOrNull() ?: return
            val state = mediaTracker.state.value
            if (state.status != LyricsStatus.FOUND) return
            val line = state.lines.getOrNull(index) ?: return
            if (line.timeMs > 0) {
                mediaTracker.seekTo(line.timeMs)
            }
        }
    }
}
