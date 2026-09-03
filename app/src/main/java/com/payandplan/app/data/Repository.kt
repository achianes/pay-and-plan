package com.payandplan.app.data

import android.content.Context
import android.net.Uri
import com.payandplan.app.alarm.AlarmScheduler
import com.payandplan.app.net.ApiClient
import com.payandplan.app.util.FileStore
import com.payandplan.app.util.Format
import com.payandplan.app.util.Prefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

class Repository(
    private val context: Context,
    private val db: AppDatabase,
    val prefs: Prefs
) {
    val api = ApiClient(prefs)

    private val payments = db.paymentDao()
    private val attachments = db.attachmentDao()
    private val notes = db.dayNoteDao()
    private val shopping = db.shoppingDao()
    private val noteDao = db.noteDao()

    private val _calendarId = MutableStateFlow(prefs.calendarId)
    val calendarId = _calendarId.asStateFlow()

    private val _calendars = MutableStateFlow(readCachedCalendars())
    val calendars = _calendars.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing = _syncing.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError = _lastError.asStateFlow()

    val myUserId: String get() = prefs.userId

    init {
        // Room drops the cache when the schema changes; the cursors must not survive it
        if (prefs.schemaVersion != AppDatabase.VERSION) {
            prefs.clearSyncCursors()
            prefs.schemaVersion = AppDatabase.VERSION
        }
    }

    /** Throws away the local copy of the cursors and pulls the calendar from scratch. */
    suspend fun fullResync() {
        prefs.clearSyncCursors()
        sync()
    }

    fun currentCalendar(): CalendarSpace? = _calendars.value.firstOrNull { it.id == _calendarId.value }
    fun members(): List<Member> = currentCalendar()?.members.orEmpty()
    fun memberById(id: String?): Member? = members().firstOrNull { it.id == id }
    fun currency(): String = currentCalendar()?.currency ?: prefs.currency

    // ---------------------------------------------------------------- reads

    fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<Payment>> =
        payments.observeBetween(_calendarId.value, from.toEpochDay(), to.toEpochDay())

    fun observeForDay(day: LocalDate): Flow<List<Payment>> =
        payments.observeForDay(_calendarId.value, day.toEpochDay())

    fun observeAll(): Flow<List<Payment>> = payments.observeAll(_calendarId.value)

    fun observePayment(id: String): Flow<Payment?> = payments.observeById(id)

    fun observePaymentAttachments(paymentId: String): Flow<List<Attachment>> =
        attachments.observeForPayment(paymentId)

    fun observeDayAttachments(day: LocalDate): Flow<List<Attachment>> =
        attachments.observeForDay(_calendarId.value, day.toEpochDay())

    fun observeDaysWithFiles(from: LocalDate, to: LocalDate): Flow<List<Long>> =
        attachments.observeDaysWithFiles(_calendarId.value, from.toEpochDay(), to.toEpochDay())

    fun observeDaysWithNotes(from: LocalDate, to: LocalDate): Flow<List<Long>> =
        notes.observeDaysWithNotes(_calendarId.value, from.toEpochDay(), to.toEpochDay())

    fun observeDayNote(day: LocalDate): Flow<DayNote?> = notes.observe(_calendarId.value, day.toEpochDay())

    fun observeLists(): Flow<List<ShoppingList>> = shopping.observeLists(_calendarId.value)
    fun observeList(id: String): Flow<ShoppingList?> = shopping.observeList(id)
    fun observeItems(listId: String): Flow<List<ShoppingItem>> = shopping.observeItems(listId)

    fun observeNotes(): Flow<List<Note>> = noteDao.observeAll(_calendarId.value)
    fun observeNote(id: String): Flow<Note?> = noteDao.observeById(id)
    fun observeNoteAttachments(noteId: String): Flow<List<Attachment>> =
        attachments.observeForNote(noteId)
    fun observeAllNoteFiles(): Flow<List<Attachment>> = attachments.observeNoteFiles(_calendarId.value)

    /** Notes the current user may see: private ones belong to their author or their target. */
    fun visibleNotes(rows: List<Note>): List<Note> {
        val me = prefs.userId
        return rows.filter { !it.isPrivate || it.createdByUserId == me || it.ownerUserId == me }
    }

    suspend fun saveNote(note: Note) {
        noteDao.upsert(
            note.copy(
                calendarId = _calendarId.value,
                createdByUserId = note.createdByUserId ?: prefs.userId.ifBlank { null },
                createdAt = if (note.createdAt == 0L) System.currentTimeMillis() else note.createdAt,
                updatedAt = System.currentTimeMillis(),
                pendingSync = true
            )
        )
        syncQuietly()
    }

    /**
     * Reads the first link of a note through the server and folds the result back into it.
     * Returns how many pictures came with it, or null when it did not work out.
     */
    suspend fun readLinkInto(note: Note): Int? {
        val link = Regex("https?://[^\\s\"'<>]+")
            .find(note.title + " " + note.body)?.value ?: return null
        return runCatching {
            saveNote(note)   // it must exist server side before pictures can point at it
            val (title, text, images) = api.unfurl(_calendarId.value, link, note.id)
            val heading = if (title.isBlank()) link else title
            val merged = listOf(
                note.body.trim().ifBlank { null },
                "--- $heading ---",
                link,
                "",
                text
            ).filterNotNull().joinToString("\n")
            saveNote(note.copy(title = note.title.ifBlank { title.take(200) }, body = merged))
            sync()
            images
        }.getOrNull()
    }

    suspend fun deleteNote(note: Note) {
        noteDao.upsert(
            note.copy(
                deletedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                pendingSync = true
            )
        )
        syncQuietly()
    }

    /** Newest photo per shopping item. */
    fun observeItemPhotos(): Flow<Map<String, Attachment>> =
        attachments.observeItemPhotos(_calendarId.value).map { rows ->
            rows.filter { it.itemId != null }.associateBy { it.itemId!! }
        }

    /** Shared shopping vocabulary of the calendar, used to complete what you type. */
    fun observeItemSuggestions(): Flow<List<String>> =
        shopping.observeItemSuggestions(_calendarId.value)

    suspend fun getPayment(id: String): Payment? = payments.getById(id)

    /** Rows the current user is allowed to see (private ones belong to their author). */
    fun visible(rows: List<Payment>): List<Payment> {
        val me = prefs.userId
        return rows.filter { !it.isPrivate || it.createdByUserId == me || it.ownerUserId == me }
    }

    // ---------------------------------------------------------------- payments

    private fun stamped(p: Payment) = p.copy(updatedAt = System.currentTimeMillis(), pendingSync = true)

    suspend fun create(template: Payment, installmentAmounts: List<Long>? = null): String {
        val stamp = System.currentTimeMillis()
        val base = template.copy(
            calendarId = _calendarId.value,
            seriesId = newId(),
            createdByUserId = prefs.userId.ifBlank { null },
            createdAt = stamp,
            updatedAt = stamp,
            pendingSync = true
        )
        val rows = when {
            !installmentAmounts.isNullOrEmpty() -> RecurrenceEngine.buildInstallments(
                base, LocalDate.ofEpochDay(base.dueDate), installmentAmounts
            )
            else -> RecurrenceEngine.build(
                base, 0, RecurrenceEngine.HORIZON, LocalDate.ofEpochDay(base.dueDate)
            )
        }
        payments.insertAll(rows)
        armWindow()
        syncQuietly()
        return rows.firstOrNull()?.id ?: base.id
    }

    suspend fun updateOne(payment: Payment) {
        payments.update(stamped(payment))
        AlarmScheduler.cancel(context, payment.id)
        AlarmScheduler.schedule(context, payment)
        syncQuietly()
    }

    suspend fun updateSeriesFromHere(payment: Payment) {
        payments.update(stamped(payment))
        val future = payments.futureInSeries(payment.seriesId, payment.dueDate)

        if (payment.isInstallment) {
            // fixed plan: dates and figures stay, only the shared traits travel
            future.forEach { row ->
                payments.update(
                    stamped(
                        row.copy(
                            title = payment.title,
                            currency = payment.currency,
                            colorIndex = payment.colorIndex,
                            category = payment.category,
                            dueTimeMinutes = payment.dueTimeMinutes,
                            notes = payment.notes,
                            ownerUserId = payment.ownerUserId,
                            visibility = payment.visibility,
                            remindDaysBefore = payment.remindDaysBefore,
                            nagMinutes = payment.nagMinutes,
                            alarmEnabled = payment.alarmEnabled,
                            requireReceipt = payment.requireReceipt
                        )
                    )
                )
            }
        } else {
            future.forEach {
                AlarmScheduler.cancel(context, it.id)
                payments.update(stamped(it.copy(deletedAt = System.currentTimeMillis())))
            }
            val rows = RecurrenceEngine.build(
                payment, 1, RecurrenceEngine.HORIZON, LocalDate.ofEpochDay(payment.dueDate)
            )
            payments.insertAll(rows)
        }
        armWindow()
        syncQuietly()
    }

    suspend fun markPaid(id: String, paidCents: Long? = null) {
        val p = payments.getById(id) ?: return
        payments.update(
            stamped(
                p.copy(
                    status = PayStatus.PAID.name,
                    paidAt = System.currentTimeMillis(),
                    paidAmountCents = paidCents ?: p.amountCents,
                    paidByUserId = prefs.userId.ifBlank { null },
                    snoozedUntil = null
                )
            )
        )
        AlarmScheduler.cancel(context, id)
        AlarmScheduler.dismissNotification(context, id)
        topUp(p.seriesId)
        syncQuietly()
    }

    suspend fun markUnpaid(id: String) {
        val p = payments.getById(id) ?: return
        val restored = p.copy(
            status = PayStatus.PENDING.name, paidAt = null, paidAmountCents = null, paidByUserId = null
        )
        payments.update(stamped(restored))
        AlarmScheduler.schedule(context, restored)
        syncQuietly()
    }

    suspend fun skip(id: String) {
        val p = payments.getById(id) ?: return
        payments.update(stamped(p.copy(status = PayStatus.SKIPPED.name, snoozedUntil = null)))
        AlarmScheduler.cancel(context, id)
        AlarmScheduler.dismissNotification(context, id)
        topUp(p.seriesId)
        syncQuietly()
    }

    suspend fun snooze(id: String, minutes: Int) {
        val p = payments.getById(id) ?: return
        val updated = p.copy(snoozedUntil = System.currentTimeMillis() + minutes * 60_000L)
        payments.update(updated) // local only, no need to bother the server
        AlarmScheduler.dismissNotification(context, id)
        AlarmScheduler.schedule(context, updated)
    }

    suspend fun deleteOne(id: String) {
        val p = payments.getById(id) ?: return
        payments.update(stamped(p.copy(deletedAt = System.currentTimeMillis())))
        AlarmScheduler.cancel(context, id)
        AlarmScheduler.dismissNotification(context, id)
        syncQuietly()
    }

    /** Closes a repeating series at [payment]: later unpaid entries go, the end date is recorded. */
    suspend fun stopSeriesAt(payment: Payment) {
        payments.wholeSeries(payment.seriesId).forEach { row ->
            if (row.dueDate > payment.dueDate && row.isOpen) {
                AlarmScheduler.cancel(context, row.id)
                payments.update(stamped(row.copy(deletedAt = System.currentTimeMillis())))
            } else {
                payments.update(stamped(row.copy(recurrenceEndDate = payment.dueDate)))
            }
        }
        syncQuietly()
    }

    /** Pushes a series forward to [endDay], creating the entries that are missing. */
    suspend fun extendSeriesTo(payment: Payment, endDay: Long): Int {
        val rows = payments.wholeSeries(payment.seriesId).sortedBy { it.dueDate }
        val last = rows.lastOrNull() ?: return 0
        if (last.recurrenceEnum == Recurrence.NONE) return 0
        rows.forEach { payments.update(stamped(it.copy(recurrenceEndDate = endDay))) }

        val anchor = LocalDate.ofEpochDay(last.dueDate)
        val fresh = ArrayList<Payment>()
        var i = 1
        while (i <= 600) {
            val date = RecurrenceEngine.dateAt(anchor, last.recurrenceEnum, i)
            if (date.toEpochDay() > endDay) break
            fresh += last.copy(
                id = newId(),
                dueDate = date.toEpochDay(),
                status = PayStatus.PENDING.name,
                paidAt = null,
                paidAmountCents = null,
                paidByUserId = null,
                snoozedUntil = null,
                recurrenceEndDate = endDay,
                deletedAt = null,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                pendingSync = true
            )
            i++
        }
        if (fresh.isNotEmpty()) payments.insertAll(fresh)
        armWindow()
        syncQuietly()
        return fresh.size
    }

    suspend fun deleteSeries(seriesId: String) {
        payments.wholeSeries(seriesId).forEach {
            AlarmScheduler.cancel(context, it.id)
            payments.update(stamped(it.copy(deletedAt = System.currentTimeMillis())))
        }
        syncQuietly()
    }

    // ---------------------------------------------------------------- attachments

    suspend fun attachFromUri(
        uri: Uri,
        paymentId: String?,
        day: LocalDate?,
        isReceipt: Boolean,
        itemId: String? = null,
        noteId: String? = null
    ): Boolean {
        val copied = FileStore.copyIn(context, uri) ?: return false
        val (file, name, mime) = copied
        return attachFile(file, name, mime, paymentId, day, isReceipt, itemId, noteId)
    }

    suspend fun attachFile(
        file: File,
        name: String,
        mime: String,
        paymentId: String?,
        day: LocalDate?,
        isReceipt: Boolean,
        itemId: String? = null,
        noteId: String? = null
    ): Boolean {
        val row = Attachment(
            id = newId(),
            calendarId = _calendarId.value,
            ownerType = when {
                noteId != null -> OwnerType.NOTE
                itemId != null -> OwnerType.ITEM
                paymentId != null -> OwnerType.PAYMENT
                else -> OwnerType.DAY
            },
            paymentId = paymentId,
            epochDay = day?.toEpochDay(),
            itemId = itemId,
            noteId = noteId,
            fileName = name,
            mime = mime,
            size = file.length(),
            isReceipt = isReceipt,
            uploadedBy = prefs.userId.ifBlank { null },
            localPath = file.absolutePath,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            pendingUpload = true
        )
        attachments.insert(row)
        syncQuietly()
        return true
    }

    suspend fun removeAttachment(a: Attachment) {
        attachments.update(a.copy(deletedAt = System.currentTimeMillis()))
        a.localPath?.let { FileStore.delete(it) }
        if (api.isLoggedIn) api.deleteAttachment(a.id)
        attachments.hardDelete(a.id)
    }

    suspend fun hasReceipt(paymentId: String): Boolean =
        attachments.getForPayment(paymentId).any { it.isReceipt }

    /** Local copy when we have it, otherwise the authenticated server URL. */
    fun attachmentSource(a: Attachment): Any =
        a.localPath?.let { File(it) }?.takeIf { it.exists() } ?: api.attachmentUrl(a.id)

    suspend fun ensureLocalCopy(a: Attachment): File? {
        a.localPath?.let { path ->
            val existing = File(path)
            if (existing.exists()) return existing
        }
        if (!api.isLoggedIn) return null
        val target = File(File(context.filesDir, "attachments"), "${a.id}_${a.fileName}")
        return if (api.download(a.id, target)) {
            attachments.update(a.copy(localPath = target.absolutePath))
            target
        } else null
    }

    // ---------------------------------------------------------------- notes

    suspend fun saveDayNote(day: LocalDate, text: String) {
        val existing = notes.get(_calendarId.value, day.toEpochDay())
        val row = (existing ?: DayNote(calendarId = _calendarId.value, epochDay = day.toEpochDay()))
            .copy(text = text.trim(), updatedAt = System.currentTimeMillis(), pendingSync = true, deletedAt = null)
        notes.upsert(row)
        syncQuietly()
    }

    // ---------------------------------------------------------------- shopping

    suspend fun saveList(list: ShoppingList) {
        shopping.upsertList(
            list.copy(
                calendarId = _calendarId.value,
                createdByUserId = list.createdByUserId ?: prefs.userId.ifBlank { null },
                createdAt = if (list.createdAt == 0L) System.currentTimeMillis() else list.createdAt,
                updatedAt = System.currentTimeMillis(),
                pendingSync = true
            )
        )
        syncQuietly()
    }

    suspend fun deleteList(list: ShoppingList) {
        shopping.upsertList(list.copy(deletedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(), pendingSync = true))
        syncQuietly()
    }

    suspend fun addItem(listId: String, text: String) {
        val count = shopping.getItems(listId).size
        shopping.upsertItem(
            ShoppingItem(
                listId = listId,
                calendarId = _calendarId.value,
                text = text.trim(),
                sortIndex = count,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                pendingSync = true
            )
        )
        syncQuietly()
    }

    /**
     * A scanned barcode becomes an item: named and pictured when the food database knows it,
     * "Product <code>" when it does not, so the scan is never wasted. Returns the label.
     */
    suspend fun addByBarcode(listId: String, code: String): String? {
        val stamp = System.currentTimeMillis()
        val item = ShoppingItem(
            listId = listId,
            calendarId = _calendarId.value,
            text = "Product $code",
            sortIndex = shopping.getItems(listId).size,
            createdAt = stamp,
            updatedAt = stamp,
            pendingSync = true
        )
        shopping.upsertItem(item)
        val found = runCatching { api.lookupProduct(_calendarId.value, code, item.id) }.getOrNull()
        if (found != null) {
            val (label, attachment) = found
            shopping.upsertItem(item.copy(text = label, updatedAt = System.currentTimeMillis(), pendingSync = true))
            attachment?.let { attachments.insert(it) }
        }
        syncQuietly()
        return found?.first
    }

    suspend fun updateItem(item: ShoppingItem) {
        shopping.upsertItem(item.copy(updatedAt = System.currentTimeMillis(), pendingSync = true))
        syncQuietly()
    }

    suspend fun deleteItem(item: ShoppingItem) {
        shopping.upsertItem(
            item.copy(deletedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(), pendingSync = true)
        )
        syncQuietly()
    }

    /**
     * The shopper closes the list with the amount actually spent: a paid bill lands in the
     * calendar, in the name of whoever did the shopping.
     */
    suspend fun completeList(listId: String, actualCents: Long) {
        val list = shopping.getList(listId) ?: return
        val payer = list.assignedToUserId ?: prefs.userId.ifBlank { null }
        val stamp = System.currentTimeMillis()
        val bill = Payment(
            calendarId = _calendarId.value,
            seriesId = newId(),
            ownerUserId = payer,
            createdByUserId = prefs.userId.ifBlank { null },
            title = list.title,
            amountCents = actualCents,
            currency = currency(),
            colorIndex = list.colorIndex,
            category = "Shopping",
            dueDate = list.dueDate ?: Format.today().toEpochDay(),
            dueTimeMinutes = 12 * 60,
            status = PayStatus.PAID.name,
            paidAt = stamp,
            paidAmountCents = actualCents,
            paidByUserId = payer,
            alarmEnabled = false,
            requireReceipt = false,
            shoppingListId = list.id,
            visibility = list.visibility,
            createdAt = stamp,
            updatedAt = stamp,
            pendingSync = true
        )
        payments.insert(bill)
        shopping.upsertList(
            list.copy(
                status = "DONE",
                actualCents = actualCents,
                doneAt = stamp,
                doneByUserId = prefs.userId.ifBlank { null },
                paymentId = bill.id,
                updatedAt = stamp,
                pendingSync = true
            )
        )
        syncQuietly()
    }

    /**
     * A photo of the till receipt becomes a list that is already ticked and priced, assigned
     * to whoever took the picture. Returns the new list's id.
     */
    suspend fun importReceipt(file: File, mime: String, jobId: String): String {
        val today = Format.today().toEpochDay()
        val r = api.readReceipt(_calendarId.value, file, mime, today, jobId)
        val stamp = System.currentTimeMillis()
        val me = prefs.userId.ifBlank { null }
        val list = ShoppingList(
            calendarId = _calendarId.value,
            title = if (r.date != null) "${r.store} · ${r.date}" else r.store,
            notes = "Read from the receipt",
            colorIndex = 2,
            dueDate = r.epochDay,
            dueTimeMinutes = 18 * 60,
            assignedToUserId = me,
            createdByUserId = me,
            budgetCents = r.totalCents,
            createdAt = stamp,
            updatedAt = stamp,
            pendingSync = true
        )
        shopping.upsertList(list)
        r.items.forEachIndexed { i, it ->
            shopping.upsertItem(
                ShoppingItem(
                    listId = list.id,
                    calendarId = _calendarId.value,
                    text = it.name,
                    quantity = it.quantity,
                    checked = true,
                    priceCents = it.priceCents,
                    sortIndex = i,
                    createdAt = stamp,
                    updatedAt = stamp,
                    pendingSync = true
                )
            )
        }
        r.attachment?.let { attachments.insert(it) }
        syncQuietly()
        return list.id
    }

    suspend fun stopReceipt(jobId: String) = runCatching { api.stopReceipt(jobId) }.isSuccess

    suspend fun reopenList(list: ShoppingList) {
        shopping.upsertList(
            list.copy(status = "OPEN", actualCents = null, doneAt = null, updatedAt = System.currentTimeMillis(), pendingSync = true)
        )
        syncQuietly()
    }

    // ---------------------------------------------------------------- account

    suspend fun login(email: String, password: String) = runCatching {
        val result = api.login(email, password)
        storeAuth(result.token, result.userId, result.name, result.email)
        refreshAccount()
    }

    suspend fun register(email: String, password: String, name: String) = runCatching {
        val result = api.register(email, password, name)
        storeAuth(result.token, result.userId, result.name, result.email)
        refreshAccount()
    }

    private fun storeAuth(token: String, userId: String, name: String, email: String) {
        prefs.token = token
        prefs.userId = userId
        prefs.userName = name
        prefs.userEmail = email
    }

    suspend fun refreshAccount() {
        val (me, calendars, receipts) = api.me()
        prefs.userName = me.name
        prefs.userEmail = me.email
        prefs.receiptsEnabled = receipts
        cacheCalendars(calendars)
        if (prefs.calendarId.isBlank() || calendars.none { it.id == prefs.calendarId }) {
            switchCalendar(calendars.firstOrNull()?.id ?: "")
        }
        sync()
    }

    fun switchCalendar(id: String) {
        prefs.calendarId = id
        _calendarId.value = id
    }

    fun signOut() {
        prefs.signOut()
        _calendars.value = emptyList()
        _calendarId.value = ""
    }

    suspend fun createCalendar(name: String) = runCatching {
        val cal = api.createCalendar(name)
        refreshAccount()
        switchCalendar(cal.id)
    }

    suspend fun joinCalendar(code: String) = runCatching {
        val cal = api.joinCalendar(code)
        refreshAccount()
        switchCalendar(cal.id)
    }

    suspend fun renameCalendar(name: String, currency: String) = runCatching {
        api.updateCalendar(_calendarId.value, name, currency)
        refreshAccount()
    }

    suspend fun rotateInvite() = runCatching {
        api.rotateInvite(_calendarId.value)
        refreshAccount()
    }

    suspend fun removeMember(userId: String) = runCatching {
        api.removeMember(_calendarId.value, userId)
        refreshAccount()
    }

    /** Owner only: wipes the calendar for everybody, so the caller must be sure. */
    suspend fun deleteCurrentCalendar() = runCatching {
        val id = _calendarId.value
        api.deleteCalendar(id)
        prefs.setLastSync(id, 0L)
        refreshAccount()
    }

    suspend fun leaveCurrentCalendar() = runCatching {
        val id = _calendarId.value
        api.leaveCalendar(id)
        prefs.setLastSync(id, 0L)
        refreshAccount()
    }

    suspend fun updateMyName(name: String) = runCatching {
        api.updateMe(name)
        prefs.userName = name
        refreshAccount()
    }

    private fun cacheCalendars(list: List<CalendarSpace>) {
        _calendars.value = list
        val array = JSONArray()
        list.forEach { cal ->
            val members = JSONArray()
            cal.members.forEach { m ->
                members.put(
                    JSONObject().put("id", m.id).put("name", m.name).put("email", m.email)
                        .put("colorIndex", m.colorIndex).put("role", m.role)
                )
            }
            array.put(
                JSONObject()
                    .put("id", cal.id).put("name", cal.name).put("colorIndex", cal.colorIndex)
                    .put("currency", cal.currency).put("ownerUserId", cal.ownerUserId)
                    .put("inviteCode", cal.inviteCode).put("members", members)
            )
        }
        prefs.calendarsJson = array.toString()
    }

    private fun readCachedCalendars(): List<CalendarSpace> = runCatching {
        val array = JSONArray(prefs.calendarsJson)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val members = o.optJSONArray("members")
            CalendarSpace(
                id = o.getString("id"),
                name = o.optString("name"),
                colorIndex = o.optInt("colorIndex"),
                currency = o.optString("currency", "EUR"),
                ownerUserId = o.optString("ownerUserId"),
                inviteCode = o.optString("inviteCode"),
                members = (0 until (members?.length() ?: 0)).map { k ->
                    val m = members!!.getJSONObject(k)
                    Member(
                        m.getString("id"), m.optString("name"), m.optString("email"),
                        m.optInt("colorIndex"), m.optString("role", "member")
                    )
                }
            )
        }
    }.getOrDefault(emptyList())

    // ---------------------------------------------------------------- sync

    private suspend fun syncQuietly() {
        runCatching { sync() }
    }

    suspend fun sync() {
        val calId = _calendarId.value
        if (!api.isLoggedIn || calId.isBlank() || _syncing.value) return
        _syncing.value = true
        try {
            val dirtyPayments = payments.pending().filter { it.calendarId == calId }
            val dirtyDayNotes = notes.pending().filter { it.calendarId == calId }
            val dirtyLists = shopping.pendingLists().filter { it.calendarId == calId }
            val dirtyItems = shopping.pendingItems().filter { it.calendarId == calId }
            val dirtyNotes = noteDao.pending().filter { it.calendarId == calId }

            if (dirtyPayments.isNotEmpty() || dirtyDayNotes.isNotEmpty() ||
                dirtyLists.isNotEmpty() || dirtyItems.isNotEmpty() || dirtyNotes.isNotEmpty()
            ) {
                api.push(calId, dirtyPayments, dirtyDayNotes, dirtyLists, dirtyItems, dirtyNotes)
                payments.clearPending(dirtyPayments.map { it.id })
                notes.clearPending(dirtyDayNotes.map { it.id })
                shopping.clearPendingLists(dirtyLists.map { it.id })
                shopping.clearPendingItems(dirtyItems.map { it.id })
                noteDao.clearPending(dirtyNotes.map { it.id })
            }

            attachments.pendingUploads().filter { it.calendarId == calId }.forEach { pendingFile ->
                val file = pendingFile.localPath?.let { File(it) }
                if (file != null && file.exists()) {
                    runCatching { api.uploadAttachment(calId, pendingFile, file) }
                        .onSuccess { attachments.update(it) }
                }
            }

            val fresh = api.pull(calId, prefs.lastSync(calId))
            applyPull(fresh)
            prefs.setLastSync(calId, fresh.serverTime)
            _lastError.value = null
            armWindow()
        } catch (e: Exception) {
            _lastError.value = e.message
        } finally {
            _syncing.value = false
        }
    }

    private suspend fun applyPull(fresh: SyncPullData) {
        fresh.calendar?.let { incoming ->
            val merged = _calendars.value.map { if (it.id == incoming.id) incoming else it }
            cacheCalendars(if (merged.any { it.id == incoming.id }) merged else merged + incoming)
        }
        fresh.payments.forEach { remote ->
            val local = payments.getById(remote.id)
            if (local == null || !local.pendingSync || remote.updatedAt >= local.updatedAt) {
                payments.insert(remote.copy(snoozedUntil = local?.snoozedUntil))
            }
        }
        fresh.dayNotes.forEach { remote ->
            val local = notes.get(remote.calendarId, remote.epochDay)
            if (local == null || !local.pendingSync || remote.updatedAt >= local.updatedAt) {
                notes.upsert(remote)
            }
        }
        fresh.attachments.forEach { remote ->
            val local = attachments.getById(remote.id)
            attachments.insert(remote.copy(localPath = local?.localPath))
        }
        fresh.lists.forEach { remote ->
            val local = shopping.getList(remote.id)
            if (local == null || !local.pendingSync || remote.updatedAt >= local.updatedAt) {
                shopping.upsertList(remote)
            }
        }
        fresh.items.forEach { remote ->
            val local = shopping.getItem(remote.id)
            if (local == null || !local.pendingSync || remote.updatedAt >= local.updatedAt) {
                shopping.upsertItem(remote)
            }
        }
        fresh.notes.forEach { remote ->
            val local = noteDao.getById(remote.id)
            if (local == null || !local.pendingSync || remote.updatedAt >= local.updatedAt) {
                noteDao.upsert(remote)
            }
        }
    }

    // ---------------------------------------------------------------- maintenance

    suspend fun topUp(seriesId: String) {
        val last = payments.lastInSeries(seriesId) ?: return
        if (last.recurrenceEnum == Recurrence.NONE || last.isInstallment) return
        val ahead = payments.countFromDay(seriesId, Format.today().toEpochDay())
        if (ahead >= RecurrenceEngine.HORIZON) return
        val rows = RecurrenceEngine.build(
            last, 1, RecurrenceEngine.HORIZON - ahead, LocalDate.ofEpochDay(last.dueDate)
        )
        if (rows.isNotEmpty()) payments.insertAll(rows)
    }

    suspend fun topUpAll() {
        payments.activeSeries().forEach { topUp(it) }
    }

    suspend fun armWindow() {
        val open = payments.getOpenWithAlarm()
        val today = Format.today().toEpochDay()
        open.filter { it.dueDate <= today + 45 }.forEach { AlarmScheduler.schedule(context, it) }
    }
}

/** Alias so the repository does not depend on the network package types by name. */
typealias SyncPullData = com.payandplan.app.net.SyncPull
