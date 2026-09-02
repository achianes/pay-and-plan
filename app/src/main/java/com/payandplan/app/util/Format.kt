package com.payandplan.app.util

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

object Format {

    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val fullFmt = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH)

    fun money(cents: Long, currency: String): String {
        val sign = if (cents < 0) "-" else ""
        val abs = abs(cents)
        val symbol = when (currency.uppercase()) {
            "EUR" -> "\u20AC"
            "USD" -> "$"
            "GBP" -> "\u00A3"
            "CHF" -> "CHF "
            else -> "$currency "
        }
        return "$sign$symbol${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
    }

    fun parseAmountToCents(input: String): Long? {
        val cleaned = input.trim().replace(",", ".").replace(" ", "")
        if (cleaned.isEmpty()) return null
        val value = cleaned.toDoubleOrNull() ?: return null
        return Math.round(value * 100.0)
    }

    fun centsToInput(cents: Long): String =
        "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"

    fun day(date: LocalDate): String = date.format(dayFmt)

    fun fullDay(date: LocalDate): String = date.format(fullFmt)

    fun time(minutes: Int): String {
        val h = (minutes / 60).coerceIn(0, 23)
        val m = (minutes % 60).coerceIn(0, 59)
        return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
    }

    fun monthTitle(date: LocalDate): String =
        "${date.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH).uppercase()} ${date.year}"

    fun relative(date: LocalDate, today: LocalDate): String {
        val diff = date.toEpochDay() - today.toEpochDay()
        return when {
            diff == 0L -> "Today"
            diff == 1L -> "Tomorrow"
            diff == -1L -> "Yesterday"
            diff < 0 -> "${-diff} days late"
            diff < 7 -> "In $diff days"
            else -> day(date)
        }
    }

    fun dateTimeMillis(epochDay: Long, minutes: Int): Long =
        LocalDateTime.of(LocalDate.ofEpochDay(epochDay), java.time.LocalTime.of(minutes / 60, minutes % 60))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun today(): LocalDate = LocalDate.now()
}
