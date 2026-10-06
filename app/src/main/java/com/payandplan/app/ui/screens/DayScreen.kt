package com.payandplan.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import com.payandplan.app.data.Payment
import com.payandplan.app.data.SMALL_GROUP
import com.payandplan.app.data.SMALL_PACKED
import com.payandplan.app.ui.MainViewModel
import com.payandplan.app.ui.components.AttachButtons
import com.payandplan.app.ui.components.AttachmentStrip
import com.payandplan.app.ui.components.ComicButton
import com.payandplan.app.ui.components.ComicCard
import com.payandplan.app.ui.components.ComicChip
import com.payandplan.app.ui.components.ComicField
import com.payandplan.app.ui.components.ComicIconButton
import com.payandplan.app.ui.components.PaymentRow
import com.payandplan.app.ui.components.SpeechBubble
import com.payandplan.app.ui.theme.Grape
import com.payandplan.app.ui.theme.Ink
import com.payandplan.app.ui.theme.Mint
import com.payandplan.app.ui.theme.Paper
import com.payandplan.app.ui.theme.Sky
import com.payandplan.app.ui.theme.Tangerine
import com.payandplan.app.ui.theme.Yellow
import com.payandplan.app.util.Format
import java.time.LocalDate

@Composable
fun DayScreen(
    vm: MainViewModel,
    day: LocalDate,
    onBack: () -> Unit,
    onOpenPayment: (String) -> Unit,
    onAddPayment: (LocalDate) -> Unit
) {
    val payments by vm.dayPayments(day).collectAsState(initial = emptyList())
    val attachments by vm.dayAttachments(day).collectAsState(initial = emptyList())
    val note by vm.dayNote(day).collectAsState(initial = null)
    val categories by vm.entryCategories.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val currency = vm.currency()

    var noteText by remember(note?.epochDay, note?.updatedAt) { mutableStateOf(note?.text ?: "") }

    LazyColumn(
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ComicIconButton(Icons.Filled.ArrowBack, onBack, color = Yellow, size = 42.dp, contentDescription = "Back")
                Box(Modifier.fillMaxWidth(0.03f))
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    Text(
                        Format.fullDay(day).uppercase(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Ink
                    )
                    Text(
                        Format.relative(day, LocalDate.now()),
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink
                    )
                }
            }
        }

        item {
            ComicCard(color = Sky, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                        Text("DAY TOTAL", style = MaterialTheme.typography.labelSmall, color = Ink)
                        Text(
                            Format.money(payments.filter { !it.isSuspended }.sumOf { it.amountCents }, currency),
                            style = MaterialTheme.typography.headlineMedium,
                            color = Ink
                        )
                    }
                    ComicButton(
                        text = "ADD BILL",
                        icon = Icons.Filled.Add,
                        onClick = { onAddPayment(day) },
                        color = Mint,
                        compact = true
                    )
                }
            }
        }

        if (payments.isEmpty()) {
            item { SpeechBubble("No bills on this day.", Modifier.fillMaxWidth(), Yellow, "😌") }
        } else {
            // the day's small change is one line until you ask to see what is inside it
            val under = vm.smallExpenseCents()
            val small = payments.filter { it.isSmallChange(under) }
            val packed = payments.filter { it.groupKey == SMALL_PACKED }
            val plain = payments.filter { !it.isSmallChange(under) && it.groupKey != SMALL_PACKED }
            items(plain, key = { it.id }) { p ->
                PaymentRow(
                    payment = p,
                    currency = currency,
                    onClick = { onOpenPayment(p.id) },
                    owner = vm.memberById(p.ownerUserId),
                    onQuickPaid = if (p.isOpen && !p.requireReceipt) ({ vm.markPaid(p.id) }) else null
                )
                Box(Modifier.height(6.dp))
            }
            if (small.isNotEmpty()) {
                item {
                    SmallChangeCard(
                        entries = small,
                        currency = currency,
                        categories = categories,
                        onCategory = { id, c -> vm.setCategory(id, c) },
                        onOpen = onOpenPayment,
                        onMerge = if (small.size > 1) ({
                            vm.mergeSmallChange(day) { made ->
                                android.widget.Toast.makeText(
                                    context,
                                    if (made > 0) "Put back together" else "Nothing to put together",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }) else null
                    )
                    Box(Modifier.height(6.dp))
                }
            }

            items(packed, key = { it.id }) { p ->
                PackedCard(
                    entry = p,
                    currency = currency,
                    onOpen = { onOpenPayment(p.id) },
                    onTakeApart = {
                        vm.unpackSmallGroups(p.id) { done ->
                            android.widget.Toast.makeText(
                                context,
                                if (done > 0) "Opened up again" else "Could not open it up",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
                Box(Modifier.height(6.dp))
            }
        }

        item {
            ComicCard(color = Paper, modifier = Modifier.fillMaxWidth()) {
                Text("📎 DAY FILES", style = MaterialTheme.typography.headlineSmall, color = Ink)
                Text(
                    "Anything you want pinned to this date: invoices, quotes, photos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink
                )
                Box(Modifier.height(10.dp))
                AttachmentStrip(
                    attachments = attachments,
                    sourceOf = { vm.attachmentSource(it) },
                    onDelete = { vm.removeAttachment(it) },
                    emptyText = "No files pinned to this day yet."
                )
                Box(Modifier.height(10.dp))
                AttachButtons(
                    onPicked = { uri -> vm.attachToDay(day, uri) },
                    onCaptured = { file -> vm.attachCameraShot(null, day, file, false) },
                    color = Grape
                )
            }
        }

        item {
            ComicCard(color = Yellow, modifier = Modifier.fillMaxWidth()) {
                Text("📝 DAY NOTE", style = MaterialTheme.typography.headlineSmall, color = Ink)
                Box(Modifier.height(8.dp))
                ComicField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = "Write something",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 3
                )
                Box(Modifier.height(10.dp))
                ComicButton(
                    text = "SAVE NOTE",
                    onClick = { vm.saveDayNote(day, noteText) },
                    color = Mint,
                    compact = true
                )
            }
        }
    }
}


/**
 * The day's small change: one line for the lot, and underneath, when you open it, every
 * single one with the shop it went to and a category you can tap on. The categories are
 * what the dashboard adds up, so the ones with none are shown as waiting.
 */
@Composable
private fun SmallChangeCard(
    entries: List<Payment>,
    currency: String,
    categories: List<String>,
    onCategory: (String, String) -> Unit,
    onOpen: (String) -> Unit,
    onMerge: (() -> Unit)? = null
) {
    var open by remember { mutableStateOf(false) }
    var tagging by remember { mutableStateOf<Payment?>(null) }
    val total = entries.filter { !it.isSuspended }.sumOf { it.amountCents }
    val loose = entries.count { it.category.isBlank() }

    ComicCard(color = Tangerine, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(
                    "🧾 SMALL CHANGE (${entries.size})",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Ink
                )
                Text(
                    if (loose > 0) "$loose still without a category" else "all sorted",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink
                )
            }
            Text(
                Format.money(total, currency),
                style = MaterialTheme.typography.headlineSmall,
                color = Ink
            )
        }
        // in plain sight, open or shut: nobody looks for a button inside something closed
        if (onMerge != null) {
            Box(Modifier.height(10.dp))
            ComicButton("\uD83D\uDCE6 PUT BACK TOGETHER", onMerge, color = Paper, compact = true)
            Text(
                "One entry for each category, so the sorting stays.",
                style = MaterialTheme.typography.bodySmall,
                color = Ink
            )
        }
        if (open) {
            for (e in entries) {
                Box(Modifier.height(8.dp))
                ComicCard(color = Paper, modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.layout.Column(
                            Modifier.weight(1f).clickable { onOpen(e.id) }
                        ) {
                            Text(e.title, style = MaterialTheme.typography.titleMedium, color = Ink)
                            Text(
                                Format.time(e.dueTimeMinutes),
                                style = MaterialTheme.typography.bodySmall,
                                color = Ink
                            )
                        }
                        ComicChip(
                            text = e.category.ifBlank { "+ category" },
                            selected = e.category.isNotBlank(),
                            onClick = { tagging = e },
                            color = if (e.category.isBlank()) Paper else Mint
                        )
                        Box(Modifier.width(8.dp))
                        Text(
                            Format.money(e.amountCents, currency),
                            style = MaterialTheme.typography.titleMedium,
                            color = Ink
                        )
                    }
                }
            }
        }
    }

    tagging?.let { e ->
        CategoryDialog(
            current = e.category,
            categories = categories,
            onPick = { c -> onCategory(e.id, c); tagging = null },
            onDismiss = { tagging = null }
        )
    }
}

/** What this one was for. The categories already used are offered; anything else is typed. */
@Composable
private fun CategoryDialog(
    current: String,
    categories: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var typed by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        title = { Text("WHAT WAS IT FOR?", style = MaterialTheme.typography.titleMedium) },
        text = {
            androidx.compose.foundation.layout.Column {
                ComicField(typed, { typed = it }, "Category", Modifier.fillMaxWidth())
                Box(Modifier.height(10.dp))
                (DEFAULT_SPEND_CATEGORIES + categories).distinct().chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { c ->
                            ComicChip(
                                text = c,
                                selected = typed.equals(c, ignoreCase = true),
                                onClick = { typed = c },
                                color = Sky
                            )
                        }
                    }
                    Box(Modifier.height(6.dp))
                }
            }
        },
        confirmButton = { ComicButton("SAVE", { onPick(typed.trim()) }, color = Mint, compact = true) },
        dismissButton = { ComicButton("Cancel", onDismiss, color = Paper, compact = true) }
    )
}

/** A starting point for somebody who has never tagged anything. */
val DEFAULT_SPEND_CATEGORIES = listOf(
    "Food", "Transport", "Home", "Health", "School", "Fun", "Clothes", "Bills"
)

/**
 * A day's small change that was put back together. It reads as one entry, and opens up into
 * the single shops again whenever the sorting has to be done differently.
 */
@Composable
private fun PackedCard(
    entry: Payment,
    currency: String,
    onOpen: () -> Unit,
    onTakeApart: () -> Unit
) {
    val lines = entry.notes.lines().filter { it.isNotBlank() }
    ComicCard(color = Tangerine, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { onOpen() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(
                    "\uD83D\uDCE6 " + entry.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Ink
                )
                Text(
                    "${lines.size} small purchases put together",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink
                )
            }
            Text(
                Format.money(entry.amountCents, currency),
                style = MaterialTheme.typography.headlineSmall,
                color = Ink
            )
        }
        Box(Modifier.height(8.dp))
        ComicButton("OPEN IT UP AGAIN", onTakeApart, color = Paper, compact = true)
    }
}
