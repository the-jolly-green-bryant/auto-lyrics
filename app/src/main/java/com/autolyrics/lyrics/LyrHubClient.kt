package com.autolyrics.lyrics

import android.text.Html
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * Last-resort plain-lyrics lookup using LyrHub's search and track pages.
 * Search access and the package-name User-Agent were approved by LyrHub support.
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
    private val searchResultPattern = Regex(
        """<a\s+class=["'][^"']*track-list[^"']*["'][^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val resultArtistPattern = Regex(
        """<div\s+class=["']search-artist-name["'][^>]*>(.*?)</div>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val resultTitlePattern = Regex(
        """<img\b[^>]*\balt=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )
    private val tagPattern = Regex("<[^>]+>")

    fun getLyrics(trackName: String, artistName: String): String? {
        findTrackPath(trackName, artistName)?.let { trackPath ->
            val body = fetchPage(trackPath) ?: return@let
            extractLyrics(body)?.let { return it }
        }

        // Retain the old direct-page guesses as a resilience fallback if the
        // search surface is temporarily unavailable.
        val titleSlug = slug(trackName)
        val artistSlug = slug(artistName)
        if (titleSlug.isBlank() || artistSlug.isBlank()) return null

        for (candidateArtist in artistCandidates(artistSlug)) {
            val body = fetchPage("/en/track/$candidateArtist/$titleSlug") ?: continue
            if (!pageMatches(body, trackName, artistName)) continue
            extractLyrics(body)?.let { return it }
        }
        return null
    }

    private fun findTrackPath(trackName: String, artistName: String): String? {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("lyrhub.com")
            .addPathSegment("en")
            .addPathSegment("Search")
            .addPathSegment("Search")
            .addQueryParameter("searchstr", "$trackName $artistName")
            .build()
        val html = fetchUrl(url) ?: return null
        val expectedTitle = normalize(trackName)
        val expectedArtist = normalize(artistName)

        return searchResultPattern.findAll(html).firstNotNullOfOrNull { result ->
            val block = result.groupValues[2]
            val title = resultTitlePattern.find(block)?.groupValues?.get(1)?.let(::decodeText)
            val artist = resultArtistPattern.find(block)?.groupValues?.get(1)?.let(::decodeText)
            result.groupValues[1].takeIf {
                normalize(title.orEmpty()) == expectedTitle &&
                    normalize(artist.orEmpty()) == expectedArtist &&
                    it.startsWith("/en/track/") &&
                    !it.contains("/translation/")
            }
        }
    }

    private fun fetchPage(path: String): String? {
        val url = "https://lyrhub.com$path".toHttpUrlOrNull() ?: return null
        return fetchUrl(url)
    }

    private fun fetchUrl(url: HttpUrl): String? {
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

    private fun extractLyrics(html: String): String? {
        val lines = originalLinePattern.findAll(html)
            .map { match -> decodeText(match.groupValues[1]) }
            .filter(String::isNotBlank)
            .toList()
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
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

    private const val USER_AGENT = "com.autolyrics"
}
