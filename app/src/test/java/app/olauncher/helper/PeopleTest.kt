package app.olauncher.helper

import app.olauncher.data.MoodStore
import org.junit.Assert.assertEquals
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
}
