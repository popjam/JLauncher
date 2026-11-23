package app.olauncher.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.updatePadding
import app.olauncher.R
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr

// The one settings row, used by Settings, Lockdown and the Newsletter pages, and the sections they go in.

/**
 * A setting's row: [title] on the left, [value] on the right (cut short rather than squeezing the
 * title), indented under the setting it belongs to by [level] (a thin line down the left for each).
 *
 * - [onTap]: what tapping does; null for a row that only shows something.
 * - [about]: what holding it shows, in a small card under it ([explainSetting]); [onHold] instead
 *   does something else on hold. With neither, holding does nothing.
 * - [faded]: an action or a done thing, drawn fainter. [color]: the title and value in a colour
 *   instead of the ink (the reader's current section).
 * - [titleLines]: at most this many lines of title before it's cut short.
 *
 * Its value is the row's last child, so [valueColor] can recolour it. Put rows in a section with
 * [CardPageFragment.rowsSection].
 */
internal fun CardPageFragment.settingRow(
    title: String,
    value: String? = null,
    about: String? = null,
    level: Int = 0,
    faded: Boolean = false,
    color: Int? = null,
    titleLines: Int = 3,
    onHold: (() -> Unit)? = null,
    onTap: (() -> Unit)?,
): LinearLayout {
    val context = requireContext()
    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(4.dpToPx(), 0, 4.dpToPx(), 0)
        setBackgroundResource(android.R.drawable.list_selector_background)
        if (faded) alpha = 0.55f
        if (onTap != null) setOnClickListener { onTap() }
        when {
            onHold != null -> setOnLongClickListener { onHold(); true }
            about != null -> setOnLongClickListener { explainSetting(this, title, about); true }
        }
        // A line down the left for each level it's under, joining it to the setting it belongs to
        for (l in 1..level) addView(View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 1.dpToPx().toFloat()
                setColor(faint(70))
            }
        }, LinearLayout.LayoutParams(2.dpToPx(), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            marginStart = if (l == 1) 6.dpToPx() else 12.dpToPx()
            if (l == level) marginEnd = 12.dpToPx()
        })
        addView(TextView(context, null, 0, R.style.TextSmall).apply {
            text = title
            textSize = Style.TEXT_BODY
            setTextColor(color ?: faint(if (level == 0) 255 else 230))
            if (level == 0) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = titleLines
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 13.dpToPx(), 0, 13.dpToPx())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (value != null) addView(TextView(context, null, 0, R.style.TextSmall).apply {
            text = value
            textSize = Style.TEXT_BODY
            setTextColor(color ?: faint(215))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END
            maxWidth = (resources.displayMetrics.widthPixels * 0.45f).toInt()
            updatePadding(left = 12.dpToPx())
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}

/** Colours a [settingRow]'s value */
internal fun View.valueColor(color: Int) {
    ((this as? ViewGroup)?.let { it.getChildAt(it.childCount - 1) } as? TextView)?.setTextColor(color)
}

/** What a setting does, in a small card under [anchor]; one at a time, gone when the page pauses */
internal fun CardPageFragment.explainSetting(anchor: View, title: String, text: String) {
    infoPopup?.dismiss()
    val context = requireContext()
    val box = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = Style.radiusSmall
            // Solid, so the rows under it don't show through
            setColor(ColorUtils.setAlphaComponent(context.getColorFromAttr(R.attr.primaryShadeDarkColor), 255))
            setStroke(1.dpToPx(), faint(60))
        }
        setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 14.dpToPx())
        addView(TextView(context, null, 0, R.style.TextSmall).apply {
            this.text = title
            textSize = Style.TEXT_BODY
            setTextColor(ink)
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(context, null, 0, R.style.TextSmall).apply {
            this.text = text
            textSize = Style.TEXT_BODY
            setTextColor(ink)
            alpha = 0.8f
            setLineSpacing(0f, 1.15f)
            updatePadding(top = 4.dpToPx())
        })
    }
    infoPopup = PopupWindow(box, anchor.width.coerceAtLeast(200.dpToPx()), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
        isOutsideTouchable = true
        elevation = 8.dpToPx().toFloat()
        showAsDropDown(anchor, 0, 4.dpToPx())
    }
}
