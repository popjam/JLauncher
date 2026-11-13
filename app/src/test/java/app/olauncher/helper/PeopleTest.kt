package app.olauncher.helper

import app.olauncher.data.MoodStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleTest {

    @Test
    fun facesGoByMessages() {
        assertEquals(MoodStore.SAD, People.faceFor(0))
        assertEquals(MoodStore.MILDLY_SAD, People.faceFor(1))
        assertEquals(MoodStore.MILDLY_SAD, People.faceFor(9))
        assertEquals(MoodStore.NEUTRAL, People.faceFor(10))
        assertEquals(MoodStore.NEUTRAL, People.faceFor(29))
        assertEquals(MoodStore.MILDLY_HAPPY, People.faceFor(30))
        assertEquals(MoodStore.MILDLY_HAPPY, People.faceFor(59))
        assertEquals(MoodStore.HAPPY, People.faceFor(60))
    }

    @Test
    fun chatAppsAndTheirNoticesArentPeople() {
        listOf("WhatsApp", "WhatsApp Business", "Missed Call", "Missed voice call", "Missed video call",
            "3 new messages", "5 messages from 2 chats", "  ").forEach { assertFalse(it, People.isPerson(it)) }
        assertFalse(People.isPerson("Signal Private Messenger", setOf("signal private messenger")))
        listOf("Mum", "Paul Dritsas", "+44 7700 900123").forEach { assertTrue(it, People.isPerson(it)) }
    }

    @Test
    fun sameNameCountsTogether() {
        assertEquals(mapOf("Mum" to 7, "Dad" to 1), People.mergeNames(mapOf("Mum" to 5, "mum" to 2, "Dad" to 1)))
        assertEquals(People.nameKey("Paul  Dritsas "), People.nameKey("paul dritsas"))
    }
}
