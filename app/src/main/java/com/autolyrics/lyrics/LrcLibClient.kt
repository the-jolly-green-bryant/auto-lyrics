package com.autolyrics.lyrics

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object LrcLibClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private const val BASE_URL = "https://lrclib.net/api"
    private const val USER_AGENT = "AutoLyrics v1.0 (https://github.com/user/auto-lyrics)"

    data class LrcLibResponse(
        @SerializedName("id") val id: Int?,
        @SerializedName("trackName") val trackName: String?,
        @SerializedName("artistName") val artistName: String?,
        @SerializedName("albumName") val albumName: String?,
        @SerializedName("duration") val duration: Int?,
        @SerializedName("instrumental") val instrumental: Boolean?,
        @SerializedName("plainLyrics") val plainLyrics: String?,
        @SerializedName("syncedLyrics") val syncedLyrics: String?,
        val matchedAlternateArtist: Boolean = false
    )

    fun getLyrics(
        trackName: String,
        artistName: String,
        albumName: String,
        durationSec: Int
    ): LrcLibResponse? {
        // Priority 1: exact match with synced lyrics
        if (durationSec > 0 && albumName.isNotBlank()) {
            val exact = getExact(trackName, artistName, albumName, durationSec)
            if (exact?.syncedLyrics != null) return exact
        }

        // Priority 2: search for synced lyrics (duration-guarded)
        val searchResults = searchAll(trackName, artistName)
        val syncedResult = searchResults.firstOrNull {
            it.syncedLyrics != null && withinDuration(it, durationSec)
        }
        if (syncedResult != null) return syncedResult

        // Priority 3: exact match without album for synced lyrics
        if (durationSec > 0) {
            val exactNoAlbum = getExact(trackName, artistName, "", durationSec)
            if (exactNoAlbum?.syncedLyrics != null) return exactNoAlbum
        }

        // Priority 4: exact match with any lyrics (including plain)
        if (durationSec > 0 && albumName.isNotBlank()) {
            val exact = getExact(trackName, artistName, albumName, durationSec)
            if (exact?.plainLyrics != null) return exact
        }

        // Priority 5: search result with plain lyrics (duration-guarded)
        val plainResult = searchResults.firstOrNull {
            it.plainLyrics != null && withinDuration(it, durationSec)
        }
        if (plainResult != null) return plainResult

        // Traditional songs and covers often have no entry for the exact artist.
        // Conservatively borrow the closest-duration recording with the exact
        // same title, keeping the tolerance tight to limit arrangement mismatch.
        if (artistName.isNotBlank() && durationSec > 0) {
            val alternateResults = searchAll(trackName, "")
                .filter {
                    it.trackName.equals(trackName, ignoreCase = true) &&
                        it.instrumental != true &&
                        withinDuration(it, durationSec)
                }
                .sortedBy { kotlin.math.abs((it.duration ?: durationSec) - durationSec) }

            alternateResults.firstOrNull { it.syncedLyrics != null }?.let {
                return it.copy(matchedAlternateArtist = true)
            }
            alternateResults.firstOrNull { it.plainLyrics != null }?.let {
                return it.copy(matchedAlternateArtist = true)
            }
        }

        return null
    }

    private fun withinDuration(result: LrcLibResponse, durationSec: Int): Boolean {
        if (durationSec <= 0 || result.duration == null) return true
        return kotlin.math.abs(result.duration - durationSec) <= 10
    }

    private fun getExact(
        trackName: String,
        artistName: String,
        albumName: String,
        durationSec: Int
    ): LrcLibResponse? {
        val urlBuilder = "$BASE_URL/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", trackName)
            .addQueryParameter("artist_name", artistName)
            .addQueryParameter("album_name", albumName)
            .addQueryParameter("duration", durationSec.toString())

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", USER_AGENT)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    gson.fromJson(response.body?.string(), LrcLibResponse::class.java)
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun searchAll(trackName: String, artistName: String): List<LrcLibResponse> {
        val urlBuilder = "$BASE_URL/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", trackName)

        if (artistName.isNotBlank()) {
            urlBuilder.addQueryParameter("artist_name", artistName)
        }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", USER_AGENT)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val type = object : TypeToken<List<LrcLibResponse>>() {}.type
                    gson.fromJson(response.body?.string(), type) ?: emptyList()
                } else emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
