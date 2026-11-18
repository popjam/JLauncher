package app.olauncher.helper

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.ui.PixelFaceView

/**
 * Lockdown's sheet, shown by the accessibility service over whatever is on screen: a card at the
 * bottom in the check-in's style, with a pixel face beside a light title, a line or two under it, and
 * a wide Close. Used for blocked apps, sites and settings pages, and for limit notices.
 */
object BlockScreen {

    /**
     * The whole overlay: [shade] over the screen and the card at the bottom. [mood] picks the face
     * (the sad red one for blocks).
     */
    fun view(context: Context, title: String, lines: List<String>, mood: Int, shade: Int, onClose: () -> Unit): View {
        val ink = Color.WHITE
        fun faint(alpha: Int) = ColorUtils.setAlphaComponent(ink, alpha)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 22.dpToPx().toFloat()
                setColor(Color.BLACK)
                setStroke((0.8f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1), faint(128))
            }
            setPadding(20.dpToPx(), 20.dpToPx(), 20.dpToPx(), 18.dpToPx())
            // Touches on the card stay on the card
            isClickable = true
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(PixelFaceView(context).apply { this.mood = mood }, LinearLayout.LayoutParams(36.dpToPx(), 36.dpToPx()).apply {
                    marginEnd = 14.dpToPx()
                })
                addView(TextView(context, null, 0, R.style.TextLarge).apply {
                    text = title
                    textSize = 23f
                    setTextColor(ink)
                    setShadowLayer(0f, 0f, 0f, 0)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
            lines.filter { it.isNotBlank() }.forEachIndexed { i, line ->
                addView(TextView(context, null, 0, R.style.TextSmall).apply {
                    text = line
                    textSize = 15f
                    setTextColor(faint(195))
                    setShadowLayer(0f, 0f, 0f, 0)
                    setLineSpacing(0f, 1.15f)
                    setPadding(0, if (i == 0) 12.dpToPx() else 4.dpToPx(), 0, 0)
                })
            }
            addView(TextView(context, null, 0, R.style.TextSmall).apply {
                text = context.getString(R.string.close)
                textSize = 16f
                setTextColor(ink)
                setShadowLayer(0f, 0f, 0f, 0)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = 18.dpToPx().toFloat()
                    setStroke(1.dpToPx(), faint(128))
                }
                setPadding(0, 12.dpToPx(), 0, 12.dpToPx())
                setOnClickListener { onClose() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 18.dpToPx()
            })
        }
        return FrameLayout(context).apply {
            setBackgroundColor(shade)
            // Nothing under the sheet can be tapped
            isClickable = true
            addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                setMargins(12.dpToPx(), 0, 12.dpToPx(), 40.dpToPx())
            })
        }
    }

    /** The sad red face, for anything blocked */
    const val BLOCKED = MoodStore.SAD

    /** A warning face, for a limit running low */
    const val WARNING = MoodStore.SAD - 1

    /** Over a blocked app: nearly black, so the app doesn't show through */
    const val SHADE_BLOCK = 0xF2000000.toInt()

    /** Over a settings page or an app with a notice: dimmed */
    const val SHADE_DIM = 0xB3000000.toInt()
}
