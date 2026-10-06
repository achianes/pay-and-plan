package com.payandplan.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.payandplan.app.data.Payment
import com.payandplan.app.ui.MainViewModel
import com.payandplan.app.ui.components.ComicCard
import com.payandplan.app.ui.components.ComicChip
import com.payandplan.app.ui.components.ComicIconButton
import com.payandplan.app.ui.components.SpeechBubble
import com.payandplan.app.ui.theme.Coral
import com.payandplan.app.ui.theme.Grape
import com.payandplan.app.ui.theme.Ink
import com.payandplan.app.ui.theme.Mint
import com.payandplan.app.ui.theme.Paper
import com.payandplan.app.ui.theme.Sky
import com.payandplan.app.ui.theme.Tangerine
import com.payandplan.app.ui.theme.Yellow
import com.payandplan.app.util.Format
import java.time.LocalDate
import java.time.YearMonth

/** A month at a time or a whole year at a time. */
private enum class Span { MONTH, YEAR }

/**
 * Where the money went. Every expense carries a category — the big ones from the day they
 * were written, the day's small change from the tap that sorted it — so a month or a year
 * can be broken down into what it was actually spent on, and set against the one before.
 */
@Composable
fun SpendScreen(vm: MainViewModel, onBack: () -> Unit, onOpenPayment: (String) -> Unit) {
    val everything by vm.allPayments.collectAsState()
    val currency = vm.currency()
    val today = LocalDate.now()

    var span by remember { mutableStateOf(Span.MONTH) }
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var year by remember { mutableStateOf(today.year) }
    var opened by remember { mutableStateOf<String?>(null) }

    // only money going out, and only what still counts: a suspended entry is not a spend
    val spend = remember(everything) {
        everything.filter { it.isBill && !it.isSuspended && it.amountCents > 0 }
    }

    val inSpan = remember(spend, span, month, year) {
        spend.filter { inside(it, span, month, year) }
    }
    val before = remember(spend, span, month, year) {
        when (span) {
            Span.MONTH -> spend.filter { inside(it, Span.MONTH, month.minusMonths(1), year) }
            Span.YEAR -> spend.filter { inside(it, Span.YEAR, month, year - 1) }
        }
    }

    val byCategory = remember(inSpan) {
        inSpan.groupBy { it.category.trim().ifBlank { NO_CATEGORY } }
            .map { (name, rows) -> name to rows.sumOf { it.amountCents } }
            .sortedByDescending { it.second }
    }
    val total = byCategory.sumOf { it.second }
    val totalBefore = before.sumOf { it.amountCents }

    LazyColumn(
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ComicIconButton(
                    Icons.Filled.ArrowBack, onBack,
                    color = Yellow, size = 42.dp, contentDescription = "Back"
                )
                Box(Modifier.width(10.dp))
                Text("WHERE IT GOES", style = MaterialTheme.typography.headlineSmall, color = Ink)
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ComicChip("Month", span == Span.MONTH, { span = Span.MONTH }, color = Sky)
                ComicChip("Year", span == Span.YEAR, { span = Span.YEAR }, color = Sky)
            }
        }

        item {
            ComicCard(color = Tangerine, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ComicIconButton(
                        Icons.Filled.ArrowBack,
                        {
                            if (span == Span.MONTH) month = month.minusMonths(1) else year -= 1
                        },
                        color = Paper, size = 36.dp, contentDescription = "Before"
                    )
                    Box(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (span == Span.MONTH) {
                                "${MONTHS[month.monthValue - 1].uppercase()} ${month.year}"
                            } else {
                                year.toString()
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = Ink
                        )
                        Text(
                            Format.money(total, currency),
                            style = MaterialTheme.typography.titleLarge,
                            color = Ink,
                            maxLines = 1
                        )
                        Text(
                            change(total, totalBefore, span),
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink
                        )
                    }
                    ComicIconButton(
                        Icons.Filled.ArrowBack,
                        {
                            if (span == Span.MONTH) month = month.plusMonths(1) else year += 1
                        },
                        color = Paper, size = 36.dp, contentDescription = "After",

                        modifier = Modifier.rotate(180f)
                    )
                }
            }
        }

        if (span == Span.YEAR) {
            item { MonthsBar(spend = spend, year = year, currency = currency) }
        }

        if (byCategory.isEmpty()) {
            item {
                SpeechBubble(
                    "Nothing spent in here yet.",
                    Modifier.fillMaxWidth(), Yellow, "📊"
                )
            }
        }

        items(byCategory) { (name, cents) ->
            val share = if (total > 0) cents.toDouble() / total else 0.0
            val mine = inSpan.filter { it.category.trim().ifBlank { NO_CATEGORY } == name }
            val wasBefore = before
                .filter { it.category.trim().ifBlank { NO_CATEGORY } == name }
                .sumOf { it.amountCents }

            ComicCard(
                color = if (name == NO_CATEGORY) Paper else Grape,
                modifier = Modifier.fillMaxWidth().clickable {
                    opened = if (opened == name) null else name
                }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            name,
                            style = MaterialTheme.typography.titleMedium,
                            color = Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${(share * 100).toInt()}%  ·  ${count(mine.size)}  ·  " +
                                change(cents, wasBefore, span),
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        Format.money(cents, currency),
                        style = MaterialTheme.typography.titleMedium,
                        color = Ink
                    )
                }
                Box(Modifier.height(6.dp))
                Bar(share = share, color = if (name == NO_CATEGORY) Coral else Mint)

                if (opened == name) {
                    for (e in mine.sortedByDescending { it.amountCents }.take(25)) {
                        Box(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenPayment(e.id) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                e.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                Format.money(e.amountCents, currency),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Ink
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Twelve months side by side, so a bad one stands out without reading a single figure. */
@Composable
private fun MonthsBar(spend: List<Payment>, year: Int, currency: String) {
    val months = (1..12).map { m ->
        spend.filter { inside(it, Span.MONTH, YearMonth.of(year, m), year) }
            .sumOf { it.amountCents }
    }
    val most = months.maxOrNull() ?: 0L

    ComicCard(color = Sky, modifier = Modifier.fillMaxWidth()) {
        Text("MONTH BY MONTH", style = MaterialTheme.typography.titleMedium, color = Ink)
        Text(
            "Most in one month: ${Format.money(most, currency)}",
            style = MaterialTheme.typography.bodySmall,
            color = Ink
        )
        Box(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().height(110.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            months.forEachIndexed { index, cents ->
                val share = if (most > 0) cents.toDouble() / most else 0.0
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height((6 + 80 * share).dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (cents > 0) Grape else Paper)
                    )
                    Text(
                        MONTHS[index].take(1),
                        style = MaterialTheme.typography.labelSmall,
                        color = Ink
                    )
                }
            }
        }
    }
}

/** How much of the whole this one is. */
@Composable
private fun Bar(share: Double, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Paper)
    ) {
        Box(
            Modifier
                .fillMaxWidth(share.coerceIn(0.02, 1.0).toFloat())
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(color)
        )
    }
}

/** One of them is not "1 entries". */
private fun count(n: Int): String = if (n == 1) "1 entry" else "$n entries"

private const val NO_CATEGORY = "No category"

private val MONTHS = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December"
)

private fun inside(p: Payment, span: Span, month: YearMonth, year: Int): Boolean {
    val day = LocalDate.ofEpochDay(p.dueDate)
    return when (span) {
        Span.MONTH -> YearMonth.from(day) == month
        Span.YEAR -> day.year == year
    }
}

/** "a fifth more than last month", in as few words as possible. */
private fun change(now: Long, before: Long, span: Span): String {
    val what = if (span == Span.MONTH) "last month" else "last year"
    if (before <= 0) return "no $what to compare"
    val delta = (now - before).toDouble() / before
    val percent = Math.abs(delta * 100).toInt()
    if (percent < 2) return "the same as $what"
    return if (delta > 0) "$percent% more than $what" else "$percent% less than $what"
}
