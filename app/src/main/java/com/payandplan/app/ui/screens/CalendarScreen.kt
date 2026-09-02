package com.payandplan.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.payandplan.app.data.DayStat
import com.payandplan.app.data.Payment
import com.payandplan.app.ui.MainViewModel
import com.payandplan.app.ui.components.ComicButton
import com.payandplan.app.ui.components.ComicCard
import com.payandplan.app.ui.components.ComicIconButton
import com.payandplan.app.ui.components.PaymentRow
import com.payandplan.app.ui.components.PosterTitle
import com.payandplan.app.ui.components.SpeechBubble
import com.payandplan.app.ui.theme.Coral
import com.payandplan.app.ui.theme.Ink
import com.payandplan.app.ui.theme.Mint
import com.payandplan.app.ui.theme.Paper
import com.payandplan.app.ui.theme.PosterFont
import com.payandplan.app.ui.theme.Sky
import com.payandplan.app.ui.theme.Yellow
import com.payandplan.app.ui.theme.stickerColor
import com.payandplan.app.util.Format
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun CalendarScreen(
    vm: MainViewModel,
    onOpenDay: (LocalDate) -> Unit,
    onOpenPayment: (String) -> Unit
) {
    val month by vm.month.collectAsState()
    val selected by vm.selectedDay.collectAsState()
    val stats by vm.dayStats.collectAsState()
    val files by vm.daysWithFiles.collectAsState()
    val notes by vm.daysWithNotes.collectAsState()
    val monthPayments by vm.monthPayments.collectAsState()
    val dayPayments by vm.selectedDayPayments.collectAsState()
    val currency = vm.currency()
    val today = LocalDate.now()

    val weekStart = if (vm.prefs.weekStartsMonday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
    val gridStart = startOfGrid(month, weekStart)

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 170.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { MonthHeader(month, onPrev = { vm.stepMonth(-1) }, onNext = { vm.stepMonth(1) }, onToday = { vm.goToToday() }) }

        item { MonthSummary(month, monthPayments, currency) }

        item {
            ComicCard(color = Paper, contentPadding = PaddingValues(10.dp)) {
                WeekdayHeader(weekStart)
                Box(Modifier.height(6.dp))
                repeat(6) { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        repeat(7) { col ->
                            val date = gridStart.plusDays((row * 7 + col).toLong())
                            DayCell(
                                modifier = Modifier.weight(1f),
                                date = date,
                                inMonth = YearMonth.from(date) == month,
                                isToday = date == today,
                                isSelected = date == selected,
                                stat = stats[date.toEpochDay()],
                                lateBills = monthPayments.count {
                                    it.dueDate == date.toEpochDay() && it.isOpen && it.isBill
                                },
                                hasFiles = files.contains(date.toEpochDay()),
                                hasNote = notes.contains(date.toEpochDay()),
                                onClick = { vm.selectDay(date) },
                                onLongClick = { onOpenDay(date) }
                            )
                        }
                    }
                    Box(Modifier.height(5.dp))
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        Format.fullDay(selected).uppercase(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        Format.relative(selected, today),
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink
                    )
                }
                ComicButton(
                    text = "OPEN DAY",
                    onClick = { onOpenDay(selected) },
                    color = Sky,
                    compact = true
                )
            }
        }

        if (dayPayments.isEmpty()) {
            item {
                SpeechBubble(
                    text = "Nothing to pay here. Enjoy!",
                    modifier = Modifier.fillMaxWidth(),
                    color = Yellow,
                    emoji = "🎉"
                )
            }
        } else {
            items(dayPayments, key = { it.id }) { p ->
                PaymentRow(
                    payment = p,
                    currency = currency,
                    onClick = { onOpenPayment(p.id) },
                    owner = vm.memberById(p.ownerUserId),
                    onQuickPaid = if (p.isOpen && !p.requireReceipt) ({ vm.markPaid(p.id) }) else null,
                    today = today
                )
            }
        }
    }
}

private fun startOfGrid(month: YearMonth, weekStart: DayOfWeek): LocalDate {
    val first = month.atDay(1)
    val diff = (first.dayOfWeek.value - weekStart.value + 7) % 7
    return first.minusDays(diff.toLong())
}

@Composable
private fun MonthHeader(
    month: YearMonth,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PosterTitle("PAY & PLAN", Modifier.weight(1f))
            ComicButton(text = "TODAY", onClick = onToday, color = Yellow, compact = true)
        }
        Box(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ComicIconButton(Icons.Filled.ChevronLeft, onPrev, color = Coral, size = 40.dp, contentDescription = "Previous month")
            Box(Modifier.width(10.dp))
            Text(
                Format.monthTitle(month.atDay(1)),
                style = MaterialTheme.typography.headlineMedium,
                color = Ink,
                modifier = Modifier.weight(1f)
            )
            ComicIconButton(Icons.Filled.ChevronRight, onNext, color = Mint, size = 40.dp, contentDescription = "Next month")
        }
    }
}

@Composable
private fun MonthSummary(month: YearMonth, payments: List<Payment>, currency: String) {
    val inMonth = payments.filter { YearMonth.from(LocalDate.ofEpochDay(it.dueDate)) == month }
    val bills = inMonth.filter { it.isBill }
    val appointments = inMonth.filter { it.isAppointment }
    val incomes = inMonth.filter { it.isIncome }
    val open = bills.filter { it.isOpen }
    val paid = bills.filter { it.isPaid }
    val incomeTotal = incomes.sumOf { it.amountCents }
    val outTotal = bills.sumOf { it.amountCents }
    ComicCard(color = Sky, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SummaryBit("TO PAY", Format.money(open.sumOf { it.amountCents }, currency), "${open.size} left")
            SummaryBit("PAID", Format.money(paid.sumOf { it.paidAmountCents ?: it.amountCents }, currency), "${paid.size} done")
            SummaryBit("IN", Format.money(incomeTotal, currency), "${incomes.size} incomes")
        }
        Text(
            "balance ${Format.money(incomeTotal - outTotal, currency)}",
            style = MaterialTheme.typography.titleSmall,
            color = Ink,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (appointments.isNotEmpty()) {
            Text(
                "🗓 ${appointments.size} appointment${if (appointments.size > 1) "s" else ""} this month",
                style = MaterialTheme.typography.bodySmall,
                color = Ink,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun SummaryBit(label: String, value: String, sub: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Ink)
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFamily = PosterFont), color = Ink)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = Ink)
    }
}

@Composable
private fun WeekdayHeader(weekStart: DayOfWeek) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(7) { i ->
            val day = weekStart.plus(i.toLong())
            Text(
                day.name.take(3),
                style = MaterialTheme.typography.labelSmall,
                color = Ink,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DayCell(
    modifier: Modifier,
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    stat: DayStat?,
    lateBills: Int,
    hasFiles: Boolean,
    hasNote: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val today = LocalDate.now()
    // only unpaid bills paint the day red: a missed appointment is not a debt
    val overdue = lateBills > 0 && date.isBefore(today)
    val bg = when {
        isSelected -> Yellow
        overdue -> Coral
        stat != null && stat.openCount == 0 && stat.paidCount > 0 -> Mint
        !inMonth -> Paper.copy(alpha = 0.45f)
        else -> Paper
    }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .aspectRatio(0.82f)
            .background(bg, shape)
            .border(if (isToday) 3.5.dp else 2.dp, Ink, shape)
            .combinedClickableCompat(onClick = onClick, onLongClick = onLongClick)
            .padding(3.dp)
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = PosterFont),
                color = if (inMonth) Ink else Ink.copy(alpha = 0.4f)
            )
            if (stat != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    val dots = (stat.openCount + stat.paidCount).coerceAtMost(3)
                    repeat(dots) { i ->
                        Box(
                            Modifier
                                .size(6.dp)
                                .background(stickerColor(stat.colorIndex + i), CircleShape)
                                .border(1.dp, Ink, CircleShape)
                        )
                    }
                }
            }
        }
        if (hasFiles) {
            Text("📎", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomStart))
        }
        if (hasNote) {
            Text("📝", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomEnd))
        }
        if (isToday) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(7.dp)
                    .background(Coral, CircleShape)
                    .border(1.dp, Ink, CircleShape)
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)
