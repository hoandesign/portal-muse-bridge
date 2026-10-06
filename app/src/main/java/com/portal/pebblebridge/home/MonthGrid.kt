package com.portal.pebblebridge.home

import java.util.Calendar

/** One month laid out as weeks of 7 cells; `null` cells are days outside the month. */
data class MonthGrid(
  val year: Int,
  /** 0-based, like [Calendar.MONTH]. */
  val month: Int,
  val today: Int?,
  val weeks: List<List<Int?>>,
  /** Calendar.DAY_OF_WEEK values in column order. */
  val columnDays: List<Int>,
)

private val MONTH_NAMES = listOf(
  "JANUARY", "FEBRUARY", "MARCH", "APRIL", "MAY", "JUNE",
  "JULY", "AUGUST", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER",
)

fun monthName(month: Int): String = MONTH_NAMES[month]

/** Two-letter weekday header for a Calendar.DAY_OF_WEEK value. */
fun weekdayShort(day: Int): String = when (day) {
  Calendar.MONDAY -> "MO"
  Calendar.TUESDAY -> "TU"
  Calendar.WEDNESDAY -> "WE"
  Calendar.THURSDAY -> "TH"
  Calendar.FRIDAY -> "FR"
  Calendar.SATURDAY -> "SA"
  else -> "SU"
}

fun buildMonthGrid(now: Calendar, weekStartsMonday: Boolean): MonthGrid {
  val year = now.get(Calendar.YEAR)
  val month = now.get(Calendar.MONTH)
  val first = (now.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
  val daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH)
  val firstColumnDay = if (weekStartsMonday) Calendar.MONDAY else Calendar.SUNDAY
  val columnDays = (0 until 7).map { ((firstColumnDay - 1 + it) % 7) + 1 }
  val lead = (first.get(Calendar.DAY_OF_WEEK) - firstColumnDay + 7) % 7

  val cells = MutableList<Int?>(lead) { null }
  for (d in 1..daysInMonth) cells.add(d)
  while (cells.size % 7 != 0) cells.add(null)

  return MonthGrid(
    year = year,
    month = month,
    today = now.get(Calendar.DAY_OF_MONTH),
    weeks = cells.chunked(7),
    columnDays = columnDays,
  )
}
