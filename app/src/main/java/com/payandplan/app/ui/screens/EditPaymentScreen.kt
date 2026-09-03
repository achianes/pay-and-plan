package com.payandplan.app.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.payandplan.app.data.EntryKind
import com.payandplan.app.data.Payment
import com.payandplan.app.data.Recurrence
import com.payandplan.app.data.RecurrenceEngine
import com.payandplan.app.data.Visibility
import com.payandplan.app.ui.MainViewModel
import com.payandplan.app.ui.components.ComicButton
import com.payandplan.app.ui.components.ComicCard
import com.payandplan.app.ui.components.ComicChip
import com.payandplan.app.ui.components.ComicField
import com.payandplan.app.ui.components.ComicIconButton
import com.payandplan.app.ui.theme.Aqua
import com.payandplan.app.ui.theme.Coral
import com.payandplan.app.ui.theme.Grape
import com.payandplan.app.ui.theme.Ink
import com.payandplan.app.ui.theme.Mint
import com.payandplan.app.ui.theme.Paper
import com.payandplan.app.ui.theme.PosterFont
import com.payandplan.app.ui.theme.Sky
import com.payandplan.app.ui.theme.Tangerine
import com.payandplan.app.ui.theme.StickerColors
import com.payandplan.app.ui.theme.Yellow
import com.payandplan.app.util.Format
import java.time.LocalDate

@Composable
fun EditPaymentScreen(
    vm: MainViewModel,
    paymentId: String?,
    presetDay: LocalDate?,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val flow = remember(paymentId) {
        if (paymentId != null) vm.payment(paymentId) else kotlinx.coroutines.flow.flowOf<Payment?>(null)
    }
    val existing by flow.collectAsState(initial = null)

    var loaded by remember { mutableStateOf(paymentId == null) }

    var kind by remember { mutableStateOf(EntryKind.BILL) }
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var latitude by remember { mutableStateOf<Double?>(null) }
    var longitude by remember { mutableStateOf<Double?>(null) }
    var places by remember { mutableStateOf<List<com.payandplan.app.net.Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }
    var colorIndex by remember { mutableStateOf(0) }
    var date by remember { mutableStateOf(presetDay ?: LocalDate.now()) }
    var timeMinutes by remember { mutableStateOf(vm.prefs.defaultTime) }
    var recurrence by remember { mutableStateOf(Recurrence.NONE) }
    var endDate by remember { mutableStateOf<LocalDate?>(null) }
    var remindDays by remember { mutableStateOf(vm.prefs.defaultRemindDaysBefore) }
    var nagMinutes by remember { mutableStateOf(vm.prefs.defaultNagMinutes) }
    var alarmOn by remember { mutableStateOf(true) }
    var needReceipt by remember { mutableStateOf(vm.prefs.requireReceipt) }
    var isPrivate by remember { mutableStateOf(false) }
    var ownerId by remember { mutableStateOf<String?>(vm.myUserId()) }
    var askScope by remember { mutableStateOf(false) }
    val usedCategories by vm.entryCategories.collectAsState()

    // installment plan (rateizzo) with a figure of its own per due date
    val amounts = remember { mutableStateListOf<String>() }
    var planMode by remember { mutableStateOf(false) }
    var countText by remember { mutableStateOf("12") }
    var totalText by remember { mutableStateOf("") }

    /** Rebuilds the rows for [count] installments, keeping the figures already typed. */
    fun refillAmounts(count: String = countText) {
        val n = (count.toIntOrNull() ?: 12).coerceIn(1, 120)
        val base = amount.ifBlank { "0" }
        val old = amounts.toList()
        amounts.clear()
        repeat(n) { i -> amounts.add(old.getOrNull(i) ?: base) }
    }

    fun splitTotal() {
        val total = Format.parseAmountToCents(totalText) ?: return
        val n = (countText.toIntOrNull() ?: amounts.size).coerceIn(1, 120)
        val each = total / n
        val last = total - each * (n - 1)
        amounts.clear()
        repeat(n) { i -> amounts.add(Format.centsToInput(if (i == n - 1) last else each)) }
    }

    val planCents = amounts.mapNotNull { Format.parseAmountToCents(it) }
    val planReady = planMode && amounts.isNotEmpty() && planCents.size == amounts.size
    val isAppointment = kind == EntryKind.APPOINTMENT
    val isIncome = kind == EntryKind.INCOME
    val isReminder = kind == EntryKind.REMINDER

    // an event shared from a calendar app arrives here as a ready made reminder
    val sharedEvent by vm.sharedEvent.collectAsState()
    LaunchedEffect(sharedEvent) {
        val e = sharedEvent ?: return@LaunchedEffect
        if (paymentId == null) {
            kind = EntryKind.REMINDER
            title = e.title
            date = LocalDate.ofEpochDay(e.epochDay)
            timeMinutes = e.minutesOfDay
            location = e.location
            notes = e.notes
            loaded = true
        }
        vm.consumeEvent()
    }

    LaunchedEffect(existing?.id) {
        val e = existing
        if (e != null && !loaded) {
            kind = e.kindEnum
            title = e.title
            amount = if (e.amountCents > 0) Format.centsToInput(e.amountCents) else ""
            category = e.category
            location = e.location
            latitude = e.latitude
            longitude = e.longitude
            notes = e.notes
            colorIndex = e.colorIndex
            date = LocalDate.ofEpochDay(e.dueDate)
            timeMinutes = e.dueTimeMinutes
            recurrence = e.recurrenceEnum
            endDate = e.recurrenceEndDate?.let { LocalDate.ofEpochDay(it) }
            remindDays = e.remindDaysBefore
            nagMinutes = e.nagMinutes
            alarmOn = e.alarmEnabled
            needReceipt = e.requireReceipt
            isPrivate = e.isPrivate
            ownerId = e.ownerUserId
            loaded = true
        }
    }

    fun buildPayment(): Payment? {
        if (title.isBlank()) return null
        val cents = Format.parseAmountToCents(amount)
            ?: planCents.firstOrNull()
            ?: (if (isAppointment || isReminder) 0L else return null)
        val base = existing ?: Payment()
        return base.copy(
            title = title.trim(),
            amountCents = cents,
            currency = vm.currency(),
            colorIndex = colorIndex,
            category = category.trim(),
            dueDate = date.toEpochDay(),
            dueTimeMinutes = timeMinutes,
            recurrence = recurrence.name,
            recurrenceEndDate = endDate?.toEpochDay(),
            notes = notes.trim(),
            remindDaysBefore = remindDays,
            nagMinutes = nagMinutes,
            alarmEnabled = alarmOn,
            requireReceipt = if (isAppointment || isIncome || isReminder) false else needReceipt,
            ownerUserId = ownerId,
            visibility = if (isPrivate) Visibility.PRIVATE else Visibility.SHARED,
            kind = kind.name,
            location = if (isAppointment) location.trim() else "",
            latitude = if (isAppointment) latitude else null,
            longitude = if (isAppointment) longitude else null
        )
    }

    val valid = title.isNotBlank() &&
        (isAppointment || isReminder || Format.parseAmountToCents(amount) != null || planReady)


    LazyColumn(
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ComicIconButton(Icons.Filled.ArrowBack, onBack, color = Yellow, size = 42.dp, contentDescription = "Back")
                Box(Modifier.size(10.dp))
                Text(
                    if (paymentId == null) "NEW ENTRY" else "EDIT",
                    style = MaterialTheme.typography.displaySmall.copy(fontFamily = PosterFont),
                    color = Ink
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ComicChip("🧾 Bill", kind == EntryKind.BILL, { kind = EntryKind.BILL }, color = Yellow)
                    ComicChip("🗓 Appointment", isAppointment, { kind = EntryKind.APPOINTMENT }, color = Aqua)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ComicChip("💰 Income", isIncome, { kind = EntryKind.INCOME }, color = Mint)
                    ComicChip("⏰ Reminder", isReminder, { kind = EntryKind.REMINDER }, color = Tangerine)
                }
            }
        }

        item {
            ComicCard(color = Paper, modifier = Modifier.fillMaxWidth()) {
                ComicField(
                    title, { title = it },
                    when {
                        isAppointment -> "What is it? (doctor, gym, hairdresser...)"
                        isIncome -> "What is it? (salary, rent, refund...)"
                        isReminder -> "What has to be done? (write the report...)"
                        else -> "What is it? (Rent, Netflix...)"
                    },
                    Modifier.fillMaxWidth()
                )
                if (!isReminder) {
                Box(Modifier.height(10.dp))
                ComicField(
                    amount, { amount = it },
                    when {
                        isAppointment -> "Cost, leave empty if none (${vm.currency()})"
                        isIncome -> "How much comes in (${vm.currency()})"
                        else -> "How much (${vm.currency()})"
                    },
                    Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                }
                Box(Modifier.height(10.dp))
                if (isAppointment) {
                    ComicField(
                        location,
                        { location = it; latitude = null; longitude = null },
                        "Where (address, clinic, studio)",
                        Modifier.fillMaxWidth()
                    )
                    Box(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ComicButton(
                            if (searching) "SEARCHING…" else "🗺 FIND ON THE MAP",
                            {
                                if (location.isNotBlank() && !searching) {
                                    searching = true
                                    vm.searchPlaces(location) { places = it; searching = false }
                                }
                            },
                            color = Sky,
                            compact = true
                        )
                        if (latitude != null) {
                            Box(Modifier.size(8.dp))
                            Text("📍 pinned", style = MaterialTheme.typography.bodySmall, color = Ink)
                        }
                    }
                    if (places.isNotEmpty()) {
                        Box(Modifier.height(6.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            places.forEach { place ->
                                ComicChip(
                                    text = place.name,
                                    selected = false,
                                    onClick = {
                                        location = place.name
                                        latitude = place.lat
                                        longitude = place.lon
                                        places = emptyList()
                                    },
                                    color = Paper
                                )
                            }
                        }
                    }
                } else {
                    ComicField(category, { category = it }, "Category (optional)", Modifier.fillMaxWidth())
                    if (usedCategories.isNotEmpty()) {
                        Box(Modifier.height(6.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            usedCategories.take(9).chunked(3).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { c ->
                                        ComicChip(
                                            text = c,
                                            selected = category.equals(c, ignoreCase = true),
                                            onClick = {
                                                category = if (category.equals(c, true)) "" else c
                                            },
                                            color = Sky
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Box(Modifier.height(12.dp))
                Text("STICKER COLOR", style = MaterialTheme.typography.labelMedium, color = Ink)
                Box(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StickerColors.forEachIndexed { i, c ->
                        Box(
                            Modifier
                                .size(if (colorIndex == i) 34.dp else 28.dp)
                                .background(c, CircleShape)
                                .border(if (colorIndex == i) 4.dp else 2.dp, Ink, CircleShape)
                                .clickable { colorIndex = i }
                        )
                    }
                }
            }
        }

        if (vm.members().isNotEmpty()) {
            item {
                ComicCard(color = Grape, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        when {
                            isAppointment -> "👤 WHOSE APPOINTMENT"
                            isIncome -> "👤 WHO RECEIVES IT"
                            isReminder -> "👤 WHO HAS TO DO IT"
                            else -> "👤 WHO PAYS IT"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = Ink
                    )
                    Box(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        vm.members().chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { m ->
                                    ComicChip(
                                        text = m.name + if (m.id == vm.myUserId()) " (you)" else "",
                                        selected = ownerId == m.id,
                                        onClick = { ownerId = m.id },
                                        color = Mint
                                    )
                                }
                            }
                        }
                        ComicChip("Nobody in particular", ownerId == null, { ownerId = null }, color = Sky)
                    }
                    Box(Modifier.height(10.dp))
                    ToggleRow(
                        if (isPrivate) "🔒 Private: only you see it" else "👥 Shared with the calendar",
                        isPrivate
                    ) { isPrivate = it }
                }
            }
        }

        item {
            ComicCard(color = Sky, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (isReminder) "📅 COMPLETE BY" else "📅 WHEN",
                    style = MaterialTheme.typography.headlineSmall, color = Ink
                )
                Box(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ComicButton(Format.day(date), {
                        DatePickerDialog(
                            context,
                            { _, y, m, dom -> date = LocalDate.of(y, m + 1, dom) },
                            date.year, date.monthValue - 1, date.dayOfMonth
                        ).show()
                    }, color = Paper, compact = true)
                    ComicButton(Format.time(timeMinutes), {
                        TimePickerDialog(
                            context,
                            { _, h, min -> timeMinutes = h * 60 + min },
                            timeMinutes / 60, timeMinutes % 60, true
                        ).show()
                    }, color = Paper, compact = true)
                }
                Box(Modifier.height(12.dp))
                Text("REPEATS", style = MaterialTheme.typography.labelMedium, color = Ink)
                Box(Modifier.height(6.dp))
                FlowChips(
                    options = Recurrence.entries.map { it to it.label },
                    selected = recurrence,
                    onSelect = { recurrence = it }
                )
                if (recurrence != Recurrence.NONE) {
                    Box(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ComicButton(
                            endDate?.let { "Until ${Format.day(it)}" } ?: "No end date",
                            {
                                val d = endDate ?: date.plusYears(1)
                                DatePickerDialog(
                                    context,
                                    { _, y, m, dom -> endDate = LocalDate.of(y, m + 1, dom) },
                                    d.year, d.monthValue - 1, d.dayOfMonth
                                ).show()
                            },
                            color = Paper, compact = true
                        )
                        if (endDate != null) {
                            ComicButton("Clear", { endDate = null }, color = Coral, compact = true)
                        }
                    }
                }
            }
        }

        if (paymentId == null && recurrence != Recurrence.NONE &&
            !isAppointment && !isIncome && !isReminder
        ) {
            item {
                ComicCard(color = Grape, modifier = Modifier.fillMaxWidth()) {
                    Text("🧾 INSTALLMENT PLAN", style = MaterialTheme.typography.headlineSmall, color = Ink)
                    Text(
                        "For a plan whose installments are not all the same size. " +
                            "Pick how many, then tweak each figure.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink
                    )
                    Box(Modifier.height(8.dp))
                    ToggleRow("Fixed plan, custom amounts", planMode) { on ->
                        planMode = on
                        if (on && amounts.isEmpty()) refillAmounts()
                    }
                    if (planMode) {
                        Box(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ComicField(
                                countText,
                                {
                                    val digits = it.filter { c -> c.isDigit() }.take(3)
                                    countText = digits
                                    // the list follows the number as it is typed
                                    if (digits.isNotBlank()) refillAmounts(digits)
                                },
                                "How many", Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            ComicButton("Reset", { refillAmounts() }, color = Yellow, compact = true)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ComicField(
                                totalText, { totalText = it }, "Split a total", Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                            )
                            ComicButton("Split", { splitTotal() }, color = Mint, compact = true)
                        }
                        Box(Modifier.height(10.dp))
                        amounts.forEachIndexed { i, value ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "#${i + 1}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Ink,
                                    modifier = Modifier.width(34.dp)
                                )
                                ComicField(
                                    value, { amounts[i] = it }, "Amount", Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                                )
                                Text(
                                    Format.day(RecurrenceEngine.dateAt(date, recurrence, i)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Ink,
                                    modifier = Modifier.width(84.dp)
                                )
                            }
                            Box(Modifier.height(6.dp))
                        }
                        Text(
                            "Plan total: ${Format.money(planCents.sum(), vm.currency())}",
                            style = MaterialTheme.typography.titleMedium,
                            color = Ink
                        )
                    }
                }
            }
        }

        item {
            ComicCard(color = Yellow, modifier = Modifier.fillMaxWidth()) {
                Text("⏰ ALARM", style = MaterialTheme.typography.headlineSmall, color = Ink)
                Box(Modifier.height(6.dp))
                ToggleRow("Wake me up for this one", alarmOn) { alarmOn = it }
                if (alarmOn) {
                    Box(Modifier.height(10.dp))
                    Text("HEADS UP BEFORE", style = MaterialTheme.typography.labelMedium, color = Ink)
                    Box(Modifier.height(6.dp))
                    FlowChips(
                        options = listOf(0 to "Same day", 1 to "1 day", 2 to "2 days", 3 to "3 days", 7 to "1 week"),
                        selected = remindDays,
                        onSelect = { remindDays = it }
                    )
                    Box(Modifier.height(10.dp))
                    Text("KEEP NAGGING EVERY", style = MaterialTheme.typography.labelMedium, color = Ink)
                    Box(Modifier.height(6.dp))
                    FlowChips(
                        options = listOf(0 to "Once", 15 to "15 min", 30 to "30 min", 60 to "1 h", 180 to "3 h", 720 to "12 h"),
                        selected = nagMinutes,
                        onSelect = { nagMinutes = it }
                    )
                }
                if (!isAppointment && !isIncome && !isReminder) {
                    Box(Modifier.height(10.dp))
                    ToggleRow("Receipt required to close it", needReceipt) { needReceipt = it }
                }
            }
        }

        item {
            ComicCard(color = Paper, modifier = Modifier.fillMaxWidth()) {
                ComicField(
                    notes, { notes = it }, "Notes (optional)",
                    Modifier.fillMaxWidth(), singleLine = false, minLines = 3
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ComicButton(
                    text = if (paymentId == null) "CREATE" else "SAVE",
                    onClick = {
                        val p = buildPayment() ?: return@ComicButton
                        when {
                            paymentId == null && planReady -> vm.create(p, planCents) { onSaved() }
                            paymentId == null -> vm.create(p, null) { onSaved() }
                            p.recurrenceEnum != Recurrence.NONE -> askScope = true
                            else -> vm.updateOne(p) { onSaved() }
                        }
                    },
                    color = Mint,
                    enabled = valid
                )
                ComicButton("CANCEL", onBack, color = Paper)
            }
            if (!valid) {
                Text(
                    if (isAppointment) "Give it a name first."
                    else "Give it a name and an amount first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }

    if (askScope) {
        AlertDialog(
            onDismissRequest = { askScope = false },
            containerColor = Paper,
            title = { Text("This one or all of them?", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "This one repeats. Apply the changes only here, or to this one and every future repeat?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    ComicButton("This and future", {
                        askScope = false
                        buildPayment()?.let { vm.updateSeries(it) { onSaved() } }
                    }, color = Mint, compact = true)
                    Box(Modifier.height(8.dp))
                    ComicButton("Only this one", {
                        askScope = false
                        buildPayment()?.let { vm.updateOne(it) { onSaved() } }
                    }, color = Sky, compact = true)
                }
            },
            dismissButton = { ComicButton("Cancel", { askScope = false }, color = Paper, compact = true) }
        )
    }
}

@Composable
private fun <T> FlowChips(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (value, label) ->
                    ComicChip(
                        text = label,
                        selected = value == selected,
                        onClick = { onSelect(value) },
                        color = Mint
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
