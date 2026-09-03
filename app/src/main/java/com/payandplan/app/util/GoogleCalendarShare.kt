package com.payandplan.app.util

import java.time.LocalDate

/**
 * Google Calendar does not share an .ics, it shares a few lines of text and a link:
 *
 *     Dentist
 *     giovedì 4 settembre 2026 ⋅ 16:30 – 17:15
 *     Via Verdi 12, Napoli
 *     https://calendar.app.google/AbCdEf
 *
 * or the e-mail style "Title: … / When: … / Where: …". This reads either into a
 * [CalendarEvent] so it can become a reminder with the fields already filled in.
 */
object GoogleCalendarShare {

    private val months = mapOf(
        "gen" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "mag" to 5, "giu" to 6,
        "lug" to 7, "ago" to 8, "set" to 9, "ott" to 10, "nov" to 11, "dic" to 12,
        "jan" to 1, "may" to 5, "jun" to 6, "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "dec" to 12
    )
    private val urlPattern = Regex("""https?://\S+""")
    // the year is optional: Google leaves it out for the current year
    private val dayFirst = Regex("""\b(\d{1,2})\s+([A-Za-zÀ-ÿ]{3,})\.?(?:\s+(\d{4}))?""")
    private val monthFirst = Regex("""\b([A-Za-z]{3,})\.?\s+(\d{1,2})(?:,?\s+(\d{4}))?\b""")
    private val numeric = Regex("""\b(\d{1,2})[/.-](\d{1,2})[/.-](\d{4})\b""")
    private val timePattern = Regex("""\b(\d{1,2})[:.](\d{2})\s*(am|pm|AM|PM)?""")
    private val boilerplate = listOf(
        "invitation from google calendar", "invito da google calendar", "you have been invited",
        "sei stato invitato", "view on google calendar", "visualizza su google calendar", "google calendar"
    )
    private val labelled = Regex("""^(title|titolo|when|quando|where|dove)\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)

    fun looksLikeGoogleCalendar(text: String?): Boolean {
        val t = text.orEmpty().lowercase()
        return t.contains("calendar.google.com") || t.contains("calendar.app.google") ||
            t.contains("google calendar")
    }

    fun parse(text: String): CalendarEvent? {
        val links = urlPattern.findAll(text).map { it.value }.toList()
        val lines = text.replace("\r", "").split('\n')
            .map { urlPattern.replace(it, "").replace('⋅', ' ').replace('·', ' ').trim() }
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) return null

        var title: String? = null
        var whenLine: String? = null
        var where: String? = null
        val rest = mutableListOf<String>()

        for (line in lines) {
            val m = labelled.find(line)
            if (m != null) {
                when (m.groupValues[1].lowercase()) {
                    "title", "titolo" -> title = m.groupValues[2].trim()
                    "when", "quando" -> whenLine = m.groupValues[2].trim()
                    "where", "dove" -> where = m.groupValues[2].trim()
                }
                continue
            }
            if (boilerplate.any { line.lowercase().contains(it) }) continue
            if (whenLine == null && dateOf(line) != null) { whenLine = line; continue }
            rest += line
        }

        val date = whenLine?.let { dateOf(it) }
        if (title == null) title = rest.removeFirstOrNull()
        if (where == null) where = rest.firstOrNull { dateOf(it) == null && timePattern.find(it) == null }
        if (title.isNullOrBlank() && date == null) return null

        val time = whenLine?.let { timePattern.find(it) }
        val minutes = time?.let {
            var h = it.groupValues[1].toInt()
            val mm = it.groupValues[2].toInt()
            val ampm = it.groupValues[3].lowercase()
            if (ampm == "pm" && h < 12) h += 12
            if (ampm == "am" && h == 12) h = 0
            h * 60 + mm
        } ?: (9 * 60)

        return CalendarEvent(
            title = title.orEmpty().ifBlank { "Event" },
            epochDay = (date ?: LocalDate.now()).toEpochDay(),
            minutesOfDay = minutes.coerceIn(0, 24 * 60 - 1),
            location = where.orEmpty(),
            notes = (listOf("Shared from Google Calendar") + links).joinToString("\n")
        )
    }

    /** "4 settembre 2026", "Sep 4, 2026" or "04/09/2026"; null when the line holds no date. */
    private fun dateOf(line: String): LocalDate? {
        for (m in dayFirst.findAll(line)) {
            val month = months[m.groupValues[2].lowercase().take(3)] ?: continue
            return safeDate(yearOrGuess(m.groupValues[3], month, m.groupValues[1].toInt()), month, m.groupValues[1].toInt())
        }
        for (m in monthFirst.findAll(line)) {
            val month = months[m.groupValues[1].lowercase().take(3)] ?: continue
            return safeDate(yearOrGuess(m.groupValues[3], month, m.groupValues[2].toInt()), month, m.groupValues[2].toInt())
        }
        numeric.find(line)?.let { m ->
            return safeDate(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt())
        }
        return null
    }

    private fun safeDate(y: Int, m: Int, d: Int): LocalDate? =
        runCatching { LocalDate.of(y, m, d) }.getOrNull()

    /** No year printed: this year, unless that day is already well behind us. */
    private fun yearOrGuess(printed: String, month: Int, day: Int): Int {
        if (printed.isNotBlank()) return printed.toInt()
        val today = LocalDate.now()
        val thisYear = safeDate(today.year, month, day) ?: return today.year
        return if (thisYear.isBefore(today.minusDays(45))) today.year + 1 else today.year
    }

    /** The share was just a link: nothing to read locally, the server has to follow it. */
    fun linkOnly(text: String): String? {
        val links = urlPattern.findAll(text).map { it.value }.toList()
        val rest = urlPattern.replace(text, "").trim()
        val calendarLink = links.firstOrNull { it.contains("calendar.google.com") || it.contains("calendar.app.google") }
        return if (calendarLink != null && rest.length < 4) calendarLink else null
    }
}
