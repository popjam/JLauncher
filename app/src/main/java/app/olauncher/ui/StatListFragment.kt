package app.olauncher.ui

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.helper.allStats
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.statDot

/** Every stat in tab order, each with its colour's dot, opened from the All tab. Tapping one opens its page. */
class StatListFragment : Fragment(R.layout.fragment_stat_list) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        view.findViewById<View>(R.id.back).setOnClickListener { findNavController().popBackStack() }
        val rows = view.findViewById<LinearLayout>(R.id.statRows)
        val ripple = TypedValue().also { context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }.resourceId
        context.allStats(MoodStore(context).load()).forEach { stat ->
            rows.addView(TextView(context, null, 0, R.style.TextLarge).apply {
                text = stat.name
                textSize = 18f
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(context.getColorFromAttr(R.attr.primaryColor))
                setCompoundDrawablesRelativeWithIntrinsicBounds(statDot(stat.color, 12.dpToPx()), null, null, null)
                compoundDrawablePadding = 16.dpToPx()
                setPadding(28.dpToPx(), 14.dpToPx(), 24.dpToPx(), 14.dpToPx())
                setBackgroundResource(ripple)
                setOnClickListener {
                    findNavController().navigate(R.id.action_statListFragment_to_statFragment, bundleOf(StatFragment.STAT_ID to stat.id))
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
}
