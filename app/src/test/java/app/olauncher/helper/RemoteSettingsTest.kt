package app.olauncher.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSettingsTest {

    @Test
    fun schedulesAreTakenAsTheyAreKept() {
        assertEquals("31:1320:420;96:1410:480", RemoteSettings.normalize(RemoteSettings.SCHEDULES, "31:1320:420;96:1410:480", false))
        // The same one twice is kept once; all of them taken off is allowed
        assertEquals("31:1320:420", RemoteSettings.normalize(RemoteSettings.SCHEDULES, "31:1320:420;31:1320:420", false))
        assertEquals("", RemoteSettings.normalize(RemoteSettings.SCHEDULES, "", false))
        assertNull(RemoteSettings.normalize(RemoteSettings.SCHEDULES, "nonsense", false))
    }

    @Test
    fun triggers() {
        assertEquals("TOTAL:300:420:7", RemoteSettings.normalize(RemoteSettings.TRIGGERS, "TOTAL:300:420:7", false))
        assertEquals("", RemoteSettings.normalize(RemoteSettings.TRIGGERS, "", false))
        assertNull(RemoteSettings.normalize(RemoteSettings.TRIGGERS, "TOTAL:0:420:7", false))
    }

    @Test
    fun breaksAreReadThenWrittenAgain() {
        val config = Breaks.Config(minutes = 5, every = 60, cap = 30)
        assertEquals(config, Breaks.Config.decode(RemoteSettings.normalize(RemoteSettings.BREAKS, config.encode(), false)))
        assertNull(RemoteSettings.normalize(RemoteSettings.BREAKS, "not json", false))
    }

    @Test
    fun passwordOnlyWhenOneIsSet() {
        assertNull(RemoteSettings.normalize(RemoteSettings.CHALLENGE, "PASSWORD:0", passwordSet = false))
        assertEquals("PASSWORD:0", RemoteSettings.normalize(RemoteSettings.CHALLENGE, "PASSWORD:0", passwordSet = true))
        assertEquals("RANDOM:200", RemoteSettings.normalize(RemoteSettings.CHALLENGE, "RANDOM:200", false))
        assertNull(RemoteSettings.normalize(RemoteSettings.CHALLENGE, "JUNK:1", false))
    }

    @Test
    fun phraseNeedsFourCharacters() {
        assertEquals("stay on task", RemoteSettings.normalize(RemoteSettings.PHRASE, "  stay   on task ", false))
        assertNull(RemoteSettings.normalize(RemoteSettings.PHRASE, "no", false))
        assertNull(RemoteSettings.normalize("unknown", "anything", false))
    }

    @Test
    fun latestChangeWins() {
        assertTrue(RemoteSettings.wins(at = 2_000, changedAt = 1_000))
        assertTrue(RemoteSettings.wins(at = 2_000, changedAt = 0))
        assertFalse(RemoteSettings.wins(at = 1_000, changedAt = 2_000))
    }

    @Test
    fun changesHereAreStamped() {
        val seen = mapOf("schedules" to "a", "phrase" to "p")
        val current = mapOf("schedules" to "b", "phrase" to "p", "triggers" to "t")
        val stamps = RemoteSettings.stamp(current, seen, mapOf("phrase" to 5L), now = 100L)
        // Changed: stamped now. Same: kept. Never seen before: not stamped.
        assertEquals(mapOf("phrase" to 5L, "schedules" to 100L), stamps)
    }
}
