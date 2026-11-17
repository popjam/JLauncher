package app.olauncher.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalTextTest {

    @Test
    fun shortNotesPreviewWhole() {
        assertEquals("One line. Two lines.", JournalText.preview("One line.\n\nTwo lines."))
    }

    @Test
    fun longNotesCutAtAWord() {
        val text = "word ".repeat(100).trim()
        val preview = JournalText.preview(text, 50)
        assertTrue(preview.endsWith("word…"))
        assertTrue(preview.length <= 51)
    }

    @Test
    fun searchNeedsEveryWord() {
        val words = JournalText.words("  Beach  DOG ")
        assertEquals(listOf("beach", "dog"), words)
        assertTrue(JournalText.matches("Took the dog to the beach", words))
        assertFalse(JournalText.matches("Took the dog out", words))
    }

    @Test
    fun snippetIsAroundTheMatch() {
        val text = "a ".repeat(200) + "the beach was cold " + "b ".repeat(200)
        val snippet = JournalText.snippet(text, listOf("beach"), radius = 20)
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertTrue("beach" in snippet)
        assertTrue(snippet.length < 60)
    }

    @Test
    fun nearbyMatchesShareOneSnippet() {
        val text = "x ".repeat(100) + "dog and beach" + " y".repeat(100)
        val snippet = JournalText.snippet(text, listOf("beach", "dog"), radius = 20)
        assertEquals(1, Regex("dog and beach").findAll(snippet).count())
        assertEquals(2, snippet.count { it == '…' })
    }

    @Test
    fun farApartMatchesAreSeparated() {
        val text = "dog " + "z ".repeat(300) + "beach"
        val snippet = JournalText.snippet(text, listOf("dog", "beach"), radius = 10)
        assertTrue(snippet.startsWith("dog"))
        assertTrue(snippet.endsWith("beach"))
        assertTrue("…" in snippet)
    }

    @Test
    fun matchesAreFoundEverywhereInOrder() {
        assertEquals(listOf(0..2, 8..10, 12..14), JournalText.matchRanges("Dog and dog cat", listOf("cat", "dog")))
        assertEquals(emptyList<IntRange>(), JournalText.matchRanges("İstanbul dog", listOf("dog")))
    }

    @Test
    fun noMatchShowsTheStart() {
        assertEquals("Hello there.", JournalText.snippet("Hello there.", listOf("nope")))
    }
}
