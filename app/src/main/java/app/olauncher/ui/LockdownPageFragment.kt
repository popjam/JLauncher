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
 * Lockdown's long lists, each a page of its own, by [PAGE]: the blocked apps and websites; the
 * allowed and always allowed apps; and Limits, each app's limits ([APP] opens one app's straight away).
 *
 * In each, anything that makes lockdown stricter is done straight away, and anything that loosens it
 * goes through the unlock (while lockdown is on). Things added here by mistake can be taken off again
 * while the page is open. Holding (or tapping) something in a list gives its menu: Edit where it can
 * be changed, Remove, and Select to remove several.
 */
class LockdownPageFragment : LockdownBaseFragment() {

    companion object {
        const val PAGE = "page"
        const val PKG = "pkg"
        const val BLOCKS = "blocks"
        const val ALLOWED = "allowed"
        const val ESSENTIALS = "essentials"
        const val LIMITS = "limits"
        const val APP = "app"
    }

    private val page get() = arguments?.getString(PAGE)?.takeIf { it in listOf(BLOCKS, ALLOWED, ESSENTIALS, LIMITS, APP) } ?: BLOCKS
    private val pkg get() = arguments?.getString(PKG).orEmpty()

    /** Every app, once read */
    private var apps: List<AppModel.App>? = null
    private var query = ""

    private val siteList = HoldList<String>(onRemove = { removeItems(it) }, tapOpensMenu = true)
    private val appList = HoldList<String>(onRemove = { removeItems(it) }, tapOpensMenu = true)
    private val chosenList = HoldList<String>(onRemove = { removeChosen(it) }, tapOpensMenu = true)
    private val limitList = HoldList<String>(onRemove = { removeLimits(it) })
    override val holdLists: List<HoldList<*>> get() = listOf(siteList, appList, chosenList, limitList)

    override fun title(): String = when (page) {
        BLOCKS -> getString(R.string.lockdown_apps_and_sites)
        ALLOWED -> getString(R.string.lockdown_allowed_apps)
        ESSENTIALS -> getString(R.string.lockdown_always_allowed)
        LIMITS -> getString(R.string.limits)
        else -> appLabel(pkg)
    }

    override fun icon() = if (page == LIMITS) StatIcons.LOCK else null

    override fun top(): View? = when (page) {
        BLOCKS -> box(requireContext(), R.string.lockdown_search_or_add, { query = it; render() }) { addSite(it) }
        ALLOWED, ESSENTIALS, LIMITS -> box(requireContext(), R.string.lockdown_search_apps, { query = it; render() })
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
            ALLOWED, ESSENTIALS -> appsPage()
            LIMITS, APP -> limitsPage()
        }
    }

    private fun matchesQuery(text: String) = query.isBlank() || text.lowercase(Locale.getDefault()).contains(query.trim().lowercase(Locale.getDefault()))

    // --- Apps and websites ---

    /** An app that can be blocked: its name, and where it is */
    private data class Candidate(val name: String, val pkg: String?, val bundle: String?, val tvName: String? = null)

    /** Every app on the phone, the Mac and the TV, one per name */
    private fun candidates(): List<Candidate> {
        val context = requireContext()
        val byKey = linkedMapOf<String, Candidate>()
        apps.orEmpty().filter { Lockdown.canBlock(context, it.appPackage) }.forEach {
            byKey[BlockItems.key(it.appLabel)] = Candidate(it.appLabel, it.appPackage, null)
        }
        if (Lockdown.macPaired(context)) LockdownSync.macInstalled(context).forEach { (bundle, name) ->
            val key = BlockItems.key(name)
            byKey[key] = byKey[key]?.copy(bundle = bundle) ?: Candidate(name, null, bundle)
        }
        LockdownSync.tvInstalled(context).forEach { name ->
            val key = BlockItems.key(name)
            byKey[key] = byKey[key]?.copy(tvName = name) ?: Candidate(name, null, null, name)
        }
        return byKey.values.sortedBy { it.name.lowercase(Locale.getDefault()) }
    }

    private fun effectiveItems() = requireContext().let { BlockItems.effective(prefs.lockdownItems, LockdownSync.macInstalled(it), LockdownSync.tvInstalled(it)) }

    private fun blocksPage() {
        val context = requireContext()
        val items = effectiveItems()
        val sites = items.values.filter { it.kind == BlockItems.Kind.SITE && it.shown && matchesQuery(it.name) }.sortedBy { it.name }
        val fromApps = BlockItems.phoneApps(items).flatMap { pkg -> Limits.APP_SITES[pkg].orEmpty() }
        val siteRows = sites.map { it to itemRow(it) }
        show(Rows(listOf(SettingLabel(getString(R.string.lockdown_websites_count, sites.size))) + siteRows.map { it.second } +
            listOfNotNull(if (sites.isEmpty()) noneRow("noSites") else null,
                if (fromApps.isNotEmpty()) SettingView(note(getString(R.string.lockdown_sites_from_apps_line, fromApps.joinToString(", ")))) else null)) { views ->
            join(siteList, views, siteRows)
        })

        val blockedApps = items.values.filter { it.kind == BlockItems.Kind.APP && it.shown && matchesQuery(it.name) }
            .filter { it.bundle != null || it.tvName != null || it.pkg == null || context.isPackageInstalled(it.pkg) }
            .sortedBy { it.name.lowercase(Locale.getDefault()) }
        val appRows = blockedApps.map { it to itemRow(it) }
        show(Rows(listOf(SettingLabel(getString(R.string.lockdown_apps_count_title, blockedApps.size))) + appRows.map { it.second } +
            listOfNotNull(if (blockedApps.isEmpty()) noneRow("noApps") else null)) { views ->
            join(appList, views, appRows)
        })

        if (apps == null) return
        val shownKeys = blockedApps.map { BlockItems.key(it.name) }.toSet()
        val others = candidates().filter { BlockItems.key(it.name) !in shownKeys && items[BlockItems.idFor(BlockItems.Kind.APP, it.name)]?.shown != true && matchesQuery(it.name) }
        show(Rows(listOf(SettingLabel(getString(R.string.lockdown_add_apps))) + others.map { c ->
            row("add:" + c.name, c.name, R.string.info_lockdown_block_app) { block(BlockItems.Item(BlockItems.Kind.APP, c.name, c.pkg, c.bundle, tvName = c.tvName)) }
        }))
    }

    /** Blocked apps or sites: a menu with Remove and Select */
    private fun join(holdList: HoldList<String>, views: Map<String, View>, rows: List<Pair<BlockItems.Item, SettingRow>>) {
        val list = rows.firstOrNull()?.let { views.listHolding(it.second.key) } ?: return
        holdList.attach(list, scrollView())
        rows.forEach { (item, row) ->
            val view = views[row.key] ?: return@forEach
            holdList.item(view, item.id, label = item.name, about = row.about,
                actions = listOf(HoldList.Action(getString(R.string.remove)) { removeItems(setOf(item.id)) }))
        }
    }

    /** A blocked app or site: its name, and the switched-on devices that block it (each blocks what it can) */
    private fun itemRow(item: BlockItems.Item): SettingRow {
        val context = requireContext()
        val where = Lockdown.deviceList(context).filter { device ->
            when (device) {
                Lockdown.Device.PHONE -> item.onPhone
                Lockdown.Device.MAC -> item.onMac
                Lockdown.Device.TV -> item.onTv
            }
        }
        return row("item:" + item.id, item.name, R.string.info_lockdown_blocked_item, value = where.joinToString(" · ") { LockdownGate.deviceName(context, it.bit) })
    }

    /** Blocks [item] on every device */
    private fun block(item: BlockItems.Item) {
        prefs.lockdownItems = BlockItems.block(prefs.lockdownItems, item.kind, item.name, true, System.currentTimeMillis(), item.pkg, item.bundle, item.tvName)
        addedHere += item.id
        render()
        if (item.onPhone && !isAccessServiceEnabled(requireContext())) runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    /** Unblocks the items with [ids] everywhere; that loosens, unless they were all added here */
    private fun removeItems(ids: Set<String>) {
        val change = {
            val items = effectiveItems()
            ids.mapNotNull { items[it] }.forEach { item ->
                prefs.lockdownItems = BlockItems.block(prefs.lockdownItems, item.kind, item.name, false, System.currentTimeMillis(), item.pkg, item.bundle, item.tvName)
            }
            addedHere -= ids
            render()
        }
        if (ids.all { it in addedHere }) change() else gate.loosen(change)
    }

    private fun addSite(text: String) {
        val site = Limits.cleanSite(text) ?: return requireContext().showToast(getString(R.string.lockdown_not_a_site))
        prefs.lockdownItems = BlockItems.block(prefs.lockdownItems, BlockItems.Kind.SITE, site, true, System.currentTimeMillis())
        addedHere += BlockItems.idFor(BlockItems.Kind.SITE, site)
        (view?.findFocus() as? android.widget.EditText)?.setText("")
        query = ""
        render()
    }

    // --- Allowed and always allowed apps ---

    private fun chosen(): Set<String> = if (page == ALLOWED) prefs.lockdownAllowedApps else prefs.lockdownEssentials

    private fun setChosen(apps: Set<String>) = if (page == ALLOWED) prefs.lockdownAllowedApps = apps else prefs.lockdownEssentials = apps

    private fun appsPage() {
        val context = requireContext()
        val all = apps ?: return
        val chosen = chosen()
        // Apps that can't be blocked are always allowed, and can't be taken off
        val fixed = if (page == ESSENTIALS) all.filter { !Lockdown.canBlock(context, it.appPackage) } else emptyList()
        val listed = all.filter { Lockdown.canBlock(context, it.appPackage) }
        val title = if (page == ALLOWED) R.string.lockdown_allowed_count_title else R.string.lockdown_always_allowed_count_title
        val inList = listed.filter { it.appPackage in chosen }
        val itemAbout = if (page == ALLOWED) R.string.info_lockdown_allowed_item else R.string.info_lockdown_essential_item
        val rows = inList.map { it to row("app:" + it.appPackage, it.appLabel, itemAbout) }
        show(Rows(listOf(SettingLabel(getString(title, inList.size + fixed.size))) +
            fixed.map { row("fixed:" + it.appPackage, it.appLabel, R.string.info_lockdown_fixed_app) } + rows.map { it.second } +
            listOfNotNull(if (fixed.isEmpty() && rows.isEmpty()) noneRow("none") else null)) { views ->
            rows.firstOrNull()?.let { views.listHolding(it.second.key) }?.let { chosenList.attach(it, scrollView()) }
            rows.forEach { (app, row) ->
                val view = views[row.key] ?: return@forEach
                chosenList.item(view, app.appPackage, label = app.appLabel, about = row.about,
                    actions = listOf(HoldList.Action(getString(R.string.remove)) { removeChosen(setOf(app.appPackage)) }))
            }
        })
        val matches = listed.filter { it.appPackage !in chosen && matchesQuery(it.appLabel) }
        val addAbout = if (page == ALLOWED) R.string.info_lockdown_allow_app else R.string.info_lockdown_essential_app
        show(Rows(listOf(SettingLabel(getString(R.string.add))) + matches.map { app -> row("add:" + app.appPackage, app.appLabel, addAbout) { addApp(app.appPackage) } }))
    }

    /** Allowing more loosens */
    private fun addApp(pkg: String) = gate.loosen {
        setChosen(chosen() + pkg)
        render()
    }

    /** Allowing fewer is stricter */
    private fun removeChosen(pkgs: Set<String>) {
        setChosen(chosen() - pkgs)
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
