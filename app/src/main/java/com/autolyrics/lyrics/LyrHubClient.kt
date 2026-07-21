package com.autolyrics.lyrics

import android.text.Html
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * Last-resort plain-lyrics lookup against public LyrHub track pages.
 *
 * LyrHub does not publish an API. This client intentionally avoids its
 * robots-excluded search routes and requests only a small set of predictable,
 * metadata-derived track URLs.
 */
object LyrHubClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private val originalLinePattern = Regex(
        """<div\s+class=["']orlangstr["'][^>]*>(.*?)</div>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val tagPattern = Regex("<[^>]+>")

    fun getLyrics(trackName: String, artistName: String): String? {
        val titleSlug = slug(trackName)
        val artistSlug = slug(artistName)
        if (titleSlug.isBlank() || artistSlug.isBlank()) return null

        for (candidateArtist in artistCandidates(artistSlug)) {
            val body = fetchTrackPage(candidateArtist, titleSlug) ?: continue
            if (!pageMatches(body, trackName, artistName)) continue

            val lines = originalLinePattern.findAll(body)
                .map { match -> decodeText(match.groupValues[1]) }
                .filter(String::isNotBlank)
                .toList()
            if (lines.isNotEmpty()) return lines.joinToString("\n")
        }
        return null
    }

    private fun fetchTrackPage(artistSlug: String, titleSlug: String): String? {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("lyrhub.com")
            .addPathSegment("en")
            .addPathSegment("track")
            .addPathSegment(artistSlug)
            .addPathSegment(titleSlug)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()
            }
        }.getOrNull()
    }

    private fun pageMatches(html: String, trackName: String, artistName: String): Boolean {
        val normalizedPage = normalize(html.substringBefore("</title>", html).substringAfterLast("<title>"))
        return normalizedPage.contains(normalize(trackName)) &&
            normalizedPage.contains(normalize(artistName))
    }

    private fun decodeText(html: String): String {
        val withoutTags = tagPattern.replace(html, "")
        return Html.fromHtml(withoutTags, Html.FROM_HTML_MODE_LEGACY)
            .toString()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun artistCandidates(base: String): List<String> = listOf(
        "$base-2",
        base,
        "$base-3",
        "$base-4"
    )

    private fun slug(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace("&", " and ")
        .replace(Regex("[^A-Za-z0-9]+"), "-")
        .trim('-')

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "")

    private const val USER_AGENT = "AutoLyrics/1.9.6 (+https://github.com/the-jolly-green-bryant/auto-lyrics)"
}
