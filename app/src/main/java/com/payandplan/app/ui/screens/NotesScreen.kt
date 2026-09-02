package com.payandplan.app.ui.screens

import android.content.Intent
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.payandplan.app.data.Note
import com.payandplan.app.data.Visibility
import com.payandplan.app.ui.MainViewModel
import com.payandplan.app.ui.components.AttachmentStrip
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
import com.payandplan.app.ui.theme.Sky
import com.payandplan.app.ui.theme.StickerColors
import com.payandplan.app.ui.theme.Yellow
import com.payandplan.app.ui.theme.stickerColor

/** The categories offered out of the box; anything typed by hand joins the list. */
private val DEFAULT_CATEGORIES =
    listOf("Recipes", "Prompts", "Thoughts", "Articles", "Links", "Photos", "Other")

@Composable
fun NotesScreen(
    vm: MainViewModel,
    onOpenNote: (String) -> Unit
) {
    val notes by vm.notes.collectAsState()
    var category by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }

    val categories = (DEFAULT_CATEGORIES + notes.map { it.category }.filter { it.isNotBlank() })
        .distinct()
    val counts = notes.groupingBy { it.category.ifBlank { "Other" } }.eachCount()

    val rows = notes
        .filter { category.isBlank() || it.category == category }
        .filter {
            search.isBlank() ||
                it.title.contains(search, true) || it.body.contains(search, true)
        }

    LazyColumn(
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 170.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { PosterTitle("NOTES") }
        item {
            Text(
                "Anything without a date: recipes, prompts, thoughts, links, photos, voice memos. " +
                    "Shared with the calendar unless you mark it private.",
                style = MaterialTheme.typography.bodySmall,
                color = Ink
            )
        }
        item {
            ComicField(search, { search = it }, "Search", Modifier.fillMaxWidth())
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf("") + categories).chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { c ->
                            ComicChip(
                                text = if (c.isBlank()) "All (${notes.size})"
                                else c + (counts[c]?.let { " $it" } ?: ""),
                                selected = category == c,
                                onClick = { category = if (category == c) "" else c },
                                color = if (c.isBlank()) Yellow else Grape
                            )
                        }
                    }
                }
            }
        }

        if (rows.isEmpty()) {
            item {
                SpeechBubble(
                    if (search.isNotBlank() || category.isNotBlank()) "Nothing here."
                    else "No notes yet. Tap + to write one.",
                    Modifier.fillMaxWidth(), Yellow, "📝"
                )
            }
        } else {
            items(rows, key = { it.id }) { note -> NoteCard(vm, note, onOpenNote) }
        }
    }
}

@Composable
private fun NoteCard(vm: MainViewModel, note: Note, onOpen: (String) -> Unit) {
    val files by vm.noteAttachments(note.id).collectAsState(initial = emptyList())
    val owner = vm.memberById(note.ownerUserId)
    ComicCard(
        color = stickerColor(note.colorIndex),
        modifier = Modifier.fillMaxWidth(),
        onClick = { onOpen(note.id) }
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (note.pinned) "📌 " else "") + note.title.ifBlank { "Untitled" },
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (note.category.isNotBlank()) {
                Text(note.category, style = MaterialTheme.typography.bodySmall, color = Ink)
            }
        }
        if (note.body.isNotBlank()) {
            Text(
                note.body,
                style = MaterialTheme.typography.bodySmall,
                color = Ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            buildString {
                if (files.isNotEmpty()) append("📎 ${files.size} · ")
                owner?.let { append("for ${it.name} · ") }
                append(if (note.isPrivate) "🔒 private" else "shared")
            },
            style = MaterialTheme.typography.bodySmall,
            color = Ink
        )
    }
}

@Composable
fun NoteEditScreen(
    vm: MainViewModel,
    noteId: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val flow = remember(noteId) {
        if (noteId != null) vm.note(noteId) else kotlinx.coroutines.flow.flowOf<Note?>(null)
    }
    val existing by flow.collectAsState(initial = null)
    val files by (if (noteId != null) vm.noteAttachments(noteId)
    else kotlinx.coroutines.flow.flowOf(emptyList()))
        .collectAsState(initial = emptyList())

    var loaded by remember { mutableStateOf(noteId == null) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var colorIndex by remember { mutableStateOf(4) }
    var pinned by remember { mutableStateOf(false) }
    var isPrivate by remember { mutableStateOf(false) }
    var ownerId by remember { mutableStateOf<String?>(null) }
    val usedCategories by vm.noteCategories.collectAsState()
    var reading by remember { mutableStateOf(false) }
    var readResult by remember { mutableStateOf<String?>(null) }

    // something arrived from another app's share sheet: pre-fill and keep the files aside
    val shared by vm.sharedPayload.collectAsState()
    var pendingUris by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    LaunchedEffect(shared) {
        val s = shared ?: return@LaunchedEffect
        if (noteId == null) {
            if (title.isBlank()) title = s.title
            if (body.isBlank()) body = s.body
            pendingUris = s.uris
            loaded = true
        }
        vm.consumeShare()
    }

    LaunchedEffect(existing?.id) {
        val e = existing
        if (e != null && !loaded) {
            title = e.title; body = e.body; category = e.category
            colorIndex = e.colorIndex; pinned = e.pinned
            isPrivate = e.isPrivate; ownerId = e.ownerUserId
            loaded = true
        }
    }

    // the note has to exist before a file can hang off it
    fun ensureSaved(then: (String) -> Unit) {
        val base = existing ?: Note(colorIndex = colorIndex)
        val saved = base.copy(
            title = title.trim(), body = body, category = category.trim(),
            colorIndex = colorIndex, pinned = pinned, ownerUserId = ownerId,
            visibility = if (isPrivate) Visibility.PRIVATE else Visibility.SHARED
        )
        vm.saveNote(saved) {
            if (pendingUris.isNotEmpty()) {
                vm.attachSharedFiles(saved.id, pendingUris)
                pendingUris = emptyList()
            }
            then(saved.id)
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) ensureSaved { id -> vm.attachToNote(id, uri) }
    }
    val recordAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.data?.let { uri -> ensureSaved { id -> vm.attachToNote(id, uri) } }
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
                    if (noteId == null) "NEW NOTE" else "EDIT NOTE",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Ink
                )
            }
        }

        item {
            ComicCard(color = Paper, modifier = Modifier.fillMaxWidth()) {
                ComicField(title, { title = it }, "Title", Modifier.fillMaxWidth())
                Box(Modifier.height(10.dp))
                ComicField(category, { category = it }, "Category", Modifier.fillMaxWidth())
                Box(Modifier.height(6.dp))
                // the standard ones plus anything already used on other notes
                val offered = (DEFAULT_CATEGORIES + usedCategories).distinct()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    offered.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { c ->
                                ComicChip(
                                    text = c,
                                    selected = category.equals(c, ignoreCase = true),
                                    onClick = { category = if (category.equals(c, true)) "" else c },
                                    color = if (c in DEFAULT_CATEGORIES) Grape else Sky
                                )
                            }
                        }
                    }
                }
                Box(Modifier.height(10.dp))
                ComicField(body, { body = it }, "Note", Modifier.fillMaxWidth(), singleLine = false, minLines = 6)
            }
        }

        if (vm.members().isNotEmpty()) {
            item {
                ComicCard(color = Sky, modifier = Modifier.fillMaxWidth()) {
                    Text("👤 FOR", style = MaterialTheme.typography.headlineSmall, color = Ink)
                    Box(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ComicChip("Everyone", ownerId == null, { ownerId = null }, color = Mint)
                        }
                        vm.members().chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { m ->
                                    ComicChip(
                                        m.name + if (m.id == vm.myUserId()) " (you)" else "",
                                        ownerId == m.id,
                                        { ownerId = m.id },
                                        color = stickerColor(m.colorIndex)
                                    )
                                }
                            }
                        }
                    }
                    Box(Modifier.height(10.dp))
                    ToggleLine("📌 Pin to the top", pinned) { pinned = it }
                    ToggleLine(
                        if (isPrivate) "🔒 Private: only you see it" else "👥 Shared with the calendar",
                        isPrivate
                    ) { isPrivate = it }
                }
            }
        }

        item {
            ComicCard(color = Paper, modifier = Modifier.fillMaxWidth()) {
                Text("🎨 COLOUR", style = MaterialTheme.typography.labelMedium, color = Ink)
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

        item {
            ComicCard(color = Paper, modifier = Modifier.fillMaxWidth()) {
                Text("📎 ATTACHED", style = MaterialTheme.typography.headlineSmall, color = Ink)
                Text(
                    "Photos, video, audio, documents: anything.",
                    style = MaterialTheme.typography.bodySmall, color = Ink
                )
                Box(Modifier.height(10.dp))
                AttachmentStrip(
                    attachments = files,
                    sourceOf = { vm.attachmentSource(it) },
                    onDelete = { vm.removeAttachment(it) },
                    emptyText = "Nothing attached yet."
                )
                if (pendingUris.isNotEmpty()) {
                    Text(
                        "${pendingUris.size} shared file(s) will be attached when you save.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink
                    )
                }
                Box(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ComicButton(
                        "File", { pickFile.launch(arrayOf("*/*")) },
                        icon = Icons.Filled.AttachFile, color = Grape, compact = true
                    )
                    ComicButton(
                        "Photo", { pickFile.launch(arrayOf("image/*", "video/*")) },
                        icon = Icons.Filled.PhotoCamera, color = Sky, compact = true
                    )
                    ComicButton(
                        "Record", {
                            // the system recorder hands back a normal audio file
                            runCatching {
                                recordAudio.launch(Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION))
                            }
                        },
                        icon = Icons.Filled.Mic, color = Coral, compact = true
                    )
                }
                Box(Modifier.height(10.dp))
                ComicButton(
                    if (reading) "READING..." else "🔗 READ THE LINK",
                    {
                        reading = true
                        val base = existing ?: Note(colorIndex = colorIndex)
                        vm.readLinkInto(
                            base.copy(
                                title = title.trim(), body = body, category = category.trim(),
                                colorIndex = colorIndex, pinned = pinned, ownerUserId = ownerId,
                                visibility = if (isPrivate) Visibility.PRIVATE else Visibility.SHARED
                            )
                        ) { images ->
                            reading = false
                            readResult = when {
                                images == null -> "No link found, or the page would not open."
                                images > 0 -> "Read it, $images image(s) saved."
                                else -> "Read it."
                            }
                        }
                    },
                    color = Yellow, compact = true, enabled = !reading
                )
                Text(
                    readResult
                        ?: "A shared chat or article: the server reads the page and puts the text " +
                        "and the pictures in here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ComicButton(
                    text = if (noteId == null) "CREATE" else "SAVE",
                    onClick = { ensureSaved { onBack() } },
                    color = Mint,
                    enabled = title.isNotBlank() || body.isNotBlank()
                )
                existing?.let { note ->
                    ComicButton("DELETE", { vm.deleteNote(note) { onBack() } }, color = Coral)
                }
                ComicButton("CANCEL", onBack, color = Paper)
            }
        }
    }
}

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
