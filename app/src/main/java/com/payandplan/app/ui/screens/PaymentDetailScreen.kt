package com.payandplan.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.payandplan.app.data.Recurrence
import com.payandplan.app.ui.MainViewModel
import com.payandplan.app.ui.components.AttachButtons
import com.payandplan.app.ui.components.AttachmentStrip
import com.payandplan.app.ui.components.ComicButton
import com.payandplan.app.ui.components.ComicCard
import com.payandplan.app.ui.components.ComicIconButton
import com.payandplan.app.ui.components.SpeechBubble
import com.payandplan.app.ui.components.StatusStamp
import com.payandplan.app.ui.theme.Aqua
import com.payandplan.app.ui.theme.Coral
import com.payandplan.app.ui.theme.Grape
import com.payandplan.app.ui.theme.Ink
import com.payandplan.app.ui.theme.Mint
import com.payandplan.app.ui.theme.Paper
import com.payandplan.app.ui.theme.PosterFont
import com.payandplan.app.ui.theme.Sky
import com.payandplan.app.ui.theme.Tangerine
import com.payandplan.app.ui.theme.Yellow
import com.payandplan.app.ui.theme.stickerColor
import com.payandplan.app.util.Format
import java.time.LocalDate

@Composable
fun PaymentDetailScreen(
    vm: MainViewModel,
    paymentId: String,
    askReceipt: Boolean,
    onBack: () -> Unit,
    onEdit: (String) -> Unit
) {
    val payment by vm.payment(paymentId).collectAsState(initial = null)
    val attachments by vm.paymentAttachments(paymentId).collectAsState(initial = emptyList())
    val currency = vm.currency()
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val p = payment
    if (p == null) {
        Box(Modifier.fillMaxWidth().padding(20.dp)) {
            SpeechBubble("This entry is gone.", Modifier.fillMaxWidth(), Yellow, "🕳️")
        }
        return
    }

    val appointment = p.isAppointment
    val income = p.isIncome
    val receipts = attachments.filter { it.isReceipt }
    val others = attachments.filter { !it.isReceipt }
    val date = LocalDate.ofEpochDay(p.dueDate)
    val today = LocalDate.now()
    val late = p.isOpen && date.isBefore(today)
    val canClose = !p.requireReceipt || receipts.isNotEmpty()
    val owner = vm.memberById(p.ownerUserId)

    LazyColumn(
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ComicIconButton(Icons.Filled.ArrowBack, onBack, color = Yellow, size = 42.dp, contentDescription = "Back")
                Box(Modifier.weight(1f))
                ComicIconButton(Icons.Filled.Edit, { onEdit(p.id) }, color = Sky, size = 42.dp, contentDescription = "Edit")
                Box(Modifier.size(8.dp))
                ComicIconButton(Icons.Filled.Delete, { confirmDelete = true }, color = Coral, size = 42.dp, contentDescription = "Delete")
            }
        }

        item {
            ComicCard(
                color = when {
                    appointment -> Aqua
                    income -> Mint
                    else -> stickerColor(p.colorIndex)
                },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp)
            ) {
                Text(p.title, style = MaterialTheme.typography.headlineMedium, color = Ink)
                if (p.category.isNotBlank()) {
                    Text(p.category, style = MaterialTheme.typography.bodySmall, color = Ink)
                }
                Box(Modifier.height(8.dp))
                Text(
                    if (appointment && p.amountCents == 0L) Format.time(p.dueTimeMinutes)
                    else (if (income) "+" else "") +
                        Format.money(p.amountCents, p.currency.ifBlank { currency }),
                    style = MaterialTheme.typography.displayMedium.copy(fontFamily = PosterFont),
                    color = Ink
                )
                Box(Modifier.height(6.dp))
                Text(
                    "${Format.fullDay(date)}  •  ${Format.time(p.dueTimeMinutes)}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Ink
                )
                if (appointment && p.location.isNotBlank()) {
                    Text("📍 ${p.location}", style = MaterialTheme.typography.bodyLarge, color = Ink)
                }
                Text(
                    buildString {
                        if (p.isInstallment) append("${p.installmentLabel}  •  ")
                        append(
                            if (p.recurrenceEnum == Recurrence.NONE) "One time only"
                            else "Repeats: ${p.recurrenceEnum.label}"
                        )
                        owner?.let { append("  •  ${it.name}") }
                        if (p.isPrivate) append("  •  🔒 private")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink
                )
                Box(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        p.isPaid -> StatusStamp(
                            if (appointment) "DONE" else if (income) "RECEIVED" else "PAID", Mint
                        )
                        p.isSkipped -> StatusStamp("SKIPPED", Paper)
                        late -> StatusStamp(Format.relative(date, today), Coral)
                        else -> StatusStamp("WAITING", Yellow)
                    }
                }
                if (p.notes.isNotBlank()) {
                    Box(Modifier.height(10.dp))
                    Text(p.notes, style = MaterialTheme.typography.bodyMedium, color = Ink)
                }
            }
        }

        item {
            ComicCard(color = if (canClose) Mint else Paper, modifier = Modifier.fillMaxWidth()) {
                if (p.isPaid) {
                    Text(
                        when {
                            appointment -> "🎉 BEEN THERE"
                            income -> "🎉 MONEY IN"
                            else -> "🎉 ALL DONE"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = Ink
                    )
                    p.paidAt?.let {
                        Text(
                            "Closed on " + java.text.SimpleDateFormat("d MMM yyyy HH:mm", java.util.Locale.ENGLISH)
                                .format(java.util.Date(it)),
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink
                        )
                    }
                    vm.memberById(p.paidByUserId)?.let {
                        Text("by ${it.name}", style = MaterialTheme.typography.bodySmall, color = Ink)
                    }
                    Box(Modifier.height(10.dp))
                    ComicButton("REOPEN", { vm.markUnpaid(p.id) }, color = Yellow, compact = true)
                } else {
                    Text(
                        when {
                            !canClose -> "Receipt required first"
                            appointment -> "Been there?"
                            income -> "Money arrived?"
                            else -> "Ready to close this one?"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = Ink
                    )
                    Text(
                        if (canClose) "The alarm stops as soon as you tick it off."
                        else "Attach the receipt below, then the button wakes up.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink
                    )
                    Box(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ComicButton(
                            text = when {
                                appointment -> "MARK DONE ✓"
                                income -> "MARK RECEIVED ✓"
                                else -> "MARK PAID ✓"
                            },
                            onClick = { vm.markPaid(p.id) },
                            color = Mint,
                            enabled = canClose
                        )
                        ComicButton("SKIP", { vm.skip(p.id) }, color = Paper, compact = true)
                    }
                    Box(Modifier.height(10.dp))
                    Text("Snooze the alarm", style = MaterialTheme.typography.labelMedium, color = Ink)
                    Box(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ComicButton("1h", { vm.snooze(p.id, 60) }, color = Tangerine, compact = true)
                        ComicButton("3h", { vm.snooze(p.id, 180) }, color = Tangerine, compact = true)
                        ComicButton("Tomorrow", { vm.snooze(p.id, 60 * 24) }, color = Tangerine, compact = true)
                    }
                }
            }
        }

        if (p.recurrenceEnum != Recurrence.NONE) {
            item {
                ComicCard(color = Sky, modifier = Modifier.fillMaxWidth()) {
                    Text("🔁 THE SERIES", style = MaterialTheme.typography.headlineSmall, color = Ink)
                    Text(
                        buildString {
                            append(p.recurrenceEnum.label)
                            append(
                                p.recurrenceEndDate?.let {
                                    " · until ${Format.day(LocalDate.ofEpochDay(it))}"
                                } ?: " · no end date"
                            )
                            append(" · ${vm.seriesSize(p.seriesId)} entries")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink
                    )
                    Box(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ComicButton("EXTEND UNTIL...", {
                            val start = p.recurrenceEndDate?.let { LocalDate.ofEpochDay(it) }
                                ?: LocalDate.ofEpochDay(p.dueDate).plusYears(1)
                            android.app.DatePickerDialog(
                                context,
                                { _, y, m, d ->
                                    vm.extendSeriesTo(p, LocalDate.of(y, m + 1, d).toEpochDay())
                                },
                                start.year, start.monthValue - 1, start.dayOfMonth
                            ).show()
                        }, color = Paper, compact = true)
                        ComicButton("STOP AFTER THIS", { confirmStop = true }, color = Coral, compact = true)
                    }
                }
            }
        }

        item {
            ComicCard(
                color = if (askReceipt && receipts.isEmpty()) Coral else Paper,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🧾 RECEIPT", style = MaterialTheme.typography.headlineSmall, color = Ink)
                Text(
                    "Proof of payment. Photo or PDF, both fine.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink
                )
                Box(Modifier.height(10.dp))
                AttachmentStrip(
                    attachments = receipts,
                    sourceOf = { vm.attachmentSource(it) },
                    onDelete = { vm.removeAttachment(it) },
                    emptyText = "No receipt attached yet."
                )
                Box(Modifier.height(10.dp))
                AttachButtons(
                    onPicked = { uri -> vm.attachToPayment(p.id, uri, true) },
                    onCaptured = { file -> vm.attachCameraShot(p.id, null, file, true) },
                    fileLabel = "Receipt file",
                    cameraLabel = "Photo receipt",
                    color = Aqua
                )
            }
        }

        item {
            ComicCard(color = Paper, modifier = Modifier.fillMaxWidth()) {
                Text("📎 OTHER FILES", style = MaterialTheme.typography.headlineSmall, color = Ink)
                Box(Modifier.height(10.dp))
                AttachmentStrip(
                    attachments = others,
                    sourceOf = { vm.attachmentSource(it) },
                    onDelete = { vm.removeAttachment(it) },
                    emptyText = "Invoices, contracts, anything."
                )
                Box(Modifier.height(10.dp))
                AttachButtons(
                    onPicked = { uri -> vm.attachToPayment(p.id, uri, false) },
                    onCaptured = { file -> vm.attachCameraShot(p.id, null, file, false) },
                    color = Grape
                )
            }
        }

        item {
            ComicCard(color = Paper, modifier = Modifier.fillMaxWidth()) {
                Text("⏰ ALARM", style = MaterialTheme.typography.headlineSmall, color = Ink)
                Text(
                    if (!p.alarmEnabled) "Alarm is off for this one."
                    else buildString {
                        append("Rings at ${Format.time(p.dueTimeMinutes)}")
                        if (p.remindDaysBefore > 0) append(", ${p.remindDaysBefore} day(s) early")
                        append(". ")
                        append(
                            if (p.nagMinutes > 0) "Repeats every ${p.nagMinutes} min until closed."
                            else "Rings once."
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink
                )
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            containerColor = Paper,
            title = { Text("Stop repeating?", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "The series ends on ${Format.day(date)}. Later entries that are still open " +
                        "are removed; everything up to that date stays as it is.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                ComicButton("Stop here", {
                    confirmStop = false
                    vm.stopSeriesAt(p)
                }, color = Coral, compact = true)
            },
            dismissButton = {
                ComicButton("Keep going", { confirmStop = false }, color = Paper, compact = true)
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Paper,
            title = {
                Text(
                    if (p.isInstallment) "Delete the whole plan?" else "Delete this one?",
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Text(
                    when {
                        // a plan is one agreement: a single instalment cannot be dropped alone
                        p.isInstallment ->
                            "This is installment ${p.installmentIndex} of ${p.installmentCount}. " +
                                "Deleting it removes the whole plan, the ones already paid included."
                        p.recurrenceEnum == Recurrence.NONE -> "It will be gone for good, with its files."
                        else -> "You can delete only this one, or the whole repeating series."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (p.isInstallment) {
                        ComicButton("Delete all ${p.installmentCount}", {
                            confirmDelete = false
                            vm.deleteSeries(p.seriesId) { onBack() }
                        }, color = Coral, compact = true)
                    } else {
                        ComicButton("Delete this one", {
                            confirmDelete = false
                            vm.deleteOne(p.id) { onBack() }
                        }, color = Coral, compact = true)
                        if (p.recurrenceEnum != Recurrence.NONE) {
                            Box(Modifier.height(8.dp))
                            ComicButton("Delete whole series", {
                                confirmDelete = false
                                vm.deleteSeries(p.seriesId) { onBack() }
                            }, color = Coral, compact = true)
                        }
                    }
                }
            },
            dismissButton = {
                ComicButton("Keep", { confirmDelete = false }, color = Paper, compact = true)
            }
        )
    }
}
