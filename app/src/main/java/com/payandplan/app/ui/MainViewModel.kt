package com.payandplan.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.payandplan.app.PayPlanApp
import com.payandplan.app.data.Attachment
import com.payandplan.app.data.DayStat
import com.payandplan.app.data.Note
import com.payandplan.app.data.Payment
import com.payandplan.app.data.ShoppingItem
import com.payandplan.app.data.ShoppingList
import com.payandplan.app.util.CalendarEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = PayPlanApp.repository(app)
    val prefs = repo.prefs

    val calendars = repo.calendars
    val activeCalendarId = repo.calendarId
    val syncing = repo.syncing
    val syncError = repo.lastError

    private val _signedIn = MutableStateFlow(repo.api.isLoggedIn)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    private val _selectedDay = MutableStateFlow(LocalDate.now())
    val selectedDay: StateFlow<LocalDate> = _selectedDay.asStateFlow()

    private fun windowOf(m: YearMonth): Pair<LocalDate, LocalDate> =
        m.atDay(1).minusDays(10) to m.atEndOfMonth().plusDays(10)

    val monthPayments: StateFlow<List<Payment>> =
        combine(_month, activeCalendarId) { m, _ -> m }
            .flatMapLatest { m -> val (a, b) = windowOf(m); repo.observeBetween(a, b) }
            .map { repo.visible(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val dayStats: StateFlow<Map<Long, DayStat>> = monthPayments
        .map { rows ->
            rows.groupBy { it.dueDate }.mapValues { (day, list) ->
                DayStat(
                    dueDate = day,
                    total = list.sumOf { it.amountCents },
                    openCount = list.count { it.isOpen },
                    paidCount = list.count { !it.isOpen },
                    colorIndex = list.firstOrNull()?.colorIndex ?: 0
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val daysWithFiles: StateFlow<Set<Long>> =
        combine(_month, activeCalendarId) { m, _ -> m }
            .flatMapLatest { m -> val (a, b) = windowOf(m); repo.observeDaysWithFiles(a, b) }
            .map { it.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val daysWithNotes: StateFlow<Set<Long>> =
        combine(_month, activeCalendarId) { m, _ -> m }
            .flatMapLatest { m -> val (a, b) = windowOf(m); repo.observeDaysWithNotes(a, b) }
            .map { it.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val selectedDayPayments: StateFlow<List<Payment>> =
        combine(_selectedDay, activeCalendarId) { d, _ -> d }
            .flatMapLatest { repo.observeForDay(it) }
            .map { repo.visible(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allPayments: StateFlow<List<Payment>> = activeCalendarId
        .flatMapLatest { repo.observeAll() }
        .map { repo.visible(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val shoppingLists: StateFlow<List<ShoppingList>> = activeCalendarId
        .flatMapLatest { repo.observeLists() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val notes: StateFlow<List<Note>> = activeCalendarId
        .flatMapLatest { repo.observeNotes() }
        .map { repo.visibleNotes(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Newest photo of each shopping item, keyed by item id. */
    val itemPhotos: StateFlow<Map<String, Attachment>> = activeCalendarId
        .flatMapLatest { repo.observeItemPhotos() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Categories already used on notes, so they can be picked instead of retyped. */
    val noteCategories: StateFlow<List<String>> = notes
        .map { rows -> rows.map { it.category.trim() }.filter { it.isNotBlank() }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Categories already used on bills, appointments and the rest. */
    val entryCategories: StateFlow<List<String>> = allPayments
        .map { rows -> rows.map { it.category.trim() }.filter { it.isNotBlank() }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** What this household usually buys: everyone's past items, most used first. */
    val itemSuggestions: StateFlow<List<String>> = activeCalendarId
        .flatMapLatest { repo.observeItemSuggestions() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            repo.topUpAll()
            repo.armWindow()
            if (repo.api.isLoggedIn) runCatching { repo.refreshAccount() }
            _receiptsEnabled.value = prefs.receiptsEnabled
        }
    }

    /** Whether the server can read receipts; observable so the card appears as soon as we know. */
    private val _receiptsEnabled = MutableStateFlow(prefs.receiptsEnabled)
    val receiptsEnabled: StateFlow<Boolean> = _receiptsEnabled.asStateFlow()

    // ---- account ----

    fun login(email: String, password: String, onResult: (String?) -> Unit) = viewModelScope.launch {
        val result = repo.login(email, password)
        _signedIn.value = repo.api.isLoggedIn
        onResult(result.exceptionOrNull()?.message)
    }

    fun register(email: String, password: String, name: String, onResult: (String?) -> Unit) =
        viewModelScope.launch {
            val result = repo.register(email, password, name)
            _signedIn.value = repo.api.isLoggedIn
            onResult(result.exceptionOrNull()?.message)
        }

    fun signOut() {
        repo.signOut()
        _signedIn.value = false
    }

    fun setServerUrl(url: String) { prefs.serverUrl = url }
    fun serverUrl(): String = prefs.serverUrl

    fun refreshAccount() = viewModelScope.launch {
        runCatching { repo.refreshAccount() }
        _receiptsEnabled.value = prefs.receiptsEnabled
    }
    fun syncNow() = viewModelScope.launch { repo.sync() }
    fun fullResync() = viewModelScope.launch { repo.fullResync() }
    fun switchCalendar(id: String) = repo.switchCalendar(id)
    fun createCalendar(name: String) = viewModelScope.launch { repo.createCalendar(name) }
    fun joinCalendar(code: String, onResult: (String?) -> Unit = {}) = viewModelScope.launch {
        onResult(repo.joinCalendar(code).exceptionOrNull()?.message)
    }
    fun renameCalendar(name: String, currency: String) = viewModelScope.launch {
        repo.renameCalendar(name, currency)
    }
    fun rotateInvite() = viewModelScope.launch { repo.rotateInvite() }
    fun removeMember(userId: String) = viewModelScope.launch { repo.removeMember(userId) }
    fun updateMyName(name: String) = viewModelScope.launch { repo.updateMyName(name) }
    fun deleteCurrentCalendar(onResult: (String?) -> Unit = {}) = viewModelScope.launch {
        onResult(repo.deleteCurrentCalendar().exceptionOrNull()?.message)
    }
    fun leaveCurrentCalendar(onResult: (String?) -> Unit = {}) = viewModelScope.launch {
        onResult(repo.leaveCurrentCalendar().exceptionOrNull()?.message)
    }

    fun members() = repo.members()
    fun memberById(id: String?) = repo.memberById(id)
    fun currency() = repo.currency()
    fun currentCalendar() = repo.currentCalendar()
    fun myUserId() = repo.myUserId

    // ---- navigation state ----

    fun stepMonth(delta: Long) { _month.value = _month.value.plusMonths(delta) }
    fun selectDay(day: LocalDate) {
        _selectedDay.value = day
        if (YearMonth.from(day) != _month.value) _month.value = YearMonth.from(day)
    }
    fun goToToday() {
        val t = LocalDate.now()
        _selectedDay.value = t
        _month.value = YearMonth.from(t)
    }

    // ---- per screen streams ----

    fun payment(id: String): Flow<Payment?> = repo.observePayment(id)
    fun paymentAttachments(id: String): Flow<List<Attachment>> = repo.observePaymentAttachments(id)
    fun dayAttachments(day: LocalDate): Flow<List<Attachment>> = repo.observeDayAttachments(day)
    fun dayPayments(day: LocalDate): Flow<List<Payment>> = repo.observeForDay(day)
    fun dayNote(day: LocalDate) = repo.observeDayNote(day)
    fun shoppingList(id: String): Flow<ShoppingList?> = repo.observeList(id)
    fun shoppingItems(listId: String): Flow<List<ShoppingItem>> = repo.observeItems(listId)
    fun attachmentSource(a: Attachment): Any = repo.attachmentSource(a)

    // ---- actions ----

    fun create(payment: Payment, installments: List<Long>? = null, onDone: () -> Unit = {}) =
        viewModelScope.launch { repo.create(payment, installments); onDone() }

    fun updateOne(payment: Payment, onDone: () -> Unit = {}) =
        viewModelScope.launch { repo.updateOne(payment); onDone() }

    fun updateSeries(payment: Payment, onDone: () -> Unit = {}) =
        viewModelScope.launch { repo.updateSeriesFromHere(payment); onDone() }

    fun markPaid(id: String) = viewModelScope.launch { repo.markPaid(id) }
    fun markUnpaid(id: String) = viewModelScope.launch { repo.markUnpaid(id) }
    fun skip(id: String) = viewModelScope.launch { repo.skip(id) }
    fun snooze(id: String, minutes: Int) = viewModelScope.launch { repo.snooze(id, minutes) }
    fun stopSeriesAt(payment: Payment, onDone: () -> Unit = {}) =
        viewModelScope.launch { repo.stopSeriesAt(payment); onDone() }

    fun extendSeriesTo(payment: Payment, endDay: Long, onDone: (Int) -> Unit = {}) =
        viewModelScope.launch { onDone(repo.extendSeriesTo(payment, endDay)) }

    fun seriesSize(seriesId: String): Int =
        allPayments.value.count { it.seriesId == seriesId }

    fun deleteOne(id: String, onDone: () -> Unit = {}) =
        viewModelScope.launch { repo.deleteOne(id); onDone() }
    fun deleteSeries(seriesId: String, onDone: () -> Unit = {}) =
        viewModelScope.launch { repo.deleteSeries(seriesId); onDone() }

    fun attachToPayment(id: String, uri: Uri, isReceipt: Boolean) =
        viewModelScope.launch { repo.attachFromUri(uri, id, null, isReceipt) }

    fun attachToDay(day: LocalDate, uri: Uri) =
        viewModelScope.launch { repo.attachFromUri(uri, null, day, false) }

    fun attachCameraShot(paymentId: String?, day: LocalDate?, file: File, isReceipt: Boolean) =
        viewModelScope.launch {
            repo.attachFile(file, file.name, "image/jpeg", paymentId, day, isReceipt)
        }

    /** Optional picture of a product, so the other person knows exactly what to buy. */
    fun attachItemPhoto(itemId: String, file: File) = viewModelScope.launch {
        repo.attachFile(file, file.name, "image/jpeg", null, null, false, itemId)
    }

    fun attachPhotoFromUri(itemId: String, uri: Uri) = viewModelScope.launch {
        repo.attachFromUri(uri, null, null, false, itemId)
    }

    fun removeAttachment(a: Attachment) = viewModelScope.launch { repo.removeAttachment(a) }

    fun saveDayNote(day: LocalDate, text: String) = viewModelScope.launch { repo.saveDayNote(day, text) }

    // ---- shopping ----

    fun saveList(list: ShoppingList, onDone: () -> Unit = {}) =
        viewModelScope.launch { repo.saveList(list); onDone() }
    fun deleteList(list: ShoppingList, onDone: () -> Unit = {}) =
        viewModelScope.launch { repo.deleteList(list); onDone() }
    fun addItem(listId: String, text: String) = viewModelScope.launch { repo.addItem(listId, text) }

    fun addByBarcode(listId: String, code: String, onResult: (String?) -> Unit) =
        viewModelScope.launch { onResult(repo.addByBarcode(listId, code)) }
    fun updateItem(item: ShoppingItem) = viewModelScope.launch { repo.updateItem(item) }
    fun deleteItem(item: ShoppingItem) = viewModelScope.launch { repo.deleteItem(item) }
    fun completeList(id: String, cents: Long) = viewModelScope.launch { repo.completeList(id, cents) }

    /**
     * Hands a receipt photo to the server and reports the id of the list it became. The
     * returned job can be cancelled from a STOP button; a cancelled run reports nothing.
     */
    fun importReceipt(file: java.io.File, mime: String, onResult: (Result<String>) -> Unit): Job {
        val jobId = java.util.UUID.randomUUID().toString()
        val job = viewModelScope.launch {
            val result = runCatching { repo.importReceipt(file, mime, jobId) }
            if (isActive) onResult(result)
        }
        // cancelled from STOP: the server should stop working on it as well
        job.invokeOnCompletion { cause ->
            if (cause is kotlinx.coroutines.CancellationException) {
                viewModelScope.launch { repo.stopReceipt(jobId) }
            }
        }
        return job
    }
    fun reopenList(list: ShoppingList) = viewModelScope.launch { repo.reopenList(list) }

    // ---- notes ----

    /** What another app just shared with us, waiting to become a note. */
    data class SharedPayload(val title: String, val body: String, val uris: List<Uri>)

    private val _sharedPayload = MutableStateFlow<SharedPayload?>(null)
    val sharedPayload: StateFlow<SharedPayload?> = _sharedPayload.asStateFlow()

    fun offerShare(title: String, body: String, uris: List<Uri>) {
        _sharedPayload.value = SharedPayload(title, body, uris)
    }

    fun consumeShare() { _sharedPayload.value = null }

    /** An event handed over by a calendar app, waiting to become a reminder. */
    private val _sharedEvent = MutableStateFlow<CalendarEvent?>(null)
    val sharedEvent: StateFlow<CalendarEvent?> = _sharedEvent.asStateFlow()

    fun offerEvent(event: CalendarEvent) { _sharedEvent.value = event }

    /** Asks the server what event hides behind a shared Google Calendar link. */
    suspend fun resolveEventLink(url: String): CalendarEvent? =
        runCatching { repo.api.resolveEventLink(repo.calendarIdNow(), url) }.getOrNull()

    /** Address -> places on the map, through the server. */
    fun searchPlaces(query: String, onResult: (List<com.payandplan.app.net.Place>) -> Unit) =
        viewModelScope.launch { onResult(runCatching { repo.api.searchPlaces(query) }.getOrDefault(emptyList())) }
    fun consumeEvent() { _sharedEvent.value = null }

    fun attachSharedFiles(noteId: String, uris: List<Uri>) = viewModelScope.launch {
        uris.forEach { repo.attachFromUri(it, null, null, false, null, noteId) }
    }

    fun note(id: String): Flow<Note?> = repo.observeNote(id)
    fun noteAttachments(noteId: String): Flow<List<Attachment>> = repo.observeNoteAttachments(noteId)

    fun saveNote(note: Note, onDone: () -> Unit = {}) =
        viewModelScope.launch { repo.saveNote(note); onDone() }

    fun deleteNote(note: Note, onDone: () -> Unit = {}) =
        viewModelScope.launch { repo.deleteNote(note); onDone() }

    fun readLinkInto(note: Note, onResult: (Int?) -> Unit) = viewModelScope.launch {
        onResult(repo.readLinkInto(note))
    }

    fun attachToNote(noteId: String, uri: Uri) = viewModelScope.launch {
        repo.attachFromUri(uri, null, null, false, null, noteId)
    }

    fun rearmAll() = viewModelScope.launch { repo.armWindow() }
}
