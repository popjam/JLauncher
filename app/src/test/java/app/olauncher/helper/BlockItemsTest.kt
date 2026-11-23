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
    fun theTvHasItsOwnSwitch() {
        val phone = BlockItems.set(emptyMap(), Kind.APP, "YouTube", phone = true, mac = null, now = 5, pkg = "com.google.android.youtube")
        val tv = BlockItems.set(phone, Kind.APP, "YouTube", phone = null, mac = null, now = 9, tv = true, tvName = "YouTube")
        // A copy from before the TV was blocked doesn't undo it
        val item = BlockItems.mergeAll(tv, phone).values.single()
        assertTrue(item.tv && item.phone)
        assertEquals(setOf("YouTube"), BlockItems.tvApps(mapOf(item.id to item)))
        assertEquals(tv, BlockItems.decode(BlockItems.encode(tv)))
        // Matched, it's blocked on the TV too
        val matched = BlockItems.matched(BlockItems.set(emptyMap(), Kind.APP, "Netflix", phone = true, mac = null, now = 1, tvName = "Netflix"))
        assertEquals(setOf("Netflix"), BlockItems.tvApps(matched))
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
    fun oneListBlocksEachItemOnEveryDeviceThatCanBlockIt() {
        // Blocked on one device only, from before there was one list
        var old = BlockItems.set(emptyMap(), Kind.SITE, "reddit.com", phone = false, mac = true, now = 1)
        old = BlockItems.set(old, Kind.APP, "YouTube", phone = true, mac = null, now = 1, pkg = "com.google.android.youtube", tvName = "YouTube")
        old = BlockItems.set(old, Kind.APP, "Slack", phone = null, mac = true, now = 1, bundle = "com.tinyspeck.slackmacgap")
        val each = BlockItems.matched(old)
        // Sites on the phone and the Mac; apps wherever they are; the TV has apps only
        assertEquals(setOf("reddit.com"), BlockItems.phoneSites(each))
        assertEquals(setOf("reddit.com"), BlockItems.macSites(each))
        assertEquals(setOf("com.google.android.youtube"), BlockItems.phoneApps(each))
        assertEquals(setOf("YouTube"), BlockItems.tvApps(each))
        assertEquals(setOf("com.tinyspeck.slackmacgap"), BlockItems.macApps(each))
        assertFalse(each.getValue(BlockItems.idFor(Kind.SITE, "reddit.com")).tv)
        // Nothing stored is lost or changed
        assertEquals(old, BlockItems.decode(BlockItems.encode(old)))
    }

    @Test
    fun blockingSwitchesEveryDeviceTogether() {
        val on = BlockItems.block(emptyMap(), Kind.APP, "Discord", true, now = 5, pkg = "com.discord")
        val item = on.values.single()
        assertTrue(item.phone && item.mac && item.tv)
        // Unblocking takes it off everywhere, even where the Mac had switched it on
        val macOn = BlockItems.set(on, Kind.APP, "Discord", phone = null, mac = true, now = 6)
        val off = BlockItems.block(macOn, Kind.APP, "Discord", false, now = 7)
        assertFalse(BlockItems.blocked(off.values.single()))
        assertTrue(BlockItems.matched(off).values.none { it.shown })
    }

    @Test
    fun appsAreFoundOnTheMacAndTheTvByName() {
        val items = BlockItems.block(emptyMap(), Kind.APP, "Netflix", true, now = 1, pkg = "com.netflix.mediaclient")
        val effective = BlockItems.effective(items, mapOf("com.netflix.Netflix" to "Netflix"), listOf("netflix", "Plex"))
        assertEquals(setOf("com.netflix.Netflix"), BlockItems.macApps(effective))
        assertEquals(setOf("netflix"), BlockItems.tvApps(effective))
        // Without them, only the phone
        val alone = BlockItems.effective(items, emptyMap(), emptyList())
        assertEquals(emptySet<String>(), BlockItems.macApps(alone) + BlockItems.tvApps(alone))
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
