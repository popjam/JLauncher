package app.olauncher.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
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
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr

/**
 * A settings page: ‹ and its title, anything [top] adds (a search box), then the list [build] makes
 * with [show], over the wallpaper. Pulling down from the top, ‹ and back all close it. [render]
 * builds it again whenever something changes; with [HIGHLIGHT] in its arguments the row with that key
 * is scrolled to and lit up once, as search opens it.
 */
abstract class SettingsBaseFragment : CardPageFragment() {

    companion object {
        /** Argument: the key of a row to show lit up for a moment, once */
        const val HIGHLIGHT = "highlight"
    }

    internal lateinit var prefs: Prefs
    private lateinit var cardList: LinearLayout
    override val cards: LinearLayout get() = cardList
    private var scroll: ScrollView? = null

    /** Where the page was scrolled to, kept while a page opened from it is on top */
    private var savedScroll = -1
    internal val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** The page's lists whose select mode ends when it pauses */
    internal open val holdLists: List<HoldList<*>> get() = emptyList()

    /** The page's title in its header */
    internal abstract fun title(): String

    /** The icon before the title, if any */
    internal open fun icon(): String? = null

    /** Builds the page with [show] */
    internal abstract fun build()

    /** Anything shown between the header and the list, like a search box */
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
            addView(pageHeader(context, icon(), title()) { findNavController().popBackStack() })
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

    /** Every row on the page now, by key */
    private val shown = mutableMapOf<String, View>()

    internal fun render() {
        if (!isAdded || view == null) return
        cards.removeAllViews()
        shown.clear()
        build()
        if (savedScroll >= 0) {
            val y = savedScroll
            savedScroll = -1
            scroll?.let { view -> view.post { view.scrollTo(0, y) } }
        }
        arguments?.getString(HIGHLIGHT)?.let { key ->
            arguments?.remove(HIGHLIGHT)
            highlight = key
        }
        // A moment later, on the rows as last built: opening a page can build it more than once
        if (highlight != null) main.postDelayed({
            val key = highlight ?: return@postDelayed
            shown[key]?.let { highlight = null; highlightRow(it) }
        }, 150)
        afterRender()
    }

    /** The row to light up once the page has settled, from [HIGHLIGHT] */
    private var highlight: String? = null

    /** Anything to do each time the page is built */
    internal open fun afterRender() {}

    /** Rows for a page, and what to do once they're on it (such as joining a [HoldList]) with their views by key */
    internal class Rows(val items: List<SettingItem?>, val then: (Map<String, View>) -> Unit = {}) {
        constructor(vararg items: SettingItem?) : this(items.toList())
        operator fun plus(other: Rows) = Rows(items + other.items) { then(it); other.then(it) }
    }

    /** Adds [rows] to the page as one list, under what's there already */
    internal fun show(rows: Rows) {
        val list = settingsList(rows.items.filterNotNull())
        cards.addView(list.view)
        shown.putAll(list.rows)
        rows.then(list.rows)
    }

    internal fun show(vararg items: SettingItem?) = show(Rows(items.toList()))

    /** The list holding the row [key], for a [HoldList] */
    internal fun Map<String, View>.listHolding(key: String): LinearLayout? = this[key]?.parent as? LinearLayout

    internal fun onOff(on: Boolean) = getString(if (on) R.string.on else R.string.off)

    /** A row, held to explain it with [about] */
    internal fun row(key: String, title: String, about: Int, value: String? = null, control: Control = Control.Value, level: Int = 0,
                     enabled: Boolean = true, locked: Boolean = false, danger: Boolean = false, faded: Boolean = false, note: String? = null,
                     onTap: (() -> Unit)? = null) =
        SettingRow(key, title, getString(about), value, control, level, enabled, locked, danger, faded, note, onTap = onTap)

    /** An on/off setting */
    internal fun switch(key: String, title: String, about: Int, on: Boolean, level: Int = 0, enabled: Boolean = true, locked: Boolean = false,
                        note: String? = null, onChange: (Boolean) -> Unit) =
        row(key, title, about, control = Control.Switch(on, onChange), level = level, enabled = enabled, locked = locked, note = note)

    /** Two or three short choices */
    internal fun segment(key: String, title: String, about: Int, options: List<String>, selected: Int, level: Int = 0, enabled: Boolean = true,
                         onPick: (Int) -> Unit) =
        row(key, title, about, control = Control.Segment(options, selected, onPick), level = level, enabled = enabled)

    /** Four or five choices, tapped through */
    internal fun cycle(key: String, title: String, about: Int, options: List<String>, selected: Int, level: Int = 0, enabled: Boolean = true,
                       onPick: (Int) -> Unit) =
        row(key, title, about, control = Control.Cycle(options, selected.coerceAtLeast(0), onPick), level = level, enabled = enabled)

    /** "Add", under a list */
    internal fun addRow(key: String, about: Int, level: Int = 1, enabled: Boolean = true, onTap: () -> Unit) =
        row(key, getString(R.string.add), about, level = level, faded = true, enabled = enabled, onTap = onTap)

    /** "None", for a list with nothing in it */
    internal fun noneRow(key: String, level: Int = 0) = row(key, getString(R.string.lockdown_none), R.string.info_list_none, level = level, faded = true)

    // --- Choosing ---

    /** A list to pick from with [selected] ticked, and Cancel; picking it again changes nothing, unless it's only [suggested] rather than the current value */
    internal fun choose(title: String, options: List<String>, selected: Int, suggested: Boolean = false, onPick: (Int) -> Unit) {
        AppDialog(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), selected) { dialog, which ->
                dialog.dismiss()
                if (which != selected || suggested) onPick(which)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- A box to search or add ---

    internal fun box(context: Context, hint: Int, onText: (String) -> Unit, onDone: ((String) -> Unit)? = null) =
        EditText(context, null, 0, R.style.TextSmall).apply {
            this.hint = getString(hint)
            textSize = Style.TEXT_BODY
            setTextColor(ink)
            setHintTextColor(faint(110))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or if (onDone != null) InputType.TYPE_TEXT_VARIATION_URI else InputType.TYPE_TEXT_VARIATION_FILTER
            imeOptions = if (onDone != null) android.view.inputmethod.EditorInfo.IME_ACTION_DONE else android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            background = GradientDrawable().apply {
                cornerRadius = Style.radiusLarge
                // Solid, so it reads over a light wallpaper
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
                onDone?.invoke(this.text.toString())
                view.clearFocus()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
                true
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(16.dpToPx(), 0, 16.dpToPx(), 4.dpToPx())
            }
        }

    /** A line of small text among rows */
    internal fun note(text: String) = TextView(requireContext(), null, 0, R.style.TextSmall).apply {
        this.text = text
        textSize = Style.TEXT_SMALL
        setTextColor(faint(150))
        setPadding(4.dpToPx(), 10.dpToPx(), 4.dpToPx(), 10.dpToPx())
    }

    /** A rounded button, for the setup's Back and Next */
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
}
