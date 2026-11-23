package app.olauncher.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.helper.AppPause
import app.olauncher.helper.BlockItems
import app.olauncher.helper.Breaks
import app.olauncher.helper.Challenge
import app.olauncher.helper.Limits
import app.olauncher.helper.Lockdown
import app.olauncher.helper.LockdownSync
import app.olauncher.helper.Places
import app.olauncher.helper.Social
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getAppsList
import app.olauncher.helper.isAccessServiceEnabled
import app.olauncher.helper.isPackageInstalled
import app.olauncher.helper.showToast
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.Locale

/**
 * Lockdown's own pages, by [PAGE]: apps and websites on each device; the allowed and essential apps;
 * places; screen time stages and the screen time that turns lockdown on; breaks; settings changes;
 * ending early; and Limits, each app's daily limits.
 *
 * In each, anything that makes lockdown stricter is done straight away, and anything that loosens it
 * goes through the unlock (while lockdown is on). Things added here by mistake can be taken off again
 * while the page is open.
 */
class LockdownPageFragment : LockdownBaseFragment() {

    companion object {
        const val PAGE = "page"
        const val PKG = "pkg"
        const val BLOCKS = "blocks"
        const val BLOCKED = "blocked"
        const val ALLOWED = "allowed"
        const val ESSENTIALS = "essentials"
        const val WEBSITES = "websites"
        const val PLACES = "places"
        const val STAGES = "stages"
        const val TRIGGERS = "triggers"
        const val BREAKS = "breaks"
        const val LIMITS = "limits"
        const val APP = "app"
        const val PROTECTION = "protection"
        const val UNLOCK = "unlock"
    }

    private val page get() = arguments?.getString(PAGE)?.let { if (it == WEBSITES || it == BLOCKED) BLOCKS else it } ?: BLOCKS
    private val pkg get() = arguments?.getString(PKG).orEmpty()

    /** Every app, once read */
    private var apps: List<AppModel.App>? = null
    private var query = ""

    /** Added since the page opened, so a mistake can be undone without the unlock */
    private val addedHere = mutableSetOf<String>()

    override fun title(): String = when (page) {
        BLOCKS -> getString(R.string.lockdown_apps_and_sites)
        ALLOWED -> getString(R.string.lockdown_allowed_apps)
        PROTECTION -> getString(R.string.lockdown_settings_changes)
        UNLOCK -> getString(R.string.lockdown_ending_early)
        ESSENTIALS -> getString(R.string.lockdown_essentials)
        PLACES -> getString(R.string.lockdown_places)
        STAGES -> getString(R.string.lockdown_stages)
        TRIGGERS -> getString(R.string.lockdown_after_screen_time)
        BREAKS -> getString(R.string.lockdown_breaks)
        LIMITS -> getString(R.string.limits)
        else -> appLabel(pkg)
    }

    override fun showIcon() = page == LIMITS

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
            PLACES -> placesPage()
            STAGES -> stagesPage()
            TRIGGERS -> triggersPage()
            BREAKS -> breaksPage()
            LIMITS, APP -> limitsPage()
            PROTECTION -> protectionPage()
            UNLOCK -> unlockPage()
        }
    }

    private fun matchesQuery(text: String) = query.isBlank() || text.lowercase(Locale.getDefault()).contains(query.trim().lowercase(Locale.getDefault()))

    // --- Apps and websites, on each device ---

    /** An app that can be blocked: its name, and where it is */
    private data class Candidate(val name: String, val pkg: String?, val bundle: String?)

    /** Every app on the phone and the Mac, one per name */
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
        return byKey.values.sortedBy { it.name.lowercase(Locale.getDefault()) }
    }

    private fun blocksPage() {
        val context = requireContext()
        val paired = Lockdown.macPaired(context)
        val matched = prefs.lockdownMatchDevices && paired
        val stored = prefs.lockdownItems
        val items = if (matched) BlockItems.matched(stored) else stored
        modeSection()
        if (matched) {
            val note = section("")
            (note.titleView.parent as? View)?.isVisible = false
            note.add(line(note, getString(R.string.lockdown_matched_note)))
        }
        val sites = items.values.filter { it.kind == BlockItems.Kind.SITE && it.shown && matchesQuery(it.name) }.sortedBy { it.name }
        val siteCard = section(getString(R.string.lockdown_websites_count, sites.size))
        if (sites.isEmpty()) siteCard.add(line(siteCard, getString(R.string.lockdown_add_site_hint)))
        siteCard.add(*sites.map { itemRow(siteCard, it, paired, matched) }.toTypedArray())
        val fromApps = BlockItems.phoneApps(items).flatMap { pkg -> Limits.APP_SITES[pkg].orEmpty().map { it to pkg } }
        if (fromApps.isNotEmpty()) siteCard.add(line(siteCard, getString(R.string.lockdown_sites_from_apps_line,
            fromApps.joinToString(", ") { it.first })))

        val blockedApps = items.values.filter { it.kind == BlockItems.Kind.APP && it.shown && matchesQuery(it.name) }
            .filter { it.bundle != null || it.pkg == null || context.isPackageInstalled(it.pkg) }
            .sortedBy { it.name.lowercase(Locale.getDefault()) }
        val appCard = section(getString(R.string.lockdown_apps_count_title, blockedApps.size))
        if (blockedApps.isEmpty()) appCard.add(line(appCard, getString(R.string.lockdown_none_chosen)))
        appCard.add(*blockedApps.map { itemRow(appCard, it, paired, matched) }.toTypedArray())

        val shownKeys = blockedApps.map { BlockItems.key(it.name) }.toSet()
        val others = candidates().filter { BlockItems.key(it.name) !in shownKeys && items[BlockItems.idFor(BlockItems.Kind.APP, it.name)]?.shown != true && matchesQuery(it.name) }
        if (apps == null) return
        val add = section(getString(R.string.lockdown_add_apps))
        add.add(*others.map { c ->
            itemRow(add, BlockItems.Item(BlockItems.Kind.APP, c.name, c.pkg, c.bundle), paired, matched)
        }.toTypedArray())
    }

    /** A row for an app or site: its name, and a switch for each device */
    private fun itemRow(card: Card, item: BlockItems.Item, paired: Boolean, matched: Boolean): View {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4.dpToPx(), 9.dpToPx(), 0, 9.dpToPx())
            addView(card.textView(15.5f, 240).apply {
                text = item.name
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(devicePill(Lockdown.Device.PHONE, item.phone, item.onPhone) { toggle(item, Lockdown.Device.PHONE, matched) })
            if (paired) addView(devicePill(Lockdown.Device.MAC, item.mac, item.onMac) { toggle(item, Lockdown.Device.MAC, matched) })
        }
    }

    /** Blocks or unblocks [item] on [device] (both, while matched); unblocking loosens unless it was added here */
    private fun toggle(item: BlockItems.Item, device: Lockdown.Device, matched: Boolean) {
        val on = if (device == Lockdown.Device.PHONE) item.phone else item.mac
        val now = System.currentTimeMillis()
        val change = {
            val phone = if (matched || device == Lockdown.Device.PHONE) (!on && item.onPhone) else null
            val mac = if (matched || device == Lockdown.Device.MAC) (!on && item.onMac) else null
            prefs.lockdownItems = BlockItems.set(prefs.lockdownItems, item.kind, item.name, phone, mac, now, item.pkg, item.bundle)
            if (!on) addedHere += item.id + device.name
            render()
            if (!on && !isAccessServiceEnabled(requireContext()) && device == Lockdown.Device.PHONE) runCatching {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        if (on && item.id + device.name !in addedHere) gate.loosen(change) else change()
    }

    private fun addSite(text: String) {
        val site = Limits.cleanSite(text) ?: return requireContext().showToast(getString(R.string.lockdown_not_a_site))
        val context = requireContext()
        // On the devices Start uses, or both while they're matched
        val devices = Lockdown.startDevices(context, prefs)
        prefs.lockdownItems = BlockItems.set(prefs.lockdownItems, BlockItems.Kind.SITE, site,
            phone = Lockdown.covers(devices, Lockdown.Device.PHONE).takeIf { it }, mac = Lockdown.covers(devices, Lockdown.Device.MAC).takeIf { it },
            now = System.currentTimeMillis())
        Lockdown.Device.values().forEach { addedHere += BlockItems.idFor(BlockItems.Kind.SITE, site) + it.name }
        (view?.findFocus() as? android.widget.EditText)?.setText("")
        query = ""
        render()
    }

    /** Block a list of apps on the phone, or allow only a list; and the apps always left open */
    private fun modeSection() {
        val context = requireContext()
        val allow = prefs.lockdownAppMode == Lockdown.ALLOW
        val card = section("")
        (card.titleView.parent as? View)?.isVisible = false
        card.add(
            row(card, getString(R.string.lockdown_during_phone), getString(if (allow) R.string.lockdown_only_allow else R.string.lockdown_block_these),
                R.string.info_lockdown_during) { pickMode() },
            if (allow) row(card, getString(R.string.lockdown_allowed_apps), prefs.lockdownAllowedApps.count { context.isPackageInstalled(it) }.toString(),
                R.string.info_allowed_apps) { open(ALLOWED) } else null,
            row(card, getString(R.string.lockdown_essentials), (prefs.lockdownEssentials.count { context.isPackageInstalled(it) } + 1).toString(),
                R.string.info_lockdown_essentials) { open(ESSENTIALS) },
        )
    }

    /**
     * Blocking a list, or allowing only a list. Leaving the allowlist loosens; going to it is stricter
     * unless it allows an app that's blocked now.
     */
    private fun pickMode() {
        val options = listOf(getString(R.string.lockdown_block_these), getString(R.string.lockdown_only_allow))
        choose(getString(R.string.lockdown_during_phone), options, prefs.lockdownAppMode) { which ->
            if (which == prefs.lockdownAppMode) return@choose
            val change = { prefs.lockdownAppMode = which; addedHere.clear(); render() }
            val unblocks = (prefs.lockdownAllowedApps + prefs.lockdownEssentials).any { it in prefs.lockdownBlockedApps }
            if (which == Lockdown.ALLOW && !unblocks) change() else gate.loosen(change)
        }
    }

    // --- Allowed and essential apps ---

    private fun chosen(): Set<String> = if (page == ALLOWED) prefs.lockdownAllowedApps else prefs.lockdownEssentials

    private fun setChosen(apps: Set<String>) = if (page == ALLOWED) prefs.lockdownAllowedApps = apps else prefs.lockdownEssentials = apps

    private fun appsPage() {
        val context = requireContext()
        val all = apps ?: return
        val chosen = chosen()
        val fixed = if (page == ESSENTIALS) all.filter { !Lockdown.canBlock(context, it.appPackage) } else emptyList()
        val listed = all.filter { Lockdown.canBlock(context, it.appPackage) }
        val title = if (page == ALLOWED) R.string.lockdown_allowed_count_title else R.string.lockdown_essentials_count_title
        val inList = listed.filter { it.appPackage in chosen }
        val card = section(getString(title, inList.size + fixed.size))
        card.add(chips(fixed.map { chip(it.appLabel, on = true) { } } + inList.map { app ->
            chip(app.appLabel, on = true, removable = true) { remove(app.appPackage) }
        }))
        val matches = listed.filter { it.appPackage !in chosen && matchesQuery(it.appLabel) }
        val add = section(getString(R.string.add))
        add.add(*matches.map { app -> row(add, app.appLabel, null, null) { addApp(app.appPackage) } }.toTypedArray())
    }

    /** Allowing more, or more essentials, loosens */
    private fun addApp(pkg: String) = gate.loosen {
        setChosen(chosen() + pkg)
        render()
    }

    private fun remove(pkg: String) {
        setChosen(chosen() - pkg)
        render()
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
                if (here && !Lockdown.isActive(context, prefs)) confirmStartsNow(save) else save()
            }
        }.toTypedArray())
    }

    private fun confirmStartsNow(save: () -> Unit) {
        AppDialog(requireContext())
            .setTitle(R.string.lockdown_starts_now)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.add) { _, _ -> save() }
            .show()
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

    // --- Screen time that turns lockdown on ---

    private fun triggersPage() {
        val context = requireContext()
        val used = Lockdown.screenTimeToday(context)
        val sources = sourcesShown()
        val today = section(getString(R.string.lockdown_today))
        today.add(line(today, sources.joinToString(" · ") { sourceName(it) + " " + LockdownGate.durationOrNone(context, used[it] ?: 0L) }))
        val triggers = Lockdown.triggers(prefs)
        val card = section(getString(R.string.lockdown_lock_after))
        if (triggers.isEmpty()) card.add(line(card, getString(R.string.lockdown_triggers_none)))
        card.add(*triggers.map { trigger ->
            row(card, triggerName(context, trigger), LockdownGate.deviceName(context, Lockdown.present(context, trigger.devices)), R.string.info_lockdown_triggers) {
                AppDialog(context)
                    .setTitle(triggerName(context, trigger))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.lockdown_remove) { _, _ ->
                        val remove = { prefs.lockdownTriggers = Lockdown.formatTriggers(Lockdown.triggers(prefs) - trigger); render() }
                        if (trigger.key in addedHere) remove() else gate.loosen(remove)
                    }
                    .show()
            }
        }.toTypedArray())
        card.add(row(card, getString(R.string.add), null, null) { addTrigger() })
        val running = Lockdown.triggerWindows(prefs)
        if (running.isNotEmpty()) {
            val now = section(getString(R.string.lockdown_running))
            now.add(*running.map { w ->
                row(now, getString(R.string.lockdown_on_until, LockdownGate.time(context, Lockdown.epoch(w.end))),
                    LockdownGate.deviceName(context, Lockdown.present(context, w.devices)), null, onTap = null)
            }.toTypedArray())
        }
    }

    /** Total and phone always; the Mac and TV when they're paired or their screen time comes in */
    private fun sourcesShown(): List<Lockdown.Source> {
        val context = requireContext()
        return listOfNotNull(
            Lockdown.Source.TOTAL, Lockdown.Source.PHONE,
            Lockdown.Source.MAC.takeIf { Lockdown.macPaired(context) || app.olauncher.helper.DeviceTime.updated(context, app.olauncher.helper.DeviceTime.Device.MAC) > 0 },
            Lockdown.Source.TV.takeIf { app.olauncher.helper.DeviceTime.updated(context, app.olauncher.helper.DeviceTime.Device.TV) > 0 },
        )
    }

    private fun sourceName(source: Lockdown.Source) = getString(when (source) {
        Lockdown.Source.TOTAL -> R.string.lockdown_source_total
        Lockdown.Source.PHONE -> R.string.lockdown_device_phone
        Lockdown.Source.MAC -> R.string.lockdown_device_mac
        Lockdown.Source.TV -> R.string.device_tv
    })

    /** Whose screen time, how much, until when, and on which devices */
    private fun addTrigger() {
        val context = requireContext()
        val sources = sourcesShown()
        choose(getString(R.string.lockdown_trigger_source), sources.map { sourceName(it) }, 0, suggested = true) { s ->
            val source = sources[s]
            val amounts = listOf(30, 60, 90, 120, 150, 180, 240, 300, 360, 480, 600)
            choose(getString(R.string.lockdown_trigger_amount), amounts.map { minutesName(it) }, amounts.indexOf(180), suggested = true) { a ->
                TimePickerDialog(context, { _, hour, minute ->
                    pickDevices(getString(R.string.lockdown_schedule_on), Lockdown.BOTH) { devices ->
                        val trigger = Lockdown.Trigger(source, amounts[a], hour * 60 + minute, devices)
                        val save = {
                            prefs.lockdownTriggers = Lockdown.formatTriggers(Lockdown.triggers(prefs).filter { it.key != trigger.key } + trigger)
                            addedHere += trigger.key
                            Lockdown.checkTriggers(context, prefs)
                            render()
                        }
                        val now = (Lockdown.screenTimeToday(context)[source] ?: 0L) >= trigger.minutes * 60_000L
                        if (now && !Lockdown.isOn(context, prefs)) confirmStartsNow(save) else save()
                    }
                }, 6, 0, DateFormat.is24HourFormat(context)).apply { setTitle(R.string.lockdown_trigger_until) }.show()
            }
        }
    }

    // --- Breaks ---

    private fun breaksPage() {
        val context = requireContext()
        val config = Lockdown.breaks(prefs)
        val log = Lockdown.breakLog(prefs)
        val main = section("")
        (main.titleView.parent as? View)?.isVisible = false
        main.add(row(main, getString(R.string.lockdown_break_length), if (config.on) minutesName(config.minutes) else getString(R.string.lockdown_no_breaks),
            R.string.info_break_length) {
            val options = listOf(0, 1, 2, 5, 10, 15, 30)
            choose(getString(R.string.lockdown_break_length), options.map { if (it == 0) getString(R.string.lockdown_no_breaks) else minutesName(it) },
                options.indexOf(config.minutes).coerceAtLeast(0)) { save(config, config.copy(minutes = options[it])) }
        })
        if (!config.on) return
        val today = Lockdown.epoch(java.time.LocalDate.now().atStartOfDay())
        main.add(line(main, getString(R.string.lockdown_breaks_today, Breaks.countSince(config, log, Breaks.PHONE, today)) + " · " +
            Lockdown.canBreak(context, prefs).let { if (it == Breaks.Can.Yes) getString(R.string.lockdown_break_ready) else LockdownGate.cantBreakText(context, it) }))

        val asking = section(getString(R.string.lockdown_taking_one))
        val types = listOf(Challenge.Type.NONE, Challenge.Type.HOLD, Challenge.Type.TAPS, Challenge.Type.PHRASE, Challenge.Type.RANDOM, Challenge.Type.PASSWORD)
        asking.add(
            row(asking, getString(R.string.lockdown_needs), challengeName(config.challenge) + " · " + difficultyName(difficultyOf(config.challenge)),
                R.string.info_break_needs) {
                pickChallenge(getString(R.string.lockdown_needs), config.challenge, types) { picked ->
                    val apply = { save(config, config.copy(challenge = picked)) }
                    if (picked.type == Challenge.Type.PASSWORD && !prefs.isLockdownPasswordSet) gate.setNewPassword(warn = true) { apply() } else apply()
                }
            },
            if (config.challenge.type == Challenge.Type.PHRASE) row(asking, getString(R.string.challenge_the_phrase), prefs.lockdownPhrase, null, level = 1) { editPhrase() } else null,
        )

        val often = section(getString(R.string.lockdown_how_often))
        fun limitName(n: Int) = if (n <= 0) getString(R.string.lockdown_no_limit) else n.toString()
        val limits = listOf(0, 1, 2, 3, 4, 5, 10)
        fun limitRow(title: Int, value: Int, about: Int, set: (Int) -> Breaks.Config) = row(often, getString(title), limitName(value), about) {
            choose(getString(title), limits.map { limitName(it) }, limits.indexOf(value).coerceAtLeast(0)) { save(config, set(limits[it])) }
        }
        often.add(
            row(often, getString(R.string.lockdown_break_gap), if (config.cooldown <= 0) getString(R.string.lockdown_none) else minutesName(config.cooldown),
                R.string.info_break_gap) {
                val options = listOf(0, 5, 10, 15, 30, 60, 120, 240)
                choose(getString(R.string.lockdown_break_gap), options.map { if (it == 0) getString(R.string.lockdown_none) else minutesName(it) },
                    options.indexOf(config.cooldown).coerceAtLeast(0)) { save(config, config.copy(cooldown = options[it])) }
            },
            limitRow(R.string.lockdown_per_hour, config.perHour, R.string.info_break_limits) { config.copy(perHour = it) },
            limitRow(R.string.lockdown_per_day, config.perDay, R.string.info_break_limits) { config.copy(perDay = it) },
            limitRow(R.string.lockdown_per_week, config.perWeek, R.string.info_break_limits) { config.copy(perWeek = it) },
            row(often, getString(R.string.lockdown_break_stack), onOff(config.stack), R.string.info_break_stack) { save(config, config.copy(stack = !config.stack)) },
            row(often, getString(R.string.lockdown_break_make_up), onOff(config.makeUp), R.string.info_break_make_up) { save(config, config.copy(makeUp = !config.makeUp)) },
            if (Lockdown.macPaired(context)) row(often, getString(R.string.lockdown_break_devices),
                getString(if (config.shared) R.string.lockdown_break_shared else R.string.lockdown_break_each), R.string.info_break_devices) {
                save(config, config.copy(shared = !config.shared))
            } else null,
        )

        val off = section(getString(R.string.lockdown_no_breaks_during))
        if (config.off.isEmpty()) off.add(line(off, getString(R.string.lockdown_no_breaks_during_none)))
        off.add(*config.off.map { schedule ->
            row(off, LockdownGate.scheduleName(context, schedule), null, R.string.info_break_off) {
                AppDialog(context).setTitle(LockdownGate.scheduleName(context, schedule))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.lockdown_remove) { _, _ -> save(config, config.copy(off = config.off - schedule)) }
                    .show()
            }
        }.toTypedArray())
        off.add(row(off, getString(R.string.add), null, null) { addBreakOff(config) })
    }

    /** Saves breaks' settings, through the unlock when they make breaks easier to get */
    private fun save(old: Breaks.Config, new: Breaks.Config) {
        val change = { prefs.lockdownBreaks = new.encode(); render() }
        if (old.loosensTo(new, prefs.lockdownPhrase.length)) gate.loosen(change) else change()
    }

    private fun addBreakOff(config: Breaks.Config) {
        val context = requireContext()
        val days = java.time.DayOfWeek.values()
        val ticked = BooleanArray(7) { true }
        AppDialog(context)
            .setTitle(R.string.lockdown_schedule_days)
            .setMultiChoiceItems(days.map { it.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault()) }.toTypedArray(), ticked) { _, i, on -> ticked[i] = on }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.okay) { _, _ ->
                val bits = ticked.foldIndexed(0) { i, acc, on -> if (on) acc or (1 shl i) else acc }
                if (bits == 0) return@setPositiveButton context.showToast(getString(R.string.lockdown_choose_a_day))
                val is24 = DateFormat.is24HourFormat(context)
                TimePickerDialog(context, { _, sh, sm ->
                    TimePickerDialog(context, { _, eh, em ->
                        save(config, config.copy(off = config.off + Lockdown.Schedule(bits, sh * 60 + sm, eh * 60 + em)))
                    }, 6, 0, is24).apply { setTitle(R.string.lockdown_schedule_ends) }.show()
                }, 22, 0, is24).apply { setTitle(R.string.lockdown_schedule_starts) }.show()
            }
            .show()
    }

    // --- Limits ---

    private fun limitsPage() {
        val context = requireContext()
        val all = apps ?: return
        AppPause.migrateIfNeeded(prefs)
        val withRules = all.filter { summary(it.appPackage).isNotEmpty() }
        // Now: today's screen time and the apps at their limit
        val now = Card("")
        now.panel()
        (now.titleView.parent as? View)?.isVisible = false
        val over = withRules.count { app ->
            val rule = Limits.rule(prefs, app.appPackage)
            !rule.isEmpty && Limits.over(rule, Limits.usedToday(context, app.appPackage), Limits.opensToday(context, app.appPackage), false) != null
        }
        now.add(android.widget.TextView(context, null, 0, R.style.TextLarge).apply {
            text = getString(R.string.lockdown_used_today, LockdownGate.duration(context, Limits.totalToday(context)))
            textSize = 26f
            setTextColor(ink)
        }, now.textView(14f, 165).apply {
            text = listOfNotNull(resources.getQuantityString(R.plurals.lockdown_apps_count, withRules.size, withRules.size).takeIf { withRules.isNotEmpty() },
                getString(R.string.limits_at_limit, over).takeIf { over > 0 }).joinToString(" · ").ifEmpty { getString(R.string.limits_none_yet) }
            updatePadding(top = 4.dpToPx())
        })

        val card = section(getString(R.string.lockdown_with_limits))
        if (withRules.isEmpty()) card.add(line(card, getString(R.string.limits_none_yet)))
        card.add(*withRules.filter { matchesQuery(it.appLabel) }.map { app ->
            limitRow(card, app.appPackage, app.appLabel)
        }.toTypedArray())

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

        val others = section(getString(R.string.limits_add))
        others.add(*all.filter { it !in withRules && Lockdown.canBlock(context, it.appPackage) && matchesQuery(it.appLabel) }
            .map { app -> row(others, app.appLabel, null, null) { appSheet(app.appPackage) } }.toTypedArray())
    }

    /** An app with limits: its name and rules, today's use under it, and a bar against its time limit */
    private fun limitRow(card: Card, pkg: String, label: String): View {
        val context = requireContext()
        val rule = Limits.rule(prefs, pkg)
        val used = Limits.usedToday(context, pkg)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4.dpToPx(), 10.dpToPx(), 4.dpToPx(), 10.dpToPx())
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { appSheet(pkg) }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(card.textView(16f, 255).apply {
                    text = label
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(card.textView(14.5f, 200).apply { text = summary(pkg) })
            })
            addView(card.textView(13f, 150).apply {
                text = listOfNotNull(getString(R.string.lockdown_used_today, LockdownGate.durationOrNone(context, used)),
                    resources.getQuantityString(R.plurals.lockdown_opens, Limits.opensToday(context, pkg), Limits.opensToday(context, pkg)).takeIf { rule.opens > 0 }
                ).joinToString(" · ")
                updatePadding(top = 2.dpToPx())
            })
            if (rule.minutes > 0) addView(StageBar(context, listOf(rule.minutes), used, ink, labels = false),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 22.dpToPx()))
        }
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
        ).joinToString(" · ").replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    /** One app's rules, in a sheet over the Limits page: each tapped to change, the sheet coming back after */
    private fun appSheet(pkg: String) {
        val context = requireContext()
        AppPause.migrateIfNeeded(prefs)
        val rule = Limits.rule(prefs, pkg)
        val allow = prefs.lockdownAppMode == Lockdown.ALLOW
        val inLockdown = if (allow) pkg in prefs.lockdownAllowedApps else pkg in prefs.lockdownBlockedApps
        val options = listOf(
            getString(R.string.lockdown_daily_time) + " · " + minutesName(rule.minutes),
            getString(R.string.lockdown_opens_per_day) + " · " + (if (rule.opens <= 0) getString(R.string.off) else rule.opens.toString()),
            getString(R.string.lockdown_pause) + " · " + onOff(pkg in prefs.pausedApps),
            getString(R.string.use_for_a_set_time) + " · " + onOff(pkg in prefs.useForApps),
            getString(if (allow) R.string.lockdown_allowed_in else R.string.lockdown_blocked_in) + " · " + getString(if (inLockdown) R.string.yes else R.string.no),
        )
        val again = { if (isAdded) { render(); appSheet(pkg) } }
        AppDialog(context)
            .setTitle(appLabel(pkg) + " · " + LockdownGate.durationOrNone(context, Limits.usedToday(context, pkg)))
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> choose(getString(R.string.lockdown_daily_time), Limits.MINUTE_CHOICES.map { minutesName(it) }, Limits.MINUTE_CHOICES.indexOf(rule.minutes)) {
                        setRule(pkg, rule, rule.copy(minutes = Limits.MINUTE_CHOICES[it]), again)
                    }
                    1 -> choose(getString(R.string.lockdown_opens_per_day), Limits.OPEN_CHOICES.map { if (it <= 0) getString(R.string.off) else it.toString() },
                        Limits.OPEN_CHOICES.indexOf(rule.opens)) { setRule(pkg, rule, rule.copy(opens = Limits.OPEN_CHOICES[it]), again) }
                    2 -> if (pkg in prefs.pausedApps) gate.loosen { prefs.pausedApps = prefs.pausedApps - pkg; again() }
                        else { prefs.pausedApps = prefs.pausedApps + pkg; again() }
                    3 -> if (pkg in prefs.useForApps) gate.loosen { prefs.useForApps = prefs.useForApps - pkg; again() }
                        else {
                            prefs.useForApps = prefs.useForApps + pkg
                            again()
                            if (!isAccessServiceEnabled(context)) runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        }
                    4 -> if (allow) {
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

    /** Lowering or adding a limit is stricter; raising or taking one off loosens */
    private fun setRule(pkg: String, old: Limits.Rule, new: Limits.Rule, then: () -> Unit) {
        val change = { Limits.setRule(prefs, pkg, new); then() }
        if (Limits.loosens(old.minutes, new.minutes) || Limits.loosens(old.opens, new.opens)) gate.loosen(change) else change()
    }

    // --- Settings changes ---

    private fun protectionPage() {
        val context = requireContext()
        val card = section("")
        (card.titleView.parent as? View)?.isVisible = false
        card.add(*Lockdown.Lock.values().map { lock ->
            row(card, lockName(lock), onOff(Lockdown.enabled(prefs, lock)), lockInfo(lock)) {
                if (Lockdown.enabled(prefs, lock)) gate.loosen { Lockdown.setEnabled(prefs, lock, false); render() }
                else {
                    Lockdown.setEnabled(prefs, lock, true)
                    render()
                    val needsService = lock == Lockdown.Lock.PROTECT || lock == Lockdown.Lock.SYSTEM_SETTINGS
                    if (needsService && !isAccessServiceEnabled(context)) runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                }
            }
        }.toTypedArray())
    }

    private fun lockName(lock: Lockdown.Lock) = getString(when (lock) {
        Lockdown.Lock.APP_LIST -> R.string.lockdown_lock_app_list
        Lockdown.Lock.APP_CHANGES -> R.string.lockdown_lock_app_changes
        Lockdown.Lock.HIDDEN_APPS -> R.string.lockdown_lock_hidden_apps
        Lockdown.Lock.LAUNCHER -> R.string.lockdown_lock_launcher
        Lockdown.Lock.PROTECT -> R.string.lockdown_lock_protect
        Lockdown.Lock.SYSTEM_SETTINGS -> R.string.lockdown_lock_system
    })

    private fun lockInfo(lock: Lockdown.Lock) = when (lock) {
        Lockdown.Lock.APP_LIST -> R.string.info_lock_app_list
        Lockdown.Lock.APP_CHANGES -> R.string.info_lock_app_changes
        Lockdown.Lock.HIDDEN_APPS -> R.string.info_lock_hidden
        Lockdown.Lock.LAUNCHER -> R.string.info_lock_launcher
        Lockdown.Lock.PROTECT -> R.string.info_lock_protect
        Lockdown.Lock.SYSTEM_SETTINGS -> R.string.info_lock_system
    }

    // --- Ending it early ---

    /** From easiest to impossible, one of each difficulty */
    private val presets = listOf(
        Challenge(Challenge.Type.NONE),
        Challenge(Challenge.Type.HOLD, 10),
        Challenge(Challenge.Type.TAPS, 300),
        Challenge(Challenge.Type.RANDOM, 500),
        Challenge(Challenge.Type.HOLD, 1800),
        Challenge(Challenge.Type.RANDOM, 2500),
        Challenge(Challenge.Type.NEVER),
    )

    private fun unlockPage() {
        val card = section("")
        (card.titleView.parent as? View)?.isVisible = false
        val challenge = Lockdown.challenge(prefs)
        val password = challenge.type == Challenge.Type.PASSWORD
        val recovery = password && prefs.passwordRecoveryEnabled
        fun method(title: Int, about: Int, get: () -> Boolean, toggle: () -> Unit) = if (recovery) row(card, getString(title), onOff(get()), about, level = 2) {
            gate.loosen(toggle)
        } else null
        card.add(
            row(card, getString(R.string.lockdown_difficulty), difficultyName(difficultyOf(challenge)), R.string.info_lockdown_difficulty) {
                choose(getString(R.string.lockdown_difficulty), presets.map { difficultyName(difficultyOf(it)) + " · " + challengeName(it) },
                    presets.indexOf(challenge), suggested = true) { setEndChallenge(presets[it]) }
            },
            row(card, getString(R.string.lockdown_needs), challengeName(challenge), R.string.info_lockdown_unlock_with) {
                pickChallenge(getString(R.string.lockdown_needs), challenge, Challenge.Type.values().toList()) { setEndChallenge(it) }
            },
            if (challenge.type == Challenge.Type.PHRASE) row(card, getString(R.string.challenge_the_phrase), prefs.lockdownPhrase, null, level = 1) { editPhrase() } else null,
            if (password) row(card, getString(R.string.change_lockdown_password), null, R.string.info_change_password, level = 1) {
                gate.requirePassword { gate.setNewPassword(warn = false) { render() } }
            } else null,
            if (password) row(card, getString(R.string.lockdown_password_recovery), onOff(prefs.passwordRecoveryEnabled), R.string.info_recovery, level = 1) {
                gate.loosen { prefs.passwordRecoveryEnabled = !prefs.passwordRecoveryEnabled; render() }
            } else null,
            method(R.string.recovery_tap_500, R.string.recovery_tap_500_info, { prefs.recoveryTap500 }) { prefs.recoveryTap500 = !prefs.recoveryTap500; render() },
            method(R.string.recovery_clue, R.string.recovery_clue_info, { prefs.recoveryClue }) { toggleClue() },
            method(R.string.recovery_essay, R.string.recovery_essay_info, { prefs.recoveryEssay }) { prefs.recoveryEssay = !prefs.recoveryEssay; render() },
        )
        val about = section(getString(R.string.lockdown_what_it_covers))
        about.add(line(about, getString(R.string.lockdown_challenge_covers)))
    }

    private fun toggleClue() {
        if (prefs.recoveryClue) {
            prefs.recoveryClue = false
            prefs.recoveryClueText = ""
            return render()
        }
        val input = android.widget.EditText(requireContext()).apply { hint = getString(R.string.enter_clue) }
        AppDialog(requireContext())
            .setTitle(R.string.enter_clue)
            .setView(LockdownGate.padded(requireContext(), input))
            .setPositiveButton(R.string.okay) { _, _ ->
                val clue = input.text.toString().trim()
                if (clue.isNotEmpty()) {
                    prefs.recoveryClue = true
                    prefs.recoveryClueText = clue
                    render()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
