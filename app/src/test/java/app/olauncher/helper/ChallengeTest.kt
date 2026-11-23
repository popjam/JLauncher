package app.olauncher.helper

import app.olauncher.helper.Challenge.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChallengeTest {

    @Test
    fun writtenAndReadBack() {
        val c = Challenge(Type.TAPS, 300)
        assertEquals(c, Challenge.decode(c.encode()))
        assertNull(Challenge.decode("JUNK:3"))
        // An amount that isn't offered moves to the nearest one that is
        assertEquals(Challenge(Type.WAIT, 15), Challenge.decode("WAIT:16"))
        assertEquals(Challenge(Type.NEVER), Challenge.decode("NEVER:0"))
    }

    @Test
    fun difficultyRunsFromSimpleToImpossible() {
        assertEquals(0, Challenge(Type.NONE).difficulty())
        assertEquals(1, Challenge(Type.HOLD, 10).difficulty())
        assertEquals(2, Challenge(Type.TAPS, 300).difficulty())
        assertEquals(3, Challenge(Type.RANDOM, 500).difficulty())
        assertEquals(4, Challenge(Type.HOLD, 1800).difficulty())
        assertEquals(5, Challenge(Type.RANDOM, 2500).difficulty())
        assertEquals(6, Challenge(Type.NEVER).difficulty())
    }

    @Test
    fun easierChoicesLoosen() {
        assertTrue(Challenge.loosens(Challenge(Type.TAPS, 1000), Challenge(Type.TAPS, 100)))
        assertFalse(Challenge.loosens(Challenge(Type.TAPS, 100), Challenge(Type.TAPS, 1000)))
        assertFalse(Challenge.loosens(Challenge(Type.NONE), Challenge(Type.NEVER)))
        assertTrue(Challenge.loosens(Challenge(Type.NEVER), Challenge(Type.WAIT, 1440)))
        // Leaving the password always needs it
        assertTrue(Challenge.loosens(Challenge(Type.PASSWORD), Challenge(Type.NEVER)))
        assertFalse(Challenge.loosens(Challenge(Type.PASSWORD), Challenge(Type.PASSWORD)))
    }

    @Test
    fun typedTextMatchesLoosely() {
        assertTrue(Challenge.matches("  I am  Choosing ", "I am choosing"))
        assertFalse(Challenge.matches("I am chosing", "I am choosing"))
        assertTrue(Challenge.matches("abcde fghjk", "abcdefghjk", ignoreSpaces = true))
        assertFalse(Challenge.matches("", ""))
        val text = Challenge.randomText(23, java.util.Random(1))
        assertEquals(23, text.replace(" ", "").length)
        assertEquals("     ".length, text.count { it == ' ' } + 1)
    }
}
