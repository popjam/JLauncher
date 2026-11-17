package app.olauncher.ui

import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.helper.AppPause
import app.olauncher.helper.Limits
import app.olauncher.helper.Lockdown
import app.olauncher.helper.Places
import app.olauncher.helper.Social
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getAppsList
import app.olauncher.helper.isPackageInstalled
import app.olauncher.helper.showToast
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Lockdown's own pages, by [PAGE]: the blocked, allowed and essential apps; websites; places; screen
 * time stages; app limits; and one app's rules ([PKG]).
 *
 * In each, anything that makes lockdown stricter is done straight away, and anything that loosens it
 * goes through the unlock (while lockdown is on). Apps added here by mistake can be taken off again
 * while the page is open.
 */
class LockdownPageFragment : LockdownBaseFragment() {

    companion object {
        const val PAGE = "page"
        const val PKG = "pkg"
        const val BLOCKED = "blocked"
        const val ALLOWED = "allowed"
        const val ESSENTIALS = "essentials"
        const val WEBSITES = "websites"
        const val PLACES = "places"
        const val STAGES = "stages"
        const val LIMITS = "limits"
        const val APP = "app"
    }

    private val page get() = arguments?.getString(PAGE) ?: BLOCKED
    private val pkg get() = arguments?.getString(PKG).orEmpty()

    /** Every app, once read */
    private var apps: List<AppModel.App>? = null
    private var query = ""

    /** Added since the page opened, so a mistake can be undone without the unlock */
    private val addedHere = mutableSetOf<String>()

    override fun title(): String = when (page) {
        BLOCKED -> getString(R.string.blocked_apps)
        ALLOWED -> getString(R.string.lockdown_allowed_apps)
        ESSENTIALS -> getString(R.string.lockdown_essentials)
        WEBSITES -> getString(R.string.lockdown_websites)
        PLACES -> getString(R.string.lockdown_places)
        STAGES -> getString(R.string.lockdown_stages)
        LIMITS -> getString(R.string.lockdown_app_limits)
        else -> appLabel(pkg)
    }

    override fun top(): View? = when (page) {
        BLOCKED, ALLOWED, ESSENTIALS -> box(requireContext(), R.string.lockdown_search_apps, { query = it; render() })
        WEBSITES -> box(requireContext(), R.string.lockdown_add_site, {}) { addSite(it) }
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
    }

    private fun appLabel(pkg: String): String = apps?.firstOrNull { it.appPackage == pkg }?.appLabel ?: runCatching {
        val pm = requireContext().packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    override fun build() {
        when (page) {
            BLOCKED, ALLOWED, ESSENTIALS -> appsPage()
            WEBSITES -> websitesPage()
            PLACES -> placesPage()
            STAGES -> stagesPage()
            LIMITS -> limitsPage()
            APP -> appPage()
        }
    }

    // --- Blocked, allowed and essential apps ---

    private fun chosen(): Set<String> = when (page) {
        BLOCKED -> prefs.lockdownBlockedApps
        ALLOWED -> prefs.lockdownAllowedApps
        else -> prefs.lockdownEssentials
    }

    private fun setChosen(apps: Set<String>) = when (page) {
        BLOCKED -> prefs.lockdownBlockedApps = apps
        ALLOWED -> prefs.lockdownAllowedApps = apps
        else -> prefs.lockdownEssentials = apps
    }

    /** Blocking more is stricter; allowing more, or more essentials, loosens */
    private fun addLoosens() = page != BLOCKED

    private fun appsPage() {
        val context = requireContext()
        val all = apps ?: return
        val chosen = chosen()
        val fixed = if (page == ESSENTIALS) all.filter { !Lockdown.canBlock(context, it.appPackage) } else emptyList()
        val listed = all.filter { Lockdown.canBlock(context, it.appPackage) }
        val title = when (page) {
            BLOCKED -> R.string.lockdown_blocked_count_title
            ALLOWED -> R.string.lockdown_allowed_count_title
            else -> R.string.lockdown_essentials_count_title
        }
        val inList = listed.filter { it.appPackage in chosen }
        val card = section(getString(title, inList.size + fixed.size))
        card.add(chips(fixed.map { chip(it.appLabel, on = true) { } } + inList.map { app ->
            chip(app.appLabel, on = true, removable = true) { remove(app.appPackage) }
        }))
        val matches = listed.filter { it.appPackage !in chosen && (query.isBlank() || it.appLabel.lowercase(Locale.getDefault()).contains(query.trim().lowercase(Locale.getDefault()))) }
        val add = section(getString(R.string.add))
        add.add(*matches.map { app -> row(add, app.appLabel, null, null) { addApp(app.appPackage) } }.toTypedArray())
    }

    private fun addApp(pkg: String) {
        val add = {
            setChosen(chosen() + pkg)
            addedHere += pkg
            render()
        }
        if (addLoosens()) gate.loosen(add) else add()
    }

    private fun remove(pkg: String) {
        val remove = {
            setChosen(chosen() - pkg)
            addedHere -= pkg
            render()
        }
        if (addLoosens() || pkg in addedHere) remove() else gate.loosen(remove)
    }

    // --- Websites ---

    private fun websitesPage() {
        val context = requireContext()
        val sites = prefs.lockdownSites.sorted()
        val card = section(getString(R.string.lockdown_sites_count_title, sites.size))
        card.add(chips(sites.map { site -> chip(site, on = true, removable = true) { removeSite(site) } }))
        val fromApps = prefs.lockdownBlockedApps.flatMap { pkg -> Limits.APP_SITES[pkg].orEmpty().map { it to pkg } }
        if (fromApps.isNotEmpty()) {
            val apps = section(getString(R.string.lockdown_sites_from_apps))
            apps.add(*fromApps.map { (site, pkg) -> row(apps, site, appLabel(pkg), R.string.info_sites_from_apps, onTap = null) }.toTypedArray())
        }
        if (listOf("com.android.chrome", "org.mozilla.firefox").none { runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess }) {
            card.add(line(card, getString(R.string.lockdown_no_browser)))
        }
    }

    private fun addSite(text: String) {
        val site = Limits.cleanSite(text) ?: return requireContext().showToast(getString(R.string.lockdown_not_a_site))
        prefs.lockdownSites = prefs.lockdownSites + site
        addedHere += site
        (view?.findFocus() as? android.widget.EditText)?.setText("")
        render()
    }

    private fun removeSite(site: String) {
        val remove = {
            prefs.lockdownSites = prefs.lockdownSites - site
            addedHere -= site
            render()
        }
        if (site in addedHere) remove() else gate.loosen(remove)
    }

    // --- Places ---

    private fun placesPage() {
        val context = requireContext()
        val known = buildList {
            Social.homes(context).forEachIndexed { slot, home -> if (home != null) add(slot to home.name) }
            Social.work(context)?.let { add(Social.WORK to it.name) }
            Places.places(context).forEach { add(it.id to it.name) }
        }
        val chosen = prefs.lockdownPlaces
        val card = section(getString(R.string.lockdown_on_at))
        if (known.isEmpty()) return card.add(line(card, getString(R.string.lockdown_no_places)))
        card.add(chips(known.filter { it.first in chosen }.map { (id, name) ->
            chip(name, on = true, removable = true) {
                val remove = { prefs.lockdownPlaces = prefs.lockdownPlaces - id; addedHere -= id.toString(); render() }
                if (id.toString() in addedHere) remove() else gate.loosen(remove)
            }
        }))
        val add = section(getString(R.string.add))
        add.add(*known.filter { it.first !in chosen }.map { (id, name) ->
            row(add, name, null, null) {
                val save = { prefs.lockdownPlaces = prefs.lockdownPlaces + id; addedHere += id.toString(); render() }
                val here = runCatching { Social.currentPlace(context) }.getOrNull() == id
                if (here && !Lockdown.isActive(context, prefs)) AlertDialog.Builder(context)
                    .setTitle(R.string.lockdown_starts_now)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.add) { _, _ -> save() }
                    .show()
                else save()
            }
        }.toTypedArray())
    }

    // --- Screen time stages ---

    private fun stagesPage() {
        val context = requireContext()
        val stages = Limits.stages(prefs)
        val used = Limits.totalToday(context)
        val today = section(getString(R.string.lockdown_today), LockdownGate.duration(context, used))
        today.root.addView(StageBar(context, stages, used, ink, labels = true), today.root.indexOfChild(today.scene),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 64.dpToPx()))
        val card = section("")
        (card.titleView.parent as? View)?.isVisible = false
        card.add(*stages.mapIndexed { i, minutes ->
            row(card, getString(R.string.lockdown_stage_row, i + 1, LockdownGate.stageName(context, i + 1)), minutesName(minutes), stageInfo(i + 1)) {
                pickStage(i)
            }
        }.toTypedArray())
        val essentials = section(getString(R.string.lockdown_essentials_count_title, prefs.lockdownEssentials.count { context.isPackageInstalled(it) } + 1))
        val all = apps.orEmpty()
        essentials.add(chips(all.filter { !Lockdown.canBlock(context, it.appPackage) || it.appPackage in prefs.lockdownEssentials }
            .map { chip(it.appLabel, on = true) { open(ESSENTIALS) } }))
        essentials.root.setOnClickListener { open(ESSENTIALS) }
    }

    private fun stageInfo(stage: Int) = when (stage) {
        1 -> R.string.info_stage_slow
        2 -> R.string.info_stage_block
        else -> R.string.info_stage_lockdown
    }

    /** A stage's time: later or off loosens; times stay in order */
    private fun pickStage(index: Int) {
        val context = requireContext()
        val stages = Limits.stages(prefs)
        val current = stages[index]
        val choices = Limits.STAGE_CHOICES.filter { Limits.fitsStage(stages, index, it) }
        // A stage that's off starts at its usual time (2h, 3h, 4h) when that still fits
        val shown = if (current <= 0 && Limits.STAGE_DEFAULTS[index] in choices) Limits.STAGE_DEFAULTS[index] else current
        choose(getString(R.string.lockdown_stage_row, index + 1, LockdownGate.stageName(context, index + 1)), choices.map { minutesName(it) },
            choices.indexOf(shown), suggested = shown != current) { which ->
            val minutes = choices[which]
            val change = {
                prefs.lockdownStages = Limits.formatStages(Limits.stages(prefs).toMutableList().also { it[index] = minutes })
                render()
            }
            if (Limits.loosens(current, minutes)) gate.loosen(change) else change()
        }
    }

    // --- App limits ---

    private fun limitsPage() {
        val context = requireContext()
        val all = apps ?: return
        val withRules = all.filter { summary(it.appPackage).isNotEmpty() }
        if (withRules.isNotEmpty()) {
            val card = section(getString(R.string.lockdown_with_limits))
            card.add(*withRules.map { app -> row(card, app.appLabel, summary(app.appPackage), null) { open(APP, app.appPackage) } }.toTypedArray())
        }
        val others = section(getString(R.string.lockdown_other_apps))
        others.add(*all.filter { it !in withRules && Lockdown.canBlock(context, it.appPackage) }
            .map { app -> row(others, app.appLabel, null, null) { open(APP, app.appPackage) } }.toTypedArray())
        val before = section(getString(R.string.lockdown_before_opening))
        before.add(
            row(before, getString(R.string.pause_length), getString(R.string.seconds_value, prefs.pauseSeconds), R.string.info_pause_length) {
                val options = listOf(5, 10, 15, 20, 30, 45, 60)
                choose(getString(R.string.pause_length), options.map { getString(R.string.seconds_value, it) }, options.indexOf(prefs.pauseSeconds)) {
                    val seconds = options[it]
                    if (seconds < prefs.pauseSeconds) gate.loosen { prefs.pauseSeconds = seconds; render() } else { prefs.pauseSeconds = seconds; render() }
                }
            },
            row(before, getString(R.string.use_for_lengths), AppPause.useForLengths(context).joinToString(", ") { getString(R.string.minutes_short, it) },
                R.string.info_use_for_lengths) {
                val sets = AppPause.USE_FOR_SETS
                choose(getString(R.string.use_for_lengths), sets.map { set -> set.joinToString(", ") { getString(R.string.minutes_short, it) } },
                    sets.indexOf(AppPause.useForLengths(context))) {
                    val change = { AppPause.setUseForSet(context, it); render() }
                    // Longer times loosen
                    if (sets[it].max() > AppPause.useForLengths(context).max()) gate.loosen(change) else change()
                }
            },
        )
    }

    /** An app's rules in a few words: "30m · 3 opens · pause" */
    private fun summary(pkg: String): String {
        val rule = Limits.rule(prefs, pkg)
        AppPause.migrateIfNeeded(prefs)
        return listOfNotNull(
            rule.minutes.takeIf { it > 0 }?.let { minutesName(it) },
            rule.opens.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.lockdown_opens, it, it) },
            getString(R.string.lockdown_pause_short).takeIf { pkg in prefs.pausedApps },
            getString(R.string.lockdown_use_for_short).takeIf { pkg in prefs.useForApps },
            getString(R.string.lockdown_blocked_short).takeIf { pkg in prefs.lockdownBlockedApps },
        ).joinToString(" · ").replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    // --- One app's rules ---

    private fun appPage() {
        val context = requireContext()
        AppPause.migrateIfNeeded(prefs)
        val rule = Limits.rule(prefs, pkg)
        val used = Limits.usedToday(context, pkg)
        val opens = Limits.opensToday(context, pkg)
        val today = Card("")
        today.scene.visibility = View.GONE
        (today.titleView.parent as? View)?.isVisible = false
        today.add(android.widget.TextView(context, null, 0, R.style.TextLarge).apply {
            text = getString(R.string.lockdown_used_today, LockdownGate.duration(context, used))
            textSize = 26f
            setTextColor(ink)
        }, today.textView(14f, 165).apply {
            text = listOfNotNull(resources.getQuantityString(R.plurals.lockdown_opens, opens, opens),
                rule.minutes.takeIf { it > 0 }?.let { getString(R.string.lockdown_limit, minutesName(it)) }).joinToString(" · ")
            updatePadding(top = 4.dpToPx())
        })
        if (rule.minutes > 0) today.root.addView(StageBar(context, listOf(rule.minutes), used, ink, labels = false), today.root.indexOfChild(today.scene),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 26.dpToPx()))

        val limits = section(getString(R.string.lockdown_limits))
        limits.add(
            row(limits, getString(R.string.lockdown_daily_time), minutesName(rule.minutes), R.string.info_daily_time) {
                choose(getString(R.string.lockdown_daily_time), Limits.MINUTE_CHOICES.map { minutesName(it) }, Limits.MINUTE_CHOICES.indexOf(rule.minutes)) {
                    setRule(rule, rule.copy(minutes = Limits.MINUTE_CHOICES[it]))
                }
            },
            row(limits, getString(R.string.lockdown_opens_per_day), if (rule.opens <= 0) getString(R.string.off) else rule.opens.toString(), R.string.info_opens_per_day) {
                choose(getString(R.string.lockdown_opens_per_day), Limits.OPEN_CHOICES.map { if (it <= 0) getString(R.string.off) else it.toString() },
                    Limits.OPEN_CHOICES.indexOf(rule.opens)) { setRule(rule, rule.copy(opens = Limits.OPEN_CHOICES[it])) }
            },
        )
        val before = section(getString(R.string.lockdown_before_opening))
        before.add(
            row(before, getString(R.string.lockdown_pause), onOff(pkg in prefs.pausedApps), R.string.info_pause) {
                if (pkg in prefs.pausedApps) gate.loosen { prefs.pausedApps = prefs.pausedApps - pkg; render() }
                else { prefs.pausedApps = prefs.pausedApps + pkg; render() }
            },
            row(before, getString(R.string.use_for_a_set_time), onOff(pkg in prefs.useForApps), R.string.info_use_for) {
                if (pkg in prefs.useForApps) gate.loosen { prefs.useForApps = prefs.useForApps - pkg; render() }
                else {
                    prefs.useForApps = prefs.useForApps + pkg
                    render()
                    if (!app.olauncher.helper.isAccessServiceEnabled(context)) runCatching {
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                }
            },
        )
        val lockdown = section(getString(R.string.lockdown))
        val allow = prefs.lockdownAppMode == Lockdown.ALLOW
        lockdown.add(if (allow) row(lockdown, getString(R.string.lockdown_allowed_in), getString(if (pkg in prefs.lockdownAllowedApps) R.string.yes else R.string.no),
            R.string.info_allowed_apps) {
            if (pkg in prefs.lockdownAllowedApps) { prefs.lockdownAllowedApps = prefs.lockdownAllowedApps - pkg; render() }
            else gate.loosen { prefs.lockdownAllowedApps = prefs.lockdownAllowedApps + pkg; render() }
        } else row(lockdown, getString(R.string.lockdown_blocked_in), getString(if (pkg in prefs.lockdownBlockedApps) R.string.yes else R.string.no),
            R.string.info_blocked_apps) {
            if (pkg in prefs.lockdownBlockedApps) gate.loosen { prefs.lockdownBlockedApps = prefs.lockdownBlockedApps - pkg; render() }
            else { prefs.lockdownBlockedApps = prefs.lockdownBlockedApps + pkg; render() }
        })
    }

    /** Lowering or adding a limit is stricter; raising or taking one off loosens */
    private fun setRule(old: Limits.Rule, new: Limits.Rule) {
        val change = { Limits.setRule(prefs, pkg, new); render() }
        if (Limits.loosens(old.minutes, new.minutes) || Limits.loosens(old.opens, new.opens)) gate.loosen(change) else change()
    }
}
