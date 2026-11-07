package app.olauncher.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import app.olauncher.R
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr

/**
 * A three-page look round on first open: home, the stats, and settings. Skippable at any point, and
 * shown again from Settings.
 */
object Tutorial {

    private val PAGES = listOf(
        R.string.tutorial_home_title to R.string.tutorial_home,
        R.string.tutorial_stats_title to R.string.tutorial_stats,
        R.string.tutorial_settings_title to R.string.tutorial_settings,
    )

    fun show(activity: Activity) {
        val ink = activity.getColorFromAttr(R.attr.primaryColor)
        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        var page = 0
        val step = TextView(activity, null, 0, R.style.TextSmall).apply {
            textSize = 13f
            setTextColor(ink)
            alpha = 0.5f
        }
        val title = TextView(activity, null, 0, R.style.TextLarge).apply {
            textSize = 26f
            setTextColor(ink)
            setPadding(0, 6.dpToPx(), 0, 0)
        }
        val body = TextView(activity, null, 0, R.style.TextSmall).apply {
            textSize = 16f
            setTextColor(ink)
            alpha = 0.85f
            setLineSpacing(0f, 1.25f)
            setPadding(0, 14.dpToPx(), 0, 0)
        }
        fun button(text: Int, faded: Boolean) = TextView(activity, null, 0, R.style.TextSmall).apply {
            setText(text)
            textSize = 16f
            setTextColor(ink)
            alpha = if (faded) 0.55f else 1f
            setPadding(12.dpToPx(), 10.dpToPx(), 12.dpToPx(), 10.dpToPx())
        }
        val skip = button(R.string.tutorial_skip, faded = true)
        val next = button(R.string.tutorial_next, faded = false)
        fun draw() {
            step.text = activity.getString(R.string.tutorial_step, page + 1, PAGES.size)
            title.setText(PAGES[page].first)
            body.setText(PAGES[page].second)
            next.setText(if (page == PAGES.lastIndex) R.string.tutorial_done else R.string.tutorial_next)
            skip.alpha = if (page == PAGES.lastIndex) 0f else 0.55f
        }
        skip.setOnClickListener { dialog.dismiss() }
        next.setOnClickListener {
            if (page == PAGES.lastIndex) dialog.dismiss() else {
                page++
                draw()
            }
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 28.dpToPx().toFloat()
                setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(activity.getColorFromAttr(R.attr.primaryShadeDarkColor), 255))
                setStroke(1.dpToPx(), androidx.core.graphics.ColorUtils.setAlphaComponent(ink, 50))
            }
            setPadding(26.dpToPx(), 24.dpToPx(), 16.dpToPx(), 12.dpToPx())
            addView(step)
            addView(title)
            addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = 10.dpToPx() })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 20.dpToPx(), 0, 0)
                addView(skip)
                addView(android.view.View(activity), LinearLayout.LayoutParams(0, 1, 1f))
                addView(next)
            })
        }
        dialog.setContentView(card)
        dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.86f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        draw()
        dialog.show()
    }
}
