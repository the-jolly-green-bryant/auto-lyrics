package com.autolyrics.spotify

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.autolyrics.BuildConfig
import com.autolyrics.media.MediaTracker
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.PlayerState

class SpotifyRemoteManager(
    private val activity: Activity,
    private val mediaTracker: MediaTracker,
    private val onStatusChanged: (Status) -> Unit
) {
    enum class Status { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    private var remote: SpotifyAppRemote? = null
    private var playerStateSubscription: Subscription<PlayerState>? = null
    private val handler = Handler(Looper.getMainLooper())
    private val connectionTimeout = Runnable {
        if (remote?.isConnected != true) onStatusChanged(Status.ERROR)
    }

    fun connect(showAuthView: Boolean = true) {
        if (remote?.isConnected == true) {
            onStatusChanged(Status.CONNECTED)
            return
        }

        onStatusChanged(Status.CONNECTING)
        handler.removeCallbacks(connectionTimeout)
        handler.postDelayed(connectionTimeout, CONNECTION_TIMEOUT_MS)
        val params = ConnectionParams.Builder(BuildConfig.SPOTIFY_CLIENT_ID)
            .setRedirectUri(BuildConfig.SPOTIFY_REDIRECT_URI)
            .showAuthView(showAuthView)
            .build()

        SpotifyAppRemote.connect(activity, params, object : Connector.ConnectionListener {
            override fun onConnected(spotifyAppRemote: SpotifyAppRemote) {
                handler.removeCallbacks(connectionTimeout)
                remote = spotifyAppRemote
                onStatusChanged(Status.CONNECTED)
                subscribeToPlayerState()
            }

            override fun onFailure(throwable: Throwable) {
                handler.removeCallbacks(connectionTimeout)
                Log.e(TAG, "Spotify App Remote connection failed", throwable)
                remote = null
                onStatusChanged(Status.ERROR)
            }
        })
    }

    private fun subscribeToPlayerState() {
        playerStateSubscription?.cancel()
        val subscription = remote?.playerApi?.subscribeToPlayerState() ?: return
        playerStateSubscription = subscription
        subscription.setEventCallback { state ->
                val track = state.track ?: return@setEventCallback
                mediaTracker.onSpotifyPlayerState(
                    title = track.name.orEmpty(),
                    artist = track.artist?.name.orEmpty(),
                    album = track.album?.name.orEmpty(),
                    durationMs = track.duration,
                    spotifyUri = track.uri.orEmpty(),
                    positionMs = state.playbackPosition,
                    isPaused = state.isPaused
                )
            }
        subscription.setErrorCallback {
                onStatusChanged(Status.ERROR)
            }
    }

    fun disconnect() {
        handler.removeCallbacks(connectionTimeout)
        playerStateSubscription?.cancel()
        playerStateSubscription = null
        remote?.let(SpotifyAppRemote::disconnect)
        remote = null
        onStatusChanged(Status.DISCONNECTED)
    }

    companion object {
        private const val TAG = "SpotifyRemote"
        private const val CONNECTION_TIMEOUT_MS = 15_000L
    }
}
