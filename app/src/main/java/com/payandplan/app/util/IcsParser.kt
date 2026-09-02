package com.payandplan.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The bits of a calendar event this app cares about. */
data class CalendarEvent(
    val title: String,
    val epochDay: Long,
    val minutesOfDay: Int,
    val location: String,
    val notes: String
)

/**
 * A deliberately small iCalendar reader: enough to turn an event shared from a calendar
 * app into a reminder. Only the first VEVENT is read, and anything unparseable is skipped
 * rather than guessed at.
 */
object IcsParser {

    fun looksLikeCalendar(text: String?, mime: String?): Boolean {
        if (mime != null && (mime.startsWith("text/calendar") || mime.contains("ics"))) return true
        val t = text.orEmpty()
        return t.contains("BEGIN:VEVENT", ignoreCase = true) ||
            t.contains("BEGIN:VCALENDAR", ignoreCase = true)
    }

    fun parse(raw: String): CalendarEvent? {
        val body = unfold(raw)
        val start = body.indexOf("BEGIN:VEVENT", ignoreCase = true)
        val lines = if (start >= 0) {
            val end = body.indexOf("END:VEVENT", start, ignoreCase = true)
            body.substring(start, if (end > start) end else body.length)
        } else body

        val summary = value(lines, "SUMMARY")
        val dtStart = value(lines, "DTSTART")
        if (summary.isNullOrBlank() && dtStart.isNullOrBlank()) return null

        val moment = dtStart?.let { parseMoment(it, property(lines, "DTSTART").orEmpty()) }
            ?: LocalDateTime.now()

        return CalendarEvent(
            title = summary?.let { unescape(it) }.orEmpty().ifBlank { "Event" },
            epochDay = moment.toLocalDate().toEpochDay(),
            minutesOfDay = moment.hour * 60 + moment.minute,
            location = value(lines, "LOCATION")?.let { unescape(it) }.orEmpty(),
            notes = value(lines, "DESCRIPTION")?.let { unescape(it) }.orEmpty()
        )
    }

    /** RFC 5545 folds long lines by starting the continuation with a space or tab. */
    private fun unfold(raw: String): String =
        raw.replace("\r\n", "\n").replace("\r", "\n").replace("\n ", "").replace("\n\t", "")

    /** The whole "NAME;PARAM=x:value" line, so parameters such as VALUE=DATE stay readable. */
    private fun property(body: String, name: String): String? =
        body.lineSequence().firstOrNull {
            it.startsWith("$name:", true) || it.startsWith("$name;", true)
        }

    private fun value(body: String, name: String): String? =
        property(body, name)?.substringAfter(':', "")?.trim()?.takeIf { it.isNotBlank() }

    private fun parseMoment(value: String, fullLine: String): LocalDateTime? = runCatching {
        val clean = value.trim()
        when {
            // 20260910T143000Z - UTC, bring it back to the phone's own time
            clean.endsWith("Z") ->
                LocalDateTime.ofInstant(
                    Instant.from(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                        .withZone(ZoneId.of("UTC")).parse(clean)),
                    ZoneId.systemDefault()
                )
            // 20260910T143000 - already local (possibly with a TZID we take at face value)
            clean.contains('T') ->
                LocalDateTime.parse(clean, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
            // 20260910 - a whole day event
            clean.length == 8 || fullLine.contains("VALUE=DATE", true) ->
                LocalDate.parse(clean.take(8), DateTimeFormatter.BASIC_ISO_DATE).atTime(9, 0)
            else -> null
        }
    }.getOrNull()

    private fun unescape(value: String): String = value
        .replace("\\n", "\n")
        .replace("\\,", ",")
        .replace("\\;", ";")
        .replace("\\\\", "\\")
        .trim()
}
