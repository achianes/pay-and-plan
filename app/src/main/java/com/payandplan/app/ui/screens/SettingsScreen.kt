package com.payandplan.app.ui.screens

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import com.payandplan.app.alarm.AlarmScheduler
import com.payandplan.app.ui.MainViewModel
import com.payandplan.app.ui.components.ComicButton
import com.payandplan.app.ui.components.ComicCard
import com.payandplan.app.ui.components.ComicChip
import com.payandplan.app.ui.components.ComicField
import com.payandplan.app.ui.components.PosterTitle
import com.payandplan.app.ui.theme.Coral
import com.payandplan.app.ui.theme.Grape
import com.payandplan.app.ui.theme.Ink
import com.payandplan.app.ui.theme.Mint
import com.payandplan.app.ui.theme.Paper
import com.payandplan.app.ui.theme.PosterFont
import com.payandplan.app.ui.theme.Sky
import com.payandplan.app.ui.theme.Yellow
import com.payandplan.app.ui.theme.stickerColor
import com.payandplan.app.util.Format

@Composable
fun SettingsScreen(vm: MainViewModel, onSignedOut: () -> Unit) {
    val context = LocalContext.current
    val prefs = vm.prefs
    val calendars by vm.calendars.collectAsState()
    val activeId by vm.activeCalendarId.collectAsState()
    val syncing by vm.syncing.collectAsState()
    val syncError by vm.syncError.collectAsState()
    val calendar = vm.currentCalendar()
    val isOwner = calendar?.ownerUserId == vm.myUserId()

    var myName by remember(prefs.userName) { mutableStateOf(prefs.userName) }
    var calName by remember(calendar?.id, calendar?.name) { mutableStateOf(calendar?.name ?: "") }
    var joinCode by remember { mutableStateOf("") }
    var newCalName by remember { mutableStateOf("") }
    var joinError by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteTyped by remember { mutableStateOf("") }
    var dangerError by remember { mutableStateOf<String?>(null) }

    var defaultTime by remember { mutableStateOf(prefs.defaultTime) }
    var remindDays by remember { mutableStateOf(prefs.defaultRemindDaysBefore) }
    var nag by remember { mutableStateOf(prefs.defaultNagMinutes) }
    var receipt by remember { mutableStateOf(prefs.requireReceipt) }
    var monday by remember { mutableStateOf(prefs.weekStartsMonday) }

    LazyColumn(
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 170.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { PosterTitle("SETUP") }

        item {
            ComicCard(color = Paper, modifier = Modifier.fillMaxWidth()) {
                Text("👤 YOU", style = MaterialTheme.typography.headlineSmall, color = Ink)
                Box(Modifier.height(8.dp))
                ComicField(myName, { myName = it }, "Your name", Modifier.fillMaxWidth())
                Text(prefs.userEmail, style = MaterialTheme.typography.bodySmall, color = Ink)
                Box(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ComicButton("SAVE", { vm.updateMyName(myName) }, color = Mint, compact = true)
                    ComicButton("LOG OUT", { vm.signOut(); onSignedOut() }, color = Coral, compact = true)
                }
            }
        }

        item {
            ComicCard(color = Sky, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📚 CALENDAR", style = MaterialTheme.typography.headlineSmall, color = Ink, modifier = Modifier.weight(1f))
                    ComicButton(
                        if (syncing) "SYNCING..." else "SYNC NOW",
                        { vm.syncNow() },
                        color = Yellow, compact = true
                    )
                }
                Box(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Missing something? Pull the whole calendar again from the server.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink,
                        modifier = Modifier.weight(1f)
                    )
                    ComicButton("RELOAD ALL", { vm.fullResync() }, color = Coral, compact = true)
                }
                syncError?.let {
                    Text("Last sync failed: $it", style = MaterialTheme.typography.bodySmall, color = Ink)
                }
                Box(Modifier.height(8.dp))
                ComicField(calName, { calName = it }, "Name", Modifier.fillMaxWidth())
                Text("CURRENCY", style = MaterialTheme.typography.labelMedium, color = Ink)
                Box(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("EUR", "USD", "GBP", "CHF").forEach { c ->
                        ComicChip(c, vm.currency() == c, {
                            if (isOwner) vm.renameCalendar(calName, c)
                        }, color = Mint)
                    }
                }
                if (isOwner) {
                    Box(Modifier.height(10.dp))
                    ComicButton("SAVE CALENDAR", { vm.renameCalendar(calName, vm.currency()) }, color = Mint, compact = true)
                }

                Box(Modifier.height(14.dp))
                Text("👥 PEOPLE", style = MaterialTheme.typography.headlineSmall, color = Ink)
                Box(Modifier.height(6.dp))
                vm.members().forEach { m ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .avatarCircle(stickerColor(m.colorIndex)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(m.name.take(1).uppercase(), style = MaterialTheme.typography.titleSmall, color = Ink)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                m.name + if (m.id == vm.myUserId()) " (you)" else "",
                                style = MaterialTheme.typography.titleSmall, color = Ink
                            )
                            Text("${m.email} · ${m.role}", style = MaterialTheme.typography.bodySmall, color = Ink)
                        }
                        if (isOwner && m.id != vm.myUserId()) {
                            ComicButton("Remove", { vm.removeMember(m.id) }, color = Coral, compact = true)
                        }
                    }
                    Box(Modifier.height(6.dp))
                }

                Box(Modifier.height(10.dp))
                ComicCard(color = Paper, modifier = Modifier.fillMaxWidth()) {
                    Text("INVITE CODE", style = MaterialTheme.typography.labelMedium, color = Ink)
                    Text(
                        calendar?.inviteCode ?: "------",
                        style = MaterialTheme.typography.displaySmall.copy(fontFamily = PosterFont),
                        color = Ink
                    )
                    Text(
                        "The other person signs up (app or web) and types this code.",
                        style = MaterialTheme.typography.bodySmall, color = Ink
                    )
                    if (isOwner) {
                        Box(Modifier.height(8.dp))
                        ComicButton("NEW CODE", { vm.rotateInvite() }, color = Yellow, compact = true)
                    }
                }
            }
        }

        item {
            ComicCard(color = Grape, modifier = Modifier.fillMaxWidth()) {
                Text("➕ ANOTHER CALENDAR", style = MaterialTheme.typography.headlineSmall, color = Ink)
                Box(Modifier.height(8.dp))
                ComicField(joinCode, { joinCode = it.uppercase() }, "Join with a code", Modifier.fillMaxWidth())
                joinError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Ink) }
                ComicButton("JOIN", {
                    vm.joinCalendar(joinCode.trim()) { err -> joinError = err; if (err == null) joinCode = "" }
                }, color = Mint, compact = true)
                Box(Modifier.height(12.dp))
                ComicField(newCalName, { newCalName = it }, "Create a new one", Modifier.fillMaxWidth())
                ComicButton("CREATE", {
                    if (newCalName.isNotBlank()) { vm.createCalendar(newCalName.trim()); newCalName = "" }
                }, color = Paper, compact = true)
                Box(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    calendars.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { c ->
                                ComicChip(c.name, c.id == activeId, { vm.switchCalendar(c.id) }, color = Yellow)
                            }
                        }
                    }
                }
            }
        }

        item {
            ComicCard(color = Paper, modifier = Modifier.fillMaxWidth()) {
                Text("⚠️ DANGER ZONE", style = MaterialTheme.typography.headlineSmall, color = Ink)
                dangerError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Ink) }
                if (isOwner) {
                    Text(
                        "Deleting this calendar removes its bills, appointments, receipts and " +
                            "shopping lists for everyone in it. It cannot be undone.",
                        style = MaterialTheme.typography.bodySmall, color = Ink
                    )
                    Box(Modifier.height(10.dp))
                    ComicButton(
                        "DELETE THIS CALENDAR",
                        { deleteTyped = ""; dangerError = null; confirmDelete = true },
                        color = Coral,
                        compact = true,
                        enabled = calendars.size > 1
                    )
                    if (calendars.size <= 1) {
                        Text(
                            "This is your only calendar: create another one first.",
                            style = MaterialTheme.typography.bodySmall, color = Ink
                        )
                    }
                } else {
                    Text(
                        "You are a guest here. Leaving removes the calendar from your app; " +
                            "the others keep it.",
                        style = MaterialTheme.typography.bodySmall, color = Ink
                    )
                    Box(Modifier.height(10.dp))
                    ComicButton(
                        "LEAVE THIS CALENDAR",
                        { deleteTyped = ""; dangerError = null; confirmDelete = true },
                        color = Coral,
                        compact = true
                    )
                }
            }
        }

        item {
            ComicCard(color = Yellow, modifier = Modifier.fillMaxWidth()) {
                Text("⏰ DEFAULTS FOR NEW ENTRIES", style = MaterialTheme.typography.headlineSmall, color = Ink)
                Box(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Alarm time", style = MaterialTheme.typography.bodyLarge, color = Ink, modifier = Modifier.weight(1f))
                    ComicButton(Format.time(defaultTime), {
                        TimePickerDialog(
                            context,
                            { _, h, m -> defaultTime = h * 60 + m; prefs.defaultTime = h * 60 + m },
                            defaultTime / 60, defaultTime % 60, true
                        ).show()
                    }, color = Paper, compact = true)
                }
                Box(Modifier.height(10.dp))
                Text("Heads up before", style = MaterialTheme.typography.labelMedium, color = Ink)
                Box(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Same day", 1 to "1 day", 3 to "3 days", 7 to "1 week").forEach { (v, l) ->
                        ComicChip(l, remindDays == v, { remindDays = v; prefs.defaultRemindDaysBefore = v }, color = Mint)
                    }
                }
                Box(Modifier.height(10.dp))
                Text("Nag every", style = MaterialTheme.typography.labelMedium, color = Ink)
                Box(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Once", 30 to "30 min", 60 to "1 h", 180 to "3 h").forEach { (v, l) ->
                        ComicChip(l, nag == v, { nag = v; prefs.defaultNagMinutes = v }, color = Mint)
                    }
                }
                Box(Modifier.height(10.dp))
                ToggleLine("Receipt required by default", receipt) { receipt = it; prefs.requireReceipt = it }
                ToggleLine("Week starts on Monday", monday) { monday = it; prefs.weekStartsMonday = it }
            }
        }

        item {
            ComicCard(color = Coral, modifier = Modifier.fillMaxWidth()) {
                Text("🔔 PERMISSIONS", style = MaterialTheme.typography.headlineSmall, color = Ink)
                Text(
                    "Pay & Plan needs to post notifications and to fire exact alarms, " +
                        "otherwise the reminders arrive late.",
                    style = MaterialTheme.typography.bodySmall, color = Ink
                )
                Box(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ComicButton("Notification settings", {
                        val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        runCatching { context.startActivity(i) }
                    }, color = Paper, compact = true)

                    if (Build.VERSION.SDK_INT >= 31) {
                        val ok = AlarmScheduler.canScheduleExact(context)
                        ComicButton(
                            if (ok) "Exact alarms: ON" else "Allow exact alarms",
                            {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                            .setData(Uri.parse("package:" + context.packageName))
                                    )
                                }
                            },
                            color = if (ok) Mint else Paper, compact = true
                        )
                    }

                    ComicButton("Battery settings (keep alarms alive)", {
                        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    }, color = Paper, compact = true)

                    ComicButton("Re-arm all alarms now", { vm.rearmAll() }, color = Yellow, compact = true)
                }
            }
        }

        item {
            ComicCard(color = Paper, modifier = Modifier.fillMaxWidth()) {
                Text("PAY & PLAN", style = MaterialTheme.typography.headlineSmall, color = Ink)
                Text(
                    "Server: ${prefs.serverUrl}\nThe same account opens the web app, so an iPhone " +
                        "can share the very same calendar.",
                    style = MaterialTheme.typography.bodyMedium, color = Ink
                )
            }
        }
    }

    if (confirmDelete) {
        DeleteCalendarDialog(
            calendarName = calendar?.name.orEmpty(),
            isOwner = isOwner,
            typed = deleteTyped,
            onTypedChange = { deleteTyped = it },
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                val done: (String?) -> Unit = { message -> dangerError = message }
                if (isOwner) vm.deleteCurrentCalendar(done) else vm.leaveCurrentCalendar(done)
            }
        )
    }
}

/** Destructive and shared, so it asks twice: the warning, then the name typed by hand. */
@Composable
private fun DeleteCalendarDialog(
    calendarName: String,
    isOwner: Boolean,
    typed: String,
    onTypedChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val armed = !isOwner || typed.trim().equals(calendarName.trim(), ignoreCase = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        title = {
            Text(
                if (isOwner) "Delete $calendarName?" else "Leave $calendarName?",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column {
                Text(
                    if (isOwner)
                        "Every bill, appointment, receipt, file and shopping list in this calendar " +
                            "disappears for you and for everyone you shared it with. There is no undo."
                    else
                        "The calendar goes away from your app. The owner and the other members keep it.",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (isOwner) {
                    Box(Modifier.height(12.dp))
                    Text(
                        "Type the calendar name to confirm:",
                        style = MaterialTheme.typography.labelMedium,
                        color = Ink
                    )
                    ComicField(typed, onTypedChange, calendarName, Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            ComicButton(
                if (isOwner) "DELETE FOREVER" else "LEAVE",
                onConfirm,
                color = Coral,
                compact = true,
                enabled = armed
            )
        },
        dismissButton = { ComicButton("Keep it", onDismiss, color = Paper, compact = true) }
    )
}

private fun Modifier.avatarCircle(color: androidx.compose.ui.graphics.Color): Modifier =
    this.background(color, CircleShape).border(3.dp, Ink, CircleShape)

@Composable
private fun ToggleLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Ink, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Paper,
                checkedTrackColor = Mint,
                checkedBorderColor = Ink,
                uncheckedThumbColor = Paper,
                uncheckedTrackColor = Paper,
                uncheckedBorderColor = Ink
            )
        )
    }
}
