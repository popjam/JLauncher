package app.olauncher.helper

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import app.olauncher.R

/**
 * The block screen's card: the app or site's name large, why it's blocked, what was used and when it
 * ends, and Close. Shown on black over the app by the accessibility service.
 */
object BlockScreen {

    fun view(context: Context, name: String, reason: String, detail: String, onClose: () -> Unit): View {
        val ink = context.getColorFromAttr(R.attr.primaryColor)
        fun faint(alpha: Int) = ColorUtils.setAlphaComponent(ink, alpha)
        fun text(value: String, size: Float, alpha: Int, large: Boolean = false) =
            TextView(context, null, 0, if (large) R.style.TextLarge else R.style.TextSmall).apply {
                text = value
                textSize = size
                setTextColor(faint(alpha))
                gravity = Gravity.CENTER
            }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = 18.dpToPx().toFloat()
                setColor(faint(20))
            }
            setPadding(18.dpToPx(), 28.dpToPx(), 18.dpToPx(), 18.dpToPx())
            addView(text(name, 26f, 255, large = true))
            addView(text(reason, 15f, 170).apply { setPadding(0, 8.dpToPx(), 0, 0) })
            if (detail.isNotEmpty()) addView(text(detail, 15f, 170).apply { setPadding(0, 4.dpToPx(), 0, 0) })
            addView(text(context.getString(R.string.close), 15f, 255).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 20.dpToPx().toFloat()
                    setColor(faint(36))
                }
                setPadding(24.dpToPx(), 8.dpToPx(), 24.dpToPx(), 8.dpToPx())
                setOnClickListener { onClose() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 22.dpToPx()
            })
        }.let { card ->
            // Margins at the sides, as the pause screen's card has
            LinearLayout(context).apply {
                setPadding(12.dpToPx(), 0, 12.dpToPx(), 0)
                addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }
    }
}
