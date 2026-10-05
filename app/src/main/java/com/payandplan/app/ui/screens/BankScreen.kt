package com.payandplan.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.payandplan.app.data.BankMovement
import com.payandplan.app.data.BankRule
import com.payandplan.app.data.MoneyKind
import com.payandplan.app.data.MovementMatch
import com.payandplan.app.data.NotificationSample
import com.payandplan.app.notify.MoneyNotificationListener
import com.payandplan.app.ui.MainViewModel
import com.payandplan.app.ui.components.ComicButton
import com.payandplan.app.ui.components.ComicCard
import com.payandplan.app.ui.components.ComicChip
import com.payandplan.app.ui.components.ComicField
import com.payandplan.app.ui.components.ComicIconButton
import com.payandplan.app.ui.components.PosterTitle
import com.payandplan.app.ui.components.SpeechBubble
import com.payandplan.app.ui.theme.Coral
import com.payandplan.app.ui.theme.Grape
import com.payandplan.app.ui.theme.Ink
import com.payandplan.app.ui.theme.Mint
import com.payandplan.app.ui.theme.Paper
import com.payandplan.app.ui.theme.PosterFont
import com.payandplan.app.ui.theme.Sky
import com.payandplan.app.ui.theme.Yellow
import com.payandplan.app.util.Format
import com.payandplan.app.util.MoneyText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Where the phone is taught to read the bank. One rule per wording: which app says it, the
 * words that give it away, and whether that means money out or money in.
 */
@Composable
fun BankScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val rules by vm.bankRules.collectAsState()
    val samples by vm.notificationSamples.collectAsState()
    var allowed by remember { mutableStateOf(MoneyNotificationListener.isAllowed(context)) }
    var teaching by remember { mutableStateOf<NotificationSample?>(null) }

    // the right is given in the system settings, so look again every time we come back
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val watcher = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allowed = MoneyNotificationListener.isAllowed(context)
            }
        }
        owner.lifecycle.addObserver(watcher)
        onDispose { owner.lifecycle.removeObserver(watcher) }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ComicIconButton(Icons.Filled.ArrowBack, onBack, color = Yellow, size = 42.dp, contentDescription = "Back")
                Box(Modifier.size(10.dp))
                PosterTitle("THE BANK")
            }
        }

        item {
            ComicCard(color = if (allowed) Mint else Coral, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (allowed) "✓ READING NOTIFICATIONS" else "NOT READING ANYTHING YET",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Ink
                )
                Text(
                    "To tick a bill off by itself the phone has to see what your bank app says. " +
                        "Nothing leaves this phone: only the lines matching a rule below become a movement, " +
                        "and only to close an entry that was already in the calendar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink
                )
                Box(Modifier.height(10.dp))
                ComicButton(
                    if (allowed) "CHANGE THE PERMISSION" else "GIVE PERMISSION",
                    { context.startActivity(MoneyNotificationListener.settingsIntent()) },
                    color = Paper,
                    compact = true
                )
            }
        }

        item {
            Text("📖 WHAT TO LOOK FOR", style = MaterialTheme.typography.headlineSmall, color = Ink)
        }

        if (rules.isEmpty()) {
            item {
                SpeechBubble(
                    "No rule yet. Pick one of the notifications below and say what it means.",
                    Modifier.fillMaxWidth(), Yellow, "🏦"
                )
            }
        }

        items(rules, key = { it.id }) { rule ->
            ComicCard(color = Paper, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(rule.appLabel.ifBlank { rule.packageName }, style = MaterialTheme.typography.titleMedium, color = Ink)
                        Text("\"${rule.phrase}\"", style = MaterialTheme.typography.bodyMedium, color = Ink)
                        Text(
                            if (rule.kind == MoneyKind.IN) "money in" else "money out",
                            style = MaterialTheme.typography.labelSmall,
                            color = Ink
                        )
                    }
                    ComicIconButton(
                        Icons.Filled.Close, { vm.deleteBankRule(rule.id) },
                        color = Coral, size = 32.dp, contentDescription = "Remove the rule"
                    )
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔔 WHAT CAME IN", style = MaterialTheme.typography.headlineSmall, color = Ink, modifier = Modifier.weight(1f))
                if (samples.isNotEmpty()) {
                    // a rule written before these arrived still has to meet them
                    ComicButton("READ AGAIN", {
                        vm.rereadSamples { made ->
                            android.widget.Toast.makeText(
                                context,
                                if (made > 0) "$made movement(s) found" else "Nothing new to take from these",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }, color = Mint, compact = true)
                    Box(Modifier.size(8.dp))
                    ComicButton("CLEAR", { vm.clearNotificationSamples() }, color = Paper, compact = true)
                }
            }
        }

        if (samples.isEmpty()) {
            item {
                SpeechBubble(
                    if (allowed) "Nothing read yet. Wait for your bank to say something, then come back."
                    else "Give the permission above and the notifications will show up here.",
                    Modifier.fillMaxWidth(), Sky, "📭"
                )
            }
        }

        items(samples, key = { it.id }) { sample ->
            ComicCard(
                color = Paper,
                modifier = Modifier.fillMaxWidth(),
                onClick = { teaching = sample }
            ) {
                Text(sample.appLabel, style = MaterialTheme.typography.labelMedium, color = Ink)
                Text(
                    MoneyText.describe(sample.title, sample.text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                MoneyText.amountCents("${sample.title} ${sample.text}")?.let { cents ->
                    Text(
                        "reads ${Format.money(cents, "EUR")} · tap to make a rule",
                        style = MaterialTheme.typography.labelSmall,
                        color = Ink
                    )
                }
            }
        }
    }

    teaching?.let { sample ->
        RuleDialog(
            sample = sample,
            onSave = { phrase, kind ->
                vm.saveBankRule(
                    BankRule(
                        packageName = sample.packageName,
                        appLabel = sample.appLabel,
                        phrase = phrase,
                        kind = kind
                    )
                )
                teaching = null
            },
            onDismiss = { teaching = null }
        )
    }
}

/** The words that give a notification away, and what they mean. */
@Composable
private fun RuleDialog(
    sample: NotificationSample,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    // the first words of the line usually are the wording the bank repeats every time
    val suggestion = remember(sample.id) {
        val source = sample.title.ifBlank { sample.text }
        // the wording is what repeats; emoji and decorations change and would never match
        source.split(Regex("""[.:·|\n]""")).firstOrNull().orEmpty()
            .replace(Regex("""[^\p{L}\p{N} '&-]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(40)
    }
    var phrase by remember(sample.id) { mutableStateOf(suggestion) }
    var kind by remember(sample.id) { mutableStateOf(MoneyKind.OUT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        title = { Text("WHAT DOES IT MEAN?", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(sample.appLabel, style = MaterialTheme.typography.labelMedium, color = Ink)
                Text(
                    MoneyText.describe(sample.title, sample.text),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink
                )
                Box(Modifier.height(10.dp))
                ComicField(phrase, { phrase = it }, "Words that always appear", Modifier.fillMaxWidth())
                Box(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ComicChip("💸 Money out", kind == MoneyKind.OUT, { kind = MoneyKind.OUT }, color = Coral)
                    ComicChip("💰 Money in", kind == MoneyKind.IN, { kind = MoneyKind.IN }, color = Mint)
                }
            }
        },
        confirmButton = {
            ComicButton("SAVE THE RULE", {
                if (phrase.isNotBlank()) onSave(phrase.trim(), kind)
            }, color = Mint, compact = true)
        },
        dismissButton = { ComicButton("Cancel", onDismiss, color = Paper, compact = true) }
    )
}

/**
 * What the bank said and the calendar has not accounted for yet. Everything the app was not
 * sure about waits here: one tap says which entry it was, or makes a new one.
 */
@Composable
fun MovementsCard(vm: MainViewModel, movements: List<BankMovement>, currency: String) {
    var matching by remember { mutableStateOf<BankMovement?>(null) }

    ComicCard(color = Grape, modifier = Modifier.fillMaxWidth()) {
        Text("🏦 TO CONFIRM (${movements.size})", style = MaterialTheme.typography.headlineSmall, color = Ink)
        Text(
            "Read from your bank's notifications, waiting to be matched.",
            style = MaterialTheme.typography.bodySmall,
            color = Ink
        )
        for (movement in movements.take(6)) {
            Box(Modifier.height(10.dp))
            ComicCard(color = Paper, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            (if (movement.isIncome) "+" else "−") +
                                Format.money(movement.amountCents, currency),
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = PosterFont),
                            color = Ink
                        )
                        Text(
                            MoneyText.describe(movement.title, movement.text),
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            movement.appLabel + " · " + Format.day(
                                Instant.ofEpochMilli(movement.happenedAt)
                                    .atZone(ZoneId.systemDefault()).toLocalDate()
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = Ink
                        )
                    }
                }
                Box(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ComicButton("MATCH", { matching = movement }, color = Mint, compact = true)
                    ComicButton("NEW ENTRY", { vm.entryFromMovement(movement.id) }, color = Sky, compact = true)
                    ComicButton("IGNORE", { vm.ignoreMovement(movement.id) }, color = Paper, compact = true)
                }
            }
        }
    }

    matching?.let { movement ->
        MatchDialog(
            vm = vm,
            movement = movement,
            currency = currency,
            onDismiss = { matching = null }
        )
    }
}

/** Which of the open entries this movement was. */
@Composable
private fun MatchDialog(
    vm: MainViewModel,
    movement: BankMovement,
    currency: String,
    onDismiss: () -> Unit
) {
    var candidates by remember(movement.id) { mutableStateOf<List<MovementMatch>?>(null) }
    if (candidates == null) {
        vm.candidatesFor(movement) { candidates = it }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        title = { Text("WHICH ONE WAS IT?", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(
                    (if (movement.isIncome) "+" else "−") + Format.money(movement.amountCents, currency) +
                        " · " + MoneyText.describe(movement.title, movement.text),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink
                )
                Box(Modifier.height(10.dp))
                val found = candidates
                when {
                    found == null -> Text("Looking…", style = MaterialTheme.typography.bodyMedium, color = Ink)
                    found.isEmpty() -> Text(
                        "Nothing open with that figure around that day. \"New entry\" writes it down as it is.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink
                    )
                    else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (match in found.take(5)) {
                            val day = LocalDate.ofEpochDay(match.payment.dueDate)
                            ComicCard(
                                color = Paper,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    vm.confirmMovement(movement.id, match.payment.id)
                                    onDismiss()
                                }
                            ) {
                                Text(match.payment.title, style = MaterialTheme.typography.titleMedium, color = Ink)
                                Text(
                                    Format.money(match.payment.amountCents, currency) + " · " + Format.day(day) +
                                        if (match.payment.amountCents == movement.amountCents) " · same figure" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Ink
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            ComicButton("NEW ENTRY", {
                vm.entryFromMovement(movement.id)
                onDismiss()
            }, color = Sky, compact = true)
        },
        dismissButton = { ComicButton("Cancel", onDismiss, color = Paper, compact = true) }
    )
}
