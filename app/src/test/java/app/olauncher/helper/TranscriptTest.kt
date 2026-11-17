package app.olauncher.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTest {

    private fun clean(vararg segments: String) = Transcript.clean(segments.toList())

    @Test
    fun fillersGo() {
        assertEquals("I went to the shops.", clean("um I went to the uh shops"))
        assertEquals("So, I think it was fine.", clean("so, um, I think it was erm fine"))
        assertEquals("It was good.", clean("hmm it was mm good"))
    }

    @Test
    fun wordsThatLookLikeFillersStay() {
        assertEquals("The umbrella was there.", clean("the umbrella was there"))
        assertEquals("Uh-huh, that's right.", clean("uh-huh, that's right"))
        assertEquals("Her error.", clean("her error"))
        assertEquals("I went to the ER.", clean("I went to the ER"))
        assertEquals("Ah, I see.", clean("ah, I see"))
    }

    @Test
    fun leadingYeahGoesOnlyBeforeMoreWords() {
        assertEquals("So I went out.", clean("yeah so I went out"))
        assertEquals("It was good.", clean("yeah yeah it was good"))
        assertEquals("I said yeah to it.", clean("I said yeah to it"))
        assertEquals("Yeah.", clean("yeah"))
    }

    @Test
    fun stutteredWordsSaidOnce() {
        assertEquals("I think the dog is fine.", clean("I I think the the dog is fine"))
        assertEquals("I had had enough.", clean("I had had enough"))
        assertEquals("It was very very good.", clean("it was very very good"))
    }

    @Test
    fun loneIIsCapitalised() {
        assertEquals("I'm sure I've seen it, and I think so.", clean("i'm sure i've seen it, and i think so"))
        assertEquals("Pi is nice.", clean("pi is nice"))
    }

    @Test
    fun eachSegmentIsASentence() {
        assertEquals("Today was long. Then I went home!", clean("today was long", "then I went home!"))
        assertEquals("One. Two.", clean("one.", " two "))
    }

    @Test
    fun aPauseMidSentenceDoesNotEndIt() {
        assertEquals("I went to the shop and then home.", clean("I went to the", "shop and then", "home"))
        assertEquals("It rained. Then it stopped.", clean("it rained", "then it stopped"))
        // Already punctuated by the recogniser, or ending on a filler
        assertEquals("I went to the shop.", clean("I went to the.", "Shop."))
        assertEquals("I went to the shop.", clean("I went to the um", "shop"))
        // Names keep their capitals
        assertEquals("I had lunch with Sarah in London.", clean("I had lunch with", "Sarah in", "London"))
        assertEquals("Then I left.", clean("then", "I left"))
        // Said in lower case (no punctuation from the recogniser): stays lower case
        assertEquals("I went to the supermarket and got milk.", clean("I went to the", "supermarket and got milk"))
    }

    @Test
    fun everydayWordsCapitalisedMidSentenceAreLowered() {
        assertEquals("So I do feel better today but you know, it's fine.", clean("So I do feel better today but You know, it's fine"))
        assertEquals("I went with Sarah to the shop.", clean("I went with Sarah to The shop"))
    }

    @Test
    fun summariesAreShort() {
        assertTrue(Transcript.acceptSummary("Felt better and calmer."))
        assertFalse(Transcript.acceptSummary("word ".repeat(100)))
        assertFalse(Transcript.acceptSummary(" "))
    }

    @Test
    fun claudesReplyIsReadFromItsTags() {
        assertEquals("I felt better today.", AiTidy.tagged("<entry>\nI felt better today.\n</entry>", "entry"))
        assertEquals("Felt better.", AiTidy.tagged("Here it is: <summary>Felt better.</summary>", "summary"))
        assertEquals(null, AiTidy.tagged("Just text", "entry"))
        assertEquals(null, AiTidy.tagged("<entry> </entry>", "entry"))
    }

    @Test
    fun onlyFillersIsNothing() {
        assertEquals("", clean("um", "uh, erm"))
        assertEquals("Fine.", clean("um", "fine"))
    }

    @Test
    fun capitalsAfterFullStops() {
        assertEquals("It rained. We stayed in.", clean("it rained. we stayed in"))
    }

    @Test
    fun tidyAcceptedOnlyIfAboutTheSameLength() {
        val local = "So I went to the shops and I bought some bread and then I came home and I made a sandwich for lunch."
        assertTrue(Transcript.acceptTidy(local, "I went to the shops and bought some bread, then came home and made a sandwich for lunch."))
        assertFalse(Transcript.acceptTidy(local, "Went shopping, made lunch."))
        assertFalse(Transcript.acceptTidy(local, ""))
        assertFalse(Transcript.acceptTidy(local, null))
        assertFalse(Transcript.acceptTidy(local, local + " " + local))
    }
}
