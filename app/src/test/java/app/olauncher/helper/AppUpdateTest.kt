package app.olauncher.helper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {

    @Test
    fun newerOnlyWhenChangedAfterInstalling() {
        val installed = 1_000_000L
        assertTrue(AppUpdate.isNewer(installed + 10 * 60_000L, installed))
        // The build just installed was put on Drive before it was installed
        assertFalse(AppUpdate.isNewer(installed - 5 * 60_000L, installed))
        // Within the minute's leeway
        assertFalse(AppUpdate.isNewer(installed + 30_000L, installed))
        // Unknown times never count as newer
        assertFalse(AppUpdate.isNewer(0L, installed))
    }
}
