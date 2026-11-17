package app.olauncher.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.helper.getColorFromAttr
import java.time.LocalDate

/**
 * Journal notes a page each ([JournalEntryFragment]), swiped through like the days: earlier to the
 * left, later to the right. Every day with a note is there, and today, to write it; the one opened is
 * too even if it has no note yet. Pulling down closes it.
 */
class JournalEntriesFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val today = LocalDate.now()
        val opened = arguments?.getString(DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.coerceAtMost(today) ?: today
        val query = arguments?.getString(QUERY).orEmpty()
        val written = MoodStore(context).load().days.mapNotNull { (key, day) ->
            if (day.note.isNullOrBlank()) null else runCatching { LocalDate.parse(key) }.getOrNull()
        }
        val dates = (written + opened + today).distinct().sorted()
        val pager = ViewPager2(context).apply {
            setBackgroundColor(context.getColorFromAttr(R.attr.primaryShadeDarkColor))
            offscreenPageLimit = 1
            adapter = object : FragmentStateAdapter(this@JournalEntriesFragment) {
                override fun getItemCount() = dates.size
                // The search that opened it is marked on its own page only
                override fun createFragment(position: Int) = JournalEntryFragment().apply {
                    val date = dates[position]
                    arguments = JournalEntryFragment.args(date, if (date == opened) query else "")
                }
            }
            setCurrentItem(dates.indexOf(opened), false)
            // Remembered, so coming back (from the day's page, say) returns to the note swiped to
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    arguments?.putString(DATE, dates[position].toString())
                    arguments?.putString(QUERY, if (dates[position] == opened) query else "")
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
        const val QUERY = "query"

        fun args(date: LocalDate, query: String = "") = bundleOf(DATE to date.toString(), QUERY to query)
    }
}

/**
 * Opens [date]'s note; [query]'s words are marked in it. From the day's page ([fromDay]) it takes that
 * page's place, so going back and forth between the two never piles up pages to go back through.
 */
fun Fragment.openNote(date: LocalDate, query: String = "", fromDay: Boolean = false) {
    findNavController().navigate(R.id.journalEntryFragment, JournalEntriesFragment.args(date, query), navOptions {
        anim {
            enter = R.anim.fade_enter
            exit = R.anim.fade_exit
            popEnter = R.anim.fade_enter
            popExit = R.anim.fade_exit
        }
        if (fromDay) popUpTo(R.id.dayFragment) { inclusive = true }
    })
}

/** Opens [date]'s page from its note, in the note's place (see [openNote]) */
fun Fragment.openDayFromNote(date: LocalDate) {
    findNavController().navigate(R.id.dayFragment, DayFragment.args(date), navOptions {
        anim {
            enter = R.anim.fade_enter
            exit = R.anim.fade_exit
            popEnter = R.anim.fade_enter
            popExit = R.anim.fade_exit
        }
        popUpTo(R.id.journalEntryFragment) { inclusive = true }
    })
}
