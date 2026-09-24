package com.payandplan.app.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.background
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import coil.compose.AsyncImage
import com.payandplan.app.data.ShoppingItem
import com.payandplan.app.data.ShoppingList
import com.payandplan.app.data.Visibility
import com.payandplan.app.ui.MainViewModel
import com.payandplan.app.ui.components.ComicButton
import com.payandplan.app.ui.components.ComicCard
import com.payandplan.app.ui.components.ComicChip
import com.payandplan.app.ui.components.ComicField
import com.payandplan.app.ui.components.ComicIconButton
import com.payandplan.app.ui.components.openAttachment
import com.payandplan.app.util.Faces
import com.payandplan.app.util.FileStore
import com.payandplan.app.ui.components.PosterTitle
import com.payandplan.app.ui.components.SpeechBubble
import com.payandplan.app.ui.theme.Coral
import com.payandplan.app.ui.theme.Ink
import com.payandplan.app.ui.theme.Mint
import com.payandplan.app.ui.theme.Paper
import com.payandplan.app.ui.theme.PosterFont
import com.payandplan.app.ui.theme.Sky
import com.payandplan.app.ui.theme.Yellow
import com.payandplan.app.util.Format
import java.time.LocalDate

@Composable
fun ListsScreen(
    vm: MainViewModel,
    onOpenList: (String) -> Unit
) {
    val lists by vm.shoppingLists.collectAsState()
    val currency = vm.currency()
    val me = vm.myUserId()
    val receipts by vm.receiptsEnabled.collectAsState()

    LazyColumn(
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 170.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { PosterTitle("SHOPPING") }
        item {
            Text(
                "Write the list, hand it to someone. They tick things off and type what it cost: " +
                    "it lands in the calendar as a paid bill in their name.",
                style = MaterialTheme.typography.bodySmall,
                color = Ink
            )
        }

        if (receipts) item { ReceiptScanner(vm, onOpenList) }

        val mine = lists.filter { it.assignedToUserId == me && !it.isDone }
        val others = lists.filter { it.assignedToUserId != me && !it.isDone }
        val done = lists.filter { it.isDone }

        if (mine.isNotEmpty()) {
            item { Text("🛒 FOR YOU", style = MaterialTheme.typography.headlineSmall, color = Ink) }
            items(mine, key = { it.id }) { ListCard(vm, it, currency, onOpenList) }
        }
        if (others.isNotEmpty()) {
            item { Text("👥 THE OTHERS", style = MaterialTheme.typography.headlineSmall, color = Ink) }
            items(others, key = { it.id }) { ListCard(vm, it, currency, onOpenList) }
        }
        if (done.isNotEmpty()) {
            item { Text("✅ DONE", style = MaterialTheme.typography.headlineSmall, color = Ink) }
            items(done, key = { it.id }) { ListCard(vm, it, currency, onOpenList) }
        }
        if (lists.isEmpty()) {
            item {
                SpeechBubble("No lists yet. Tap + to write one.", Modifier.fillMaxWidth(), Yellow, "🛒")
            }
        }
    }
}

/**
 * Opens Google's barcode scanner and turns what it reads into an item, named and pictured
 * from the Italian food database when the code is known there.
 */
private fun scanBarcode(
    context: android.content.Context,
    listId: String,
    vm: MainViewModel,
    onNeedsName: (String) -> Unit
) {
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E
        )
        .build()
    GmsBarcodeScanning.getClient(context, options).startScan()
        .addOnSuccessListener { barcode ->
            val code = barcode.rawValue?.filter { it.isDigit() } ?: return@addOnSuccessListener
            Toast.makeText(context, "Looking up $code…", Toast.LENGTH_SHORT).show()
            vm.addByBarcode(listId, code) { itemId, named ->
                if (named) {
                    Toast.makeText(context, "Added", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Nobody has named this one yet: give it a name", Toast.LENGTH_LONG).show()
                    onNeedsName(itemId)
                }
            }
        }
        .addOnFailureListener { e ->
            Toast.makeText(context, e.message ?: "Scanner not available", Toast.LENGTH_LONG).show()
        }
}

/**
 * Full screen "working on it" card: a striped bar that keeps moving, a running clock and a
 * STOP button. The caller decides what STOP cancels.
 */
@Composable
private fun BusySplash(emoji: String, title: String, onStop: () -> Unit) {
    var seconds by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            seconds += 1
        }
    }
    val stage = if (seconds < 3) "Sending the picture…" else "The model is reading it… usually 10-20 seconds."

    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xD1281E14))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            ComicCard(color = Yellow, modifier = Modifier.fillMaxWidth()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(emoji, style = MaterialTheme.typography.displayMedium)
                    Text(title, style = MaterialTheme.typography.headlineSmall, color = Ink)
                    Box(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(16.dp)
                            .border(3.dp, Ink, RoundedCornerShape(10.dp))
                            .clip(RoundedCornerShape(10.dp)),
                        color = Sky,
                        trackColor = Paper
                    )
                    Box(Modifier.height(10.dp))
                    Text(stage, style = MaterialTheme.typography.bodyMedium, color = Ink)
                    Text("$seconds s", style = MaterialTheme.typography.bodySmall, color = Ink)
                    Box(Modifier.height(14.dp))
                    ComicButton("✋ STOP", onStop, color = Coral)
                }
            }
        }
    }
}

/** Photograph the till receipt and get back a list that is already ticked and priced. */
@Composable
private fun ReceiptScanner(vm: MainViewModel, onOpenList: (String) -> Unit) {
    val context = LocalContext.current
    var reading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val pendingShot = remember { arrayOfNulls<java.io.File>(1) }

    fun send(file: java.io.File, mime: String) {
        reading = true
        error = null
        job = vm.importReceipt(file, mime) { result ->
            reading = false
            result.onSuccess(onOpenList).onFailure { error = it.message ?: "could not read it" }
        }
    }

    if (reading) {
        BusySplash(
            emoji = "📷",
            title = "READING THE RECEIPT",
            onStop = {
                job?.cancel()
                reading = false
            }
        )
    }

    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pendingShot[0]
        if (ok && file != null && file.exists()) send(file, "image/jpeg")
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val file = FileStore.newCameraFile(context)
            pendingShot[0] = file
            takePhoto.launch(FileStore.uriFor(context, file))
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) FileStore.copyIn(context, uri)?.let { (file, _, mime) -> send(file, mime) }
    }

    ComicCard(color = Sky) {
        Text("📷 ALREADY SHOPPED?", style = MaterialTheme.typography.headlineSmall, color = Ink)
        Text(
            "Snap the receipt: the items and the prices come out as a list, ticked and ready to log.",
            style = MaterialTheme.typography.bodySmall,
            color = Ink
        )
        Box(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ComicButton("TAKE A PHOTO", { askCamera.launch(android.Manifest.permission.CAMERA) }, color = Yellow)
            ComicButton("PICK ONE", { pickPhoto.launch(arrayOf("image/*")) }, color = Paper)
        }
        error?.let {
            Box(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Coral)
        }
    }
}

@Composable
private fun ListCard(
    vm: MainViewModel,
    list: ShoppingList,
    currency: String,
    onOpen: (String) -> Unit
) {
    val items by vm.shoppingItems(list.id).collectAsState(initial = emptyList())
    val ticked = items.count { it.checked }
    val who = vm.memberById(list.assignedToUserId)
    ComicCard(
        color = if (list.isDone) Mint else Paper,
        modifier = Modifier.fillMaxWidth(),
        onClick = { onOpen(list.id) }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(list.title, style = MaterialTheme.typography.titleMedium, color = Ink)
                Text(
                    buildString {
                        append("$ticked/${items.size} items")
                        who?.let { append(" · for ${it.name}") }
                        list.dueDate?.let {
                            append(" · ${Format.day(LocalDate.ofEpochDay(it))} ${Format.time(list.dueTimeMinutes)}")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (list.isDone) {
                    Text(
                        Format.money(list.actualCents ?: 0, currency),
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = PosterFont),
                        color = Ink
                    )
                    Text("SPENT", style = MaterialTheme.typography.labelSmall, color = Ink)
                } else if (list.budgetCents != null) {
                    Text("budget", style = MaterialTheme.typography.labelSmall, color = Ink)
                    Text(
                        Format.money(list.budgetCents, currency),
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = PosterFont),
                        color = Ink
                    )
                }
            }
        }
    }
}

@Composable
fun ListDetailScreen(
    vm: MainViewModel,
    listId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit
) {
    val list by vm.shoppingList(listId).collectAsState(initial = null)
    val items by vm.shoppingItems(listId).collectAsState(initial = emptyList())
    val suggestions by vm.itemSuggestions.collectAsState()
    val photos by vm.itemPhotos.collectAsState()
    val currency = vm.currency()
    var newItem by remember { mutableStateOf("") }
    var actual by remember { mutableStateOf("") }
    var mosaic by remember { mutableStateOf(vm.prefs.listMosaic) }
    var editItemId by remember { mutableStateOf<String?>(null) }

    val l = list
    if (l == null) {
        SpeechBubble("This list is gone.", Modifier.fillMaxWidth(), Yellow, "🕳️")
        return
    }
    val who = vm.memberById(l.assignedToUserId)
    val suggested = items.mapNotNull { it.priceCents }.sum()

    LazyColumn(
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ComicIconButton(Icons.Filled.ArrowBack, onBack, color = Yellow, size = 42.dp, contentDescription = "Back")
                Box(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(l.title, style = MaterialTheme.typography.headlineSmall, color = Ink)
                    Text(
                        buildString {
                            append(who?.let { "For ${it.name}" } ?: "Anyone can do it")
                            l.dueDate?.let {
                                append(" · ${Format.day(LocalDate.ofEpochDay(it))} ${Format.time(l.dueTimeMinutes)}")
                            }
                            l.budgetCents?.let { append(" · budget ${Format.money(it, currency)}") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink
                    )
                }
                ComicButton("EDIT", { onEdit(l.id) }, color = Sky, compact = true)
            }
        }

        item {
            ComicCard(color = Paper, modifier = Modifier.fillMaxWidth()) {
                if (items.isEmpty()) {
                    Text("Empty list. Add what is needed.", style = MaterialTheme.typography.bodyMedium, color = Ink)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ComicChip("☰ List", !mosaic, { mosaic = false; vm.prefs.listMosaic = false }, color = Yellow)
                    ComicChip("▦ Mosaic", mosaic, { mosaic = true; vm.prefs.listMosaic = true }, color = Yellow)
                }
                Box(Modifier.height(10.dp))
                if (mosaic) {
                    // every product as a tile: one tap ticks it, another tap unticks it
                    items.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            row.forEach { item ->
                                MosaicTile(vm, item, photos[item.id], Modifier.weight(1f))
                            }
                            repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                        }
                        Box(Modifier.height(10.dp))
                    }
                } else {
                    items.forEach { item ->
                        ItemRow(vm, item, currency, photos[item.id]) { editItemId = item.id }
                    }
                }
                Box(Modifier.height(10.dp))
                val context = LocalContext.current
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ComicButton("▦", { scanBarcode(context, l.id, vm) { id -> editItemId = id } }, color = Sky, compact = true)
                    Box(Modifier.size(8.dp))
                    ComicField(newItem, { newItem = it }, "Add something", Modifier.weight(1f))
                    Box(Modifier.size(8.dp))
                    ComicButton("ADD", {
                        if (newItem.isNotBlank()) { vm.addItem(l.id, newItem); newItem = "" }
                    }, color = Mint, compact = true)
                }

                // what the household usually buys, filtered by what is being typed
                val onList = items.map { it.text.lowercase() }.toSet()
                val matches = suggestions
                    .filter { it.lowercase() !in onList && it.contains(newItem.trim(), ignoreCase = true) }
                    .take(8)
                if (matches.isNotEmpty()) {
                    Box(Modifier.height(8.dp))
                    Text(
                        if (newItem.isBlank()) "USUAL" else "MATCHING",
                        style = MaterialTheme.typography.labelSmall,
                        color = Ink
                    )
                    Box(Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        matches.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { suggestion ->
                                    ComicChip(
                                        text = suggestion,
                                        selected = false,
                                        onClick = { vm.addItem(l.id, suggestion); newItem = "" },
                                        color = Sky
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            if (l.isDone) {
                ComicCard(color = Mint, modifier = Modifier.fillMaxWidth()) {
                    Text("✅ DONE", style = MaterialTheme.typography.headlineSmall, color = Ink)
                    Text(
                        Format.money(l.actualCents ?: 0, currency),
                        style = MaterialTheme.typography.displaySmall.copy(fontFamily = PosterFont),
                        color = Ink
                    )
                    vm.memberById(l.doneByUserId)?.let {
                        Text("Paid by ${it.name}", style = MaterialTheme.typography.bodySmall, color = Ink)
                    }
                    Box(Modifier.height(10.dp))
                    ComicButton("REOPEN", { vm.reopenList(l) }, color = Yellow, compact = true)
                }
            } else {
                ComicCard(color = Yellow, modifier = Modifier.fillMaxWidth()) {
                    Text("💶 FINISHED SHOPPING?", style = MaterialTheme.typography.headlineSmall, color = Ink)
                    Text(
                        "Type what it actually cost. It becomes a paid bill in the calendar, " +
                            "in the name of whoever did it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink
                    )
                    Box(Modifier.height(8.dp))
                    ComicField(
                        actual.ifBlank { if (suggested > 0) Format.centsToInput(suggested) else "" },
                        { actual = it },
                        "Total spent ($currency)",
                        Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    Box(Modifier.height(8.dp))
                    ComicButton(
                        "DONE, THIS IS THE COST",
                        {
                            val cents = Format.parseAmountToCents(
                                actual.ifBlank { Format.centsToInput(suggested) }
                            )
                            if (cents != null) vm.completeList(l.id, cents)
                        },
                        color = Mint
                    )
                }
            }
        }

        item {
            ComicButton("DELETE LIST", { vm.deleteList(l) { onBack() } }, color = Coral, compact = true)
        }
    }
    editItemId?.let { id ->
        val target = items.find { it.id == id }
        if (target == null) editItemId = null
        else ItemEditDialog(
            item = target,
            onSave = { name, quantity ->
                vm.updateItem(target.copy(text = name, quantity = quantity))
                editItemId = null
            },
            onDismiss = { editItemId = null }
        )
    }
}

/**
 * A till style amount: only digits go in and they fill the cents from the right, so 1-2-5
 * reads 1.25. The cursor always sits at the end; backspace takes the last digit away.
 */
@Composable
private fun CashField(
    key: String,
    cents: Long?,
    onCents: (Long?) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    var digits by remember(key) { mutableStateOf(cents?.takeIf { it > 0 }?.toString() ?: "") }
    // what we sent ourselves comes back from the database a beat later: do not let it rewind us
    val sent = remember(key) { mutableSetOf<Long?>() }
    LaunchedEffect(cents) {
        if (cents !in sent && digits.toLongOrNull() != cents) {
            digits = cents?.takeIf { it > 0 }?.toString() ?: ""
        }
    }
    ComicField(
        value = digits,
        onValueChange = { typed ->
            val clean = typed.filter { it.isDigit() }.trimStart('0').take(7)
            digits = clean
            val value = clean.toLongOrNull()
            sent += value
            onCents(value)
        },
        label = label,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        visualTransformation = CentsTransformation
    )
}

private object CentsTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (text.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val n = text.text.toLong()
        val shown = "${n / 100}.${(n % 100).toString().padStart(2, '0')}"
        // wherever the finger lands, the cursor stays at the end: digits only enter from the right
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = shown.length
            override fun transformedToOriginal(offset: Int) = text.length
        }
        return TransformedText(AnnotatedString(shown), mapping)
    }
}

/** Rename a product and say how much of it: two fields and the usual amounts. */
@Composable
private fun ItemEditDialog(
    item: ShoppingItem,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(item.id) { mutableStateOf(item.text) }
    var quantity by remember(item.id) { mutableStateOf(item.quantity) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        title = { Text("THE PRODUCT", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                ComicField(name, { name = it }, "Name", Modifier.fillMaxWidth())
                Box(Modifier.height(8.dp))
                ComicField(quantity, { quantity = it }, "How much", Modifier.fillMaxWidth())
                Box(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("1", "2", "3", "500 g", "1 kg").forEach { q ->
                        ComicChip(q, quantity == q, { quantity = if (quantity == q) "" else q }, color = Sky)
                    }
                }
            }
        },
        confirmButton = {
            ComicButton("SAVE", {
                if (name.isNotBlank()) onSave(name.trim(), quantity.trim())
            }, color = Mint, compact = true)
        },
        dismissButton = { ComicButton("Cancel", onDismiss, color = Paper, compact = true) }
    )
}

@Composable
private fun MosaicTile(
    vm: MainViewModel,
    item: ShoppingItem,
    photo: com.payandplan.app.data.Attachment?,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(Paper, shape)
            .border(3.dp, Ink, shape)
            .clickable { vm.updateItem(item.copy(checked = !item.checked)) }
            .alpha(if (item.checked) 0.6f else 1f)
    ) {
        if (photo != null) {
            AsyncImage(
                model = vm.attachmentSource(photo),
                contentDescription = item.text,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(Faces.of(item.text), style = MaterialTheme.typography.displaySmall)
            }
        }
        Text(
            if (item.quantity.isBlank()) item.text else "${item.text} · ${item.quantity}",
            style = MaterialTheme.typography.labelSmall,
            color = Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Paper.copy(alpha = 0.92f))
                .padding(horizontal = 6.dp, vertical = 4.dp)
        )
        if (item.checked) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(30.dp)
                    .background(Mint, CircleShape)
                    .border(2.dp, Ink, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("✓", style = MaterialTheme.typography.titleMedium.copy(fontFamily = PosterFont), color = Ink)
            }
        }
    }
}

@Composable
private fun ItemRow(
    vm: MainViewModel,
    item: ShoppingItem,
    currency: String,
    photo: com.payandplan.app.data.Attachment?,
    onEdit: () -> Unit
) {
    val context = LocalContext.current
    val pendingShot = remember { arrayOfNulls<java.io.File>(1) }

    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pendingShot[0]
        if (ok && file != null && file.exists()) vm.attachItemPhoto(item.id, file)
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val file = FileStore.newCameraFile(context)
            pendingShot[0] = file
            takePhoto.launch(FileStore.uriFor(context, file))
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.attachPhotoFromUri(item.id, uri)
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(
            checked = item.checked,
            onCheckedChange = { vm.updateItem(item.copy(checked = it)) },
            colors = CheckboxDefaults.colors(checkedColor = Mint, checkmarkColor = Ink, uncheckedColor = Ink)
        )
        if (photo != null) {
            AsyncImage(
                model = vm.attachmentSource(photo),
                contentDescription = item.text,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(2.dp, Ink, RoundedCornerShape(10.dp))
                    .clickable { openAttachment(context, photo, vm.attachmentSource(photo) as? String ?: "") }
            )
            Box(Modifier.size(8.dp))
        } else {
            // bread, cold cuts, whatever comes from the counter: a face instead of nothing
            Box(
                Modifier
                    .size(42.dp)
                    .background(Paper, RoundedCornerShape(10.dp))
                    .border(2.dp, Ink, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(Faces.of(item.text), style = MaterialTheme.typography.titleMedium)
            }
            Box(Modifier.size(8.dp))
        }
        Text(
            if (item.quantity.isBlank()) item.text else "${item.text} · ${item.quantity}",
            style = MaterialTheme.typography.bodyLarge.copy(
                textDecoration = if (item.checked) TextDecoration.LineThrough else null
            ),
            color = Ink,
            // tap the name to rename it, or to say how much of it is needed
            modifier = Modifier
                .weight(1f)
                .clickable { onEdit() }
        )
        ComicIconButton(
            Icons.Filled.PhotoCamera,
            {
                if (photo == null) askCamera.launch(android.Manifest.permission.CAMERA)
                else pickPhoto.launch(arrayOf("image/*"))
            },
            color = if (photo == null) Paper else Mint,
            size = 30.dp,
            contentDescription = "Photo of the product"
        )
        Box(Modifier.size(80.dp, 56.dp)) {
            CashField(
                key = item.id,
                cents = item.priceCents,
                onCents = { vm.updateItem(item.copy(priceCents = it)) },
                label = currency,
                modifier = Modifier.fillMaxWidth()
            )
        }
        ComicIconButton(
            Icons.Filled.Close, { vm.deleteItem(item) },
            color = Paper, size = 30.dp, contentDescription = "Remove"
        )
    }
}

@Composable
fun ListEditScreen(
    vm: MainViewModel,
    listId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val existing by (if (listId != null) vm.shoppingList(listId) else kotlinx.coroutines.flow.flowOf(null))
        .collectAsState(initial = null)

    var loaded by remember { mutableStateOf(listId == null) }
    var title by remember { mutableStateOf("") }
    var assignee by remember { mutableStateOf<String?>(vm.myUserId()) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var timeMinutes by remember { mutableStateOf(18 * 60) }
    var budget by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(existing?.id) {
        val e = existing
        if (e != null && !loaded) {
            title = e.title
            assignee = e.assignedToUserId
            date = e.dueDate?.let { LocalDate.ofEpochDay(it) } ?: LocalDate.now()
            timeMinutes = e.dueTimeMinutes
            budget = e.budgetCents?.let { Format.centsToInput(it) } ?: ""
            isPrivate = e.visibility == Visibility.PRIVATE
            loaded = true
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ComicIconButton(Icons.Filled.ArrowBack, onBack, color = Yellow, size = 42.dp, contentDescription = "Back")
                Box(Modifier.size(10.dp))
                Text(
                    if (listId == null) "NEW LIST" else "EDIT LIST",
                    style = MaterialTheme.typography.displaySmall.copy(fontFamily = PosterFont),
                    color = Ink
                )
            }
        }

        item {
            ComicCard(color = Paper, modifier = Modifier.fillMaxWidth()) {
                ComicField(title, { title = it }, "Title (weekly shop, pharmacy...)", Modifier.fillMaxWidth())
                Box(Modifier.height(10.dp))
                Text("WHO DOES IT", style = MaterialTheme.typography.labelMedium, color = Ink)
                Box(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.members().chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { m ->
                                ComicChip(
                                    text = m.name + if (m.id == vm.myUserId()) " (you)" else "",
                                    selected = assignee == m.id,
                                    onClick = { assignee = m.id },
                                    color = Mint
                                )
                            }
                        }
                    }
                    ComicChip("Anyone", assignee == null, { assignee = null }, color = Sky)
                }
                Box(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ComicButton(Format.day(date), {
                        DatePickerDialog(
                            context,
                            { _, y, m, d -> date = LocalDate.of(y, m + 1, d) },
                            date.year, date.monthValue - 1, date.dayOfMonth
                        ).show()
                    }, color = Yellow, compact = true)
                    ComicButton(Format.time(timeMinutes), {
                        TimePickerDialog(
                            context,
                            { _, h, min -> timeMinutes = h * 60 + min },
                            timeMinutes / 60, timeMinutes % 60, true
                        ).show()
                    }, color = Yellow, compact = true)
                }
                Box(Modifier.height(10.dp))
                ComicField(
                    budget, { budget = it }, "Budget (optional)",
                    Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Box(Modifier.height(6.dp))
                ComicChip(
                    if (isPrivate) "🔒 Private" else "👥 Shared",
                    selected = true,
                    onClick = { isPrivate = !isPrivate },
                    color = if (isPrivate) Coral else Mint
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ComicButton(
                    text = if (listId == null) "CREATE" else "SAVE",
                    onClick = {
                        val base = existing ?: ShoppingList()
                        vm.saveList(
                            base.copy(
                                title = title.trim(),
                                assignedToUserId = assignee,
                                dueDate = date.toEpochDay(),
                                dueTimeMinutes = timeMinutes,
                                budgetCents = Format.parseAmountToCents(budget),
                                visibility = if (isPrivate) Visibility.PRIVATE else Visibility.SHARED
                            )
                        ) { onSaved() }
                    },
                    color = Mint,
                    enabled = title.isNotBlank()
                )
                ComicButton("CANCEL", onBack, color = Paper)
            }
        }
    }
}
