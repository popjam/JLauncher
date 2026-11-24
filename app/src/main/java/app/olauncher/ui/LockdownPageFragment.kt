package app.olauncher.ui

import android.content.Intent
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import app.olauncher.helper.Formats
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.helper.AppPause
import app.olauncher.helper.BlockItems
import app.olauncher.helper.Limits
import app.olauncher.helper.Lockdown
import app.olauncher.helper.LockdownSync
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getAppsList
import app.olauncher.helper.isAccessServiceEnabled
import app.olauncher.helper.isPackageInstalled
import app.olauncher.helper.showToast
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Lockdown's long lists, each a page of its own, by [PAGE]: the apps and websites, with a button for
 * each device on each; and Limits, each app's limits ([APP] opens one app's straight away).
 *
 * Anything that makes lockdown stricter is done straight away, and anything that loosens it goes through
 * the unlock (while lockdown is on). Things blocked here by mistake can be unblocked again while the page
 * is open.
 */
class LockdownPageFragment : LockdownBaseFragment() {

    companion object {
        const val PAGE = "page"
        const val PKG = "pkg"
        const val BLOCKS = "blocks"
        const val LIMITS = "limits"
        const val APP = "app"
    }

    private val page get() = arguments?.getString(PAGE)?.takeIf { it in listOf(BLOCKS, LIMITS, APP) } ?: BLOCKS
    private val pkg get() = arguments?.getString(PKG).orEmpty()

    /** Every app, once read */
    private var apps: List<AppModel.App>? = null
    private var query = ""

    private val limitList = HoldList<String>(onRemove = { removeLimits(it) })
    override val holdLists: List<HoldList<*>> get() = listOf(limitList)

    override fun title(): String = when (page) {
        BLOCKS -> getString(R.string.lockdown_apps_and_sites)
        LIMITS -> getString(R.string.limits)
        else -> appLabel(pkg)
    }

    override fun icon() = if (page == LIMITS) StatIcons.LOCK else null

    override fun top(): View? = when (page) {
        BLOCKS -> box(requireContext(), R.string.lockdown_search_or_add, { query = it; render() }) { addSite(it) }
        LIMITS -> box(requireContext(), R.string.lockdown_search_apps, { query = it; render() })
        else -> null
    }

    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            apps = getAppsList(requireContext(), prefs, includeRegularApps = true, includeHiddenApps = true)
                .filterIsInstance<AppModel.App>().distinctBy { it.appPackage }
            render()
        }
        if (page == APP || page == LIMITS) Limits.refresh(requireContext()) { main.post { render() } }
        if (page == APP && savedInstanceState == null) main.post { if (isAdded) appSheet(pkg) }
    }

    private fun appLabel(pkg: String): String = apps?.firstOrNull { it.appPackage == pkg }?.appLabel ?: runCatching {
        val pm = requireContext().packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    override fun build() {
        when (page) {
            BLOCKS -> blocksPage()
            LIMITS, APP -> limitsPage()
        }
    }

    private fun matchesQuery(text: String) = query.isBlank() || text.lowercase(Locale.getDefault()).contains(query.trim().lowercase(Locale.getDefault()))

    // --- Apps and websites ---

    /** An app or site on the page: where it is, and its item in the list if it has one */
    private data class Entry(val kind: BlockItems.Kind, val name: String, val pkg: String?, val bundle: String?, val tvName: String?, val item: BlockItems.Item?) {
        val id get() = BlockItems.idFor(kind, name)
    }

    /** What was blocked anywhere when the page opened, which goes first; kept so rows don't move while they're changed */
    private var blockedAtOpen: Set<String>? = null

    /** Sites shown since the page opened, so one turned off everywhere stays until the page is left */
    private val sitesShown = mutableSetOf<String>()

    /** The list, with apps found on the Mac and the TV by name */
    private fun knownItems() = requireContext().let { BlockItems.known(prefs.lockdownItems, LockdownSync.macInstalled(it), LockdownSync.tvInstalled(it)) }

    /** The apps the phone allows during lockdown when it allows only a list */
    private fun allowed() = prefs.lockdownAllowedApps + prefs.lockdownEssentials

    /** Every site on the list, and every app on the phone, the Mac and the TV, one per name */
    private fun entries(items: Map<String, BlockItems.Item>): List<Entry> {
        val context = requireContext()
        val byId = linkedMapOf<String, Entry>()
        fun add(kind: BlockItems.Kind, name: String, pkg: String? = null, bundle: String? = null, tvName: String? = null) {
            val id = BlockItems.idFor(kind, name)
            val old = byId[id]
            byId[id] = old?.copy(pkg = old.pkg ?: pkg, bundle = old.bundle ?: bundle, tvName = old.tvName ?: tvName)
                ?: Entry(kind, items[id]?.name ?: name, pkg, bundle, tvName, items[id])
        }
        items.values.filter { it.kind == BlockItems.Kind.SITE && (it.shown || it.id in sitesShown) }.forEach { add(it.kind, it.name) }
        apps.orEmpty().filter { Lockdown.canBlock(context, it.appPackage) }.forEach { add(BlockItems.Kind.APP, it.appLabel, pkg = it.appPackage) }
        if (Lockdown.macPaired(context)) LockdownSync.macInstalled(context).forEach { (bundle, name) -> add(BlockItems.Kind.APP, name, bundle = bundle) }
        if (Lockdown.tvPaired(context)) LockdownSync.tvInstalled(context).forEach { add(BlockItems.Kind.APP, it, tvName = it) }
        return byId.values.map { e -> e.copy(pkg = e.pkg ?: e.item?.pkg, bundle = e.bundle ?: e.item?.bundle, tvName = e.tvName ?: e.item?.tvName) }
    }

    /** Whether [entry] is blocked on [device] now */
    private fun blockedOn(entry: Entry, device: Lockdown.Device): Boolean = when (device) {
        Lockdown.Device.PHONE -> if (entry.kind == BlockItems.Kind.APP && prefs.lockdownAppMode == Lockdown.ALLOW) entry.pkg != null && entry.pkg !in allowed()
            else entry.item?.phone == true
        Lockdown.Device.MAC -> entry.item?.mac == true
        Lockdown.Device.TV -> entry.item?.tv == true
    }

    /**
     * Whether [entry] can be blocked on [device]: sites on the phone and the Mac, apps where they are. A
     * phone app with a website (Instagram) can be blocked on the Mac too, which blocks its website there.
     * An app blocked on a device it isn't on gets no button there (it's blocked if it's put there).
     */
    private fun canBlockOn(entry: Entry, device: Lockdown.Device): Boolean {
        val context = requireContext()
        val site = entry.kind == BlockItems.Kind.SITE
        val pkg = entry.pkg
        return when (device) {
            Lockdown.Device.PHONE -> site || (pkg != null && context.isPackageInstalled(pkg) && Lockdown.canBlock(context, pkg))
            Lockdown.Device.MAC -> site || entry.bundle != null || Limits.APP_SITES[pkg].orEmpty().isNotEmpty()
            Lockdown.Device.TV -> !site && entry.tvName != null
        }
    }

    private fun blocksPage() {
        val context = requireContext()
        val items = knownItems()
        val devices = Lockdown.deviceList(context)
        val all = entries(items)
        fun blocked(e: Entry) = devices.any { canBlockOn(e, it) && blockedOn(e, it) }
        val first = blockedAtOpen ?: all.filter { blocked(it) }.map { it.id }.toSet().also { if (apps != null) blockedAtOpen = it }
        sitesShown += all.filter { it.kind == BlockItems.Kind.SITE }.map { it.id }
        fun ordered(kind: BlockItems.Kind) = all.filter { it.kind == kind && matchesQuery(it.name) && devices.any { d -> canBlockOn(it, d) } }
            .sortedWith(compareBy<Entry>({ it.id !in first }, { it.name.lowercase(Locale.getDefault()) }))
        fun count(list: List<Entry>) = list.count { blocked(it) }

        show(segment("mode", getString(R.string.lockdown_during_phone), R.string.info_lockdown_during,
            listOf(getString(R.string.lockdown_block_short), getString(R.string.lockdown_allow_short)), prefs.lockdownAppMode) { pickMode(it) })

        val sites = ordered(BlockItems.Kind.SITE)
        show(Rows(listOf(SettingLabel(getString(R.string.lockdown_websites_count, count(sites)))) + sites.map { entryRow(it, devices) } +
            listOfNotNull(if (sites.isEmpty()) noneRow("noSites") else null)))

        if (apps == null) return
        val appEntries = ordered(BlockItems.Kind.APP)
        show(Rows(listOf(SettingLabel(getString(R.string.lockdown_apps_count_title, count(appEntries)))) + appEntries.map { entryRow(it, devices) } +
            listOfNotNull(if (appEntries.isEmpty()) noneRow("noApps") else null)))
    }

    /** An app or site: its name (an app's websites under it, blocked with it), and a button for each switched-on device, lit where it's blocked */
    private fun entryRow(entry: Entry, devices: List<Lockdown.Device>): SettingRow {
        val context = requireContext()
        val buttons = devices.map { device ->
            if (!canBlockOn(entry, device)) null
            else DeviceButton(LockdownGate.deviceName(context, device.bit), blockedOn(entry, device)) { toggle(entry, device) }
        }
        val sites = Limits.APP_SITES[entry.pkg].orEmpty().takeIf { entry.kind == BlockItems.Kind.APP }.orEmpty()
        return row("item:" + entry.id, entry.name, R.string.info_lockdown_item_devices, control = Control.Devices(buttons),
            note = sites.joinToString(", ").ifEmpty { null })
    }

    /** Blocks or unblocks [entry] on [device]: blocking is done straight away; unblocking needs the unlock, unless it was blocked here */
    private fun toggle(entry: Entry, device: Lockdown.Device) {
        val context = requireContext()
        val key = entry.id + ":" + device.name
        val on = !blockedOn(entry, device)
        val change = {
            val pkg = entry.pkg
            if (device == Lockdown.Device.PHONE && entry.kind == BlockItems.Kind.APP && prefs.lockdownAppMode == Lockdown.ALLOW) {
                if (pkg != null) {
                    if (on) {
                        prefs.lockdownAllowedApps = prefs.lockdownAllowedApps - pkg
                        prefs.lockdownEssentials = prefs.lockdownEssentials - pkg
                    } else prefs.lockdownAllowedApps = prefs.lockdownAllowedApps + pkg
                }
            } else prefs.lockdownItems = BlockItems.set(prefs.lockdownItems, entry.kind, entry.name,
                phone = on.takeIf { device == Lockdown.Device.PHONE }, mac = on.takeIf { device == Lockdown.Device.MAC },
                now = System.currentTimeMillis(), pkg = pkg, bundle = entry.bundle,
                tv = on.takeIf { device == Lockdown.Device.TV }, tvName = entry.tvName)
            if (on) addedHere += key else addedHere -= key
            render()
            if (on && device == Lockdown.Device.PHONE && !isAccessServiceEnabled(context)) runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        if (on || key in addedHere) change() else gate.loosen(change)
    }

    /** A site typed in: blocked on every device that blocks sites */
    private fun addSite(text: String) {
        val site = Limits.cleanSite(text) ?: return requireContext().showToast(getString(R.string.lockdown_not_a_site))
        prefs.lockdownItems = BlockItems.block(prefs.lockdownItems, BlockItems.Kind.SITE, site, true, System.currentTimeMillis())
        Lockdown.Device.values().forEach { addedHere += BlockItems.idFor(BlockItems.Kind.SITE, site) + ":" + it.name }
        (view?.findFocus() as? android.widget.EditText)?.setText("")
        query = ""
        render()
    }

    // --- Limits ---

    private fun limitsPage() {
        val context = requireContext()
        val all = apps ?: return
        AppPause.migrateIfNeeded(prefs)
        val withRules = all.filter { summary(it.appPackage).isNotEmpty() }
        // Now: today's screen time and the apps at their limit
        val over = withRules.count { app ->
            val rule = Limits.rule(prefs, app.appPackage)
            !rule.isEmpty && Limits.over(rule, Limits.usedToday(context, app.appPackage), Limits.opensToday(context, app.appPackage), false) != null
        }
        show(row("today", getString(R.string.lockdown_used_today, Formats.duration(Limits.totalToday(context))), R.string.info_limits_today,
            note = listOfNotNull(resources.getQuantityString(R.plurals.lockdown_apps_count, withRules.size, withRules.size).takeIf { withRules.isNotEmpty() },
                getString(R.string.limits_at_limit, over).takeIf { over > 0 }).joinToString(" · ").ifEmpty { getString(R.string.limits_none_yet) }))

        val shown = withRules.filter { matchesQuery(it.appLabel) }
        val rows = shown.map { it to limitRow(it.appPackage, it.appLabel) }
        show(Rows(listOf(SettingLabel(getString(R.string.lockdown_with_limits))) + rows.map { SettingView(it.second, "limit:" + it.first.appPackage) } +
            listOfNotNull(if (withRules.isEmpty()) noneRow("none") else null)) { views ->
            rows.firstOrNull()?.let { (it.second.parent as? LinearLayout) }?.let { limitList.attach(it, scrollView()) }
            rows.forEach { (app, view) ->
                limitList.item(view, app.appPackage, label = app.appLabel, about = getString(R.string.info_lockdown_limit_item), onTap = { appSheet(app.appPackage) },
                    actions = listOf(
                        HoldList.Action(getString(R.string.edit)) { appSheet(app.appPackage) },
                        HoldList.Action(getString(R.string.remove)) { removeLimits(setOf(app.appPackage)) },
                    ))
            }
        })

        show(beforeOpeningRows())

        show(Rows(listOf(SettingLabel(getString(R.string.limits_add))) + all.filter { it !in withRules && Lockdown.canBlock(context, it.appPackage) && matchesQuery(it.appLabel) }
            .map { app -> row("add:" + app.appPackage, app.appLabel, R.string.info_lockdown_limit_app) { appSheet(app.appPackage) } }))
    }

    /** Takes every limit, the pause and use for a set time off the apps in [pkgs]; that loosens */
    private fun removeLimits(pkgs: Set<String>) = gate.loosen {
        pkgs.forEach { Limits.setRule(prefs, it, Limits.Rule()) }
        prefs.pausedApps = prefs.pausedApps - pkgs
        prefs.useForApps = prefs.useForApps - pkgs
        render()
    }

    /** An app with limits: its name and rules, today's use under it, and a bar against its time limit */
    private fun limitRow(pkg: String, label: String): View {
        val context = requireContext()
        val rule = Limits.rule(prefs, pkg)
        val used = Limits.usedToday(context, pkg)
        val today = java.time.LocalDate.now().dayOfWeek
        fun text(size: Float, alpha: Int) = android.widget.TextView(context, null, 0, R.style.TextSmall).apply {
            textSize = size
            setTextColor(faint(alpha))
        }
        // Across, so a tick mark can go before it when picking several
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(4.dpToPx(), 10.dpToPx(), 4.dpToPx(), 10.dpToPx())
            setBackgroundResource(android.R.drawable.list_selector_background)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(text(Style.TEXT_BODY, 255).apply {
                        text = label
                        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(text(Style.TEXT_BODY, 200).apply { text = summary(pkg) })
                })
                addView(text(Style.TEXT_SMALL, 150).apply {
                    text = listOfNotNull(getString(R.string.lockdown_used_today, Formats.duration(used)),
                        resources.getQuantityString(R.plurals.lockdown_opens, Limits.opensToday(context, pkg), Limits.opensToday(context, pkg)).takeIf { rule.opensOn(today) > 0 }
                    ).joinToString(" · ")
                    updatePadding(top = 2.dpToPx())
                })
                if (rule.minutesOn(today) > 0) addView(LimitBar(context, listOf(rule.minutesOn(today)), used, ink),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 22.dpToPx()))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /** An app's rules in a few words, with today's limits: "30m · 3 opens · gentle · pause" */
    private fun summary(pkg: String): String {
        val rule = Limits.rule(prefs, pkg)
        val today = java.time.LocalDate.now().dayOfWeek
        AppPause.migrateIfNeeded(prefs)
        return listOfNotNull(
            rule.minutesOn(today).takeIf { it > 0 }?.let { minutesName(it) },
            rule.opensOn(today).takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.lockdown_opens, it, it) },
            getString(R.string.limit_gentle).lowercase(Locale.getDefault()).takeIf { rule.gentle && !rule.isEmpty },
            getString(R.string.lockdown_pause_short).takeIf { pkg in prefs.pausedApps },
            getString(R.string.lockdown_use_for_short).takeIf { pkg in prefs.useForApps },
        ).joinToString(" · ").replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    /** A day-by-day value in a few words: the one value when every day is the same, otherwise "Varies" */
    private fun daysName(values: List<Int>, name: (Int) -> String) =
        if (values.distinct().size == 1) name(values[0]) else getString(R.string.limit_varies)

    private fun opensName(n: Int) = if (n <= 0) getString(R.string.off) else n.toString()

    /**
     * A limit day by day: "Every day" sets all seven, each day sets its own; [values] is Monday first,
     * [choices] what can be picked, [onPick] gets the day (null for every day) and the value
     */
    private fun pickDays(title: String, values: List<Int>, choices: List<Int>, name: (Int) -> String, onPick: (java.time.DayOfWeek?, Int) -> Unit) {
        val days = java.time.DayOfWeek.values()
        val rows = listOf(getString(R.string.lockdown_every_day) + " · " + daysName(values, name)) +
            days.map { it.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault()) + " · " + name(values[it.value - 1]) }
        AppDialog(requireContext())
            .setTitle(title)
            .setItems(rows.toTypedArray()) { _, which ->
                val day = if (which == 0) null else days[which - 1]
                val current = if (day == null) values.distinct().singleOrNull() ?: -1 else values[day.value - 1]
                choose(rows[which].substringBefore(" · "), choices.map(name), choices.indexOf(current)) { onPick(day, choices[it]) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** One app's rules, in a sheet over the Limits page: each tapped to change, the sheet coming back after */
    private fun appSheet(pkg: String) {
        val context = requireContext()
        AppPause.migrateIfNeeded(prefs)
        val rule = Limits.rule(prefs, pkg)
        val allow = prefs.lockdownAppMode == Lockdown.ALLOW
        val inLockdown = if (allow) pkg in prefs.lockdownAllowedApps else pkg in prefs.lockdownBlockedApps
        val options = listOf(
            getString(R.string.lockdown_daily_time) + " · " + daysName(rule.minutes) { minutesName(it) },
            getString(R.string.lockdown_opens_per_day) + " · " + daysName(rule.opens) { opensName(it) },
            getString(R.string.limit_when_reached) + " · " + getString(if (rule.gentle) R.string.limit_gentle else R.string.limit_strict),
            getString(R.string.lockdown_pause) + " · " + onOff(pkg in prefs.pausedApps),
            getString(R.string.use_for_a_set_time) + " · " + onOff(pkg in prefs.useForApps),
            getString(if (allow) R.string.lockdown_allowed_in else R.string.lockdown_blocked_in) + " · " + getString(if (inLockdown) R.string.yes else R.string.no),
        )
        val again = { if (isAdded) { render(); appSheet(pkg) } }
        AppDialog(context)
            .setTitle(appLabel(pkg) + " · " + Formats.duration(Limits.usedToday(context, pkg)))
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> pickDays(getString(R.string.lockdown_daily_time), rule.minutes, Limits.MINUTE_CHOICES, { minutesName(it) }) { day, value ->
                        setRule(pkg, rule, rule.withMinutes(day, value), again)
                    }
                    1 -> pickDays(getString(R.string.lockdown_opens_per_day), rule.opens, Limits.OPEN_CHOICES, { opensName(it) }) { day, value ->
                        setRule(pkg, rule, rule.withOpens(day, value), again)
                    }
                    2 -> {
                        val modes = listOf(getString(R.string.limit_strict), getString(R.string.limit_gentle))
                        choose(getString(R.string.limit_when_reached), modes, if (rule.gentle) 1 else 0) {
                            setRule(pkg, rule, rule.copy(gentle = it == 1), again)
                        }
                    }
                    3 -> if (pkg in prefs.pausedApps) gate.loosen { prefs.pausedApps = prefs.pausedApps - pkg; again() }
                        else { prefs.pausedApps = prefs.pausedApps + pkg; again() }
                    4 -> if (pkg in prefs.useForApps) gate.loosen { prefs.useForApps = prefs.useForApps - pkg; again() }
                        else {
                            prefs.useForApps = prefs.useForApps + pkg
                            again()
                            if (!isAccessServiceEnabled(context)) runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        }
                    5 -> if (allow) {
                        if (inLockdown) { prefs.lockdownAllowedApps = prefs.lockdownAllowedApps - pkg; again() }
                        else gate.loosen { prefs.lockdownAllowedApps = prefs.lockdownAllowedApps + pkg; again() }
                    } else {
                        if (inLockdown) gate.loosen { prefs.lockdownBlockedApps = prefs.lockdownBlockedApps - pkg; again() }
                        else { prefs.lockdownBlockedApps = prefs.lockdownBlockedApps + pkg; again() }
                    }
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** Lowering or adding a limit, or making it strict, is stricter; raising or taking one off, or making it gentle, loosens */
    private fun setRule(pkg: String, old: Limits.Rule, new: Limits.Rule, then: () -> Unit) {
        val change = { Limits.setRule(prefs, pkg, new); then() }
        if (Limits.loosens(old, new)) gate.loosen(change) else change()
    }
}
