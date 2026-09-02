package com.payandplan.app.data

import java.time.LocalDate

/**
 * Occurrences of a series are materialised as real rows so every single one can
 * carry its own status, receipt and attachments. Dates are always derived from
 * the series anchor (never from the previous row) so monthly plans anchored on
 * the 31st do not drift after a short month.
 */
object RecurrenceEngine {

    /** How many future occurrences we keep materialised at any time. */
    const val HORIZON = 24

    fun dateAt(anchor: LocalDate, recurrence: Recurrence, index: Int): LocalDate = when (recurrence) {
        Recurrence.NONE -> anchor
        Recurrence.DAILY -> anchor.plusDays(index.toLong())
        Recurrence.WEEKLY -> anchor.plusWeeks(index.toLong())
        Recurrence.BIWEEKLY -> anchor.plusWeeks(2L * index)
        Recurrence.MONTHLY -> anchor.plusMonths(index.toLong())
        Recurrence.QUARTERLY -> anchor.plusMonths(3L * index)
        Recurrence.SEMIANNUAL -> anchor.plusMonths(6L * index)
        Recurrence.YEARLY -> anchor.plusYears(index.toLong())
    }

    /**
     * Builds the rows for [template] starting at occurrence [fromIndex] (0 = the anchor itself).
     * The template carries the anchor date in [Payment.dueDate].
     */
    fun build(template: Payment, fromIndex: Int, count: Int, anchor: LocalDate): List<Payment> {
        val recurrence = template.recurrenceEnum
        if (recurrence == Recurrence.NONE) {
            return if (fromIndex == 0) listOf(template.copy(id = newId(), dueDate = anchor.toEpochDay())) else emptyList()
        }
        val end = template.recurrenceEndDate?.let { LocalDate.ofEpochDay(it) }
        val out = ArrayList<Payment>(count)
        var i = fromIndex
        while (out.size < count) {
            val date = dateAt(anchor, recurrence, i)
            if (end != null && date.isAfter(end)) break
            out += template.copy(
                id = newId(),
                dueDate = date.toEpochDay(),
                status = PayStatus.PENDING.name,
                paidAt = null,
                paidAmountCents = null,
                snoozedUntil = null
            )
            i++
            if (i > fromIndex + 4000) break
        }
        return out
    }

    /**
     * Builds a fixed length installment plan: one row per entry of [amounts], on the recurrence
     * grid, so every installment can carry its own figure (deposit bigger, last one smaller, ...).
     */
    fun buildInstallments(template: Payment, anchor: LocalDate, amounts: List<Long>): List<Payment> {
        val recurrence = template.recurrenceEnum
        val count = amounts.size
        return amounts.mapIndexed { i, cents ->
            template.copy(
                id = newId(),
                dueDate = dateAt(anchor, recurrence, i).toEpochDay(),
                amountCents = cents,
                installmentIndex = i + 1,
                installmentCount = count,
                status = PayStatus.PENDING.name,
                paidAt = null,
                paidAmountCents = null,
                snoozedUntil = null
            )
        }
    }

    /** index of [date] inside the series anchored at [anchor], or null if it is not on the grid. */
    fun indexOf(anchor: LocalDate, recurrence: Recurrence, date: LocalDate): Int? {
        if (recurrence == Recurrence.NONE) return if (date == anchor) 0 else null
        var i = 0
        while (i < 5000) {
            val d = dateAt(anchor, recurrence, i)
            if (d == date) return i
            if (d.isAfter(date)) return null
            i++
        }
        return null
    }
}
