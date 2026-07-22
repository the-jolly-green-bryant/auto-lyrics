package com.autolyrics.lyrics

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object SyncLrcClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private const val BASE_URL = "https://synclrc.tharuk.pro"
    private const val USER_AGENT = "AutoLyrics v1.4 (https://github.com/efrayim-dev/auto-lyrics)"

    data class SyncLrcResponse(
        @SerializedName("id") val id: String?,
        @SerializedName("track") val track: String?,
        @SerializedName("artist") val artist: String?,
        @SerializedName("lyrics") val lyrics: String?,
        @SerializedName("type") val type: String?
    )

    enum class LyricsType(val value: String) {
        KARAOKE("karaoke"),
        SYNCED("synced"),
        PLAIN("plain")
    }

    data class SyncLrcResult(
        val lyrics: String,
        val type: LyricsType
    )

    data class LookupResult(
        val value: SyncLrcResult?,
        val hadDefinitiveResponse: Boolean,
        val hadUnavailableResponse: Boolean
    )

    fun getLyrics(trackName: String, artistName: String): LookupResult {
        var hadDefinitiveResponse = false
        var hadUnavailableResponse = false
        for (type in listOf(LyricsType.KARAOKE, LyricsType.SYNCED)) {
            when (val result = fetchLyrics(trackName, artistName, type)) {
                is RequestResult.Found -> return LookupResult(
                    result.value,
                    hadDefinitiveResponse = true,
                    hadUnavailableResponse = hadUnavailableResponse
                )
                RequestResult.NotFound -> hadDefinitiveResponse = true
                RequestResult.Unavailable -> hadUnavailableResponse = true
            }
        }
        return LookupResult(null, hadDefinitiveResponse, hadUnavailableResponse)
    }

    private sealed interface RequestResult {
        data class Found(val value: SyncLrcResult) : RequestResult
        data object NotFound : RequestResult
        data object Unavailable : RequestResult
    }

    private fun fetchLyrics(
        trackName: String,
        artistName: String,
        type: LyricsType
    ): RequestResult {
        val url = "$BASE_URL/lyrics".toHttpUrl().newBuilder()
            .addQueryParameter("track", trackName)
            .addQueryParameter("artist", artistName)
            .addQueryParameter("type", type.value)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.code == 404) return@use RequestResult.NotFound
                if (!response.isSuccessful) return@use RequestResult.Unavailable
                val body = response.body?.string() ?: return@use RequestResult.NotFound
                val parsed = gson.fromJson(body, SyncLrcResponse::class.java)
                    ?: return@use RequestResult.NotFound
                val lyrics = parsed.lyrics
                if (lyrics.isNullOrBlank()) return@use RequestResult.NotFound
                val actualType = when (parsed.type) {
                    "karaoke" -> LyricsType.KARAOKE
                    "synced" -> LyricsType.SYNCED
                    "plain" -> LyricsType.PLAIN
                    else -> type
                }
                RequestResult.Found(SyncLrcResult(lyrics, actualType))
            }
        } catch (_: Exception) {
            RequestResult.Unavailable
        }
    }
}
