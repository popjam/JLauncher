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
import app.olauncher.R
import app.olauncher.data.Prefs
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
    private var info: PopupWindow? = null
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

    override fun onPause() {
        info?.dismiss()
        super.onPause()
    }

    override fun onDestroyView() {
        info?.dismiss()
        info = null
        main.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    internal fun render() {
        if (!isAdded || view == null) return
        cards.removeAllViews()
        build()
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

    /**
     * A setting: its name, its value on the right, indented by [level]; tapping does [onTap],
     * holding explains it with [about].
     */
    internal fun row(card: Card, title: String, value: String?, about: Int?, level: Int = 0, onTap: (() -> Unit)?): View {
        val context = requireContext()
        return LinearLayout(context).also { settingRows.add(it) }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4.dpToPx(), 0, 4.dpToPx(), 0)
            setBackgroundResource(android.R.drawable.list_selector_background)
            if (onTap != null) setOnClickListener { onTap() }
            if (about != null) setOnLongClickListener {
                explain(this, title, getString(about))
                true
            }
            for (l in 1..level) addView(View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 1.dpToPx().toFloat()
                    setColor(faint(70))
                }
            }, LinearLayout.LayoutParams(2.dpToPx(), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginStart = if (l == 1) 6.dpToPx() else 12.dpToPx()
                if (l == level) marginEnd = 12.dpToPx()
            })
            addView(card.textView(if (level == 0) 16f else 15f, if (level == 0) 255 else 230).apply {
                text = title
                if (level == 0) typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setPadding(0, 13.dpToPx(), 0, 13.dpToPx())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (value != null) addView(card.textView(15f, 215).apply {
                text = value
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.END
                maxWidth = (resources.displayMetrics.widthPixels * 0.45f).toInt()
                updatePadding(left = 12.dpToPx())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** What a setting does, in a small card under it */
    private fun explain(anchor: View, title: String, text: String) {
        info?.dismiss()
        val context = requireContext()
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 18.dpToPx().toFloat()
                setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(context.getColorFromAttr(R.attr.primaryShadeDarkColor), 255))
                setStroke(1.dpToPx(), faint(60))
            }
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 14.dpToPx())
            addView(TextView(context, null, 0, R.style.TextSmall).apply {
                this.text = title
                textSize = 14f
                setTextColor(ink)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(context, null, 0, R.style.TextSmall).apply {
                this.text = text
                textSize = 14f
                setTextColor(ink)
                alpha = 0.8f
                updatePadding(top = 4.dpToPx())
            })
        }
        info = PopupWindow(box, anchor.width.coerceAtLeast(200.dpToPx()), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = 8.dpToPx().toFloat()
            showAsDropDown(anchor, 0, 4.dpToPx())
        }
    }

    /** A line of small text in a card, like "Pause before blocked apps" */
    internal fun line(card: Card, text: String) = card.textView(13.5f, 160).apply {
        this.text = text
        setPadding(4.dpToPx(), 0, 4.dpToPx(), 6.dpToPx())
    }

    // --- Chips ---

    /** A chip: filled when [on], with "×" when it can be removed by tapping */
    internal fun chip(text: String, on: Boolean, removable: Boolean = false, onTap: (() -> Unit)?): TextView {
        val context = requireContext()
        return TextView(context, null, 0, R.style.TextSmall).apply {
            this.text = if (removable) "$text  ×" else text
            textSize = 13.5f
            setTextColor(if (on) context.getColorFromAttr(R.attr.primaryShadeDarkColor).let { androidx.core.graphics.ColorUtils.setAlphaComponent(it, 255) } else ink)
            background = GradientDrawable().apply {
                cornerRadius = 14.dpToPx().toFloat()
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
        textSize = 15f
        setTextColor(ink)
        setHintTextColor(faint(110))
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or if (onDone != null) InputType.TYPE_TEXT_VARIATION_URI else InputType.TYPE_TEXT_VARIATION_FILTER
        imeOptions = if (onDone != null) android.view.inputmethod.EditorInfo.IME_ACTION_DONE else android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        background = GradientDrawable().apply {
            cornerRadius = 22.dpToPx().toFloat()
            setColor(panelColor)
        }
        setPadding(18.dpToPx(), 11.dpToPx(), 18.dpToPx(), 11.dpToPx())
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
        textSize = 14.5f
        setTextColor(ink)
        background = GradientDrawable().apply {
            cornerRadius = 20.dpToPx().toFloat()
            setColor(faint(36))
        }
        setPadding(18.dpToPx(), 9.dpToPx(), 18.dpToPx(), 9.dpToPx())
        setOnClickListener { onTap() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = 8.dpToPx() }
    }

    internal fun unlockName(unlock: Lockdown.Unlock) = when (unlock) {
        Lockdown.Unlock.NOTHING -> getString(R.string.lockdown_unlock_nothing)
        Lockdown.Unlock.PASSWORD -> getString(R.string.lockdown_password)
        Lockdown.Unlock.WAIT -> getString(R.string.lockdown_unlock_wait, LockdownGate.duration(requireContext(), prefs.lockdownWaitMinutes * 60_000L))
    }

    /** Nothing, the password, or one of the waits. Anything but a stricter choice loosens lockdown. */
    internal fun pickUnlock() {
        val context = requireContext()
        val current = Lockdown.unlock(prefs)
        val waits = Lockdown.WAIT_CHOICES
        val names = listOf(getString(R.string.lockdown_unlock_nothing), getString(R.string.lockdown_password)) +
            waits.map { getString(R.string.lockdown_unlock_wait, LockdownGate.duration(context, it * 60_000L)) }
        val selected = when (current) {
            Lockdown.Unlock.NOTHING -> 0
            Lockdown.Unlock.PASSWORD -> 1
            Lockdown.Unlock.WAIT -> 2 + waits.indexOf(prefs.lockdownWaitMinutes)
        }
        choose(getString(R.string.lockdown_unlock_with), names, selected) { which ->
            val stricter = current == Lockdown.Unlock.NOTHING ||
                (current == Lockdown.Unlock.WAIT && which >= 2 && waits[which - 2] > prefs.lockdownWaitMinutes)
            val change = {
                when (which) {
                    0 -> {
                        gate.clearPassword()
                        prefs.lockdownUnlock = Lockdown.Unlock.NOTHING.ordinal
                        render()
                    }
                    1 -> gate.setNewPassword(warn = true) {
                        prefs.lockdownUnlock = Lockdown.Unlock.PASSWORD.ordinal
                        render()
                    }
                    else -> {
                        gate.clearPassword()
                        prefs.lockdownWaitMinutes = waits[which - 2]
                        prefs.lockdownUnlock = Lockdown.Unlock.WAIT.ordinal
                        Lockdown.cancelWait(prefs)
                        render()
                    }
                }
            }
            if (stricter) change() else gate.loosen(change)
        }
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
    internal fun minutesName(minutes: Int) = if (minutes <= 0) getString(R.string.off) else LockdownGate.duration(requireContext(), minutes * 60_000L)
}
