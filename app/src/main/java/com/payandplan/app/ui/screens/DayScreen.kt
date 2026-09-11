package com.payandplan.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.unit.dp
import com.payandplan.app.ui.MainViewModel
import com.payandplan.app.ui.components.AttachButtons
import com.payandplan.app.ui.components.AttachmentStrip
import com.payandplan.app.ui.components.ComicButton
import com.payandplan.app.ui.components.ComicCard
import com.payandplan.app.ui.components.ComicField
import com.payandplan.app.ui.components.ComicIconButton
import com.payandplan.app.ui.components.PaymentRow
import com.payandplan.app.ui.components.SpeechBubble
import com.payandplan.app.ui.theme.Grape
import com.payandplan.app.ui.theme.Ink
import com.payandplan.app.ui.theme.Mint
import com.payandplan.app.ui.theme.Paper
import com.payandplan.app.ui.theme.Sky
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
            items(payments, key = { it.id }) { p ->
                PaymentRow(
                    payment = p,
                    currency = currency,
                    onClick = { onOpenPayment(p.id) },
                    owner = vm.memberById(p.ownerUserId),
                    onQuickPaid = if (p.isOpen && !p.requireReceipt) ({ vm.markPaid(p.id) }) else null
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
