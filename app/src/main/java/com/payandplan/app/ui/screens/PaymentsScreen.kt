package com.payandplan.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.payandplan.app.data.PayStatus
import com.payandplan.app.data.Payment
import com.payandplan.app.ui.MainViewModel
import com.payandplan.app.ui.components.ComicCard
import com.payandplan.app.ui.components.ComicChip
import com.payandplan.app.ui.components.PaymentRow
import com.payandplan.app.ui.components.PosterTitle
import com.payandplan.app.ui.components.SectionHeader
import com.payandplan.app.ui.components.SpeechBubble
import com.payandplan.app.ui.theme.Aqua
import com.payandplan.app.ui.theme.Coral
import com.payandplan.app.ui.theme.Ink
import com.payandplan.app.ui.theme.Mint
import com.payandplan.app.ui.theme.Paper
import com.payandplan.app.ui.theme.PosterFont
import com.payandplan.app.ui.theme.Sky
import com.payandplan.app.ui.theme.Yellow
import com.payandplan.app.ui.theme.stickerColor
import com.payandplan.app.util.Format
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

private enum class Filter(val label: String) {
    OPEN("To pay"), INCOMING("Incoming"), APPOINTMENTS("Appointments"),
    LATE("Late"), PAID("Paid"), ALL("Everything")
}

@Composable
fun PaymentsScreen(
    vm: MainViewModel,
    onOpenPayment: (String) -> Unit
) {
    val everything by vm.allPayments.collectAsState()
    val currency = vm.currency()
    val today = LocalDate.now()
    var filter by remember { mutableStateOf(Filter.OPEN) }
    // "" means everybody; only one person at a time otherwise
    var ownerFilter by remember { mutableStateOf("") }
    val openMonths = remember { mutableStateListOf<String>() }
    val openBlocks = remember { mutableStateListOf<String>() }

    val all = if (ownerFilter.isBlank()) everything
    else everything.filter { it.ownerUserId == ownerFilter }

    val t = today.toEpochDay()
    val horizon = today.plusDays(30).toEpochDay()
    val byTime = compareBy<Payment>({ it.dueDate }, { it.dueTimeMinutes })
    fun within30(p: Payment) = p.isOpen && p.dueDate in t..horizon
    fun beyond30(p: Payment) = p.isOpen && p.dueDate > horizon

    val bills = all.filter { it.isBill }
    val late = bills.filter { it.isOpen && it.dueDate < t }.sortedWith(byTime)
    val pay30 = bills.filter { within30(it) }.sortedWith(byTime)
    val payLater = bills.filter { beyond30(it) }
    val paid = bills.filter { it.isPaid }

    val appt30 = all.filter { it.isAppointment && within30(it) }.sortedWith(byTime)
    val apptLater = all.filter { it.isAppointment && beyond30(it) }

    val in30 = all.filter { it.isIncome && within30(it) }.sortedWith(byTime)
    val inLater = all.filter { it.isIncome && beyond30(it) }
    val received = all.filter { it.isIncome && it.isPaid }.sortedByDescending { it.dueDate }

    val outTotal30 = pay30.sumOf { it.amountCents }
    val inTotal30 = in30.sumOf { it.amountCents }

    // everything of the next 30 days in one chronological run, whatever its type
    val next30 = (late + pay30 + appt30 + in30).sortedWith(byTime)

    LazyColumn(
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 170.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { PosterTitle("MY BILLS") }

        if (vm.members().size > 1) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ComicChip("Everyone", ownerFilter.isBlank(), { ownerFilter = "" }, color = Yellow)
                        vm.members().take(2).forEach { m ->
                            ComicChip(
                                text = m.name,
                                selected = ownerFilter == m.id,
                                onClick = { ownerFilter = if (ownerFilter == m.id) "" else m.id },
                                color = stickerColor(m.colorIndex)
                            )
                        }
                    }
                    if (vm.members().size > 2) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            vm.members().drop(2).forEach { m ->
                                ComicChip(
                                    text = m.name,
                                    selected = ownerFilter == m.id,
                                    onClick = { ownerFilter = if (ownerFilter == m.id) "" else m.id },
                                    color = stickerColor(m.colorIndex)
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            ComicCard(color = Yellow, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("LATE", late.size.toString(), Format.money(late.sumOf { it.amountCents }, currency))
                    Stat("OUT 30d", Format.money(outTotal30, currency), "${pay30.size} bills")
                    Stat("IN 30d", Format.money(inTotal30, currency), "${in30.size} incomes")
                }
                Box(Modifier.height(6.dp))
                Text(
                    "net ${Format.money(inTotal30 - outTotal30, currency)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = Ink,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Filter.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { f ->
                            ComicChip(
                                text = f.label,
                                selected = filter == f,
                                onClick = { filter = f },
                                color = when (f) {
                                    Filter.LATE -> Coral
                                    Filter.PAID, Filter.INCOMING -> Mint
                                    Filter.APPOINTMENTS -> Aqua
                                    Filter.ALL -> Sky
                                    else -> Yellow
                                }
                            )
                        }
                    }
                }
            }
        }

        when (filter) {
            Filter.OPEN -> {
                // money going out only: income and appointments have their own filters
                if (late.isEmpty() && pay30.isEmpty() && payLater.isEmpty()) {
                    item { SpeechBubble("Nothing to pay. Enjoy!", Modifier.fillMaxWidth(), Mint, "🥳") }
                }
                section("Late", "🔥", late, vm, currency, onOpenPayment)
                fold("pay30", "Payments (30 days)", "🧾", pay30, vm, currency, openBlocks, onOpenPayment)
                monthSection("Later", "🌙", payLater, vm, currency, openMonths, onOpenPayment)
            }
            Filter.APPOINTMENTS -> {
                val been = all.filter { it.isAppointment && it.isPaid }.sortedByDescending { it.dueDate }
                if (appt30.isEmpty() && apptLater.isEmpty() && been.isEmpty()) {
                    item { SpeechBubble("No appointments yet.", Modifier.fillMaxWidth(), Mint, "🗓") }
                }
                fold("appt30open", "Appointments (30 days)", "🗓", appt30, vm, currency, openBlocks, onOpenPayment, false)
                monthSection("Appointments later", "🗓", apptLater, vm, currency, openMonths, onOpenPayment)
                section("Already been", "✅", been.take(20), vm, currency, onOpenPayment)
            }
            Filter.INCOMING -> {
                if (in30.isEmpty() && inLater.isEmpty() && received.isEmpty()) {
                    item { SpeechBubble("No income planned yet.", Modifier.fillMaxWidth(), Mint, "💰") }
                }
                fold("in30open", "Incoming (30 days)", "💰", in30, vm, currency, openBlocks, onOpenPayment)
                monthSection("Income later", "💰", inLater, vm, currency, openMonths, onOpenPayment)
                section("Already received", "✅", received.take(20), vm, currency, onOpenPayment)
            }
            Filter.LATE -> {
                if (late.isEmpty()) {
                    item { SpeechBubble("Nothing late. You rock!", Modifier.fillMaxWidth(), Mint, "💪") }
                }
                section("Late", "🔥", late, vm, currency, onOpenPayment)
            }
            Filter.PAID -> {
                if (paid.isEmpty()) item { SpeechBubble("No receipts yet.", Modifier.fillMaxWidth(), Yellow, "🧾") }
                monthSection(
                    "Paid", "✅", paid.sortedByDescending { it.dueDate },
                    vm, currency, openMonths, onOpenPayment
                )
            }
            Filter.ALL -> {
                if (next30.isEmpty()) {
                    item { SpeechBubble("Nothing in the next 30 days.", Modifier.fillMaxWidth(), Mint, "🥳") }
                }
                section("Next 30 days", "📆", next30, vm, currency, onOpenPayment)
                monthSection(
                    "Further ahead", "🌙", payLater + inLater + apptLater,
                    vm, currency, openMonths, onOpenPayment
                )
            }
        }
    }
}

private fun LazyListScope.section(
    title: String,
    emoji: String,
    list: List<Payment>,
    vm: MainViewModel,
    currency: String,
    onOpenPayment: (String) -> Unit
) {
    if (list.isEmpty()) return
    item(key = "h_$title") { SectionHeader(title, emoji) }
    items(list, key = { "s_${title}_${it.id}" }) { p ->
        PaymentRow(
            payment = p,
            currency = currency,
            onClick = { onOpenPayment(p.id) },
            owner = vm.memberById(p.ownerUserId),
            onQuickPaid = if (p.statusEnum == PayStatus.PENDING && !p.requireReceipt) ({ vm.markPaid(p.id) }) else null
        )
        Box(Modifier.height(8.dp))
    }
}

/** One folded line with count and total; tap to unfold the entries underneath. */
private fun LazyListScope.fold(
    key: String,
    title: String,
    emoji: String,
    list: List<Payment>,
    vm: MainViewModel,
    currency: String,
    openBlocks: MutableList<String>,
    onOpenPayment: (String) -> Unit,
    withTotal: Boolean = true
) {
    if (list.isEmpty()) return
    item(key = "f_$key") {
        val open = openBlocks.contains(key)
        ComicCard(
            color = Paper,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            onClick = { if (open) openBlocks.remove(key) else openBlocks.add(key) }
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${if (open) "▾" else "▸"} $emoji $title",
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                    modifier = Modifier.weight(1f)
                )
                Column(horizontalAlignment = Alignment.End) {
                    if (withTotal) {
                        Text(
                            Format.money(list.sumOf { it.amountCents }, currency),
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = PosterFont),
                            color = Ink
                        )
                    }
                    Text("${list.size}", style = MaterialTheme.typography.bodySmall, color = Ink)
                }
            }
        }
        Box(Modifier.height(8.dp))
    }
    if (openBlocks.contains(key)) {
        items(list, key = { "fi_${key}_${it.id}" }) { p ->
            PaymentRow(
                payment = p,
                currency = currency,
                onClick = { onOpenPayment(p.id) },
                owner = vm.memberById(p.ownerUserId),
                onQuickPaid = if (p.statusEnum == PayStatus.PENDING && !p.requireReceipt) ({ vm.markPaid(p.id) }) else null
            )
            Box(Modifier.height(8.dp))
        }
    }
}

/**
 * Anything far away is read month by month: one folded row per month with its total,
 * instead of a long list of single activities.
 */
private fun LazyListScope.monthSection(
    title: String,
    emoji: String,
    list: List<Payment>,
    vm: MainViewModel,
    currency: String,
    openMonths: MutableList<String>,
    onOpenPayment: (String) -> Unit
) {
    if (list.isEmpty()) return
    item(key = "mh_$title") { SectionHeader(title, emoji) }

    val groups = list
        .groupBy { YearMonth.from(LocalDate.ofEpochDay(it.dueDate)) }
        .toSortedMap()

    groups.forEach { (month, rows) ->
        val key = "$title-$month"
        item(key = "m_$key") {
            val open = openMonths.contains(key)
            ComicCard(
                color = Paper,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                onClick = { if (open) openMonths.remove(key) else openMonths.add(key) }
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (open) "▾ " else "▸ ") +
                            month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH).uppercase() +
                            " " + month.year,
                        style = MaterialTheme.typography.titleMedium,
                        color = Ink,
                        modifier = Modifier.weight(1f)
                    )
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            Format.money(rows.sumOf { it.amountCents }, currency),
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = PosterFont),
                            color = Ink
                        )
                        Text("${rows.size} entries", style = MaterialTheme.typography.bodySmall, color = Ink)
                    }
                }
            }
            Box(Modifier.height(8.dp))
        }

        if (openMonths.contains(key)) {
            items(rows.sortedBy { it.dueDate }, key = { "mi_${key}_${it.id}" }) { p ->
                PaymentRow(
                    payment = p,
                    currency = currency,
                    onClick = { onOpenPayment(p.id) },
                    owner = vm.memberById(p.ownerUserId),
                    onQuickPaid = if (p.statusEnum == PayStatus.PENDING && !p.requireReceipt) ({ vm.markPaid(p.id) }) else null
                )
                Box(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, sub: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Ink)
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFamily = PosterFont), color = Ink)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = Ink)
    }
}
