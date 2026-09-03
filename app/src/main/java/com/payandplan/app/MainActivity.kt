package com.payandplan.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.core.content.IntentCompat
import com.payandplan.app.ui.MainViewModel
import com.payandplan.app.util.CalendarEvent
import com.payandplan.app.util.GoogleCalendarShare
import com.payandplan.app.util.IcsParser
import com.payandplan.app.ui.components.ComicIconButton
import com.payandplan.app.ui.screens.CalendarScreen
import com.payandplan.app.ui.screens.DayScreen
import com.payandplan.app.ui.screens.EditPaymentScreen
import com.payandplan.app.ui.screens.ListDetailScreen
import com.payandplan.app.ui.screens.ListEditScreen
import com.payandplan.app.ui.screens.ListsScreen
import com.payandplan.app.ui.screens.LoginScreen
import com.payandplan.app.ui.screens.NoteEditScreen
import com.payandplan.app.ui.screens.NotesScreen
import com.payandplan.app.ui.screens.PaymentDetailScreen
import com.payandplan.app.ui.screens.PaymentsScreen
import com.payandplan.app.ui.screens.SettingsScreen
import com.payandplan.app.ui.theme.Aqua
import com.payandplan.app.ui.theme.Cream
import com.payandplan.app.ui.theme.Grape
import com.payandplan.app.ui.theme.Ink
import com.payandplan.app.ui.theme.Mint
import com.payandplan.app.ui.theme.Paper
import com.payandplan.app.ui.theme.PayPlanTheme
import com.payandplan.app.ui.theme.Sky
import com.payandplan.app.ui.theme.Yellow
import java.time.LocalDate

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_PAYMENT = "open_payment"
        const val EXTRA_ASK_RECEIPT = "ask_receipt"
    }

    private var pendingPayment by mutableStateOf<Pair<String, Boolean>?>(null)
    private var pendingShare by mutableStateOf<SharedContent?>(null)
    private var pendingEvent by mutableStateOf<CalendarEvent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readIntent(intent)
        setContent {
            PayPlanTheme {
                Root(
                    pending = pendingPayment,
                    onPendingConsumed = { pendingPayment = null },
                    share = pendingShare,
                    onShareConsumed = { pendingShare = null },
                    event = pendingEvent,
                    onEventConsumed = { pendingEvent = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_OPEN_PAYMENT)
        if (!id.isNullOrBlank()) {
            pendingPayment = id to (intent.getBooleanExtra(EXTRA_ASK_RECEIPT, false))
        }
        readShare(intent)
    }

    /** Text, links and files handed over by another app through the share sheet. */
    private fun readShare(intent: Intent?) {
        if (intent == null) return
        val uris = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    .orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> return
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()

        // something handed over by a calendar app belongs in the calendar, not in a note
        val event = readCalendarEvent(intent.type, text, uris)
        if (event != null) {
            pendingEvent = event
            return
        }

        if (uris.isEmpty() && text.isBlank() && subject.isBlank()) return

        // a link shared on its own makes a better title than an empty note
        val title = subject.ifBlank {
            if (uris.isEmpty() && text.length <= 60) text else ""
        }
        pendingShare = SharedContent(title = title, body = text, uris = uris)
    }

    /** Reads an .ics out of the shared text or file, when there is one. */
    private fun readCalendarEvent(mime: String?, text: String, uris: List<Uri>): CalendarEvent? {
        if (IcsParser.looksLikeCalendar(text, mime)) {
            IcsParser.parse(text)?.let { return it }
        }
        // Google Calendar shares a few lines of text and a link rather than an .ics
        if (GoogleCalendarShare.looksLikeGoogleCalendar(text)) {
            GoogleCalendarShare.parse(text)?.let { return it }
        }
        val looksLikeFile = mime != null &&
            (mime.startsWith("text/calendar") || mime.contains("ics") || mime.contains("vcalendar"))
        if (!looksLikeFile) return null

        for (uri in uris) {
            val body = runCatching {
                contentResolver.openInputStream(uri)?.use { stream ->
                    // events are small; anything larger is not an invitation
                    String(stream.readNBytes(256 * 1024))
                }
            }.getOrNull() ?: continue
            IcsParser.parse(body)?.let { return it }
        }
        return null
    }
}

/** What another app shared with us before the UI had a chance to look at it. */
data class SharedContent(val title: String, val body: String, val uris: List<android.net.Uri>)

private sealed class Tab(val route: String, val label: String, val icon: ImageVector, val color: Color) {
    data object Calendar : Tab("calendar", "Calendar", Icons.Filled.CalendarMonth, Yellow)
    data object Bills : Tab("bills", "Bills", Icons.Filled.ReceiptLong, Sky)
    data object Lists : Tab("lists", "Shopping", Icons.Filled.ShoppingCart, Aqua)
    data object Notes : Tab("notes", "Notes", Icons.Filled.EditNote, Grape)
    data object Settings : Tab("settings", "Setup", Icons.Filled.Settings, Mint)
}

@Composable
private fun Root(
    pending: Pair<String, Boolean>?,
    onPendingConsumed: () -> Unit,
    share: SharedContent? = null,
    onShareConsumed: () -> Unit = {},
    event: CalendarEvent? = null,
    onEventConsumed: () -> Unit = {}
) {
    val vm: MainViewModel = viewModel()
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val signedIn by vm.signedIn.collectAsState()
    val tabs = listOf(Tab.Calendar, Tab.Bills, Tab.Lists, Tab.Notes, Tab.Settings)
    val onMainTab = tabs.any { it.route == route }

    if (Build.VERSION.SDK_INT >= 33) {
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        LaunchedEffect(Unit) { ask.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    LaunchedEffect(pending) {
        val p = pending ?: return@LaunchedEffect
        nav.navigate("payment/${p.first}?receipt=${p.second}")
        onPendingConsumed()
    }

    LaunchedEffect(share) {
        val s = share ?: return@LaunchedEffect
        vm.offerShare(s.title, s.body, s.uris)
        nav.navigate("noteEdit?id=")
        onShareConsumed()
    }

    LaunchedEffect(event) {
        val e = event ?: return@LaunchedEffect
        vm.offerEvent(e)
        nav.navigate("edit?id=&day=${e.epochDay}")
        onEventConsumed()
    }

    if (!signedIn) {
        LoginScreen(vm = vm, onSignedIn = { vm.refreshAccount() })
        return
    }

    Scaffold(
        containerColor = Cream,
        bottomBar = {
            if (onMainTab) {
                ComicBottomBar(
                    tabs = tabs,
                    current = route,
                    onSelect = { tab ->
                        nav.navigate(tab.route) {
                            popUpTo(Tab.Calendar.route) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            when (route) {
                Tab.Calendar.route, Tab.Bills.route -> Box(Modifier.padding(bottom = 6.dp)) {
                    ComicIconButton(
                        icon = Icons.Filled.Add,
                        onClick = { nav.navigate("edit?id=&day=${vm.selectedDay.value.toEpochDay()}") },
                        color = Mint,
                        size = 62.dp,
                        contentDescription = "New entry"
                    )
                }
                Tab.Lists.route -> Box(Modifier.padding(bottom = 6.dp)) {
                    ComicIconButton(
                        icon = Icons.Filled.Add,
                        onClick = { nav.navigate("listEdit?id=") },
                        color = Mint,
                        size = 62.dp,
                        contentDescription = "New list"
                    )
                }
                Tab.Notes.route -> Box(Modifier.padding(bottom = 6.dp)) {
                    ComicIconButton(
                        icon = Icons.Filled.Add,
                        onClick = { nav.navigate("noteEdit?id=") },
                        color = Mint,
                        size = 62.dp,
                        contentDescription = "New note"
                    )
                }
                else -> {}
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
        ) {
            NavHost(navController = nav, startDestination = Tab.Calendar.route) {

                composable(Tab.Calendar.route) {
                    CalendarScreen(
                        vm = vm,
                        onOpenDay = { day -> nav.navigate("day/${day.toEpochDay()}") },
                        onOpenPayment = { id -> nav.navigate("payment/$id?receipt=false") }
                    )
                }

                composable(Tab.Bills.route) {
                    PaymentsScreen(vm = vm, onOpenPayment = { id -> nav.navigate("payment/$id?receipt=false") })
                }

                composable(Tab.Lists.route) {
                    ListsScreen(vm = vm, onOpenList = { id -> nav.navigate("list/$id") })
                }

                composable(Tab.Notes.route) {
                    NotesScreen(vm = vm, onOpenNote = { id -> nav.navigate("noteEdit?id=$id") })
                }

                composable(
                    route = "noteEdit?id={id}",
                    arguments = listOf(navArgument("id") { type = NavType.StringType; defaultValue = "" })
                ) { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    NoteEditScreen(
                        vm = vm,
                        noteId = id.ifBlank { null },
                        onBack = { nav.popBackStack() }
                    )
                }

                composable(Tab.Settings.route) {
                    SettingsScreen(vm) { nav.navigate(Tab.Calendar.route) { popUpTo(0) } }
                }

                composable(
                    route = "day/{day}",
                    arguments = listOf(navArgument("day") { type = NavType.LongType })
                ) { entry ->
                    val day = LocalDate.ofEpochDay(entry.arguments?.getLong("day") ?: LocalDate.now().toEpochDay())
                    DayScreen(
                        vm = vm,
                        day = day,
                        onBack = { nav.popBackStack() },
                        onOpenPayment = { id -> nav.navigate("payment/$id?receipt=false") },
                        onAddPayment = { d -> nav.navigate("edit?id=&day=${d.toEpochDay()}") }
                    )
                }

                composable(
                    route = "payment/{id}?receipt={receipt}",
                    arguments = listOf(
                        navArgument("id") { type = NavType.StringType },
                        navArgument("receipt") { type = NavType.BoolType; defaultValue = false }
                    )
                ) { entry ->
                    PaymentDetailScreen(
                        vm = vm,
                        paymentId = entry.arguments?.getString("id").orEmpty(),
                        askReceipt = entry.arguments?.getBoolean("receipt") ?: false,
                        onBack = { nav.popBackStack() },
                        onEdit = { pid -> nav.navigate("edit?id=$pid&day=-1") }
                    )
                }

                composable(
                    route = "edit?id={id}&day={day}",
                    arguments = listOf(
                        navArgument("id") { type = NavType.StringType; defaultValue = "" },
                        navArgument("day") { type = NavType.LongType; defaultValue = -1L }
                    )
                ) { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    val day = entry.arguments?.getLong("day") ?: -1L
                    EditPaymentScreen(
                        vm = vm,
                        paymentId = id.ifBlank { null },
                        presetDay = if (day > 0) LocalDate.ofEpochDay(day) else null,
                        onBack = { nav.popBackStack() },
                        onSaved = { nav.popBackStack() }
                    )
                }

                composable(
                    route = "list/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.StringType })
                ) { entry ->
                    ListDetailScreen(
                        vm = vm,
                        listId = entry.arguments?.getString("id").orEmpty(),
                        onBack = { nav.popBackStack() },
                        onEdit = { id -> nav.navigate("listEdit?id=$id") }
                    )
                }

                composable(
                    route = "listEdit?id={id}",
                    arguments = listOf(navArgument("id") { type = NavType.StringType; defaultValue = "" })
                ) { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    ListEditScreen(
                        vm = vm,
                        listId = id.ifBlank { null },
                        onBack = { nav.popBackStack() },
                        onSaved = { nav.popBackStack() }
                    )
                }
            }
        }
    }
}

@Composable
private fun ComicBottomBar(
    tabs: List<Tab>,
    current: String?,
    onSelect: (Tab) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .background(Paper, RoundedCornerShape(22.dp))
            .border(3.dp, Ink, RoundedCornerShape(22.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEach { tab ->
            val selected = current == tab.route
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable { onSelect(tab) }
                    .background(if (selected) tab.color else Color.Transparent, RoundedCornerShape(16.dp))
                    .then(
                        if (selected) Modifier.border(3.dp, Ink, RoundedCornerShape(16.dp)) else Modifier
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(tab.icon, contentDescription = tab.label, tint = Ink, modifier = Modifier.size(22.dp))
                Text(tab.label, style = MaterialTheme.typography.labelSmall, color = Ink)
            }
        }
    }
}
