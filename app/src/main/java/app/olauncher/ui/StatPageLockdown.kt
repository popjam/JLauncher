package app.olauncher.ui

import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.helper.Lockdown
import app.olauncher.helper.dpToPx
import java.time.LocalDate
import kotlin.math.roundToInt

/** Lockdown's page: today's blocked attempts, the week's average, and blocked attempts by day */
internal fun StatPageFragment.lockdownTodayCard() {
    val days = Lockdown.blockedDays(Prefs(requireContext()))
    val card = Card(getString(R.string.mac_today))
    val today = LocalDate.now()
    val count = days[today] ?: 0
    val week = (1..7L).map { days[today.minusDays(it)] ?: 0 }
    // To Lockdown's own page
    card.headerEnd.addView(card.textView(13f, 200).apply {
        text = getString(R.string.settings)
        setPadding(12.dpToPx(), 4.dpToPx(), 0, 4.dpToPx())
        setOnClickListener { runCatching { androidx.navigation.fragment.NavHostFragment.findNavController(this@lockdownTodayCard).navigate(R.id.lockdownFragment) } }
    })
    card.headline(count.toString(), getString(R.string.lockdown_stat_caption, week.average().roundToInt()))
    dayBars(card, days.mapValues { it.value.toFloat() }, stat.color) { date, value ->
        dateText(date) + " · " + getString(R.string.lockdown_blocked_count, value.roundToInt())
    }
}
