package com.autolyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataCleanerTest {

    @Test
    fun `removes playback quality labels from artist metadata`() {
        assertEquals(
            "CHVRCHES",
            MetadataCleaner.cleanArtist("CHVRCHES • Lossless • 24-bit")
        )
    }

    @Test
    fun `removes common video labels from titles`() {
        assertEquals(
            "Clearest Blue",
            MetadataCleaner.cleanTitle("Clearest Blue (Official Audio)")
        )
    }

    @Test
    fun `removes edition labels from albums`() {
        assertEquals(
            "Every Open Eye",
            MetadataCleaner.cleanAlbum("Every Open Eye [Deluxe Edition]")
        )
    }
}
