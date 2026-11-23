package app.olauncher.helper

import org.json.JSONObject

/**
 * What lockdown blocks: one list of apps and websites for every device, each device blocking what it
 * can (the TV has apps only). The list is shared with the Mac and either side can change it; the TV
 * (through the PC) only reads it.
 *
 * An app is known by its name, so the same app on both (Discord on the phone and on the Mac) is one
 * item with the phone's package and the Mac's bundle id. The stored item still has a switch for each
 * device (the Mac app's format): an item on for any of them is blocked on all ([matched]). Each switch
 * keeps the time it was last changed, and when two copies of the list meet, each switch takes the later
 * change. Items are never deleted; one off everywhere doesn't show.
 */
object BlockItems {

    enum class Kind { APP, SITE }

    data class Item(
        val kind: Kind,
        /** The site's host, or the app's name */
        val name: String,
        /** The app's package on the phone, if it's there */
        val pkg: String? = null,
        /** The app's bundle id on the Mac, if it's there */
        val bundle: String? = null,
        val phone: Boolean = false,
        val phoneAt: Long = 0,
        val mac: Boolean = false,
        val macAt: Long = 0,
        /** The app's name on the TV, if it's there */
        val tvName: String? = null,
        val tv: Boolean = false,
        val tvAt: Long = 0,
    ) {
        val id get() = idFor(kind, name)
        val shown get() = phone || mac || tv

        /** Whether it can be blocked on the phone, the Mac: sites anywhere, apps where they are */
        val onPhone get() = kind == Kind.SITE || pkg != null
        val onMac get() = kind == Kind.SITE || bundle != null
        val onTv get() = kind == Kind.APP && tvName != null

        fun json(): JSONObject = JSONObject().put("k", if (kind == Kind.APP) "app" else "site").put("n", name)
            .put("p", pkg ?: "").put("m", bundle ?: "").put("ph", phone).put("tph", phoneAt).put("mc", mac).put("tmc", macAt)
            .put("t", tvName ?: "").put("tv", tv).put("ttv", tvAt)
    }

    fun fromJson(o: JSONObject): Item? {
        val name = o.optString("n").ifEmpty { return null }
        return Item(
            kind = if (o.optString("k") == "app") Kind.APP else Kind.SITE,
            name = name,
            pkg = o.optString("p").ifEmpty { null },
            bundle = o.optString("m").ifEmpty { null },
            phone = o.optBoolean("ph"), phoneAt = o.optLong("tph"),
            mac = o.optBoolean("mc"), macAt = o.optLong("tmc"),
            tvName = o.optString("t").ifEmpty { null }, tv = o.optBoolean("tv"), tvAt = o.optLong("ttv"),
        )
    }

    /** An app's name made plain, so "Discord" and "discord " are the same app */
    fun key(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

    /**
     * The item's key in the shared database, which can't have . $ # [ ] / in it: "s_reddit,com" or
     * "a_discord".
     */
    fun idFor(kind: Kind, name: String): String = when (kind) {
        Kind.SITE -> "s_" + name.lowercase().map { c -> if (c in ".$#[]/%") ',' else c }.joinToString("")
        Kind.APP -> "a_" + key(name).ifEmpty { "x" + name.hashCode().toUInt().toString(16) }
    }

    /** Two copies of one item together: each switch from the later change, and whatever's known of the app on each device */
    fun merge(a: Item, b: Item): Item {
        val phone = if (b.phoneAt > a.phoneAt) b else a
        val mac = if (b.macAt > a.macAt) b else a
        val tv = if (b.tvAt > a.tvAt) b else a
        return a.copy(
            tvName = a.tvName ?: b.tvName,
            tv = tv.tv, tvAt = tv.tvAt,
            name = if (a.kind == Kind.APP && b.name.length > a.name.length && a.pkg == null) b.name else a.name,
            pkg = a.pkg ?: b.pkg,
            bundle = a.bundle ?: b.bundle,
            phone = phone.phone, phoneAt = phone.phoneAt,
            mac = mac.mac, macAt = mac.macAt,
        )
    }

    /** Two copies of the list together */
    fun mergeAll(a: Map<String, Item>, b: Map<String, Item>): Map<String, Item> =
        (a.keys + b.keys).associateWith { id ->
            val x = a[id]
            val y = b[id]
            if (x != null && y != null) merge(x, y) else (x ?: y)!!
        }

    fun encode(items: Map<String, Item>): String = JSONObject().apply { items.forEach { (id, item) -> put(id, item.json()) } }.toString()

    fun decode(text: String?): Map<String, Item> = runCatching {
        val o = JSONObject(text ?: return emptyMap())
        o.keys().asSequence().mapNotNull { id -> o.optJSONObject(id)?.let(::fromJson)?.let { it.id to it } }.toMap()
    }.getOrDefault(emptyMap())

    /**
     * [items] with [name] switched [on] for the phone and/or Mac at [now]; an app can be given its
     * package or bundle id at the same time.
     */
    fun set(items: Map<String, Item>, kind: Kind, name: String, phone: Boolean?, mac: Boolean?, now: Long,
            pkg: String? = null, bundle: String? = null, tv: Boolean? = null, tvName: String? = null): Map<String, Item> {
        val id = idFor(kind, name)
        val old = items[id] ?: Item(kind, name)
        var item = old.copy(pkg = old.pkg ?: pkg, bundle = old.bundle ?: bundle, tvName = old.tvName ?: tvName)
        if (phone != null && phone != old.phone) item = item.copy(phone = phone, phoneAt = now)
        if (mac != null && mac != old.mac) item = item.copy(mac = mac, macAt = now)
        if (tv != null && tv != old.tv) item = item.copy(tv = tv, tvAt = now)
        return items + (id to item)
    }

    /** The phone's blocked apps and sites */
    fun phoneApps(items: Map<String, Item>) = items.values.filter { it.kind == Kind.APP && it.phone && it.pkg != null }.mapNotNull { it.pkg }.toSet()
    fun phoneSites(items: Map<String, Item>) = items.values.filter { it.kind == Kind.SITE && it.phone }.map { it.name }.toSet()
    fun macApps(items: Map<String, Item>) = items.values.filter { it.kind == Kind.APP && it.mac && it.bundle != null }.mapNotNull { it.bundle }.toSet()
    fun macSites(items: Map<String, Item>) = items.values.filter { it.kind == Kind.SITE && it.mac }.map { it.name }.toSet()
    /** The TV's blocked apps, by their names there */
    fun tvApps(items: Map<String, Item>) = items.values.filter { it.kind == Kind.APP && it.tv && it.tvName != null }.mapNotNull { it.tvName }.toSet()

    /**
     * [items] with [name] blocked or not ([on]) everywhere at [now]: one list for every device, each
     * blocking it where it can. An app can be given its package, bundle id or TV name at the same time.
     */
    fun block(items: Map<String, Item>, kind: Kind, name: String, on: Boolean, now: Long,
              pkg: String? = null, bundle: String? = null, tvName: String? = null): Map<String, Item> =
        set(items, kind, name, phone = on, mac = on, now = now, pkg = pkg, bundle = bundle, tv = on, tvName = tvName)

    /** Whether [item] is blocked: switched on for any device (the Mac app still keeps a switch for each) */
    fun blocked(item: Item) = item.phone || item.mac || item.tv

    /**
     * What each device blocks: anything blocked anywhere is blocked on every device where it can be:
     * sites on the phone and the Mac, and apps on each device they're on (the TV has apps only).
     */
    fun matched(items: Map<String, Item>): Map<String, Item> = items.mapValues { (_, it) ->
        val on = blocked(it)
        it.copy(phone = on && it.onPhone, mac = on && it.onMac, tv = on && it.onTv)
    }

    /**
     * [items] with apps given their Mac bundle id and TV name when the Mac ([macApps], bundle id to name)
     * or the TV ([tvApps], names) has an app by the same name, then [matched]: what each device blocks.
     */
    fun effective(items: Map<String, Item>, macApps: Map<String, String>, tvApps: Collection<String>): Map<String, Item> {
        val bundles = macApps.entries.associate { (bundle, name) -> key(name) to bundle }
        val tv = tvApps.associateBy { key(it) }
        return matched(items.mapValues { (_, it) ->
            if (it.kind != Kind.APP) it
            else it.copy(bundle = it.bundle ?: bundles[key(it.name)], tvName = it.tvName ?: tv[key(it.name)])
        })
    }

    /** The items whose copy in [remote] isn't the same as in [local], to send */
    fun changed(local: Map<String, Item>, remote: Map<String, Item>): Map<String, Item> =
        local.filter { (id, item) -> remote[id] != item }
}
