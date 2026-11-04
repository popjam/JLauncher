package app.olauncher.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.helper.allStats

/**
 * The stat pages, opened on the stat held under the All graph (or the open tab tapped), swiping
 * sideways to the others in tab order. The pages either side are built ahead so the swipe stays smooth.
 */
class StatFragment : Fragment(R.layout.fragment_stat) {

    /** The stats in tab order, kept while the pages are open so swiping doesn't jump about */
    private var ids = emptyList<String>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (ids.isEmpty()) ids = requireContext().allStats(MoodStore(requireContext()).load()).map { it.id }
        val pager = view.findViewById<ViewPager2>(R.id.statPager)
        pager.offscreenPageLimit = 1
        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = ids.size
            override fun createFragment(position: Int) =
                StatPageFragment().apply { arguments = bundleOf(STAT_ID to ids[position]) }
        }
        // The pages own their scrolling; the pager's own overscroll glow only gets in the way
        (pager.getChildAt(0) as? ViewGroup)?.overScrollMode = View.OVER_SCROLL_NEVER
        if (savedInstanceState == null) {
            val start = ids.indexOf(requireArguments().getString(STAT_ID)).coerceAtLeast(0)
            pager.setCurrentItem(start, false)
        }
    }

    companion object {
        const val STAT_ID = "statId"
    }
}
