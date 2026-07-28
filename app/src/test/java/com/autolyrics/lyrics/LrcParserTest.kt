package com.autolyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {

    @Test
    fun `parses and sorts line timestamps`() {
        val result = LrcParser.parse(
            """
            [00:10.50]Second
            [00:02.125][00:05.00]First
            """.trimIndent()
        )

        assertEquals(listOf(2_125L, 5_000L, 10_500L), result.map { it.timeMs })
        assertEquals(listOf("First", "First", "Second"), result.map { it.text })
    }

    @Test
    fun `uses a musical placeholder for an empty timed line`() {
        val result = LrcParser.parse("[01:02.34]")

        assertEquals(1, result.size)
        assertEquals(62_340L, result.single().timeMs)
        assertEquals("♪", result.single().text)
    }

    @Test
    fun `parses word level karaoke timestamps`() {
        val result = LrcParser.parseKaraoke(
            "[00:01.00]<00:01.00>Hello <00:01.50>world"
        ).single()

        assertEquals("Hello world", result.text)
        assertEquals(listOf(1_000L, 1_500L), result.words.map { it.timeMs })
        assertEquals(listOf("Hello", "world"), result.words.map { it.text })
    }
}
