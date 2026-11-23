package app.olauncher.ui

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import app.olauncher.R
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr

/**
 * The one way to act on a list of things the user added (subreddits, places, supplements, …):
 *
 * - Hold and drag a row to move it, when the list has an order ([onReorder] given).
 * - Hold and let go (or tap, with [tapOpensMenu]) to open a menu of the row's [Action]s, plus
 *   **Select** when the list can remove several at once ([onRemove] given).
 * - Select puts the list in select mode: each row gets a tick mark, taps tick and untick, and a bar
 *   at the top of the screen says "N selected   Remove   ✕". Remove gives the ticked keys to
 *   [onRemove]; ✕ (or [end]) leaves select mode.
 *
 * Use one per list, kept by the page so select mode lasts while the page rebuilds:
 *
 * ```
 * private val subs = HoldList<String>(onReorder = { keys -> save(keys) }, onRemove = { keys -> remove(keys) })
 *
 * fun render() {
 *     val rows = ...                       // settingRow(name, value, onTap = null), say
 *     val list = card.rowsSection(rows)    // any vertical LinearLayout holding the rows
 *     subs.attach(list, scrollView)
 *     rows.forEach { row ->
 *         subs.item(row, key, label = name, onTap = { open(key) }, actions = listOf(
 *             HoldList.Action(getString(R.string.rename)) { rename(key) },
 *             HoldList.Action(getString(R.string.remove)) { remove(setOf(key)) },
 *         ))
 *     }
 * }
 *
 * override fun onPause() { subs.end(); super.onPause() }
 * ```
 *
 * Rows should be horizontal [LinearLayout]s (like [settingRow]); the tick goes at their start. Rows
 * given to [item] are the only ones that move; others in the list (an "Add" row) stay where they are.
 * [onReorder] gets every item's key in its new order after a drag.
 */
class HoldList<K : Any>(
    private val onReorder: ((List<K>) -> Unit)? = null,
    private val onRemove: ((Set<K>) -> Unit)? = null,
    private val tapOpensMenu: Boolean = false,
) {
    /** Something the menu offers for one row */
    class Action(val label: String, val run: () -> Unit)

    private var list: LinearLayout? = null
    private var scroll: ScrollView? = null
    private val keys = java.util.WeakHashMap<View, Any>()
    private val ticks = java.util.WeakHashMap<View, TextView>()
    private val selected = linkedSetOf<K>()
    private var bar: View? = null
    private var count: TextView? = null

    /** Whether the list is in select mode */
    var selecting = false
        private set

    /** Starts (again) with [list], which holds the rows about to be given to [item]; [scroll] scrolls near its edges while dragging */
    fun attach(list: LinearLayout, scroll: ScrollView?) {
        this.list = list
        this.scroll = scroll
        keys.clear()
        ticks.clear()
    }

    /**
     * Makes [row] one of the list's items, kept as [key]: [onTap] for a tap (null opens the menu when
     * [tapOpensMenu], else nothing), [actions] in its menu, titled [label].
     */
    fun item(row: View, key: K, label: String? = null, actions: List<Action> = emptyList(), onTap: (() -> Unit)? = null) {
        keys[row] = key
        fun menu() = showMenu(row, key, label, actions)
        row.isClickable = true
        row.setOnClickListener {
            when {
                selecting -> toggle(key)
                onTap != null -> onTap()
                tapOpensMenu -> menu()
            }
        }
        row.setOnLongClickListener(null)
        row.setOnTouchListener(HoldToDrag(onHold = { if (selecting) toggle(key) else menu() }) { _, x, y ->
            if (onReorder != null && !selecting) drag(row, x, y) else null
        })
        if (selecting) tick(row, key in selected)
    }

    /** Leaves select mode */
    fun end() {
        if (!selecting) return
        selecting = false
        selected.clear()
        (bar?.parent as? ViewGroup)?.removeView(bar)
        bar = null
        rows().forEach { untick(it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun keyOf(row: View) = keys[row] as K?

    private fun rows(): List<View> = list?.let { l -> (0 until l.childCount).map { l.getChildAt(it) }.filter { keys[it] != null } }.orEmpty()

    private fun showMenu(row: View, key: K, label: String?, actions: List<Action>) {
        val all = actions + listOfNotNull(if (onRemove != null) Action(row.context.getString(R.string.select)) { select(key) } else null)
        if (all.isEmpty()) return
        val dialog = AppDialog(row.context)
        if (label != null) dialog.setTitle(label)
        dialog.setItems(all.map { it.label }.toTypedArray()) { _, which -> all[which].run() }.show()
    }

    private fun select(first: K) {
        selecting = true
        selected.clear()
        selected.add(first)
        rows().forEach { tick(it, keyOf(it) in selected) }
        showBar()
    }

    private fun toggle(key: K) {
        if (!selected.add(key)) selected.remove(key)
        rows().forEach { tick(it, keyOf(it) in selected) }
        if (selected.isEmpty()) end() else updateBar()
    }

    // --- Tick marks ---

    private fun tick(row: View, on: Boolean) {
        val group = row as? LinearLayout ?: return
        val context = row.context
        val ink = context.getColorFromAttr(R.attr.primaryColor)
        val mark = ticks[row] ?: TextView(context, null, 0, R.style.TextSmall).apply {
            gravity = Gravity.CENTER
            textSize = Style.TEXT_SMALL
            setTypeface(typeface, Typeface.BOLD)
            group.addView(this, 0, LinearLayout.LayoutParams(22.dpToPx(), 22.dpToPx()).apply { marginEnd = 12.dpToPx() })
            ticks[row] = this
        }
        mark.text = if (on) "✓" else ""
        mark.setTextColor(context.getColorFromAttr(R.attr.primaryShadeDarkColor).let { ColorUtils.setAlphaComponent(it, 255) })
        mark.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (on) setColor(ink) else setStroke(1.dpToPx(), ColorUtils.setAlphaComponent(ink, 110))
        }
        mark.contentDescription = context.getString(if (on) R.string.on else R.string.off)
    }

    private fun untick(row: View) {
        ticks.remove(row)?.let { (it.parent as? ViewGroup)?.removeView(it) }
    }

    // --- The bar while selecting ---

    private fun showBar() {
        val list = list ?: return
        val context = list.context
        val root = (context as? Activity)?.findViewById<FrameLayout>(android.R.id.content)
            ?: (list.rootView as? ViewGroup) ?: return
        (bar?.parent as? ViewGroup)?.removeView(bar)
        val ink = context.getColorFromAttr(R.attr.primaryColor)
        val dark = ColorUtils.calculateLuminance(ink) > 0.5
        fun action(text: String, onTap: () -> Unit) = TextView(context, null, 0, R.style.TextSmall).apply {
            this.text = text
            textSize = Style.TEXT_BODY
            setTextColor(ink)
            gravity = Gravity.CENTER
            setPadding(14.dpToPx(), 10.dpToPx(), 14.dpToPx(), 10.dpToPx())
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { onTap() }
        }
        val label = TextView(context, null, 0, R.style.TextSmall).apply {
            textSize = Style.TEXT_BODY
            setTextColor(ink)
            setTypeface(typeface, Typeface.BOLD)
        }
        count = label
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = Style.radiusLarge
                setColor(Style.panel(dark))
                setStroke(1.dpToPx(), ColorUtils.setAlphaComponent(ink, 60))
            }
            elevation = 8.dpToPx().toFloat()
            setPadding(18.dpToPx(), 2.dpToPx(), 4.dpToPx(), 2.dpToPx())
            isClickable = true
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(action(context.getString(R.string.remove)) {
                val keys = selected.toSet()
                end()
                onRemove?.invoke(keys)
            })
            addView(action("✕") { end() }.apply { contentDescription = context.getString(R.string.cancel) })
        }
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            setMargins(12.dpToPx(), 40.dpToPx(), 12.dpToPx(), 0)
        })
        bar = view
        updateBar()
    }

    private fun updateBar() {
        val label = count ?: return
        label.text = label.context.getString(R.string.n_selected, selected.size)
    }

    // --- Moving ---

    private fun drag(row: View, x: Float, y: Float): DragSession {
        val list = list!!
        val drag = ListDrag(list, scroll) { keys[it] != null }
        drag.start(row)
        return DragSession(row, x, y, onMove = { _, fingerY -> drag.move(fingerY) }, onEnd = { drag.stop() }) {
            onReorder?.invoke(rows().mapNotNull { keyOf(it) })
        }
    }
}
