package io.nisfeb.talon.ui.screens

import io.nisfeb.talon.ui.SkyClock
import io.nisfeb.talon.ui.SkyClock.Weather
import io.nisfeb.talon.ui.parseForecast
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ring saying *when* rather than merely *whether*: clouds over the
 * hours that are cloudy, gloom over the hours that rain.
 */
class HourlyWeatherTest {

    private val rise = 6 * 60
    private val dayMinutes = 12 * 60

    /** Clear all day except a cloudy stretch from noon to four. */
    private val cloudyAfternoon = List(24) { h -> if (h in 12..16) 0.9f else 0.05f }

    private fun minutesOf(cover: List<Float>) =
        cloudMinutes(cover, currentCover = 0f, sunriseMinute = rise, dayMinutes = dayMinutes)

    @Test
    fun `clouds land on the hours that are cloudy`() {
        val at = minutesOf(cloudyAfternoon)
        assertTrue(at.isNotEmpty(), "a cloudy afternoon should put something on the ring")
        for ((minute, _) in at) {
            val h = minute / 60
            assertTrue(h in 12..16, "a cloud at ${h}:00, where cover is ${cloudyAfternoon[h]}")
        }
    }

    @Test
    fun `a clear day gets no clouds at all`() {
        assertTrue(minutesOf(List(24) { 0.02f }).isEmpty())
        // And a hair under the threshold is still clear.
        assertTrue(minutesOf(List(24) { CLOUD_THRESHOLD - 0.01f }).isEmpty())
    }

    @Test
    fun `clouds never bunch into one smear`() {
        val at = cloudMinutes(List(24) { 1f }, 0f, rise, dayMinutes)
        for (i in at.indices) {
            for (j in i + 1 until at.size) {
                val d = abs(at[i].first - at[j].first)
                assertTrue(
                    minOf(d, 1440 - d) >= CLOUD_MIN_GAP_MINUTES,
                    "clouds at ${at[i].first} and ${at[j].first} overlap",
                )
            }
        }
        assertTrue(at.size <= CLOUD_MAX, "${at.size} clouds is a crowd")
    }

    @Test
    fun `clouds stay in the daylight and off its ends`() {
        val at = cloudMinutes(List(24) { 1f }, 0f, rise, dayMinutes)
        for ((minute, _) in at) {
            val since = ((minute - rise) % 1440 + 1440) % 1440
            assertTrue(since >= 45, "a cloud $since minutes after sunrise hangs into the night")
            assertTrue(since <= dayMinutes - 45, "a cloud $since minutes in hangs past sunset")
        }
    }

    @Test
    fun `with no hourly run it falls back to the current reading`() {
        // An older forecast, or one that answered with only the current
        // block. Better an even scatter than an empty ring.
        assertTrue(cloudMinutes(emptyList(), 0.9f, rise, dayMinutes).isNotEmpty())
        assertTrue(cloudMinutes(emptyList(), 0.0f, rise, dayMinutes).isEmpty())
    }

    @Test
    fun `a cloudier hour draws a bigger cloud`() {
        assertTrue(cloudScale(1f) > cloudScale(0.3f))
        for (c in listOf(0f, 0.5f, 1f, 2f, -1f)) {
            assertTrue(cloudScale(c) in 0.8f..1.4f, "scale for $c is ${cloudScale(c)}")
        }
    }

    @Test
    fun `rain darkens the hour it rains, not the whole day`() {
        val hourly = List(24) { h -> if (h in 14..16) Weather.RAIN else Weather.CLEAR }
        assertEquals(0f, gloomAt(9 * 60, hourly, Weather.CLEAR), "the morning is left alone")
        assertEquals(Weather.RAIN.gloom, gloomAt(15 * 60, hourly, Weather.CLEAR))
        assertEquals(0f, gloomAt(20 * 60, hourly, Weather.CLEAR), "and the evening too")
    }

    @Test
    fun `gloom slides between hours instead of stepping`() {
        // A hundred and eighty segments over a day is seven and a half
        // to the hour; stepping draws visible stairs round the ring.
        val hourly = List(24) { h -> if (h >= 15) Weather.RAIN else Weather.CLEAR }
        val half = gloomAt(14 * 60 + 30, hourly, Weather.CLEAR)
        assertTrue(half > 0f && half < Weather.RAIN.gloom, "half past fourteen gave $half")
        var prev = gloomAt(13 * 60, hourly, Weather.CLEAR)
        for (m in 13 * 60..16 * 60) {
            val now = gloomAt(m, hourly, Weather.CLEAR)
            assertTrue(abs(now - prev) < 0.02f, "jumped at $m: $prev to $now")
            prev = now
        }
    }

    @Test
    fun `no hourly conditions falls back to what it is doing now`() {
        assertEquals(Weather.THUNDER.gloom, gloomAt(0, emptyList(), Weather.THUNDER))
        assertEquals(Weather.THUNDER.gloom, gloomAt(700, List(5) { Weather.CLEAR }, Weather.THUNDER))
    }

    @Test
    fun `the hourly run comes off the wire onto the ring`() {
        val body = """
        {"timezone":"America/New_York",
         "current":{"temperature_2m":18.0,"cloud_cover":40,"weather_code":3},
         "hourly":{"time":["2026-09-11T00:00","2026-09-11T01:00","2026-09-11T14:00"],
                   "temperature_2m":[10.0,11.0,20.0],
                   "cloud_cover":[10,20,95],
                   "weather_code":[0,0,61]}}
        """
        val s = parseForecast(body)!!
        assertEquals(24, s.hourlyCloud.size)
        assertEquals(0.1f, s.hourlyCloud[0])
        assertEquals(0.95f, s.hourlyCloud[14])
        assertEquals(Weather.RAIN, s.hourlyCondition[14])
        assertEquals(Weather.CLEAR, s.hourlyCondition[0])
        // The gap between 01:00 and 14:00 carries the last known cover
        // forward rather than reading as a sudden clearing.
        assertEquals(0.2f, s.hourlyCloud[7])
    }

    @Test
    fun `an answer with no hourly extras leaves the lists empty`() {
        val s = parseForecast(
            """{"current":{"temperature_2m":5.0},"hourly":{"time":["2026-09-11T00:00"],"temperature_2m":[5.0]}}""",
        )!!
        assertTrue(s.hourlyCloud.isEmpty())
        assertTrue(s.hourlyCondition.isEmpty())
        // Which is exactly the case the fallbacks above cover.
        assertEquals(Weather.CLEAR, SkyClock.weatherOf(null))
    }
}
