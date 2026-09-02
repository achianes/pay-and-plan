package com.payandplan.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.payandplan.app.ui.MainViewModel
import com.payandplan.app.ui.components.ComicButton
import com.payandplan.app.ui.components.ComicCard
import com.payandplan.app.ui.components.ComicField
import com.payandplan.app.ui.components.PosterTitle
import com.payandplan.app.ui.theme.Coral
import com.payandplan.app.ui.theme.Ink
import com.payandplan.app.ui.theme.Mint
import com.payandplan.app.ui.theme.Paper
import com.payandplan.app.ui.theme.Yellow

@Composable
fun LoginScreen(vm: MainViewModel, onSignedIn: () -> Unit) {
    var email by remember { mutableStateOf(vm.prefs.userEmail) }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(vm.serverUrl()) }
    var signUp by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showServer by remember { mutableStateOf(false) }

    fun submit() {
        error = null
        busy = true
        vm.setServerUrl(server)
        val done: (String?) -> Unit = { message ->
            busy = false
            if (message == null) onSignedIn() else error = message
        }
        if (signUp) vm.register(email.trim(), password, name.trim().ifBlank { email.substringBefore('@') }, done)
        else vm.login(email.trim(), password, done)
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 40.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            PosterTitle("PAY & PLAN", Modifier.fillMaxWidth())
            Text(
                "Bills, receipts and shopping, shared with the people you live with.",
                style = MaterialTheme.typography.bodyMedium,
                color = Ink
            )
        }

        item {
            ComicCard(color = Paper, modifier = Modifier.fillMaxWidth()) {
                error?.let {
                    ComicCard(color = Coral, modifier = Modifier.fillMaxWidth(), shadow = 0.dp) {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = Ink)
                    }
                    Box(Modifier.height(10.dp))
                }
                ComicField(
                    email, { email = it }, "Email", Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email, imeAction = ImeAction.Next
                    )
                )
                Box(Modifier.height(8.dp))
                ComicField(
                    password, { password = it }, "Password", Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation()
                )
                if (signUp) {
                    Box(Modifier.height(8.dp))
                    ComicField(name, { name = it }, "Your name", Modifier.fillMaxWidth())
                }
                Box(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ComicButton(
                        text = if (busy) "..." else if (signUp) "CREATE ACCOUNT" else "LOG IN",
                        onClick = { if (!busy) submit() },
                        color = Mint,
                        enabled = !busy && email.isNotBlank() && password.length >= 6
                    )
                    ComicButton(
                        text = if (signUp) "I HAVE ONE" else "SIGN UP",
                        onClick = { signUp = !signUp; error = null },
                        color = Yellow,
                        compact = true
                    )
                }
            }
        }

        item {
            ComicCard(color = Paper, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⚙️ SERVER", style = MaterialTheme.typography.headlineSmall, color = Ink, modifier = Modifier.weight(1f))
                    ComicButton(
                        text = if (showServer) "Hide" else "Change",
                        onClick = { showServer = !showServer },
                        color = Paper,
                        compact = true
                    )
                }
                if (showServer) {
                    Box(Modifier.height(8.dp))
                    ComicField(server, { server = it }, "Server address", Modifier.fillMaxWidth())
                } else {
                    Text(server, style = MaterialTheme.typography.bodySmall, color = Ink)
                }
            }
        }

        item {
            Text(
                "Your data lives on your own server. The same account works in the web app,\n" +
                    "so an iPhone can join the very same calendar.",
                style = MaterialTheme.typography.bodySmall,
                color = Ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
