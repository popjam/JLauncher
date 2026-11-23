package app.olauncher.helper

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsletterSyncTest {

    private val database = """{
      "status": {"state": "running", "profile": "daily", "stage": "subreddits", "index": 11, "total": 61,
                 "subreddit": "worldnews", "percent": 16, "startedAt": 1000, "finishedAt": null, "message": null},
      "profiles": {"daily": {"settings": {"reddit": {"subreddits": ["worldnews", {"name": "all", "sort": "controversial"}],
                                                     "defaults": {"postsPerSubreddit": 10}}}, "updatedAt": 5}},
      "issues": [{"file": "daily_2026-11-23_07-00.epub", "profile": "daily", "date": "2026-11-23 07:00", "size": 1200}],
      "bridge": {"seen": 99}
    }"""

    @Test
    fun readsWhatThePcWrote() {
        val s = NewsletterSync.parse(database)
        val status = s.status!!
        assertTrue(status.running)
        assertEquals("daily", status.profile)
        assertEquals(16, status.percent)
        assertNull(status.message)
        assertEquals(0L, status.finishedAt)
        assertEquals(Triple("worldnews", 12, 61), NewsletterSync.fetching(status))
        assertEquals(setOf("daily"), s.profiles.keys)
        assertEquals(listOf(NewsletterSync.Issue("daily_2026-11-23_07-00.epub", "daily", "2026-11-23 07:00", 1200)), s.issues)
        assertEquals(99L, s.pcSeen)
    }

    @Test
    fun nothingThereYet() {
        for (text in listOf(null, "null", "", "junk")) {
            val s = NewsletterSync.parse(text)
            assertNull(s.status)
            assertTrue(s.profiles.isEmpty())
            assertTrue(s.issues.isEmpty())
        }
    }

    @Test
    fun listsWithGapsComeBackAsObjects() {
        val s = NewsletterSync.parse("""{"issues": {"0": {"file": "a.epub"}, "2": {"file": "b.epub"}}}""")
        assertEquals(listOf("a.epub", "b.epub"), s.issues.map { it.file })
    }

    @Test
    fun subredditsRoundTrip() {
        val settings = NewsletterSync.parse(database).profiles.getValue("daily")
        val subs = NewsletterSync.subreddits(settings)
        assertEquals(listOf("worldnews", "all"), subs.map { it.name })
        assertEquals("controversial", subs[1].own.getString("sort"))

        val changed = NewsletterSync.withSubreddits(settings, subs.reversed() + NewsletterSync.Subreddit("aus", JSONObject()))
        val list = changed.getJSONObject("reddit").getJSONArray("subreddits")
        assertEquals("all", list.getJSONObject(0).getString("name"))
        assertEquals("worldnews", list.getString(1))
        assertEquals("aus", list.getString(2))
        // The rest is kept, and the original isn't changed
        assertEquals(10, changed.getJSONObject("reddit").getJSONObject("defaults").getInt("postsPerSubreddit"))
        assertEquals("worldnews", settings.getJSONObject("reddit").getJSONArray("subreddits").getString(0))
    }

    @Test
    fun typedNamesAreCleaned() {
        assertEquals("AskReddit", NewsletterSync.cleanName(" r/AskReddit "))
        assertEquals("worldnews", NewsletterSync.cleanName("https://www.reddit.com/r/worldnews/top/?t=day"))
        assertEquals("aus", NewsletterSync.cleanName("/r/aus"))
        assertNull(NewsletterSync.cleanName("two words"))
        assertNull(NewsletterSync.cleanName("x"))
    }
}
