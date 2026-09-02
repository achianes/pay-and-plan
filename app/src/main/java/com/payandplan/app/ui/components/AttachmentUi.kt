package com.payandplan.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.payandplan.app.data.Attachment
import com.payandplan.app.ui.theme.Ink
import com.payandplan.app.ui.theme.Mint
import com.payandplan.app.ui.theme.Paper
import com.payandplan.app.ui.theme.Sky
import com.payandplan.app.ui.theme.Yellow
import java.io.File

/** Opens the local copy when there is one, otherwise hands the server URL to the system. */
fun openAttachment(context: Context, attachment: Attachment, remoteUrl: String) {
    val local = attachment.localPath?.let { File(it) }?.takeIf { it.exists() }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (local != null) {
            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", local)
            setDataAndType(uri, attachment.mime.ifBlank { "*/*" })
        } else {
            data = Uri.parse(remoteUrl)
        }
    }
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show() }
}

@Composable
fun AttachmentTile(
    attachment: Attachment,
    model: Any,
    onOpen: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val isImage = attachment.mime.startsWith("image/")
    Box(Modifier.width(112.dp)) {
        Column(
            modifier = Modifier
                .comicSurface(if (attachment.isReceipt) Mint else Paper, radius = 16.dp, shadow = 4.dp)
                .clickable(onClick = onOpen)
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Paper)
                    .border(2.dp, Ink, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (isImage) {
                    AsyncImage(
                        model = model,
                        contentDescription = attachment.fileName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    Icon(
                        if (attachment.mime.contains("pdf")) Icons.Filled.PictureAsPdf else Icons.Filled.Description,
                        contentDescription = null,
                        tint = Ink,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
            Text(
                attachment.fileName,
                style = MaterialTheme.typography.labelSmall,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp)
            )
            if (attachment.isReceipt) {
                Text("RECEIPT", style = MaterialTheme.typography.labelSmall, color = Ink)
            }
        }
        if (onDelete != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(26.dp)
                    .background(Yellow, RoundedCornerShape(13.dp))
                    .border(2.dp, Ink, RoundedCornerShape(13.dp))
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Remove", tint = Ink, modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable
fun AttachmentStrip(
    attachments: List<Attachment>,
    sourceOf: (Attachment) -> Any,
    onDelete: (Attachment) -> Unit,
    emptyText: String = "Nothing attached yet"
) {
    val context = LocalContext.current
    if (attachments.isEmpty()) {
        Text(emptyText, style = MaterialTheme.typography.bodyMedium, color = Ink)
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(attachments, key = { it.id }) { a ->
            val model = sourceOf(a)
            AttachmentTile(
                attachment = a,
                model = model,
                onOpen = { openAttachment(context, a, model as? String ?: "") },
                onDelete = { onDelete(a) }
            )
        }
    }
}

@Composable
fun AttachButtons(
    onPicked: (Uri) -> Unit,
    onCaptured: (File) -> Unit,
    fileLabel: String = "Attach file",
    cameraLabel: String = "Snap photo",
    color: androidx.compose.ui.graphics.Color = Sky
) {
    val context = LocalContext.current
    val pendingPhoto = remember { arrayOfNulls<File>(1) }

    val pickDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            onPicked(uri)
        }
    }

    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val file = pendingPhoto[0]
        if (ok && file != null && file.exists()) onCaptured(file)
    }

    val askCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val file = com.payandplan.app.util.FileStore.newCameraFile(context)
            pendingPhoto[0] = file
            takePhoto.launch(com.payandplan.app.util.FileStore.uriFor(context, file))
        } else {
            Toast.makeText(context, "Camera permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ComicButton(
            text = fileLabel,
            icon = Icons.Filled.AttachFile,
            color = color,
            compact = true,
            onClick = { pickDoc.launch(arrayOf("*/*")) }
        )
        ComicButton(
            text = cameraLabel,
            icon = Icons.Filled.PhotoCamera,
            color = Yellow,
            compact = true,
            onClick = { askCamera.launch(android.Manifest.permission.CAMERA) }
        )
    }
}
