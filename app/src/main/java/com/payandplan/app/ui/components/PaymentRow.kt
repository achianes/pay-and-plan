package com.payandplan.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.payandplan.app.data.Member
import com.payandplan.app.data.PayStatus
import com.payandplan.app.data.Payment
import com.payandplan.app.data.Recurrence
import com.payandplan.app.ui.theme.Aqua
import com.payandplan.app.ui.theme.Coral
import com.payandplan.app.ui.theme.Ink
import com.payandplan.app.ui.theme.Mint
import com.payandplan.app.ui.theme.Paper
import com.payandplan.app.ui.theme.PosterFont
import com.payandplan.app.ui.theme.stickerColor
import com.payandplan.app.util.Format
import java.time.LocalDate

@Composable
fun PaymentRow(
    payment: Payment,
    currency: String,
    onClick: () -> Unit,
    owner: Member? = null,
    onQuickPaid: (() -> Unit)? = null,
    today: LocalDate = LocalDate.now()
) {
    val date = LocalDate.ofEpochDay(payment.dueDate)
    val appointment = payment.isAppointment
    val income = payment.isIncome
    // a reminder is done, never paid
    val reminder = payment.isReminder
    val overdue = payment.isOpen && date.isBefore(today)
    val dueToday = payment.isOpen && date == today
    val cardColor = when {
        payment.isPaid -> Mint
        overdue && !appointment && !income -> Coral
        else -> Paper
    }

    ComicCard(
        modifier = Modifier.fillMaxWidth().alpha(if (payment.isSuspended) 0.55f else 1f),
        color = cardColor,
        onClick = onClick,
        contentPadding = PaddingValues(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .background(
                        when {
                            appointment -> Aqua
                            income -> Mint
                            else -> stickerColor(payment.colorIndex)
                        },
                        CircleShape
                    )
                    .border(3.dp, Ink, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    when {
                        appointment -> "🗓"
                        income -> "💰"
                        else -> payment.title.trim().take(1).uppercase().ifBlank { "?" }
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink
                )
            }
            Box(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    payment.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            payment.isPaid -> when {
                                appointment || reminder -> "Done"
                                income -> "Received"
                                else -> "Paid"
                            }
                            payment.isSuspended -> "Suspended"
                            dueToday -> "Today ${Format.time(payment.dueTimeMinutes)}"
                            else -> Format.relative(date, today)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink
                    )
                    if (payment.isInstallment) {
                        Box(Modifier.width(6.dp))
                        Text(payment.installmentLabel, style = MaterialTheme.typography.bodySmall, color = Ink)
                    }
                    if (payment.recurrenceEnum != Recurrence.NONE) {
                        Box(Modifier.width(6.dp))
                        Icon(Icons.Filled.Autorenew, null, tint = Ink, modifier = Modifier.size(14.dp))
                    }
                    if (payment.isPrivate) {
                        Box(Modifier.width(4.dp))
                        Icon(Icons.Filled.Lock, null, tint = Ink, modifier = Modifier.size(13.dp))
                    }
                    if (payment.alarmEnabled && payment.isOpen) {
                        Box(Modifier.width(4.dp))
                        Icon(Icons.Filled.NotificationsActive, null, tint = Ink, modifier = Modifier.size(14.dp))
                    }
                }
                if (appointment && payment.location.isNotBlank()) {
                    Text(
                        "📍 ${payment.location}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (appointment) {
                    Text(
                        Format.time(payment.dueTimeMinutes),
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = PosterFont),
                        color = Ink
                    )
                    Text(
                        if (payment.amountCents > 0)
                            Format.money(payment.amountCents, payment.currency.ifBlank { currency })
                        else "no cost",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink
                    )
                } else {
                    Text(
                        (if (income) "+" else "") +
                            Format.money(payment.amountCents, payment.currency.ifBlank { currency }),
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = PosterFont),
                        color = Ink
                    )
                }
                if (owner != null) {
                    Text(owner.name, style = MaterialTheme.typography.bodySmall, color = Ink, maxLines = 1)
                }
                when {
                    payment.isPaid -> Text(
                        if (appointment || reminder) "DONE!" else if (income) "IN!" else "PAID!",
                        style = MaterialTheme.typography.labelMedium,
                        color = Ink,
                        modifier = Modifier.rotate(-8f)
                    )
                    payment.isSuspended ->
                        Text("PAUSED", style = MaterialTheme.typography.labelSmall, color = Ink)
                    payment.statusEnum == PayStatus.SKIPPED ->
                        Text("SKIPPED", style = MaterialTheme.typography.labelSmall, color = Ink)
                    onQuickPaid != null -> Box(Modifier.padding(top = 4.dp)) {
                        ComicIconButton(
                            icon = Icons.Filled.Check,
                            onClick = onQuickPaid,
                            color = Mint,
                            size = 32.dp,
                            contentDescription = "Mark done"
                        )
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
fun SectionHeader(text: String, emoji: String) {
    Row(
        Modifier.padding(top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(emoji, style = MaterialTheme.typography.titleMedium)
        Text(text.uppercase(), style = MaterialTheme.typography.headlineSmall, color = Ink)
    }
}

@Composable
fun StatusStamp(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier
            .rotate(-10f)
            .background(color, RoundedCornerShape(8.dp))
            .border(3.dp, Ink, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.headlineSmall, color = Ink)
    }
}
