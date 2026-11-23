package app.olauncher.helper

import app.olauncher.helper.BlockItems.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockItemsTest {

    @Test
    fun theLaterChangeToEachSwitchWins() {
        val start = BlockItems.set(emptyMap(), Kind.SITE, "reddit.com", phone = true, mac = true, now = 10)
        // The phone turns the Mac off at 20; the Mac turns the phone off at 15
        val phoneCopy = BlockItems.set(start, Kind.SITE, "reddit.com", phone = null, mac = false, now = 20)
        val macCopy = BlockItems.set(start, Kind.SITE, "reddit.com", phone = false, mac = null, now = 15)
        val merged = BlockItems.mergeAll(phoneCopy, macCopy)
        val item = merged.values.single()
        assertFalse(item.phone)
        assertFalse(item.mac)
        assertEquals(merged, BlockItems.mergeAll(macCopy, phoneCopy))
    }

    @Test
    fun theSameAppOnBothIsOneItem() {
        val phone = BlockItems.set(emptyMap(), Kind.APP, "Discord", phone = true, mac = null, now = 5, pkg = "com.discord")
        val mac = BlockItems.set(emptyMap(), Kind.APP, "discord", phone = null, mac = true, now = 6, bundle = "com.hnc.Discord")
        val item = BlockItems.mergeAll(phone, mac).values.single()
        assertEquals("com.discord", item.pkg)
        assertEquals("com.hnc.Discord", item.bundle)
        assertTrue(item.phone && item.mac)
        assertEquals(setOf("com.discord"), BlockItems.phoneApps(mapOf(item.id to item)))
        assertEquals(setOf("com.hnc.Discord"), BlockItems.macApps(mapOf(item.id to item)))
    }

    @Test
    fun matchingBlocksOnBothWhereItCan() {
        var items = BlockItems.set(emptyMap(), Kind.SITE, "youtube.com", phone = false, mac = true, now = 1)
        items = BlockItems.set(items, Kind.APP, "Instagram", phone = true, mac = null, now = 1, pkg = "com.instagram.android")
        val matched = BlockItems.matched(items)
        assertEquals(setOf("youtube.com"), BlockItems.phoneSites(matched))
        // Instagram isn't on the Mac
        assertEquals(emptySet<String>(), BlockItems.macApps(matched))
        assertFalse(matched.values.first { it.kind == Kind.APP }.mac)
    }

    @Test
    fun idsAreSafeForTheDatabase() {
        assertEquals("s_reddit,com", BlockItems.idFor(Kind.SITE, "reddit.com"))
        assertEquals("a_googlechrome", BlockItems.idFor(Kind.APP, "Google Chrome"))
        assertTrue(BlockItems.idFor(Kind.APP, "!!").startsWith("a_x"))
    }

    @Test
    fun itemsSurviveBeingWrittenAndReadBack() {
        var items = BlockItems.set(emptyMap(), Kind.SITE, "x.com", phone = true, mac = false, now = 3)
        items = BlockItems.set(items, Kind.APP, "Slack", phone = null, mac = true, now = 4, bundle = "com.tinyspeck.slackmacgap")
        assertEquals(items, BlockItems.decode(BlockItems.encode(items)))
        assertEquals(items, BlockItems.changed(items, emptyMap()))
        assertEquals(emptyMap<String, BlockItems.Item>(), BlockItems.changed(items, items))
    }
}
