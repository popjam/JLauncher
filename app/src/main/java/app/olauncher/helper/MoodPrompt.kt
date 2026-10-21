package app.olauncher.helper

import android.view.View
import android.widget.LinearLayout
import app.olauncher.R
import app.olauncher.ui.PixelFaceView

object MoodPrompt {

    /**
     * Fills a view_mood_prompt layout with the five faces. [onResult] gets the chosen mood,
     * or null when the prompt is closed without answering.
     */
    fun bind(root: View, onResult: (Int?) -> Unit) {
        val context = root.context
        val names = context.resources.getStringArray(R.array.mood_names)
        val row = root.findViewById<LinearLayout>(R.id.moodPromptFaces)
        row.removeAllViews()
        for (mood in 0..4) {
            row.addView(PixelFaceView(context).apply {
                this.mood = mood
                contentDescription = names[mood]
                setPadding(6.dpToPx())
                isClickable = true
                isFocusable = true
                setOnClickListener { onResult(mood) }
            }, LinearLayout.LayoutParams(0, 52.dpToPx(), 1f))
        }
        root.findViewById<View>(R.id.moodPromptClose).setOnClickListener { onResult(null) }
    }

    private fun View.setPadding(px: Int) = setPadding(px, px, px, px)
}
