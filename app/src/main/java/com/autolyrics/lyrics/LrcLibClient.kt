package com.autolyrics.lyrics

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object LrcLibClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private const val BASE_URL = "https://lrclib.net/api"
    private const val USER_AGENT =
        "com.autolyrics/1.9.6 (https://github.com/the-jolly-green-bryant/auto-lyrics)"
    private const val TAG = "LrcLibClient"

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

    data class LookupResult(
        val value: LrcLibResponse?,
        val hadDefinitiveResponse: Boolean,
        val hadUnavailableResponse: Boolean
    )

    private data class RequestResult<T>(
        val value: T?,
        val definitive: Boolean,
        val unavailable: Boolean
    )

    fun getLyrics(
        trackName: String,
        artistName: String,
        albumName: String,
        durationSec: Int
    ): LookupResult {
        var hadDefinitiveResponse = false
        var hadUnavailableResponse = false

        fun <T> record(result: RequestResult<T>): T? {
            hadDefinitiveResponse = hadDefinitiveResponse || result.definitive
            hadUnavailableResponse = hadUnavailableResponse || result.unavailable
            return result.value
        }

        // Fetch each query shape at most once. Repeating exact calls made an
        // upstream outage multiply into long UI stalls.
        val exact = if (durationSec > 0 && albumName.isNotBlank()) {
            record(getExact(trackName, artistName, albumName, durationSec))
        } else null
        if (exact?.syncedLyrics != null) {
            return LookupResult(exact, hadDefinitiveResponse, hadUnavailableResponse)
        }

        // Priority 2: search for synced lyrics (duration-guarded)
        val searchResults = record(searchAll(trackName, artistName)).orEmpty()
        val syncedResult = searchResults
            .asSequence()
            .filter {
                it.syncedLyrics != null &&
                    titlesEquivalent(it.trackName.orEmpty(), trackName) &&
                    withinDuration(it, durationSec)
            }
            .minByOrNull { kotlin.math.abs((it.duration ?: durationSec) - durationSec) }
        if (syncedResult != null) {
            return LookupResult(syncedResult, hadDefinitiveResponse, hadUnavailableResponse)
        }

        // Traditional songs and covers often have no entry for the exact artist.
        // Conservatively borrow the closest-duration recording with the exact
        // same title, keeping the tolerance tight to limit arrangement mismatch.
        if (artistName.isNotBlank() && durationSec > 0) {
            val alternateResults = record(searchAll(trackName, "")).orEmpty()
                .filter {
                    titlesEquivalent(it.trackName.orEmpty(), trackName) &&
                        it.instrumental != true &&
                        withinDuration(it, durationSec)
                }
                .sortedBy { kotlin.math.abs((it.duration ?: durationSec) - durationSec) }

            alternateResults.firstOrNull { it.syncedLyrics != null }?.let {
                return LookupResult(
                    it.copy(matchedAlternateArtist = true),
                    hadDefinitiveResponse,
                    hadUnavailableResponse
                )
            }
        }

        return LookupResult(null, hadDefinitiveResponse, hadUnavailableResponse)
    }

    private fun withinDuration(result: LrcLibResponse, durationSec: Int): Boolean {
        if (durationSec <= 0 || result.duration == null) return true
        return kotlin.math.abs(result.duration - durationSec) <= MAX_DURATION_DIFFERENCE_SECONDS
    }

    private fun titlesEquivalent(left: String, right: String): Boolean {
        fun normalize(value: String): String = value
            .lowercase()
            .replace(
                Regex(
                    """\s*[\(\[].*?\b(remaster(ed)?|re-?recorded|new recording|live|version|edit|mix)\b.*?[\)\]]"""
                ),
                ""
            )
            .replace(
                Regex(
                    """\s+-\s+(remaster(ed)?|re-?recorded|new recording|live|.*?version|.*?edit|.*?mix).*$"""
                ),
                ""
            )
            .replace(Regex("""[^a-z0-9]+"""), "")
        return normalize(left) == normalize(right)
    }

    private fun getExact(
        trackName: String,
        artistName: String,
        albumName: String,
        durationSec: Int
    ): RequestResult<LrcLibResponse> {
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
                    RequestResult(
                        gson.fromJson(response.body?.string(), LrcLibResponse::class.java),
                        definitive = true,
                        unavailable = false
                    )
                } else if (response.code == 404) {
                    RequestResult(null, definitive = true, unavailable = false)
                } else {
                    Log.w(TAG, "Exact lookup returned HTTP ${response.code}")
                    RequestResult(null, definitive = false, unavailable = true)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exact lookup failed", e)
            RequestResult(null, definitive = false, unavailable = true)
        }
    }

    private fun searchAll(trackName: String, artistName: String): RequestResult<List<LrcLibResponse>> {
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
                    RequestResult(
                        gson.fromJson(response.body?.string(), type) ?: emptyList(),
                        definitive = true,
                        unavailable = false
                    )
                } else if (response.code == 404) {
                    RequestResult(emptyList(), definitive = true, unavailable = false)
                } else {
                    Log.w(TAG, "Search lookup returned HTTP ${response.code}")
                    RequestResult(null, definitive = false, unavailable = true)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Search lookup failed", e)
            RequestResult(null, definitive = false, unavailable = true)
        }
    }

    private const val MAX_DURATION_DIFFERENCE_SECONDS = 60
}
