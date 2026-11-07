package app.olauncher.ui

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.helper.dpToPx
import app.olauncher.helper.formattedTimeSpent
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.setStatDot
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A page of cards, each a title with options on its right, a drawing ([StatSceneView]) and what's
 * picked in it. Stat pages and Home build theirs from these.
 */
abstract class CardPageFragment : Fragment() {

    /** Where cards go, in order */
    internal abstract val cards: LinearLayout

    /** Colours for the dots before details rows whose label is a stat's name, and the stat's icon to use instead */
    internal open fun colorOfLabel(label: String): Int? = null
    internal open fun iconOfLabel(label: String): String? = null

    /** The day page for [date], from wherever a day was held */
    internal open fun openDay(date: LocalDate) {
        runCatching { findNavController().navigate(R.id.action_global_dayFragment, DayFragment.args(date)) }
    }

    /** A stat's page, from wherever it was held */
    internal fun openStat(id: String) {
        runCatching { findNavController().navigate(R.id.action_global_statFragment, androidx.core.os.bundleOf(StatFragment.STAT_ID to id)) }
    }

    internal val ink by lazy { requireContext().getColorFromAttr(R.attr.primaryColor) }
    internal fun faint(alpha: Int) = ColorUtils.setAlphaComponent(ink, alpha)

    internal fun dateText(date: LocalDate) = date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
    /** A time in the phone's own style, 24-hour or not */
    internal fun clock(time: java.time.LocalTime): String =
        if (android.text.format.DateFormat.is24HourFormat(requireContext())) time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
        else time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())).lowercase(Locale.getDefault())
    internal fun duration(ms: Long) = requireContext().formattedTimeSpent(ms)
    internal fun decimal(value: Float) = String.format(Locale.getDefault(), "%.1f", value)
    internal fun money(cents: Long) = String.format(Locale.getDefault(), "$%,d.%02d", cents / 100, cents % 100)

    /** Whole dollars when it's at least $100, else dollars and cents */
    internal fun moneyShort(cents: Long) = if (cents >= 10_000) String.format(Locale.getDefault(), "$%,d", (cents + 50) / 100) else money(cents)

    /** A card with its title, anything on the right of it, its drawing, a key, and the tapped point's details. */
    internal inner class Card(title: String, at: Int = -1, icon: String? = null, iconColor: Int? = null) {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.rounded_rect_shade_color)
            setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 14.dpToPx())
        }
        val headerEnd = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = textView(15f, 255).apply {
            this.text = title
            if (icon != null) {
                setCompoundDrawablesRelativeWithIntrinsicBounds(StatIcons.drawable(icon, iconColor ?: ink, 15.dpToPx()), null, null, null)
                compoundDrawablePadding = 8.dpToPx()
            }
        }
        val scene = StatSceneView(context)
        val note = textView(13f, 150).apply {
            gravity = Gravity.CENTER
            updatePadding(top = 20.dpToPx(), bottom = 20.dpToPx())
            isVisible = false
        }
        val chips = FlowLayout(context).apply { isVisible = false }
        val key = FlowLayout(context).apply { isVisible = false }
        val details = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 14.dpToPx().toFloat()
                setColor(faint(16))
            }
            setPadding(14.dpToPx(), 10.dpToPx(), 14.dpToPx(), 10.dpToPx())
            isVisible = false
        }

        /** What the tapped point's details say: a title, then " · " or new lines between parts, "Name: value" for a row */
        var describe: (Any) -> String = { "" }

        /** The stat a held target stands for, if any, to open its page */
        var statOf: (Any) -> String? = { null }

        init {
            root.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(headerEnd)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 14.dpToPx()
            })
            root.addView(scene)
            root.addView(note)
            root.addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 14.dpToPx()
            })
            root.addView(key, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 12.dpToPx()
            })
            root.addView(details, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 12.dpToPx()
            })
            scene.onPicked = { picked -> showDetails(picked?.let(describe)) }
            scene.onHeld = { key -> if (key is LocalDate) openDay(key) else statOf(key)?.let { openStat(it) } }
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 0)
            }
            if (at >= 0) cards.addView(root, at, params) else cards.addView(root, params)
        }

        fun textView(size: Float, alpha: Int) = TextView(context, null, 0, R.style.TextSmall).apply {
            textSize = size
            setTextColor(faint(alpha))
        }

        /**
         * The picked point's details as a small panel: the first part bold as its title, then a row per
         * part, "Name: value" ones with the name on the left (and its stat's dot) and the value on the right.
         */
        fun showDetails(text: String?) {
            details.removeAllViews()
            details.isVisible = !text.isNullOrBlank()
            if (text.isNullOrBlank()) return
            val parts = text.split("\n").flatMap { it.split(" · ") }.map { it.trim() }.filter { it.isNotEmpty() }
            details.addView(textView(14f, 255).apply {
                this.text = parts.first()
                setTypeface(null, Typeface.BOLD)
            })
            parts.drop(1).forEach { part ->
                val split = part.indexOf(": ")
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    updatePadding(top = 5.dpToPx())
                }
                if (split > 0) {
                    val label = part.substring(0, split)
                    row.addView(textView(13f, 170).apply {
                        this.text = label
                        colorOfLabel(label)?.let { color ->
                            val icon = iconOfLabel(label)
                            if (icon != null) {
                                setCompoundDrawablesRelativeWithIntrinsicBounds(StatIcons.drawable(icon, color, 13.dpToPx()), null, null, null)
                                compoundDrawablePadding = 8.dpToPx()
                            } else setStatDot(color)
                        }
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    row.addView(textView(13f, 255).apply {
                        this.text = part.substring(split + 2)
                        setTypeface(null, Typeface.BOLD)
                        gravity = Gravity.END
                    })
                } else row.addView(textView(13f, 200).apply { this.text = part })
                details.addView(row)
            }
        }

        fun subtitle(text: String) {
            headerEnd.removeAllViews()
            headerEnd.addView(textView(13f, 150).apply { this.text = text })
        }

        /** A big figure with a line under it, between the title and the drawing */
        fun headline(figure: String, caption: String) {
            root.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(TextView(context, null, 0, R.style.TextLarge).apply {
                    text = figure
                    textSize = 40f
                    setTextColor(ink)
                    includeFontPadding = false
                })
                if (caption.isNotEmpty()) addView(textView(13f, 150).apply {
                    text = caption
                    updatePadding(top = 4.dpToPx())
                })
            }, root.indexOfChild(scene), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 16.dpToPx()
            })
        }

        /** Options on the right, the [selected] one bold. */
        fun segment(options: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
            headerEnd.removeAllViews()
            options.forEachIndexed { i, option ->
                headerEnd.addView(textView(13f, if (i == selected) 255 else 120).apply {
                    setText(option)
                    if (i == selected) setTypeface(null, Typeface.BOLD)
                    setPadding(8.dpToPx(), 4.dpToPx(), if (i == options.lastIndex) 0 else 2.dpToPx(), 4.dpToPx())
                    setOnClickListener { if (i != selected) onSelect(i) }
                })
            }
        }

        /** ‹ [text] ›, stepping with [onStep] (-1 back, 1 on) where [canGoBack] and [canGoForward]. */
        fun stepper(text: String, canGoBack: Boolean, canGoForward: Boolean, days: Int, onStep: (Int) -> Unit) {
            headerEnd.removeAllViews()
            fun arrow(symbol: String, enabled: Boolean, by: Int, description: Int) = TextView(context, null, 0, R.style.TextLarge).apply {
                this.text = symbol
                textSize = 18f
                setTextColor(ink)
                alpha = if (enabled) 0.8f else 0.2f
                isEnabled = enabled
                contentDescription = getString(description, days)
                setPadding(10.dpToPx(), 0, 10.dpToPx(), 0)
                setOnClickListener { onStep(by) }
            }
            headerEnd.addView(arrow("‹", canGoBack, -1, R.string.previous_days))
            headerEnd.addView(textView(13f, 150).apply { this.text = text })
            headerEnd.addView(arrow("›", canGoForward, 1, R.string.next_days))
        }

        /**
         * A chip per option, each with its colour's dot. The [selected] one stands out and the rest fade;
         * with none selected they're all as they are.
         */
        fun chipRow(options: List<Pair<String, Int>>, selected: Int?, onTap: (Int) -> Unit) {
            chips.isVisible = true
            chips.removeAllViews()
            options.forEachIndexed { i, (name, color) ->
                chips.addView(textView(13f, 255).apply {
                    text = name
                    setBackgroundResource(R.drawable.bg_stat_chip)
                    setPadding(10.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
                    setStatDot(color)
                    alpha = when (selected) {
                        null -> 0.8f
                        i -> 1f
                        else -> 0.35f
                    }
                    if (i == selected) setTypeface(null, Typeface.BOLD)
                    setOnClickListener { onTap(i) }
                })
            }
        }

        /** A key entry: a dot (or ring, or [icon]) in [color] and what it stands for */
        fun keyItem(color: Int, text: String, ring: Boolean = false, icon: String? = null) {
            key.isVisible = true
            key.addView(textView(13f, 150).apply {
                this.text = text
                gravity = Gravity.CENTER_VERTICAL
                val size = 11.dpToPx()
                val mark = if (icon != null) StatIcons.drawable(icon, color, 13.dpToPx()) else GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setSize(size, size)
                    if (ring) setStroke(2.dpToPx(), color) else setColor(color)
                }
                setCompoundDrawablesRelativeWithIntrinsicBounds(mark, null, null, null)
                compoundDrawablePadding = 6.dpToPx()
                setPadding(0, 2.dpToPx(), 16.dpToPx(), 2.dpToPx())
            })
        }

        fun draw(build: (StatSceneView.Scene) -> Unit) {
            note.isVisible = false
            scene.isVisible = true
            details.isVisible = false
            scene.setScene(build = build)
        }

        /** Instead of a drawing, when there's too little to draw */
        fun showNote(text: String) {
            scene.isVisible = false
            chips.isVisible = false
            details.isVisible = false
            note.isVisible = true
            note.text = text
        }

        /** A view of its own under the title, in place of the drawing */
        fun addBody(view: android.view.View) {
            scene.isVisible = false
            root.addView(view, root.indexOfChild(scene), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** Three (or so) figures side by side under [caption] */
    internal fun tiles(card: Card, caption: String?, tiles: List<Pair<String, String>>) {
        card.draw { s ->
            var top = 0f
            if (caption != null) {
                s.text(caption, 0f, s.sp(12f), faint(140), s.sp(12f), Paint.Align.LEFT)
                top = s.dp(22f)
            }
            val gap = s.dp(8f)
            val width = (s.width - gap * (tiles.size - 1)) / tiles.size
            tiles.forEachIndexed { i, (value, label) ->
                val x = i * (width + gap)
                s.rect(x, top, x + width, top + s.dp(64f), faint(18), s.dp(14f))
                s.text(value, x + width / 2, top + s.dp(30f), ink, s.sp(17f), bold = true)
                s.text(label, x + width / 2, top + s.dp(50f), faint(150), s.sp(12f))
            }
            s.height = top + s.dp(64f)
        }
    }

    /** A bar per row, as long as its count, with the count after it; [icons] go before the names. */
    internal fun horizontalBars(card: Card, rows: List<Triple<String, Int, Int>>, icons: List<String?>? = null, countText: (Int) -> String) {
        val most = rows.maxOfOrNull { it.second } ?: 0
        if (most == 0) return card.showNote(getString(R.string.no_data))
        card.draw { s ->
            val nameSize = s.sp(13f)
            val textPaint = TextPaint().apply { textSize = nameSize }
            val iconSpace = if (icons != null) s.dp(20f) else 0f
            val countWidth = rows.maxOf { s.textWidth(countText(it.second), s.sp(12f)) } + s.dp(10f)
            val left = minOf(maxOf(s.dp(60f), rows.maxOf { s.textWidth(it.first, nameSize) } + s.dp(12f)), s.width * 0.42f) + iconSpace
            val longest = s.width - left - countWidth
            val rowHeight = s.dp(28f)
            rows.forEachIndexed { i, (name, count, color) ->
                val y = i * rowHeight
                icons?.getOrNull(i)?.let { s.icon(it, 0f, y + s.dp(4f), s.dp(14f), faint(190)) }
                val shown = TextUtils.ellipsize(name, textPaint, left - iconSpace - s.dp(8f), TextUtils.TruncateAt.END).toString()
                s.text(shown, iconSpace, y + s.dp(16f), faint(220), nameSize, Paint.Align.LEFT)
                val length = maxOf(longest * count / most, s.dp(3f))
                s.rect(left, y + s.dp(5f), left + length, y + s.dp(19f), color, s.dp(5f))
                s.text(countText(count), left + length + s.dp(8f), y + s.dp(16.5f), faint(150), s.sp(12f), Paint.Align.LEFT)
                s.target(i, RectF(0f, y, s.width, y + rowHeight))
            }
            s.height = rows.size * rowHeight
        }
    }

    /** Every [step]th of [count] points, and the last, dropping one that would crowd it. */
    internal fun labelIndices(count: Int, step: Int): List<Int> {
        if (count <= 0) return emptyList()
        val indices = (0 until count step step).toMutableList()
        if (indices.last() != count - 1) {
            if (count - 1 - indices.last() <= step / 2 && indices.size > 1) indices.removeAt(indices.lastIndex)
            indices.add(count - 1)
        }
        return indices
    }
}
