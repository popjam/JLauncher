package app.olauncher.ui

import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.databinding.FragmentWeatherBinding
import app.olauncher.helper.Weather
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

/** Today's weather in detail, the next 24 hours and the week, from the forecast the home screen uses. */
class WeatherFragment : Fragment() {

    private var _binding: FragmentWeatherBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MainViewModel
    private var forecast: Weather.Forecast? = null
    private var selectedDate: LocalDate = LocalDate.now()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWeatherBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]
        viewModel.weather.observe(viewLifecycleOwner) {
            forecast = it
            render()
        }
        viewModel.weatherLoading.observe(viewLifecycleOwner) { renderStatus() }
        binding.status.setOnClickListener { viewModel.getWeather(force = true) }
    }

    override fun onResume() {
        super.onResume()
        selectedDate = LocalDate.now()
        viewModel.getWeather()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private val textColor get() = requireContext().getColorFromAttr(R.attr.primaryColor)

    private fun render() {
        val forecast = forecast
        binding.dayCard.isVisible = forecast != null
        binding.hoursCard.isVisible = forecast != null
        (binding.daysList.parent as View).isVisible = forecast != null
        renderStatus()
        forecast ?: return
        val days = forecast.upcomingDays()
        if (days.none { it.date == selectedDate }) selectedDate = days.firstOrNull()?.date ?: return
        renderDay(forecast, days.first { it.date == selectedDate })
        renderHours(forecast)
        renderDays(days)
    }

    private fun renderDay(forecast: Weather.Forecast, day: Weather.Day) {
        val context = requireContext()
        val isToday = day.date == LocalDate.now()
        val dateText = day.date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
        binding.place.text = forecast.place
        binding.place.isVisible = forecast.place.isNotBlank()
        binding.dayLabel.text = if (isToday) "${getString(R.string.today)} · $dateText" else dateText

        // Today shows the temperature now; other days their high
        val now = if (isToday) forecast.upcomingHours(1).firstOrNull() else null
        binding.dayIcon.code = day.code
        binding.dayIcon.uv = day.uv
        binding.dayIcon.isDay = true
        binding.dayIcon.contentDescription = Weather.conditionName(context, day.code)
        binding.dayTemp.text = "${now?.temp ?: day.max}°"
        binding.dayCondition.text = Weather.conditionName(context, day.code)
        binding.dayHighLow.text = getString(R.string.weather_high_low, day.max, day.min)
        // Only today has one, but the line keeps its space
        binding.dayFeelsLike.isInvisible = now == null
        now?.let { binding.dayFeelsLike.text = getString(R.string.weather_feels_like, it.feelsLike) }

        val sun = listOfNotNull(day.sunrise, day.sunset).joinToString(" · ") { formatTime(it) }
        val stats = listOf(
            getString(R.string.weather_uv) to "${day.uv} · ${Weather.uvLevel(context, day.uv)}",
            getString(R.string.weather_rain_label) to "${day.rainChance}% · ${forecast.rainText(day.rain)}",
            getString(R.string.weather_wind) to "${day.windMax} ${forecast.windUnit}",
            getString(R.string.weather_sun) to sun.ifEmpty { "–" },
        )
        binding.dayStats.removeAllViews()
        stats.chunked(2).forEachIndexed { row, pair ->
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                if (row > 0) setPadding(0, 12.dpToPx(), 0, 0)
            }
            pair.forEach { (label, value) ->
                line.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(smallText(label, 12f, 0.6f))
                    addView(smallText(value, 15f).apply { isSingleLine = true })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            binding.dayStats.addView(line)
        }
    }

    private fun renderHours(forecast: Weather.Forecast) {
        val context = requireContext()
        val hourFormat = DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h a", Locale.getDefault())
        binding.hoursRow.removeAllViews()
        // Equal-width columns with every line centred in them, so the hours line up
        fun line(text: String, size: Float, alpha: Float = 1f) = smallText(text, size, alpha).apply {
            gravity = Gravity.CENTER
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        forecast.upcomingHours().forEachIndexed { i, hour ->
            binding.hoursRow.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(line(if (i == 0) getString(R.string.weather_now) else hour.time.format(hourFormat).lowercase(), 12f, 0.7f))
                addView(PixelWeatherView(context).apply {
                    code = hour.code
                    uv = hour.uv
                    isDay = hour.isDay
                    contentDescription = Weather.conditionName(context, hour.code)
                }, LinearLayout.LayoutParams(28.dpToPx(), 28.dpToPx()).apply { topMargin = 6.dpToPx() })
                addView(line("${hour.temp}°", 15f).apply { setPadding(0, 6.dpToPx(), 0, 0) })
                addView(line(getString(R.string.uv_index, hour.uv), 11f, 0.7f).apply { setPadding(0, 2.dpToPx(), 0, 0) })
                // Only worth a mention when rain is a real chance
                addView(line(if (hour.rainChance >= 10) "${hour.rainChance}%" else " ", 11f, 0.7f))
            }, LinearLayout.LayoutParams(60.dpToPx(), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun renderDays(days: List<Weather.Day>) {
        val context = requireContext()
        binding.daysList.removeAllViews()
        days.forEach { day ->
            val name = if (day.date == LocalDate.now()) getString(R.string.today)
            else day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            binding.daysList.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
                if (day.date == selectedDate) setBackgroundResource(R.drawable.bg_day_selected)
                setOnClickListener {
                    selectedDate = day.date
                    render()
                }
                addView(smallText(name, 15f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(PixelWeatherView(context).apply {
                    code = day.code
                    uv = day.uv
                    contentDescription = Weather.conditionName(context, day.code)
                }, LinearLayout.LayoutParams(28.dpToPx(), 28.dpToPx()))
                addView(smallText(if (day.rainChance >= 10) "${day.rainChance}%" else "", 13f, 0.7f).apply {
                    gravity = Gravity.END
                }, LinearLayout.LayoutParams(48.dpToPx(), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(smallText(getString(R.string.uv_index, day.uv), 13f, 0.7f).apply {
                    gravity = Gravity.END
                }, LinearLayout.LayoutParams(56.dpToPx(), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(smallText("${day.max}°", 15f).apply {
                    gravity = Gravity.END
                }, LinearLayout.LayoutParams(52.dpToPx(), ViewGroup.LayoutParams.WRAP_CONTENT))
            })
        }
    }

    private fun renderStatus() {
        val forecast = forecast
        binding.status.text = when {
            viewModel.weatherLoading.value == true -> getString(R.string.weather_loading)
            forecast == null -> getString(R.string.weather_failed)
            else -> getString(R.string.weather_updated, DateFormat.getTimeFormat(requireContext()).format(Date(forecast.fetchedAt)))
        }
    }

    private fun formatTime(time: LocalTime) =
        time.format(DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(requireContext())) "HH:mm" else "h:mm a", Locale.getDefault()))

    private fun smallText(text: String, size: Float, alpha: Float = 1f) =
        TextView(requireContext(), null, 0, R.style.TextSmall).apply {
            this.text = text
            textSize = size
            this.alpha = alpha
            setTextColor(textColor)
        }
}
