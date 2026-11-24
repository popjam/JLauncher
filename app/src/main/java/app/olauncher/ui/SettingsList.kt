package app.olauncher.ui

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.updatePadding
import app.olauncher.R
import app.olauncher.helper.dpToPx

// The settings pages' one list: rows split by thin lines, small capital labels over groups, no panels.
// Settings, Lockdown, its lists and the Newsletter's settings all build their pages from these.

/** One thing on a settings page */
internal sealed class SettingItem

/** Small capitals over the rows after it ("Rules", "More") */
internal class SettingLabel(val text: String) : SettingItem()

/** A view of its own among the rows: a list entry with a bar, a big status line, buttons. [key] finds it again. */
internal class SettingView(val view: View, val key: String? = null) : SettingItem()

/**
 * A setting: [title] on the left, what it is now on the right as [control] shows it, [about] shown on
 * hold (and searched). [key] is unique on its page, so search can open the page at it.
 *
 * - [level]: indented under the setting it depends on, a thin line down the left for each level.
 * - [enabled]: false greys it out and taps do nothing (holding still explains), as for a setting
 *   whose parent is off.
 * - [locked]: lockdown blocks what it opens; it's greyed, says Locked, and holding says why.
 * - [danger]: a red action (Delete, Reset), put last on its page; it still asks first.
 * - [faded]: an Add row, or a value that's only the default.
 * - [note]: a line of small text under the title (what a device blocks, a search result's page).
 */
internal data class SettingRow(
    val key: String,
    val title: String,
    val about: String,
    val value: String? = null,
    val control: Control = Control.Value,
    val level: Int = 0,
    val enabled: Boolean = true,
    val locked: Boolean = false,
    val danger: Boolean = false,
    val faded: Boolean = false,
    val note: String? = null,
    val valueColor: Int? = null,
    val onTap: (() -> Unit)? = null,
) : SettingItem()

/** How a row shows its value and takes a tap */
internal sealed class Control {
    /** The value as plain text, if any; a tap does [SettingRow.onTap] */
    object Value : Control()

    /** The value, if any, and ›: a tap opens a page */
    object Opens : Control()

    /** On or off; a tap anywhere on the row flips it */
    class Switch(val on: Boolean, val onChange: (Boolean) -> Unit) : Control()

    /** Two or three short choices side by side, the [selected] one filled */
    class Segment(val options: List<String>, val selected: Int, val onPick: (Int) -> Unit) : Control()

    /** Four or five choices: the [selected] one in the accent, a tap moves to the next */
    class Cycle(val options: List<String>, val selected: Int, val onPick: (Int) -> Unit) : Control()

    /**
     * A button for each device, each tapped on its own and filled while it's on; a null leaves its
     * place empty, so the buttons line up down a list
     */
    class Devices(val buttons: List<DeviceButton?>) : Control()
}

/** One of [Control.Devices]' buttons */
internal class DeviceButton(val label: String, val on: Boolean, val onTap: () -> Unit)

/** What [settingsList] made: the list, and each row's view by its key */
internal class RenderedList(val view: LinearLayout, val rows: Map<String, View>)

/** The groups of rows (between labels) the list is made of, each split by thin lines */
internal fun CardPageFragment.settingsList(items: List<SettingItem>): RenderedList {
    val context = requireContext()
    val rows = linkedMapOf<String, View>()
    val list = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        updatePadding(left = 20.dpToPx(), right = 20.dpToPx())
    }
    var group: LinearLayout? = null
    items.forEach { item ->
        when (item) {
            is SettingLabel -> {
                group = null
                list.addView(settingLabel(item.text))
            }
            else -> {
                val into = group ?: LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    dividerDrawable = rowDivider()
                    showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
                    list.addView(this)
                    group = this
                }
                when (item) {
                    is SettingRow -> settingRowView(item).also { into.addView(it); rows[item.key] = it }
                    is SettingView -> {
                        (item.view.parent as? ViewGroup)?.removeView(item.view)
                        into.addView(item.view)
                        item.key?.let { rows[it] = item.view }
                    }
                    else -> {}
                }
            }
        }
    }
    return RenderedList(list, rows)
}

/** A group's label: small, letter-spaced capitals, muted */
internal fun CardPageFragment.settingLabel(text: String) = TextView(requireContext(), null, 0, R.style.TextSmall).apply {
    this.text = text
    textSize = Style.TEXT_SMALL - 1
    isAllCaps = true
    letterSpacing = 0.12f
    setTextColor(faint(140))
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    setPadding(4.dpToPx(), 26.dpToPx(), 4.dpToPx(), 6.dpToPx())
}

/** A [SettingRow] as a view: across, so a [HoldList] can put its tick first */
internal fun CardPageFragment.settingRowView(row: SettingRow): LinearLayout {
    val context = requireContext()
    val accent = Style.accent(darkPage)
    val active = row.enabled && !row.locked
    val about = if (row.locked) getString(R.string.info_locked_by_lockdown) else row.about
    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(4.dpToPx(), 0, 4.dpToPx(), 0)
        minimumHeight = 48.dpToPx()
        setBackgroundResource(android.R.drawable.list_selector_background)
        val tap: (() -> Unit)? = when {
            !active -> null
            row.control is Control.Switch -> ({ row.control.onChange(!row.control.on) })
            row.control is Control.Cycle -> ({ row.control.onPick((row.control.selected + 1).mod(row.control.options.size)) })
            else -> row.onTap
        }
        if (tap != null) setOnClickListener { tap() }
        setOnLongClickListener { explainSetting(this, row.title, about); true }
        // A line down the left for each level it's under, joining it to the setting it belongs to
        for (l in 1..row.level) addView(View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 1.dpToPx().toFloat()
                setColor(faint(70))
            }
        }, LinearLayout.LayoutParams(2.dpToPx(), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            marginStart = if (l == 1) 6.dpToPx() else 12.dpToPx()
            if (l == row.level) marginEnd = 12.dpToPx()
        })
        val dim = if (active) 1f else 0.4f
        val words = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 13.dpToPx(), 0, 13.dpToPx())
            alpha = dim
            addView(TextView(context, null, 0, R.style.TextSmall).apply {
                text = row.title
                textSize = Style.TEXT_BODY
                setTextColor(if (row.danger) Style.DANGER else faint(if (row.faded) 150 else if (row.level == 0) 255 else 230))
                if (row.level == 0 && !row.faded) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            })
            if (row.note != null) addView(TextView(context, null, 0, R.style.TextSmall).apply {
                text = row.note
                textSize = Style.TEXT_SMALL
                setTextColor(faint(150))
                updatePadding(top = 2.dpToPx())
            })
        }
        addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun valueText(text: String, color: Int) = TextView(context, null, 0, R.style.TextSmall).apply {
            this.text = text
            textSize = Style.TEXT_BODY
            setTextColor(color)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END
            maxWidth = (resources.displayMetrics.widthPixels * 0.45f).toInt()
            updatePadding(left = 12.dpToPx())
            alpha = dim
        }
        val control = row.control
        val value = if (row.locked) getString(R.string.locked) else row.value
        when {
            row.locked -> addView(valueText(value.orEmpty(), faint(215)))
            control is Control.Switch -> {
                if (value != null) addView(valueText(value, faint(170)))
                addView(settingSwitch(control.on, active), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = 12.dpToPx()
                })
            }
            control is Control.Segment -> addView(segment(control, active).apply { alpha = dim },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = 12.dpToPx() })
            control is Control.Cycle -> addView(valueText(control.options.getOrElse(control.selected) { "" }, accent))
            control is Control.Devices -> control.buttons.forEach { button ->
                addView(deviceButton(button, active), LinearLayout.LayoutParams(58.dpToPx(), 32.dpToPx()).apply { marginStart = 6.dpToPx() })
            }
            else -> {
                if (value != null) addView(valueText(value, row.valueColor ?: if (row.danger) Style.DANGER else faint(if (row.faded) 150 else 215)))
                if (control is Control.Opens) addView(TextView(context, null, 0, R.style.TextSmall).apply {
                    text = "›"
                    textSize = Style.TEXT_TITLE
                    setTextColor(faint(130))
                    alpha = dim
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    updatePadding(left = 12.dpToPx(), bottom = 2.dpToPx())
                })
            }
        }
    }
}

/** A switch in the accent, flipped by tapping its row rather than itself */
private fun CardPageFragment.settingSwitch(on: Boolean, active: Boolean) = SwitchCompat(requireContext()).apply {
    isChecked = on
    isClickable = false
    isFocusable = false
    isEnabled = active
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    val accent = Style.accent(darkPage)
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    thumbTintList = ColorStateList(states, intArrayOf(accent, ColorUtils.blendARGB(ink, panelColor, 0.35f)))
    trackTintList = ColorStateList(states, intArrayOf(ColorUtils.setAlphaComponent(accent, 110), faint(60)))
    if (!active) alpha = 0.4f
}

/** The options side by side in a thin outline, the selected one filled with the accent */
private fun CardPageFragment.segment(control: Control.Segment, active: Boolean): LinearLayout {
    val context = requireContext()
    val accent = Style.accent(darkPage)
    val onAccent = Style.inkOn(darkPage)
    val radius = Style.radiusSmall
    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        background = GradientDrawable().apply {
            cornerRadius = radius
            setStroke(1.dpToPx(), faint(70))
        }
        setPadding(2.dpToPx(), 2.dpToPx(), 2.dpToPx(), 2.dpToPx())
        control.options.forEachIndexed { i, option ->
            val selected = i == control.selected
            addView(TextView(context, null, 0, R.style.TextSmall).apply {
                text = option
                textSize = Style.TEXT_SMALL
                maxLines = 1
                setTextColor(if (selected) onAccent else faint(200))
                if (selected) {
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    background = GradientDrawable().apply {
                        cornerRadius = radius - 2.dpToPx()
                        setColor(accent)
                    }
                }
                setPadding(10.dpToPx(), 5.dpToPx(), 10.dpToPx(), 5.dpToPx())
                contentDescription = option + if (selected) ", " + getString(R.string.on) else ""
                if (active) setOnClickListener { if (!selected) control.onPick(i) }
            })
        }
    }
}

/** A device's button: filled with the accent while on, outlined while off; none keeps the place empty */
private fun CardPageFragment.deviceButton(button: DeviceButton?, active: Boolean): View {
    val context = requireContext()
    if (button == null) return View(context)
    val accent = Style.accent(darkPage)
    return TextView(context, null, 0, R.style.TextSmall).apply {
        text = button.label
        textSize = Style.TEXT_SMALL
        maxLines = 1
        gravity = Gravity.CENTER
        setTextColor(if (button.on) Style.inkOn(darkPage) else faint(190))
        if (button.on) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = GradientDrawable().apply {
            cornerRadius = Style.radiusSmall
            if (button.on) setColor(accent) else setStroke(1.dpToPx(), faint(80))
        }
        contentDescription = button.label + if (button.on) ", " + getString(R.string.on) else ", " + getString(R.string.off)
        if (active) setOnClickListener { button.onTap() } else alpha = 0.4f
    }
}

/** Draws the eye to [row] for a moment, once it's scrolled into view: as search opens a page at it */
internal fun CardPageFragment.highlightRow(row: View) {
    val accent = Style.accent(darkPage)
    row.post {
        if (!row.isAttachedToWindow) return@post
        row.requestRectangleOnScreen(android.graphics.Rect(0, -row.height, row.width, row.height * 2), false)
        val shade = GradientDrawable().apply { cornerRadius = Style.radiusSmall }
        val before = row.background
        row.background = shade
        ValueAnimator.ofInt(90, 0).apply {
            startDelay = 700
            duration = 1300
            addUpdateListener { shade.setColor(ColorUtils.setAlphaComponent(accent, it.animatedValue as Int)) }
            doOnEnd { row.background = before }
            shade.setColor(ColorUtils.setAlphaComponent(accent, 90))
            start()
        }
    }
}

private fun ValueAnimator.doOnEnd(action: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
    override fun onAnimationEnd(animation: android.animation.Animator) = action()
})
