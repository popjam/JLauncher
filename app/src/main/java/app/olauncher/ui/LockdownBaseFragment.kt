package app.olauncher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.updatePadding
import androidx.navigation.fragment.findNavController
import app.olauncher.helper.Formats
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.helper.BlockItems
import app.olauncher.helper.Challenge
import app.olauncher.helper.Limits
import app.olauncher.helper.Lockdown
import app.olauncher.helper.LockdownSync
import app.olauncher.helper.LockdownSystem
import app.olauncher.helper.Places
import app.olauncher.helper.Social
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.isAccessServiceEnabled
import app.olauncher.helper.isPackageInstalled
import app.olauncher.helper.showToast
import java.time.LocalDateTime
import java.util.Locale

/**
 * The Lockdown page, its pages and its setup: a header, then sections of settings rows in the
 * Settings style (name on the left, value on the right, held to explain), and a box to search or add.
 * [render] builds the sections; it's called again whenever something changes.
 *
 * The rows that the page and the setup share are made here, by section: [blocksRows], [turnsOnRows]
 * and [unlockRows], with the values their headers show.
 */
abstract class LockdownBaseFragment : CardPageFragment() {

    internal lateinit var prefs: Prefs
    private lateinit var cardList: LinearLayout
    override val cards: LinearLayout get() = cardList
    internal val gate by lazy { LockdownGate(this) { render() } }
    private var scroll: ScrollView? = null

    /** Where the page was scrolled to, kept while a page opened from it is on top */
    private var savedScroll = -1
    internal val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** The page's collapsible sections and rows, one open at a time */
    internal val sections = OneOpen()

    /** Things added since the page opened, so a mistake can be taken off again without the unlock */
    internal val addedHere = mutableSetOf<String>()

    internal val scheduleList = HoldList<Lockdown.Schedule>(onRemove = { removeSchedules(it) }, tapOpensMenu = true)
    internal val placeList = HoldList<Int>(onRemove = { removePlaces(it) }, tapOpensMenu = true)

    /** The page's lists, whose select mode ends when it pauses */
    internal open val holdLists: List<HoldList<*>> get() = listOf(scheduleList, placeList)

    /** The page's title in its header */
    internal abstract fun title(): String

    /** Builds the page's cards into [cards] */
    internal abstract fun build()

    /** Anything shown between the header and the cards, like a search box */
    internal open fun top(): View? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        prefs = Prefs(context)
        cardList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            updatePadding(bottom = 64.dpToPx())
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            updatePadding(top = 48.dpToPx())
            addView(pageHeader(context, StatIcons.LOCK.takeIf { showIcon() }, title()) { findNavController().popBackStack() })
            top()?.let { addView(it) }
            addView(cardList)
        }
        val page = FrameLayout(context).apply {
            setBackgroundColor(context.getColorFromAttr(R.attr.primaryShadeDarkColor))
            isClickable = true
            addView(ScrollView(context).apply {
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                addView(content)
                scroll = this
            })
        }
        return PullDismissLayout(context).apply {
            addView(page)
            onDismiss = { if (isAdded) findNavController().popBackStack() }
        }
    }

    internal open fun showIcon() = false

    /** The page's scrolling, for lists that scroll while dragging */
    internal fun scrollView(): ScrollView? = scroll

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onPause() {
        holdLists.forEach { it.end() }
        super.onPause()
    }

    override fun onDestroyView() {
        scroll?.let { savedScroll = it.scrollY }
        scroll = null
        main.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    internal fun render() {
        if (!isAdded || view == null) return
        cards.removeAllViews()
        build()
        if (savedScroll >= 0) {
            val y = savedScroll
            savedScroll = -1
            scroll?.let { view -> view.post { view.scrollTo(0, y) } }
        }
        LockdownSystem.sync(requireContext())
    }

    internal fun onOff(on: Boolean) = getString(if (on) R.string.on else R.string.off)

    internal fun open(page: String, pkg: String? = null) {
        runCatching {
            findNavController().navigate(R.id.lockdownPageFragment, androidx.core.os.bundleOf(LockdownPageFragment.PAGE to page, LockdownPageFragment.PKG to pkg))
        }
    }

    // --- Sections and rows ---

    /** A section titled [title], with [subtitle] on its right */
    internal fun section(title: String, subtitle: String? = null): Card {
        val card = Card(title)
        card.panel()
        if (subtitle != null) card.subtitle(subtitle)
        return card
    }

    /** A section with no title */
    internal fun untitled(): Card = section("").also { (it.titleView.parent as? View)?.visibility = View.GONE }

    /** Rows for a section, and what to do once they're in the section's list (such as joining a [HoldList]) */
    internal class Rows(val views: List<View?>, val then: (LinearLayout) -> Unit = {})

    /** [rows] in this card, as a settings section */
    internal fun Card.rows(rows: Rows): LinearLayout = rowsSection(rows.views).also(rows.then)

    /** [rows] in this card as a section that opens and closes, one open at a time, [value] in its header */
    internal fun Card.collapsible(key: String, value: String?, about: Int?, rows: Rows): LinearLayout =
        collapsibleSection(sections, key, value, about?.let { getString(it) }, rows.views).also(rows.then)

    /** Adds [rows] (nulls skipped) to [card], above its drawing */
    internal fun Card.add(vararg rows: View?) {
        rows.filterNotNull().forEach {
            // A thin line between two settings in a row, like Settings
            val before = root.getChildAt(root.indexOfChild(scene) - 1)
            if (it in settingRows && before in settingRows) root.addView(View(context).apply { background = rowDivider() },
                root.indexOfChild(scene), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1.dpToPx()))
            root.addView(it, root.indexOfChild(scene))
        }
    }

    /** The views made by [row], to put lines between */
    private val settingRows = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    /** A [settingRow] in a card, held to explain it with [about] */
    internal fun row(card: Card, title: String, value: String?, about: Int?, level: Int = 0, onTap: (() -> Unit)?): View =
        row(title, value, about, level, onTap = onTap)

    /** A [settingRow], held to explain it with [about] */
    internal fun row(title: String, value: String?, about: Int?, level: Int = 0, faded: Boolean = false, onTap: (() -> Unit)?): LinearLayout =
        settingRow(title, value, about?.let { getString(it) }, level, faded, onTap = onTap).also { settingRows.add(it) }

    /** "Add", under a list */
    internal fun addRow(level: Int = 1, onTap: () -> Unit) = row(getString(R.string.add), null, null, level, faded = true, onTap = onTap)

    /** A row with a line of small text under it, like a device and what it blocks */
    internal fun noted(row: View, text: String): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        addView(row)
        addView(note(text), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (-8).dpToPx() })
    }

    /** A line of small text among rows */
    internal fun note(text: String) = TextView(requireContext(), null, 0, R.style.TextSmall).apply {
        this.text = text
        textSize = Style.TEXT_SMALL
        setTextColor(faint(150))
        setPadding(4.dpToPx(), 0, 4.dpToPx(), 10.dpToPx())
    }

    /** A line of small text in a card */
    internal fun line(card: Card, text: String) = card.textView(Style.TEXT_SMALL, 160).apply {
        this.text = text
        setPadding(4.dpToPx(), 0, 4.dpToPx(), 6.dpToPx())
    }

    // --- Chips ---

    /** A chip: filled when [on] */
    internal fun chip(text: String, on: Boolean, onTap: (() -> Unit)?): TextView {
        val context = requireContext()
        return TextView(context, null, 0, R.style.TextSmall).apply {
            this.text = text
            textSize = Style.TEXT_SMALL
            setTextColor(if (on) context.getColorFromAttr(R.attr.primaryShadeDarkColor).let { androidx.core.graphics.ColorUtils.setAlphaComponent(it, 255) } else ink)
            background = GradientDrawable().apply {
                cornerRadius = Style.radiusSmall
                if (on) setColor(faint(230)) else setStroke(1.dpToPx(), faint(80))
            }
            setPadding(12.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
            if (onTap != null) setOnClickListener { onTap() }
        }
    }

    /** Chips in rows that wrap */
    internal fun chips(views: List<View>): View = FlowLayout(requireContext()).apply {
        views.forEach { addView(it) }
        setPadding(0, 2.dpToPx(), 0, 8.dpToPx())
    }

    // --- A box to search or add ---

    internal fun box(context: Context, hint: Int, onText: (String) -> Unit, onDone: ((String) -> Unit)? = null) = EditText(context, null, 0, R.style.TextSmall).apply {
        this.hint = getString(hint)
        textSize = Style.TEXT_BODY
        setTextColor(ink)
        setHintTextColor(faint(110))
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or if (onDone != null) InputType.TYPE_TEXT_VARIATION_URI else InputType.TYPE_TEXT_VARIATION_FILTER
        imeOptions = if (onDone != null) android.view.inputmethod.EditorInfo.IME_ACTION_DONE else android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        background = GradientDrawable().apply {
            cornerRadius = Style.radiusLarge
            setColor(panelColor)
        }
        setPadding(18.dpToPx(), 11.dpToPx(), 18.dpToPx(), 11.dpToPx())
        // Built without the usual text box style, so it has to be told it can take focus from a tap
        isFocusable = true
        isFocusableInTouchMode = true
        isCursorVisible = true
        setOnClickListener {
            requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(this, 0)
        }
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = onText(s?.toString().orEmpty())
        })
        setOnEditorActionListener { view, _, _ ->
            onDone?.invoke(text.toString())
            view.clearFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
            true
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(12.dpToPx(), 0, 12.dpToPx(), 0)
        }
    }

    internal fun pill(text: String, onTap: () -> Unit) = TextView(requireContext(), null, 0, R.style.TextSmall).apply {
        this.text = text
        textSize = Style.TEXT_BODY
        setTextColor(ink)
        background = GradientDrawable().apply {
            cornerRadius = Style.radiusLarge
            setColor(faint(36))
        }
        setPadding(18.dpToPx(), 9.dpToPx(), 18.dpToPx(), 9.dpToPx())
        setOnClickListener { onTap() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = 8.dpToPx() }
    }

    // --- Blocks ---

    /**
     * Apps and websites, then on the phone: blocking a list or allowing only one (with the allowed and
     * always allowed apps), the settings changes locked, and accessibility when it's needed and off
     */
    internal fun blocksRows(): Rows {
        val context = requireContext()
        val items = BlockItems.effective(prefs.lockdownItems, LockdownSync.macInstalled(context), LockdownSync.tvInstalled(context))
        val allow = prefs.lockdownAppMode == Lockdown.ALLOW
        val locks = Lockdown.Lock.values()
        val limits = Limits.appsWithRules(context, prefs)
        val needsService = items.values.any { it.phone } || prefs.lockdownProtect || prefs.lockdownSystemSettings || limits > 0 || allow
        val settings = collapsibleGroup(sections, "settingsChanges", getString(R.string.lockdown_settings_changes),
            getString(R.string.lockdown_n_of, locks.count { Lockdown.enabled(prefs, it) }, locks.size),
            getString(R.string.info_lockdown_settings_changes), locks.map { lockRow(it) })
        return Rows(listOf(
            row(getString(R.string.lockdown_apps_and_sites), blockedValue(items), R.string.info_lockdown_blocks) { open(LockdownPageFragment.BLOCKS) }
                .also { if (!allow && items.values.none { it.shown }) it.valueColor(Style.DANGER) },
            row(getString(R.string.lockdown_during_phone), modeName(), R.string.info_lockdown_during) { pickMode() },
            if (allow) row(getString(R.string.lockdown_allowed_apps), prefs.lockdownAllowedApps.count { context.isPackageInstalled(it) }.toString(),
                R.string.info_allowed_apps, level = 1) { open(LockdownPageFragment.ALLOWED) } else null,
            if (allow) row(getString(R.string.lockdown_always_allowed), (prefs.lockdownEssentials.count { context.isPackageInstalled(it) } + 1).toString(),
                R.string.info_lockdown_always_allowed, level = 1) { open(LockdownPageFragment.ESSENTIALS) } else null,
            settings.view,
            // Only when it's needed and off, since nothing outside the launcher is blocked without it
            if (needsService && !isAccessServiceEnabled(context)) row(getString(R.string.accessibility), onOff(false), R.string.info_lockdown_accessibility) {
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            } else null,
        ))
    }

    /** How many apps and sites each switched-on device blocks: "12", or "Phone 12 · Mac 5 · TV 2" */
    private fun blockedValue(items: Map<String, BlockItems.Item>): String {
        val context = requireContext()
        fun count(device: Lockdown.Device) = when (device) {
            Lockdown.Device.PHONE -> BlockItems.phoneApps(items).count { context.isPackageInstalled(it) } + BlockItems.phoneSites(items).size
            Lockdown.Device.MAC -> BlockItems.macApps(items).size + BlockItems.macSites(items).size
            Lockdown.Device.TV -> BlockItems.tvApps(items).size
        }
        return if (Lockdown.several(context)) Lockdown.deviceList(context).joinToString(" · ") { LockdownGate.deviceName(context, it.bit) + " " + count(it) }
            else count(Lockdown.Device.PHONE).toString()
    }

    internal fun modeName() = getString(if (prefs.lockdownAppMode == Lockdown.ALLOW) R.string.lockdown_only_allow else R.string.lockdown_block_these)

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

    /** A settings change locked or not; unlocking it loosens */
    private fun lockRow(lock: Lockdown.Lock): View {
        val context = requireContext()
        return row(lockName(lock), onOff(Lockdown.enabled(prefs, lock)), lockInfo(lock), level = 1) {
            if (Lockdown.enabled(prefs, lock)) gate.loosen { Lockdown.setEnabled(prefs, lock, false); render() }
            else {
                Lockdown.setEnabled(prefs, lock, true)
                render()
                val needsService = lock == Lockdown.Lock.PROTECT || lock == Lockdown.Lock.SYSTEM_SETTINGS
                if (needsService && !isAccessServiceEnabled(context)) runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
        }
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

    // --- Turns on: schedules and places ---

    /** "Weekdays 10 pm–7 am · Home", "2 schedules", or Off */
    internal fun turnsOnValue(): String {
        val context = requireContext()
        val schedules = Lockdown.schedules(prefs)
        val places = prefs.lockdownPlaces.mapNotNull { LockdownGate.placeName(context, it) }
        return listOfNotNull(
            when (schedules.size) {
                0 -> null
                1 -> LockdownGate.scheduleName(context, schedules.first())
                else -> resources.getQuantityString(R.plurals.lockdown_schedules_count, schedules.size, schedules.size)
            },
            places.joinToString(", ").ifEmpty { null },
        ).joinToString(" · ").ifEmpty { getString(R.string.off) }
    }

    /** The schedules and the places, each held or tapped for Edit / Remove / Select, with Add under each */
    internal fun turnsOnRows(): Rows {
        val context = requireContext()
        val schedules = Lockdown.schedules(prefs).map { it to row(LockdownGate.scheduleName(context, it), null, null, level = 1, onTap = null) }
        val places = knownPlaces().filter { it.first in prefs.lockdownPlaces }.map { (id, name) -> Triple(id, name, row(name, null, null, level = 1, onTap = null)) }
        val views = listOf(row(getString(R.string.lockdown_schedule), null, R.string.info_lockdown_schedules, onTap = null)) +
            schedules.map { it.second } + addRow { addSchedule() } +
            row(getString(R.string.lockdown_at_places), null, R.string.info_lockdown_places, onTap = null) +
            places.map { it.third } + addRow { addPlace() }
        return Rows(views) { list ->
            scheduleList.attach(list, scroll)
            schedules.forEach { (schedule, view) ->
                scheduleList.item(view, schedule, label = LockdownGate.scheduleName(context, schedule), actions = listOf(
                    HoldList.Action(getString(R.string.edit)) { editSchedule(schedule) },
                    HoldList.Action(getString(R.string.remove)) { removeSchedules(setOf(schedule)) },
                ))
            }
            placeList.attach(list, scroll)
            places.forEach { (id, name, view) ->
                placeList.item(view, id, label = name, actions = listOf(HoldList.Action(getString(R.string.remove)) { removePlaces(setOf(id)) }))
            }
        }
    }

    /** Homes, work and named places, by their id in [Social] */
    private fun knownPlaces(): List<Pair<Int, String>> {
        val context = requireContext()
        return buildList {
            Social.homes(context).forEachIndexed { slot, home -> if (home != null) add(slot to home.name) }
            Social.work(context)?.let { add(Social.WORK to it.name) }
            Places.places(context).forEach { add(it.id to it.name) }
        }
    }

    private fun addSchedule() = pickSchedule(Lockdown.Schedule(Lockdown.WEEKDAYS, 22 * 60, 7 * 60)) { schedule ->
        val save = {
            prefs.lockdownSchedules = Lockdown.formatSchedules((Lockdown.schedules(prefs) + schedule).distinct())
            render()
        }
        if (schedule.covers(LocalDateTime.now()) && !Lockdown.isOn(requireContext(), prefs)) confirmStartsNow(save) else save()
    }

    /** Changing a schedule so it covers less of the week loosens */
    private fun editSchedule(old: Lockdown.Schedule) = pickSchedule(old) { new ->
        if (new == old) return@pickSchedule
        val before = Lockdown.schedules(prefs)
        val after = before.map { if (it == old) new else it }.distinct()
        val save = { prefs.lockdownSchedules = Lockdown.formatSchedules(after); render() }
        when {
            Lockdown.coverageLost(before, after) -> gate.loosen(save)
            new.covers(LocalDateTime.now()) && !Lockdown.isOn(requireContext(), prefs) -> confirmStartsNow(save)
            else -> save()
        }
    }

    private fun removeSchedules(picked: Set<Lockdown.Schedule>) = gate.loosen {
        prefs.lockdownSchedules = Lockdown.formatSchedules(Lockdown.schedules(prefs) - picked)
        render()
    }

    /** A place not chosen yet; one that's where the phone is now asks first, as it starts lockdown */
    private fun addPlace() {
        val context = requireContext()
        val others = knownPlaces().filter { it.first !in prefs.lockdownPlaces }
        if (others.isEmpty()) return context.showToast(getString(R.string.lockdown_no_places))
        AppDialog(context)
            .setTitle(R.string.lockdown_at_places)
            .setItems(others.map { it.second }.toTypedArray()) { _, which ->
                val id = others[which].first
                val save = { prefs.lockdownPlaces = prefs.lockdownPlaces + id; addedHere += "place:$id"; render() }
                val here = runCatching { Social.currentPlace(context) }.getOrNull() == id
                if (here && !Lockdown.isActive(context, prefs)) confirmStartsNow(save) else save()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Taking places off loosens, unless they were all added while the page was open */
    private fun removePlaces(picked: Set<Int>) {
        val change = { prefs.lockdownPlaces = prefs.lockdownPlaces - picked; picked.forEach { addedHere -= "place:$it" }; render() }
        if (picked.all { "place:$it" in addedHere }) change() else gate.loosen(change)
    }

    /** The days, then when it starts and when it ends, starting from [initial] */
    internal fun pickSchedule(initial: Lockdown.Schedule, onPicked: (Lockdown.Schedule) -> Unit) {
        val context = requireContext()
        val days = java.time.DayOfWeek.values()
        val ticked = days.map { initial.on(it) }.toBooleanArray()
        AppDialog(context)
            .setTitle(R.string.lockdown_schedule_days)
            .setMultiChoiceItems(days.map { it.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault()) }.toTypedArray(), ticked) { _, i, on -> ticked[i] = on }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.lockdown_next) { _, _ ->
                val bits = ticked.foldIndexed(0) { i, acc, on -> if (on) acc or (1 shl i) else acc }
                if (bits == 0) return@setPositiveButton context.showToast(getString(R.string.lockdown_choose_a_day))
                val is24 = DateFormat.is24HourFormat(context)
                android.app.TimePickerDialog(context, { _, startHour, startMinute ->
                    android.app.TimePickerDialog(context, { _, endHour, endMinute ->
                        onPicked(Lockdown.Schedule(bits, startHour * 60 + startMinute, endHour * 60 + endMinute))
                    }, initial.end / 60, initial.end % 60, is24).apply { setTitle(R.string.lockdown_schedule_ends) }.show()
                }, initial.start / 60, initial.start % 60, is24).apply { setTitle(R.string.lockdown_schedule_starts) }.show()
            }
            .show()
    }

    /** Something that turns lockdown on straight away is asked about first */
    internal fun confirmStartsNow(save: () -> Unit) {
        AppDialog(requireContext())
            .setTitle(R.string.lockdown_starts_now)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ -> save() }
            .show()
    }

    // --- Unlock ---

    /** "Type 200 random characters · Hard" */
    internal fun unlockValue(): String {
        val challenge = Lockdown.challenge(prefs)
        return challengeName(challenge) + " · " + difficultyName(difficultyOf(challenge))
    }

    /** What unlocking needs, the phrase or password, and the ways to get a forgotten password back */
    internal fun unlockRows(): Rows {
        val challenge = Lockdown.challenge(prefs)
        val password = challenge.type == Challenge.Type.PASSWORD
        val recovery = password && prefs.passwordRecoveryEnabled
        fun method(title: Int, about: Int, get: () -> Boolean, toggle: () -> Unit) = if (recovery) row(getString(title), onOff(get()), about, level = 2) {
            gate.loosen(toggle)
        } else null
        return Rows(listOf(
            row(getString(R.string.lockdown_unlock_needs), unlockValue(), R.string.info_lockdown_unlock) {
                pickChallenge(getString(R.string.lockdown_unlock_needs), challenge, Challenge.Type.values().toList()) { setEndChallenge(it) }
            },
            if (challenge.type == Challenge.Type.PHRASE) row(getString(R.string.challenge_the_phrase), prefs.lockdownPhrase, null, level = 1) { editPhrase() } else null,
            if (password) row(getString(R.string.change_lockdown_password), null, R.string.info_change_password, level = 1) {
                gate.requirePassword { gate.setNewPassword(warn = false) { render() } }
            } else null,
            if (password) row(getString(R.string.lockdown_password_recovery), onOff(prefs.passwordRecoveryEnabled), R.string.info_recovery, level = 1) {
                gate.loosen { prefs.passwordRecoveryEnabled = !prefs.passwordRecoveryEnabled; render() }
            } else null,
            method(R.string.recovery_tap_500, R.string.recovery_tap_500_info, { prefs.recoveryTap500 }) { prefs.recoveryTap500 = !prefs.recoveryTap500; render() },
            method(R.string.recovery_clue, R.string.recovery_clue_info, { prefs.recoveryClue }) { toggleClue() },
            method(R.string.recovery_essay, R.string.recovery_essay_info, { prefs.recoveryEssay }) { prefs.recoveryEssay = !prefs.recoveryEssay; render() },
        ))
    }

    private fun toggleClue() {
        if (prefs.recoveryClue) {
            prefs.recoveryClue = false
            prefs.recoveryClueText = ""
            return render()
        }
        val input = EditText(requireContext()).apply { hint = getString(R.string.recovery_clue) }
        AppDialog(requireContext())
            .setTitle(R.string.recovery_clue)
            .setView(LockdownGate.padded(requireContext(), input))
            .setPositiveButton(R.string.save) { _, _ ->
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

    // --- What unlocking, loosening and breaks need ---

    /** A challenge in a few words: "100 taps", "Type 200 random characters", "15m wait" */
    internal fun challengeName(challenge: Challenge): String = when (challenge.type) {
        Challenge.Type.NONE -> getString(R.string.lockdown_unlock_nothing)
        Challenge.Type.HOLD -> getString(R.string.challenge_hold_n, if (challenge.amount < 60) getString(R.string.seconds_value, challenge.amount)
            else Formats.duration(challenge.amount * 1000L))
        Challenge.Type.TAPS -> resources.getQuantityString(R.plurals.challenge_taps, challenge.amount, challenge.amount)
        Challenge.Type.PHRASE -> if (challenge.amount <= 1) getString(R.string.challenge_phrase)
            else resources.getQuantityString(R.plurals.challenge_phrase_times, challenge.amount, challenge.amount)
        Challenge.Type.RANDOM -> resources.getQuantityString(R.plurals.challenge_random, challenge.amount, challenge.amount)
        Challenge.Type.PASSWORD -> getString(R.string.lockdown_password)
        Challenge.Type.WAIT -> getString(R.string.lockdown_unlock_wait, Formats.duration(challenge.amount * 60_000L))
        Challenge.Type.NEVER -> getString(R.string.challenge_never)
    }

    internal fun typeName(type: Challenge.Type) = getString(when (type) {
        Challenge.Type.NONE -> R.string.lockdown_unlock_nothing
        Challenge.Type.HOLD -> R.string.challenge_type_hold
        Challenge.Type.TAPS -> R.string.challenge_type_taps
        Challenge.Type.PHRASE -> R.string.challenge_type_phrase
        Challenge.Type.RANDOM -> R.string.challenge_type_random
        Challenge.Type.PASSWORD -> R.string.lockdown_password
        Challenge.Type.WAIT -> R.string.challenge_type_wait
        Challenge.Type.NEVER -> R.string.challenge_never
    })

    /** Very easy … Impossible */
    internal fun difficultyName(level: Int) = resources.getStringArray(R.array.challenge_difficulty).getOrElse(level) { "" }

    internal fun difficultyOf(challenge: Challenge) = challenge.difficulty(prefs.lockdownPhrase.length)

    /**
     * Picks a challenge from [types]: the type, then how much of it, each shown with how hard it is.
     * Choosing the password sets one if there isn't one. [onPicked] gets the choice.
     */
    internal fun pickChallenge(title: String, current: Challenge, types: List<Challenge.Type>, onPicked: (Challenge) -> Unit) {
        val selected = types.indexOf(current.type)
        choose(title, types.map { typeName(it) }, selected, suggested = true) { which ->
            val type = types[which]
            val amounts = Challenge.amount(type)
            if (amounts.isEmpty()) return@choose onPicked(Challenge(type))
            val start = if (type == current.type) current.amount else Challenge.defaultAmount(type)
            choose(typeName(type), amounts.map { amount ->
                val c = Challenge(type, amount)
                challengeName(c) + " · " + difficultyName(difficultyOf(c))
            }, amounts.indexOf(start), suggested = true) { onPicked(Challenge(type, amounts[it])) }
        }
    }

    /** Saves [new] as what unlocking needs, through the unlock when it's easier; a password is set first if there isn't one */
    internal fun setEndChallenge(new: Challenge) {
        val old = Lockdown.challenge(prefs)
        if (new == old) return
        val save = {
            val apply = {
                prefs.lockdownChallenge = new.encode()
                Lockdown.cancelWait(prefs)
                render()
            }
            if (new.type == Challenge.Type.PASSWORD && !prefs.isLockdownPasswordSet) gate.setNewPassword(warn = true) { apply() } else apply()
        }
        if (Challenge.loosens(old, new, prefs.lockdownPhrase.length)) gate.loosen(save) else save()
    }

    /** The phrase typed out; a shorter one loosens */
    internal fun editPhrase() {
        val context = requireContext()
        val input = EditText(context).apply {
            setText(prefs.lockdownPhrase)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 6
        }
        AppDialog(context)
            .setTitle(R.string.challenge_the_phrase)
            .setView(LockdownGate.padded(context, input))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val phrase = input.text.toString().trim().replace(Regex("\\s+"), " ")
                if (phrase.length < 4) return@setPositiveButton
                val save = { prefs.lockdownPhrase = phrase; render() }
                if (phrase.length < prefs.lockdownPhrase.length) gate.loosen(save) else save()
            }
            .show()
    }

    // --- Breaks ---

    /** Automatic breaks in a few words: "5m every 50m", or Off */
    internal fun autoBreakName(config: app.olauncher.helper.Breaks.Config) = if (!config.autoOn) getString(R.string.off)
        else getString(R.string.lockdown_auto_break_value, Formats.duration(config.autoMinutes * 60_000L), Formats.duration(config.autoEvery * 60_000L))

    /** Greyscale needs a permission only a computer can give */
    internal fun greyscaleSetup() {
        val context = requireContext()
        val command = LockdownSystem.grantCommand(context)
        AppDialog(context)
            .setTitle(R.string.lockdown_greyscale)
            .setMessage(getString(R.string.lockdown_greyscale_setup) + "\n\n" + command)
            .setNegativeButton(R.string.lockdown_copy) { _, _ ->
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(command, command))
            }
            .setPositiveButton(R.string.okay, null)
            .show()
    }

    // --- Choosing ---

    /** A list to pick from with [selected] ticked; picking it again changes nothing, unless it's only [suggested] rather than the current value */
    internal fun choose(title: String, options: List<String>, selected: Int, suggested: Boolean = false, onPick: (Int) -> Unit) {
        AppDialog(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), selected) { dialog, which ->
                dialog.dismiss()
                if (which != selected || suggested) onPick(which)
            }
            .show()
    }

    /** Minutes as "30m", "1h", "1h 30m", or Off for 0 */
    internal fun minutesName(minutes: Int) = if (minutes <= 0) getString(R.string.off) else Formats.duration(minutes * 60_000L)
}
