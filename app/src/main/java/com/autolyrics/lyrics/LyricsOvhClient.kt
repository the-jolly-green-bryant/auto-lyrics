package com.autolyrics.lyrics

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Plain-lyrics fallback for tracks unavailable from the synced providers. */
object LyricsOvhClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    private data class Response(
        @SerializedName("lyrics") val lyrics: String?
    )

    fun getLyrics(trackName: String, artistName: String): String? {
        if (trackName.isBlank() || artistName.isBlank()) return null

        val url = HttpUrl.Builder()
            .scheme("https")
            .host("api.lyrics.ovh")
            .addPathSegment("v1")
            .addPathSegment(artistName)
            .addPathSegment(trackName)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                gson.fromJson(body, Response::class.java)?.lyrics?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    private const val USER_AGENT = "AutoLyrics/1.9.6"
}
