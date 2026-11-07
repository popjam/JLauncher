package app.olauncher.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.helper.getColorFromAttr
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The day pages ([DayPageFragment]), side by side from the first day with anything in it up to today:
 * swiping goes a day either way, pulling down closes them.
 */
class DayFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val today = LocalDate.now()
        val opened = arguments?.getString(DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.coerceAtMost(today) ?: today
        val earliest = MoodStore(context).load().days.keys.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.minOrNull()
        val first = minOf(earliest ?: opened, opened, today.minusDays(MIN_DAYS_BACK))
        val count = ChronoUnit.DAYS.between(first, today).toInt() + 1
        val pager = ViewPager2(context).apply {
            setBackgroundColor(context.getColorFromAttr(R.attr.primaryShadeDarkColor))
            offscreenPageLimit = 1
            adapter = object : FragmentStateAdapter(this@DayFragment) {
                override fun getItemCount() = count
                override fun createFragment(position: Int) = DayPageFragment().apply {
                    arguments = bundleOf(DATE to first.plusDays(position.toLong()).toString())
                }
            }
            setCurrentItem(ChronoUnit.DAYS.between(first, opened).toInt(), false)
            // Remembered, so coming back (from a stat's page, say) returns to the day swiped to
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    arguments?.putString(DATE, first.plusDays(position.toLong()).toString())
                }
            })
        }
        return PullDismissLayout(context).apply {
            addView(pager)
            onDismiss = { if (isAdded) findNavController().popBackStack() }
        }
    }

    companion object {
        const val DATE = "date"
        private const val MIN_DAYS_BACK = 30L

        fun args(date: LocalDate) = bundleOf(DATE to date.toString())
    }
}
