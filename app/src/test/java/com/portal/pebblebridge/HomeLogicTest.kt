package com.portal.pebblebridge

import com.portal.pebblebridge.home.WeatherClient
import com.portal.pebblebridge.home.WeatherKind
import com.portal.pebblebridge.home.buildMonthGrid
import com.portal.pebblebridge.home.weatherKind
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeLogicTest {

  private fun day(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { clear(); set(y, m, d) }

  @Test
  fun `October 2026 starts on Thursday, Monday-first grid`() {
    val grid = buildMonthGrid(day(2026, Calendar.OCTOBER, 7), weekStartsMonday = true)
    assertEquals(listOf(null, null, null, 1, 2, 3, 4), grid.weeks.first())
    assertEquals(Calendar.MONDAY, grid.columnDays.first())
    assertEquals(7, grid.today)
    assertEquals(31, grid.weeks.flatten().filterNotNull().size)
    assertEquals(5, grid.weeks.size)
  }

  @Test
  fun `Sunday-first grid shifts the lead days`() {
    val grid = buildMonthGrid(day(2026, Calendar.OCTOBER, 7), weekStartsMonday = false)
    assertEquals(listOf(null, null, null, null, 1, 2, 3), grid.weeks.first())
    assertEquals(Calendar.SUNDAY, grid.columnDays.first())
  }

  @Test
  fun `February in a leap year has 29 days and full weeks`() {
    val grid = buildMonthGrid(day(2028, Calendar.FEBRUARY, 1), weekStartsMonday = true)
    assertEquals(29, grid.weeks.flatten().filterNotNull().last())
    grid.weeks.forEach { assertEquals(7, it.size) }
    assertNull(grid.weeks.last().last())
  }

  @Test
  fun `weather codes map to icon families`() {
    assertEquals(WeatherKind.SUN, weatherKind(0, isDay = true))
    assertEquals(WeatherKind.MOON, weatherKind(0, isDay = false))
    assertEquals(WeatherKind.RAIN, weatherKind(63, isDay = true))
    assertEquals(WeatherKind.THUNDER, weatherKind(95, isDay = true))
    assertEquals(WeatherKind.CLOUD, weatherKind(999, isDay = true))
  }

  @Test
  fun `parses Open-Meteo current weather`() {
    val body = """{"current":{"temperature_2m":28.6,"weather_code":61,"is_day":0}}"""
    val w = WeatherClient.parseCurrent(body, fahrenheit = false, place = "Tokyo")
    assertEquals(29, w.temp)
    assertEquals(61, w.code)
    assertEquals(false, w.isDay)
    assertEquals("C", w.unit)
  }

  @Test
  fun `parses geocoding results`() {
    val body = """{"results":[{"name":"Hanoi","country_code":"VN","latitude":21.02,"longitude":105.84}]}"""
    val places = WeatherClient.parseGeocode(body)
    assertEquals("Hanoi, VN", places.single().label)
    assertEquals(21.02, places.single().lat, 0.001)
  }

  @Test
  fun `Muse message carries the transcription hint after the note`() {
    val msg = com.portal.pebblebridge.muse.museMessage("  Buy milk  ")
    assertEquals("Buy milk\n\n" + com.portal.pebblebridge.muse.TRANSCRIPTION_HINT, msg)
  }
}
