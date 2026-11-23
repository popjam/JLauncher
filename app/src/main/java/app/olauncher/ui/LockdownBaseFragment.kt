package app.olauncher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.updatePadding
import androidx.navigation.fragment.findNavController
import app.olauncher.helper.Formats
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.helper.Challenge
import app.olauncher.helper.Lockdown
import app.olauncher.helper.LockdownSystem
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr

/**
 * The Lockdown page and its pages: a header, then cards of settings rows in the Settings style
 * (name on the left, value on the right, held to explain), chips for chosen things, and a box to
 * search or add. [render] builds the cards; it's called again whenever something changes.
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

    override fun onResume() {
        super.onResume()
        render()
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
        app.olauncher.helper.LockdownSystem.sync(requireContext())
    }

    internal fun onOff(on: Boolean) = getString(if (on) R.string.on else R.string.off)

    internal fun open(page: String, pkg: String? = null) {
        runCatching {
            findNavController().navigate(R.id.lockdownPageFragment, androidx.core.os.bundleOf(LockdownPageFragment.PAGE to page, LockdownPageFragment.PKG to pkg))
        }
    }

    // --- Cards and rows ---

    /** A card titled [title] in small spaced capitals, with [subtitle] on its right */
    internal fun section(title: String, subtitle: String? = null): Card {
        val card = Card(title)
        card.panel()
        if (subtitle != null) card.subtitle(subtitle)
        return card
    }

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
        settingRow(title, value, about?.let { getString(it) }, level, onTap = onTap).also { settingRows.add(it) }

    /** A line of small text in a card, like "Pause before blocked apps" */
    internal fun line(card: Card, text: String) = card.textView(Style.TEXT_SMALL, 160).apply {
        this.text = text
        setPadding(4.dpToPx(), 0, 4.dpToPx(), 6.dpToPx())
    }

    // --- Chips ---

    /** A chip: filled when [on], with "×" when it can be removed by tapping */
    internal fun chip(text: String, on: Boolean, removable: Boolean = false, onTap: (() -> Unit)?): TextView {
        val context = requireContext()
        return TextView(context, null, 0, R.style.TextSmall).apply {
            this.text = if (removable) "$text  ×" else text
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

    // --- Shared with the setup ---

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

    // --- What ending early, loosening and breaks need ---

    /** A challenge in a few words: "100 taps", "Type 200 random characters", "15m wait" */
    internal fun challengeName(challenge: Challenge): String {
        val context = requireContext()
        return when (challenge.type) {
            Challenge.Type.NONE -> getString(R.string.lockdown_unlock_nothing)
            Challenge.Type.HOLD -> getString(R.string.challenge_hold_n, if (challenge.amount < 60) getString(R.string.seconds_value, challenge.amount)
                else Formats.duration(challenge.amount * 1000L))
            Challenge.Type.TAPS -> getString(R.string.challenge_taps_n, challenge.amount)
            Challenge.Type.PHRASE -> if (challenge.amount <= 1) getString(R.string.challenge_phrase) else getString(R.string.challenge_phrase_n, challenge.amount)
            Challenge.Type.RANDOM -> getString(R.string.challenge_random_n, challenge.amount)
            Challenge.Type.PASSWORD -> getString(R.string.lockdown_password)
            Challenge.Type.WAIT -> getString(R.string.lockdown_unlock_wait, Formats.duration(challenge.amount * 60_000L))
            Challenge.Type.NEVER -> getString(R.string.challenge_never)
        }
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

    /** Simple … Impossible */
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

    /** Saves [new] as what ending early needs, through the unlock when it's easier; a password is set first if there isn't one */
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
            .setPositiveButton(R.string.okay) { _, _ ->
                val phrase = input.text.toString().trim().replace(Regex("\\s+"), " ")
                if (phrase.length < 4) return@setPositiveButton
                val save = { prefs.lockdownPhrase = phrase; render() }
                if (phrase.length < prefs.lockdownPhrase.length) gate.loosen(save) else save()
            }
            .show()
    }

    // --- Devices ---

    /**
     * A switch for one device: its icon and name, filled while [on]. Faded and untappable when the thing
     * can't be blocked there ([enabled] false).
     */
    internal fun devicePill(device: Lockdown.Device, on: Boolean, enabled: Boolean, onTap: () -> Unit): TextView {
        val context = requireContext()
        val fill = Style.lockdown(darkPage)
        val text = when {
            on -> if (darkPage) Style.LOCKDOWN_INK_ON_DARK else android.graphics.Color.WHITE
            else -> faint(if (enabled) 200 else 70)
        }
        return TextView(context, null, 0, R.style.TextSmall).apply {
            this.text = LockdownGate.deviceName(context, device.bit)
            textSize = Style.TEXT_SMALL
            setTextColor(text)
            val icon = StatIcons.drawable(when (device) {
                Lockdown.Device.PHONE -> StatIcons.PHONE_SMALL
                Lockdown.Device.MAC -> StatIcons.LAPTOP
                Lockdown.Device.TV -> StatIcons.TV
            }, text, 13.dpToPx())
            icon.setBounds(0, 0, 13.dpToPx(), 13.dpToPx())
            setCompoundDrawablesRelative(icon, null, null, null)
            compoundDrawablePadding = 5.dpToPx()
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = Style.radiusSmall
                if (on) setColor(fill) else setStroke(1.dpToPx(), faint(if (enabled) 90 else 30))
            }
            setPadding(10.dpToPx(), 5.dpToPx(), 11.dpToPx(), 5.dpToPx())
            isEnabled = enabled
            if (enabled) setOnClickListener { onTap() }
            contentDescription = this.text.toString() + ", " + getString(if (on) R.string.on else R.string.off)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = 6.dpToPx() }
        }
    }

    /**
     * Picks devices: all of them, each one, and each two when there are three; straight to [onPicked]
     * with the phone when it's the only one
     */
    internal fun pickDevices(title: String, current: Int, onPicked: (Int) -> Unit) {
        val present = Lockdown.deviceList(requireContext())
        if (present.size < 2) return onPicked(Lockdown.Device.PHONE.bit)
        val all = present.sumOf { it.bit }
        val pairs = if (present.size > 2) present.flatMapIndexed { i, a -> present.drop(i + 1).map { b -> a.bit or b.bit } } else emptyList()
        val options = listOf(all) + present.map { it.bit } + pairs
        val shown = (current and all).takeIf { it != 0 } ?: all
        choose(title, options.map { LockdownGate.deviceName(requireContext(), it) }, options.indexOf(shown).coerceAtLeast(0), suggested = true) { onPicked(options[it]) }
    }

    /** Grayscale needs a permission only a computer can give */
    internal fun grayscaleSetup() {
        val context = requireContext()
        val command = LockdownSystem.grantCommand(context)
        AppDialog(context)
            .setTitle(R.string.lockdown_grayscale)
            .setMessage(getString(R.string.lockdown_grayscale_setup) + "\n\n" + command)
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
