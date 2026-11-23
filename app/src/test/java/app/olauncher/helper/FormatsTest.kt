package app.olauncher.helper

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class FormatsTest {

    @Test fun timesIn12Hours() {
        assertEquals("9:30 pm", Formats.time(LocalTime.of(21, 30), false))
        assertEquals("9 pm", Formats.time(LocalTime.of(21, 0), false))
        assertEquals("9:05 am", Formats.time(LocalTime.of(9, 5), false))
        assertEquals("12 am", Formats.time(LocalTime.MIDNIGHT, false))
        assertEquals("12 pm", Formats.time(LocalTime.NOON, false))
        assertEquals("12:30 am", Formats.time(LocalTime.of(0, 30), false))
    }

    @Test fun timesIn24Hours() {
        assertEquals("21:30", Formats.time(LocalTime.of(21, 30), true))
        assertEquals("09:00", Formats.time(LocalTime.of(9, 0), true))
        assertEquals("00:00", Formats.time(LocalTime.MIDNIGHT, true))
    }

    @Test fun ranges() {
        assertEquals("11 pm–5 am", Formats.timeRange(LocalTime.of(23, 0), LocalTime.of(5, 0), false))
        assertEquals("9:30 pm–6:15 am", Formats.timeRange(LocalTime.of(21, 30), LocalTime.of(6, 15), false))
        assertEquals("23:00–05:00", Formats.timeRange(LocalTime.of(23, 0), LocalTime.of(5, 0), true))
    }

    @Test fun axisHours() {
        assertEquals("6 am", Formats.hour(6, false))
        assertEquals("12 am", Formats.hour(24, false))
        assertEquals("06", Formats.hour(6, true))
        assertEquals("00", Formats.hour(24, true))
    }

    @Test fun minutesOfDay() {
        assertEquals(LocalTime.of(22, 30), Formats.minutesOfDay(22 * 60 + 30))
        assertEquals(LocalTime.of(23, 0), Formats.minutesOfDay(-60))
    }

    @Test fun durations() {
        assertEquals("0m", Formats.duration(0))
        assertEquals("0m", Formats.duration(-5_000))
        assertEquals("<1m", Formats.duration(59_999))
        assertEquals("1m", Formats.duration(60_000))
        assertEquals("45m", Formats.duration(45 * 60_000L))
        assertEquals("1h", Formats.duration(60 * 60_000L))
        assertEquals("1h 5m", Formats.duration(65 * 60_000L))
        assertEquals("25h", Formats.duration(25 * 3_600_000L))
    }

    @Test fun durationsRoundDown() {
        assertEquals("1m", Formats.duration(119_999))
        assertEquals("59m", Formats.duration(3_599_999))
        assertEquals("1h 59m", Formats.duration(2 * 3_600_000L - 1))
        assertEquals("1h 30m", Formats.minutes(90))
    }

    @Test fun percents() {
        assertEquals("<1%", Formats.percent(0.4f))
        assertEquals("0%", Formats.percent(0f))
        assertEquals("1%", Formats.percent(1f))
        assertEquals("25%", Formats.percent(24.6f))
    }
}
