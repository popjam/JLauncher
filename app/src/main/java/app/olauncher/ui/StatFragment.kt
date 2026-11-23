package app.olauncher.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.helper.allStats

/**
 * The stat pages, opened on the stat tapped in the list, swiping sideways to the others in its order
 * (Supplements among them, where it sits in the list) and pulling down from the top to go back. The
 * pages either side are built ahead so the swipe stays smooth.
 */
class StatFragment : Fragment(R.layout.fragment_stat) {

    /** The stats in tab order, kept while the pages are open so swiping doesn't jump about */
    private var ids = emptyList<String>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (ids.isEmpty()) ids = pageIds(requireContext())
        view.findViewById<PullDismissLayout>(R.id.pull).onDismiss = { if (isAdded) findNavController().popBackStack() }
        val pager = view.findViewById<ViewPager2>(R.id.statPager)
        pager.offscreenPageLimit = 1
        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = ids.size
            override fun createFragment(position: Int): Fragment =
                if (ids[position] == MoodStore.SUPPLEMENTS) SupplementsFragment().apply {
                    // A supplement to show first, when opened from one
                    arguments = bundleOf(SupplementsFragment.IN_PAGER to true,
                        SupplementsFragment.SELECTED to this@StatFragment.arguments?.getString(SupplementsFragment.SELECTED))
                }
                else StatPageFragment().apply { arguments = bundleOf(STAT_ID to ids[position]) }
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

        /** The pages in the stats list's order: every stat, and Supplements where it was dragged to (at the top until then) */
        fun pageIds(context: Context): List<String> {
            val data = MoodStore(context).load()
            val ids = context.allStats(data).map { it.id }.toMutableList()
            val place = data.order.indexOf(MoodStore.SUPPLEMENTS).takeIf { it >= 0 }
                ?.let { at -> ids.count { data.order.indexOf(it) in 0 until at } } ?: 0
            ids.add(place, MoodStore.SUPPLEMENTS)
            return ids
        }
    }
}
