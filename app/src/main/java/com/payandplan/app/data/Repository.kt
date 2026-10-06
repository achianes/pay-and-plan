package com.payandplan.app.data

import android.content.Context
import android.net.Uri
import com.payandplan.app.alarm.AlarmScheduler
import com.payandplan.app.net.ApiClient
import com.payandplan.app.net.LocalServices
import com.payandplan.app.net.Place
import com.payandplan.app.util.CalendarEvent
import com.payandplan.app.util.FileStore
import com.payandplan.app.util.Format
import com.payandplan.app.util.MoneyText
import com.payandplan.app.util.SmallChange
import com.payandplan.app.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val local = LocalServices(context)

    /** Working with no server: calendars, people and files all live on this phone. */
    val isLocal: Boolean get() = prefs.localMode && !api.isLoggedIn

    private val payments = db.paymentDao()
    private val attachments = db.attachmentDao()
    private val notes = db.dayNoteDao()
    private val shopping = db.shoppingDao()
    private val noteDao = db.noteDao()
    private val bank = db.bankDao()

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
            val (title, text, images) = if (api.isLoggedIn) api.unfurl(_calendarId.value, link, note.id)
            else local.unfurl(link)?.let { (t, x) -> Triple(t, x, 0) } ?: return null
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
        val before = payments.getById(payment.id)
        payments.update(stamped(payment))
        AlarmScheduler.cancel(context, payment.id)
        AlarmScheduler.schedule(context, payment)
        // a category written here means the same as one tapped on the day: the shop keeps it
        if (payment.category.isNotBlank() && payment.category != before?.category) {
            rememberCategory(payment.title, payment.category)
        }
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

    /** What an entry was for. Free text: whatever is typed joins the list of the others. */
    suspend fun setCategory(id: String, category: String) {
        val entry = payments.getById(id) ?: return
        val named = category.trim()
        payments.update(stamped(entry.copy(category = named)))
        if (named.isNotBlank()) rememberCategory(entry.title, named)
        syncQuietly()
    }

    /**
     * Said once, meant always: the shop keeps the category. The next payment read from the
     * bank at the same shop arrives already sorted, and the ones already sitting there with
     * no category are brought along, since the answer would have been the same for them.
     */
    private suspend fun rememberCategory(title: String, category: String) {
        val shop = MoneyText.bare(title)
        if (shop.isBlank()) return
        val had = bank.linkFor(shop)
        bank.upsertLink(
            (had ?: BankLink(shop = shop, label = title, createdAt = System.currentTimeMillis()))
                .copy(category = category)
        )
        for (other in payments.titled(_calendarId.value, title)) {
            if (other.category.isBlank()) {
                payments.update(stamped(other.copy(category = category)))
            }
        }
    }

    /** What this shop has been for before, if anybody ever said. */
    private suspend fun categoryFor(title: String): String =
        bank.linkFor(MoneyText.bare(title))?.category.orEmpty()

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

    /**
     * Suspended: kept, but out of every total and silent. With [wholeSeries] every entry of the
     * series that is still open goes, and the ones the series grows later are born suspended.
     */
    suspend fun suspendEntry(id: String, wholeSeries: Boolean) {
        val p = payments.getById(id) ?: return
        val rows = if (wholeSeries) payments.wholeSeries(p.seriesId) else listOf(p)
        rows.filter { it.isOpen }.forEach { row ->
            payments.update(stamped(row.copy(status = PayStatus.SUSPENDED.name, snoozedUntil = null)))
            AlarmScheduler.cancel(context, row.id)
            AlarmScheduler.dismissNotification(context, row.id)
        }
        syncQuietly()
    }

    /** Back in the counts, alarms armed again. */
    suspend fun resumeEntry(id: String, wholeSeries: Boolean) {
        val p = payments.getById(id) ?: return
        val rows = if (wholeSeries) payments.wholeSeries(p.seriesId) else listOf(p)
        rows.filter { it.isSuspended }.forEach { row ->
            val back = row.copy(status = PayStatus.PENDING.name)
            payments.update(stamped(back))
            AlarmScheduler.schedule(context, back)
        }
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
        val item = ShoppingItem(
            listId = listId,
            calendarId = _calendarId.value,
            text = text.trim(),
            sortIndex = count,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            pendingSync = true
        )
        shopping.upsertItem(item)
        inheritPhoto(item)
        syncQuietly()
    }

    /**
     * A scanned barcode becomes an item: named and pictured when the food database knows it,
     * "Product <code>" when it does not, so the scan is never wasted. Returns the label.
     */
    suspend fun addByBarcode(listId: String, code: String): Pair<String, Boolean> {
        val stamp = System.currentTimeMillis()
        val item = ShoppingItem(
            listId = listId,
            calendarId = _calendarId.value,
            text = "Product $code",
            barcode = code,
            sortIndex = shopping.getItems(listId).size,
            createdAt = stamp,
            updatedAt = stamp,
            pendingSync = true
        )
        shopping.upsertItem(item)

        // the household's own book first: a name given by hand once is worth more than any
        // database, and it answers for the products no database has ever heard of
        val known = shopping.itemsWithBarcode(_calendarId.value, code)
            .firstOrNull { it.id != item.id && it.text.isNotBlank() && !it.text.startsWith("Product ") }
        if (known != null) {
            shopping.upsertItem(
                item.copy(
                    text = known.text,
                    quantity = known.quantity,
                    updatedAt = System.currentTimeMillis(),
                    pendingSync = true
                )
            )
            if (attachments.forItem(known.id).isNotEmpty()) copyItemPhoto(item.id, known.id)
            syncQuietly()
            return item.id to true
        }

        if (!api.isLoggedIn) {
            val hit = runCatching { local.lookupProduct(code) }.getOrNull()
            if (hit != null) {
                shopping.upsertItem(
                    item.copy(text = hit.label, quantity = hit.quantity, updatedAt = System.currentTimeMillis(), pendingSync = true)
                )
                hit.image?.let { attachFile(it, "$code.jpg", "image/jpeg", null, null, false, item.id) }
            }
            return item.id to (hit?.named == true)
        }

        val found = runCatching { api.lookupProduct(_calendarId.value, code, item.id) }.getOrNull()
        if (found != null) {
            shopping.upsertItem(
                item.copy(
                    text = found.label,
                    quantity = found.quantity,
                    updatedAt = System.currentTimeMillis(),
                    pendingSync = true
                )
            )
            found.attachment?.let { attachments.insert(it) }
        }
        syncQuietly()
        // the id, and whether it came back with a name: an unnamed one waits for the shopper
        return item.id to (found?.named == true)
    }

    /**
     * The household's picture cache: a product bought before keeps its face. The newest
     * other item with the same name and a photo lends it, through a server side copy.
     */
    private suspend fun inheritPhoto(item: ShoppingItem) {
        val wanted = item.text.trim().lowercase()
        if (wanted.isBlank()) return
        val twins = shopping.itemsNamed(_calendarId.value, item.id)
            .filter { it.text.trim().lowercase() == wanted }
            .sortedByDescending { it.updatedAt }
        for (twin in twins) {
            if (attachments.forItem(twin.id).isEmpty()) continue
            if (copyItemPhoto(item.id, twin.id)) return
        }
    }

    /** Lends [sourceItemId]'s photo to [itemId]: a server side copy, or a file copy on the phone. */
    private suspend fun copyItemPhoto(itemId: String, sourceItemId: String): Boolean {
        if (api.isLoggedIn) {
            val copied = runCatching { api.copyItemPhoto(_calendarId.value, itemId, sourceItemId) }.getOrNull()
                ?: return false
            attachments.insert(copied)
            return true
        }
        val source = attachments.forItem(sourceItemId).firstOrNull { a ->
            a.localPath?.let { File(it).exists() } == true
        } ?: return false
        val from = File(source.localPath!!)
        val to = File(from.parentFile, "${newId()}_${source.fileName}")
        if (runCatching { from.copyTo(to) }.isFailure) return false
        return attachFile(to, source.fileName, source.mime, null, null, false, itemId)
    }

    /** Address search: through the server when there is one, straight to OpenStreetMap otherwise. */
    suspend fun searchPlaces(query: String): List<Place> =
        if (api.isLoggedIn) api.searchPlaces(query) else local.searchPlaces(query)

    /** A Google Calendar link can only be followed by the server; alone, the link becomes a note. */
    suspend fun resolveEventLink(url: String): CalendarEvent? =
        if (api.isLoggedIn) api.resolveEventLink(_calendarId.value, url) else null

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

    /** First start without a server: a person and a calendar of their own, on this phone. */
    fun startLocal(name: String) {
        prefs.localMode = true
        if (prefs.userId.isBlank()) prefs.userId = "local-" + newId()
        prefs.userName = name.ifBlank { "Me" }
        prefs.receiptsEnabled = false
        if (_calendars.value.isEmpty()) {
            val cal = localCalendar("Home")
            cacheCalendars(listOf(cal))
            switchCalendar(cal.id)
        }
    }

    private fun localMe() = Member(prefs.userId, prefs.userName.ifBlank { "Me" }, "", 0, "owner")

    private fun localCalendar(name: String) = CalendarSpace(
        id = newId(), name = name, colorIndex = _calendars.value.size % 8, currency = prefs.currency,
        ownerUserId = prefs.userId, inviteCode = "", members = listOf(localMe())
    )

    /** Every row of a calendar kept only on this phone, files and alarms included. */
    private suspend fun wipeLocalCalendar(id: String) = withContext(Dispatchers.IO) {
        val sql = db.openHelper.writableDatabase
        sql.query("SELECT localPath FROM attachments WHERE calendarId = ?", arrayOf(id)).use { c ->
            while (c.moveToNext()) c.getString(0)?.let { FileStore.delete(it) }
        }
        sql.query("SELECT id FROM payments WHERE calendarId = ?", arrayOf(id)).use { c ->
            while (c.moveToNext()) AlarmScheduler.cancel(context, c.getString(0))
        }
        listOf("payments", "attachments", "day_notes", "shopping_items", "shopping_lists", "notes")
            .forEach { table -> sql.execSQL("DELETE FROM $table WHERE calendarId = ?", arrayOf(id)) }
        db.invalidationTracker.refreshVersionsAsync()
    }

    suspend fun refreshAccount() {
        if (!api.isLoggedIn) return
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

    fun calendarIdNow(): String = _calendarId.value

    // ---------------------------------------------------------------- what the bank says

    fun observeBankRules(): Flow<List<BankRule>> = bank.observeRules()
    fun observePendingMovements(): Flow<List<BankMovement>> = bank.observePending()
    fun observeRecentMovements(): Flow<List<BankMovement>> = bank.observeRecent()
    fun observeNotificationSamples(): Flow<List<NotificationSample>> = bank.observeSamples()

    suspend fun saveBankRule(rule: BankRule) {
        val stored = rule.copy(
            createdAt = if (rule.createdAt == 0L) System.currentTimeMillis() else rule.createdAt
        )
        bank.upsertRule(stored)
        if (!stored.enabled) return

        // the notification it was taught from is already here: read it now, not next time
        bank.samplesFor(stored.packageName)
            .filter { stored.matches(it.title, it.text) }
            .forEach { sample ->
                recordMovement(stored, sample.packageName, sample.appLabel, sample.title, sample.text, sample.seenAt)
            }
    }

    suspend fun deleteBankRule(id: String) = bank.deleteRule(id)

    suspend fun clearNotificationSamples() = bank.clearSamples()

    /**
     * Every notification worth a look passes here. It is remembered for a moment so a rule
     * can be taught from it, and it becomes a movement only when one of the owner's rules
     * recognises the wording and there is a figure to read.
     */
    suspend fun readNotification(
        packageName: String,
        appLabel: String,
        title: String,
        text: String,
        postedAt: Long
    ): Boolean {
        bank.upsertSample(
            NotificationSample(
                packageName = packageName, appLabel = appLabel,
                title = title, text = text, seenAt = postedAt
            )
        )
        bank.trimSamples()

        val rule = bank.enabledRules().firstOrNull {
            it.packageName == packageName && it.matches(title, text)
        } ?: return false
        return recordMovement(rule, packageName, appLabel, title, text, postedAt)
    }

    /**
     * Goes over the notifications already on the phone with every rule there is. For the
     * rules written before this existed, and for a wording taught after the fact.
     */
    suspend fun rereadSamples(): Int {
        var made = 0
        for (rule in bank.enabledRules()) {
            bank.samplesFor(rule.packageName)
                .filter { rule.matches(it.title, it.text) }
                .forEach { sample ->
                    if (recordMovement(rule, sample.packageName, sample.appLabel, sample.title, sample.text, sample.seenAt)) {
                        made++
                    }
                }
        }
        return made
    }

    /** One recognised line becomes one movement, and ticks an entry off when it is sure. */
    private suspend fun recordMovement(
        rule: BankRule,
        packageName: String,
        appLabel: String,
        title: String,
        text: String,
        happenedAt: Long
    ): Boolean {
        val amount = MoneyText.amountCents("$title $text") ?: return false

        // banks repeat themselves: the same line twice in a few hours is one movement
        if (bank.seenAlready(packageName, amount, text, happenedAt - 6 * 60 * 60 * 1000L) > 0) return false

        val movement = BankMovement(
            packageName = packageName, appLabel = appLabel, title = title, text = text,
            amountCents = amount, kind = rule.kind, happenedAt = happenedAt
        )
        bank.upsertMovement(movement)
        bank.forgetOlderThan(happenedAt - 90L * 24 * 60 * 60 * 1000)

        // a shop the house has already sorted out once closes the same series again
        val shop = MoneyText.merchant(text, title)
        val learnt = shop?.let { bank.linkFor(MoneyText.bare(it)) }
        if (learnt != null) {
            val day = java.time.Instant.ofEpochMilli(happenedAt)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
            val waiting = payments.nearestOpenInSeries(learnt.seriesId, day)
            if (waiting != null) {
                confirmMovement(movement.id, waiting.id, auto = true)
                return true
            }
        }

        // figure and wording both agree with something that was waiting: tick it off
        val best = candidatesFor(movement).firstOrNull()
        if (best != null && best.sure) {
            confirmMovement(movement.id, best.payment.id, auto = true)
            return true
        }

        // nothing was waiting for it: write it into its day rather than leave it hanging
        if (prefs.autoAddExpenses) fileIntoTheDay(movement)
        return true
    }

    /**
     * The movements still waiting, put where they belong. Anything that goes wrong is said
     * out loud: a silence here is how a payment disappears without anybody noticing.
     */
    suspend fun fileWaitingMovements(): Int {
        if (!prefs.autoAddExpenses) return 0
        var filed = 0
        for (movement in bank.stillWaiting()) {
            runCatching { fileIntoTheDay(movement) }
                .onSuccess { filed++ }
                .onFailure {
                    android.util.Log.w(
                        "PayPlan",
                        "could not file ${movement.amountCents} cents: ${it.javaClass.simpleName}: ${it.message}"
                    )
                }
        }
        return filed
    }

    /**
     * An expense nobody expected goes into the day it happened. A big one gets an entry of
     * its own; the small change of a day, a coffee here and a bus ticket there, would bury
     * the calendar, so it all joins one entry that grows as the day goes on.
     */
    private suspend fun fileIntoTheDay(movement: BankMovement) {
        // money arriving is never guessed at: it waits to be confirmed by hand
        if (movement.isIncome) return

        if (movement.amountCents > prefs.smallExpenseCents) {
            entryFromMovement(movement.id)
            return
        }

        val stamp = System.currentTimeMillis()
        val moment = java.time.Instant.ofEpochMilli(movement.happenedAt)
            .atZone(java.time.ZoneId.systemDefault())
        val day = moment.toLocalDate()
        val shop = MoneyText.merchant(movement.text, movement.title)
            ?: movement.title.ifBlank { movement.appLabel }
        val me = prefs.userId.ifBlank { null }

        // its own entry, with its own figure and its own category: the day shows them as one
        // line, but a year of coffees can be added up and told apart from a year of petrol
        val entry = Payment(
            calendarId = _calendarId.value,
            seriesId = newId(),
            ownerUserId = me,
            createdByUserId = me,
            title = shop,
            amountCents = movement.amountCents,
            currency = currency(),
            category = categoryFor(shop),
            groupKey = SMALL_GROUP,
            dueDate = day.toEpochDay(),
            dueTimeMinutes = moment.hour * 60 + moment.minute,
            kind = EntryKind.BILL.name,
            status = PayStatus.PAID.name,
            paidAt = movement.happenedAt,
            paidAmountCents = movement.amountCents,
            paidByUserId = me,
            alarmEnabled = false,
            requireReceipt = false,
            createdAt = stamp,
            updatedAt = stamp,
            pendingSync = true
        )
        payments.insert(entry)

        bank.upsertMovement(
            movement.copy(
                status = MovementStatus.MATCHED,
                matchedPaymentId = entry.id,
                matchedAt = stamp
            )
        )
        syncQuietly()
    }

    // ---------------------------------------------------------------- the same money twice

    /**
     * What a planned bill might already have been paid as: anything closed in the last week
     * for roughly the same figure. The grouped small change is left out, it is not one thing.
     */
    suspend fun alreadyPaidLike(planned: Payment, days: Long = 7, tolerance: Double = 0.10): List<Payment> {
        if (planned.amountCents <= 0) return emptyList()
        val from = Format.today().minusDays(days).toEpochDay()
        val room = (planned.amountCents * tolerance).toLong().coerceAtLeast(1)
        return payments.paidSince(_calendarId.value, from)
            .filter { it.id != planned.id && it.seriesId != planned.seriesId }
            .filter { it.title != SMALL_EXPENSES && it.groupKey != SMALL_GROUP }
            .filter { it.kind == planned.kind }
            .filter { kotlin.math.abs(it.amountCents - planned.amountCents) <= room }
            .sortedBy { kotlin.math.abs(it.amountCents - planned.amountCents) }
    }

    /**
     * The planned bill and the payment that actually happened are one thing: the planned one
     * is closed for the figure that left the account, and the bank's own entry goes, so the
     * month is not counted twice. With [remember], that shop closes this series from now on.
     */
    suspend fun resolveWith(plannedId: String, actualId: String, remember: Boolean) {
        val planned = payments.getById(plannedId) ?: return
        val actual = payments.getById(actualId) ?: return
        val stamp = System.currentTimeMillis()

        payments.update(
            stamped(
                planned.copy(
                    status = PayStatus.PAID.name,
                    paidAt = actual.paidAt ?: stamp,
                    paidAmountCents = actual.paidAmountCents ?: actual.amountCents,
                    paidByUserId = actual.paidByUserId ?: prefs.userId.ifBlank { null },
                    notes = listOf(planned.notes, actual.notes).filter { it.isNotBlank() }.joinToString("\n"),
                    snoozedUntil = null
                )
            )
        )
        payments.update(stamped(actual.copy(deletedAt = stamp)))
        AlarmScheduler.cancel(context, planned.id)
        AlarmScheduler.dismissNotification(context, planned.id)

        if (remember && actual.title.isNotBlank()) {
            bank.upsertLink(
                BankLink(
                    shop = MoneyText.bare(actual.title),
                    label = actual.title,
                    seriesId = planned.seriesId,
                    createdAt = stamp
                )
            )
        }
        topUp(planned.seriesId)
        syncQuietly()
    }

    fun observeBankLinks(): Flow<List<BankLink>> = bank.observeLinks()

    suspend fun deleteBankLink(id: String) = bank.deleteLink(id)

    // ---------------------------------------------------------------- undoing the old lump

    /** How many of those lumps can be taken apart without losing a cent. */
    suspend fun unpackableGroups(): Int = lumps().count { canUnpack(it) }

    private suspend fun lumps(): List<Payment> =
        (payments.titled(_calendarId.value, SMALL_EXPENSES) + payments.packed(_calendarId.value))
            .distinctBy { it.id }

    private fun canUnpack(entry: Payment): Boolean =
        SmallChange.isPacked(entry.notes, entry.amountCents)

    /**
     * Takes the old lumps apart: one entry per line, each with its shop, its time and its
     * own category to be given. Only the ones whose lines add up exactly to the total are
     * touched; anything else is left alone rather than guessed at. Returns how many went.
     */
    suspend fun unpackSmallGroups(onlyId: String? = null): Int {
        var done = 0
        val me = prefs.userId.ifBlank { null }
        for (entry in lumps().filter { onlyId == null || it.id == onlyId }) {
            if (!canUnpack(entry)) continue
            val stamp = System.currentTimeMillis()
            for ((minutes, shop, cents, sorted) in entry.notes.lines()
                .mapNotNull { SmallChange.read(it) }) {
                payments.insert(
                    Payment(
                        calendarId = entry.calendarId,
                        seriesId = newId(),
                        ownerUserId = entry.ownerUserId ?: me,
                        createdByUserId = entry.createdByUserId ?: me,
                        title = shop,
                        amountCents = cents,
                        currency = entry.currency,
                        category = sorted.ifBlank { categoryFor(shop) },
                        groupKey = SMALL_GROUP,
                        dueDate = entry.dueDate,
                        dueTimeMinutes = minutes,
                        kind = EntryKind.BILL.name,
                        status = PayStatus.PAID.name,
                        paidAt = entry.paidAt ?: stamp,
                        paidAmountCents = cents,
                        paidByUserId = entry.paidByUserId ?: me,
                        alarmEnabled = false,
                        requireReceipt = false,
                        createdAt = stamp,
                        updatedAt = stamp,
                        pendingSync = true
                    )
                )
            }
            payments.update(stamped(entry.copy(deletedAt = stamp)))
            done++
        }
        if (done > 0) syncQuietly()
        return done
    }

    private suspend fun loose(day: LocalDate?): List<Payment> {
        val under = prefs.smallExpenseCents
        val all = payments.smallChange(_calendarId.value).filter { it.isSmallChange(under) }
        return if (day == null) all else all.filter { it.dueDate == day.toEpochDay() }
    }

    /**
     * Puts the day's small change back into one entry. Every purchase keeps its shop, its
     * time, its figure and the category it was sorted into, written on a line of its own, so
     * opening it up again gives back exactly what went in. A day of one purchase is left as
     * it is: there is nothing to put together.
     */
    suspend fun mergeSmallChange(day: LocalDate? = null): Int {
        var made = 0
        val me = prefs.userId.ifBlank { null }
        val money = currency()
        for ((_, rows) in loose(day).groupBy { it.dueDate }) {
            if (rows.size < 2) continue
            val stamp = System.currentTimeMillis()
            val first = rows.minByOrNull { it.dueTimeMinutes } ?: continue
            val sorted = rows.map { it.category.trim() }.filter { it.isNotBlank() }.distinct()
            payments.insert(
                Payment(
                    calendarId = first.calendarId,
                    seriesId = newId(),
                    ownerUserId = first.ownerUserId ?: me,
                    createdByUserId = first.createdByUserId ?: me,
                    title = SMALL_EXPENSES,
                    amountCents = rows.sumOf { it.amountCents },
                    currency = money,
                    // one entry can only carry one; the lines below carry the rest
                    category = sorted.singleOrNull().orEmpty(),
                    groupKey = SMALL_PACKED,
                    notes = rows.sortedBy { it.dueTimeMinutes }.joinToString("\n") {
                        SmallChange.line(
                            it.dueTimeMinutes,
                            it.title,
                            Format.money(it.amountCents, money),
                            it.category
                        )
                    },
                    dueDate = first.dueDate,
                    dueTimeMinutes = first.dueTimeMinutes,
                    kind = EntryKind.BILL.name,
                    status = PayStatus.PAID.name,
                    paidAt = rows.mapNotNull { it.paidAt }.minOrNull() ?: stamp,
                    paidAmountCents = rows.sumOf { it.amountCents },
                    paidByUserId = first.paidByUserId ?: me,
                    alarmEnabled = false,
                    requireReceipt = false,
                    createdAt = stamp,
                    updatedAt = stamp,
                    pendingSync = true
                )
            )
            for (row in rows) payments.update(stamped(row.copy(deletedAt = stamp)))
            made++
        }
        if (made > 0) syncQuietly()
        return made
    }

    /** What this movement could be paying off, best first. */
    suspend fun candidatesFor(movement: BankMovement): List<MovementMatch> {
        val day = java.time.Instant.ofEpochMilli(movement.happenedAt)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val rows = payments.openBetween(
            _calendarId.value, day.minusDays(12).toEpochDay(), day.plusDays(12).toEpochDay()
        )
        val said = "${movement.title} ${movement.text}"

        return rows.mapNotNull { payment ->
            val wanted = if (movement.isIncome) payment.isIncome else payment.isBill
            if (!wanted) return@mapNotNull null

            val gap = kotlin.math.abs(payment.amountCents - movement.amountCents)
            val exact = gap == 0L
            val close = gap <= maxOf(50L, payment.amountCents / 100)
            if (!exact && !close) return@mapNotNull null

            val like = MoneyText.similarity("${payment.title} ${payment.category}", said)
            val score = (if (exact) 0.6 else 0.35) + like * 0.4
            MovementMatch(
                payment = payment,
                score = score,
                like = like,
                // the figure to the cent and words in common: no need to ask
                sure = exact && like >= 0.34
            )
        }.sortedByDescending { it.score }
    }

    /** The movement was this entry: close the entry for the figure the bank said. */
    suspend fun confirmMovement(movementId: String, paymentId: String, auto: Boolean = false) {
        val movement = bank.movement(movementId) ?: return
        markPaid(paymentId, movement.amountCents)
        bank.upsertMovement(
            movement.copy(
                status = MovementStatus.MATCHED,
                matchedPaymentId = paymentId,
                matchedAt = System.currentTimeMillis(),
                auto = auto
            )
        )
    }

    /** Nothing was waiting for it: the movement becomes an entry of its own, already closed. */
    suspend fun entryFromMovement(movementId: String): String? {
        val movement = bank.movement(movementId) ?: return null
        val stamp = System.currentTimeMillis()
        val day = java.time.Instant.ofEpochMilli(movement.happenedAt)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val me = prefs.userId.ifBlank { null }
        val shopName = MoneyText.merchant(movement.text, movement.title)
            ?: movement.title.ifBlank { movement.appLabel }
        val entry = Payment(
            calendarId = _calendarId.value,
            seriesId = newId(),
            ownerUserId = me,
            createdByUserId = me,
            // the shop, not the bank's greeting: "Centro Sportivo" beats "Pagamento accettato"
            title = shopName,
            amountCents = movement.amountCents,
            currency = currency(),
            category = categoryFor(shopName),
            notes = movement.text,
            dueDate = day.toEpochDay(),
            dueTimeMinutes = 12 * 60,
            kind = if (movement.isIncome) EntryKind.INCOME.name else EntryKind.BILL.name,
            status = PayStatus.PAID.name,
            paidAt = movement.happenedAt,
            paidAmountCents = movement.amountCents,
            paidByUserId = me,
            alarmEnabled = false,
            requireReceipt = false,
            createdAt = stamp,
            updatedAt = stamp,
            pendingSync = true
        )
        payments.insert(entry)
        bank.upsertMovement(
            movement.copy(
                status = MovementStatus.MATCHED,
                matchedPaymentId = entry.id,
                matchedAt = stamp
            )
        )
        syncQuietly()
        return entry.id
    }

    suspend fun ignoreMovement(movementId: String) {
        val movement = bank.movement(movementId) ?: return
        bank.upsertMovement(movement.copy(status = MovementStatus.IGNORED))
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
        if (isLocal) {
            val cal = localCalendar(name)
            cacheCalendars(_calendars.value + cal)
            switchCalendar(cal.id)
            return@runCatching
        }
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
        if (isLocal) {
            cacheCalendars(_calendars.value.map {
                if (it.id == _calendarId.value) it.copy(name = name.ifBlank { it.name }, currency = currency) else it
            })
            prefs.currency = currency
            return@runCatching
        }
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
        if (isLocal) {
            wipeLocalCalendar(id)
            val rest = _calendars.value.filter { it.id != id }
            cacheCalendars(rest)
            switchCalendar(rest.firstOrNull()?.id ?: "")
            return@runCatching
        }
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
        if (isLocal) {
            prefs.userName = name
            cacheCalendars(_calendars.value.map { cal -> cal.copy(members = listOf(localMe())) })
            return@runCatching
        }
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

/** One way a movement could be read: which entry, how sure. */
data class MovementMatch(
    val payment: Payment,
    val score: Double,
    val like: Double,
    /** figure to the cent and words in common: safe to tick off alone */
    val sure: Boolean
)

/** The one entry that holds a day's small change. */
const val SMALL_EXPENSES = "Small expenses"

/** What marks an entry as part of the day's small change, shown as one line. */
const val SMALL_GROUP = "small"

/** What marks a day's small change that was put back together, and can be opened again. */
const val SMALL_PACKED = "smallpack"
