package com.payandplan.app.net

import com.payandplan.app.data.Attachment
import com.payandplan.app.data.CalendarSpace
import com.payandplan.app.data.DayNote
import com.payandplan.app.data.Member
import com.payandplan.app.data.Note
import com.payandplan.app.data.OwnerType
import com.payandplan.app.data.Payment
import com.payandplan.app.data.ShoppingItem
import com.payandplan.app.data.ShoppingList
import com.payandplan.app.util.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ApiException(message: String, val code: Int = 0) : Exception(message)

data class AuthResult(val token: String, val userId: String, val name: String, val email: String)

/** A place found on the map. */
data class Place(val name: String, val lat: Double, val lon: Double)

/** What the server read off a photographed till receipt. */
data class ReceiptItem(val name: String, val quantity: String, val priceCents: Long?)
data class ReceiptResult(
    val store: String,
    val date: String?,
    val epochDay: Long,
    val totalCents: Long,
    val items: List<ReceiptItem>,
    val attachment: Attachment?
)

data class SyncPull(
    val serverTime: Long,
    val calendar: CalendarSpace?,
    val payments: List<Payment>,
    val dayNotes: List<DayNote>,
    val attachments: List<Attachment>,
    val lists: List<ShoppingList>,
    val items: List<ShoppingItem>,
    val notes: List<Note>
)

/**
 * Small hand written REST client. No third party HTTP library: the API is tiny and this keeps
 * the app free of extra dependencies.
 */
class ApiClient(private val prefs: Prefs) {

    private fun base(): String = prefs.serverUrl.trimEnd('/')

    val isConfigured: Boolean get() = prefs.serverUrl.isNotBlank()
    val isLoggedIn: Boolean get() = prefs.token.isNotBlank() && isConfigured

    // ------------------------------------------------------------ plumbing

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        auth: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        val url = URL(base() + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 30000
            doInput = true
            setRequestProperty("Accept", "application/json")
            if (auth && prefs.token.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${prefs.token}")
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).optString("error") }.getOrNull()
                throw ApiException(message?.takeIf { it.isNotBlank() } ?: "HTTP $code", code)
            }
            text
        } finally {
            conn.disconnect()
        }
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else optLong(key)

    // ------------------------------------------------------------ auth

    suspend fun register(email: String, password: String, name: String): AuthResult =
        auth("/api/auth/register", JSONObject().put("email", email).put("password", password).put("name", name))

    suspend fun login(email: String, password: String): AuthResult =
        auth("/api/auth/login", JSONObject().put("email", email).put("password", password))

    private suspend fun auth(path: String, body: JSONObject): AuthResult {
        val json = JSONObject(request("POST", path, body, auth = false))
        val user = json.getJSONObject("user")
        return AuthResult(
            token = json.getString("token"),
            userId = user.getString("id"),
            name = user.optString("name"),
            email = user.optString("email")
        )
    }

    suspend fun me(): Triple<Member, List<CalendarSpace>, Boolean> {
        val json = JSONObject(request("GET", "/api/me"))
        val u = json.getJSONObject("user")
        val me = Member(u.getString("id"), u.optString("name"), u.optString("email"), u.optInt("colorIndex"), "self")
        val calendars = json.getJSONArray("calendars").mapObjects { calendarOf(it) }
        // third value: whether this server can read receipts (it has an Ollama behind it)
        val receipts = json.optJSONObject("features")?.optBoolean("receipts") ?: false
        return Triple(me, calendars, receipts)
    }

    suspend fun updateMe(name: String) {
        request("PATCH", "/api/me", JSONObject().put("name", name))
    }

    suspend fun createCalendar(name: String): CalendarSpace =
        calendarOf(JSONObject(request("POST", "/api/calendars", JSONObject().put("name", name))))

    suspend fun joinCalendar(code: String): CalendarSpace =
        calendarOf(JSONObject(request("POST", "/api/calendars/join", JSONObject().put("code", code))))

    suspend fun updateCalendar(id: String, name: String, currency: String): CalendarSpace =
        calendarOf(JSONObject(request("PATCH", "/api/calendars/$id",
            JSONObject().put("name", name).put("currency", currency))))

    suspend fun rotateInvite(id: String): CalendarSpace =
        calendarOf(JSONObject(request("POST", "/api/calendars/$id/rotate-code", JSONObject())))

    suspend fun removeMember(calendarId: String, userId: String): CalendarSpace =
        calendarOf(JSONObject(request("DELETE", "/api/calendars/$calendarId/members/$userId")))

    suspend fun leaveCalendar(calendarId: String) {
        request("POST", "/api/calendars/$calendarId/leave", JSONObject())
    }

    /** Owner only. The calendar and everything in it stop being visible to every member. */
    suspend fun deleteCalendar(calendarId: String) {
        request("DELETE", "/api/calendars/$calendarId")
    }

    private fun calendarOf(json: JSONObject) = CalendarSpace(
        id = json.getString("id"),
        name = json.optString("name"),
        colorIndex = json.optInt("colorIndex"),
        currency = json.optString("currency", "EUR"),
        ownerUserId = json.optString("ownerUserId"),
        inviteCode = json.optString("inviteCode"),
        members = json.optJSONArray("members").mapObjects {
            Member(
                it.getString("id"), it.optString("name"), it.optString("email"),
                it.optInt("colorIndex"), it.optString("role", "member")
            )
        }
    )

    // ------------------------------------------------------------ sync

    suspend fun pull(calendarId: String, since: Long): SyncPull {
        val json = JSONObject(request("GET", "/api/calendars/$calendarId/sync?since=$since"))
        return SyncPull(
            serverTime = json.optLong("serverTime"),
            calendar = json.optJSONObject("calendar")?.let { calendarOf(it) },
            payments = json.optJSONArray("payments").mapObjects { paymentOf(it, calendarId) },
            dayNotes = json.optJSONArray("dayNotes").mapObjects { noteOf(it, calendarId) },
            attachments = json.optJSONArray("attachments").mapObjects { attachmentOf(it, calendarId) },
            lists = json.optJSONArray("shoppingLists").mapObjects { listOf(it, calendarId) },
            items = json.optJSONArray("shoppingItems").mapObjects { itemOf(it, calendarId) },
            notes = json.optJSONArray("notes").mapObjects { freeNoteOf(it, calendarId) }
        )
    }

    suspend fun push(
        calendarId: String,
        payments: List<Payment>,
        dayNotes: List<DayNote>,
        lists: List<ShoppingList>,
        items: List<ShoppingItem>,
        notes: List<Note>
    ): Long {
        val body = JSONObject()
            .put("payments", JSONArray(payments.map { paymentJson(it) }))
            .put("dayNotes", JSONArray(dayNotes.map { noteJson(it) }))
            .put("shoppingLists", JSONArray(lists.map { listJson(it) }))
            .put("shoppingItems", JSONArray(items.map { itemJson(it) }))
            .put("notes", JSONArray(notes.map { freeNoteJson(it) }))
        val json = JSONObject(request("POST", "/api/calendars/$calendarId/sync", body))
        return json.optLong("serverTime")
    }

    /** Asks the server to read a public page and, when [noteId] is given, save its images. */
    suspend fun unfurl(calendarId: String, url: String, noteId: String?): Triple<String, String, Int> {
        val body = JSONObject().put("url", url)
        if (noteId != null) body.put("noteId", noteId)
        val json = JSONObject(request("POST", "/api/calendars/$calendarId/unfurl", body))
        return Triple(
            json.optString("title"),
            json.optString("text"),
            json.optJSONArray("savedImages")?.length() ?: 0
        )
    }

    // ------------------------------------------------------------ attachments

    fun attachmentUrl(id: String): String =
        "${base()}/api/attachments/$id/raw?token=${URLEncoder.encode(prefs.token, "UTF-8")}"

    suspend fun uploadAttachment(
        calendarId: String,
        attachment: Attachment,
        file: File
    ): Attachment = withContext(Dispatchers.IO) {
        val boundary = "----payplan${System.currentTimeMillis()}"
        val conn = (URL("${base()}/api/calendars/$calendarId/attachments").openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.setRequestProperty("Authorization", "Bearer ${prefs.token}")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        try {
            BufferedOutputStream(conn.outputStream).use { out ->
                fun field(name: String, value: String) {
                    out.write("--$boundary\r\n".toByteArray())
                    out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                    out.write("$value\r\n".toByteArray())
                }
                field("id", attachment.id)
                field("isReceipt", attachment.isReceipt.toString())
                attachment.paymentId?.let { field("paymentId", it) }
                attachment.itemId?.let { field("itemId", it) }
                attachment.noteId?.let { field("noteId", it) }
                attachment.epochDay?.let { field("epochDay", it.toString()) }

                out.write("--$boundary\r\n".toByteArray())
                out.write(
                    ("Content-Disposition: form-data; name=\"file\"; filename=\"${attachment.fileName}\"\r\n")
                        .toByteArray()
                )
                out.write("Content-Type: ${attachment.mime}\r\n\r\n".toByteArray())
                file.inputStream().use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw ApiException("upload failed: HTTP $code", code)
            attachmentOf(JSONObject(text), calendarId).copy(
                localPath = attachment.localPath,
                pendingUpload = false
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Sends a receipt photo to the server, which has the model read it. Slow: give it minutes. */
    /**
     * A barcode against the food database. Returns the readable label and, when [itemId] was
     * given, the picture the server stored as that item's photo. Null when it is unknown.
     */
    suspend fun lookupProduct(calendarId: String, barcode: String, itemId: String?): Pair<String, Attachment?>? {
        val body = JSONObject().put("barcode", barcode)
        if (itemId != null) body.put("itemId", itemId)
        val text = try {
            request("POST", "/api/calendars/$calendarId/products/lookup", body)
        } catch (e: ApiException) {
            if (e.code == 404) return null
            throw e
        }
        val j = JSONObject(text)
        val label = j.getJSONObject("product").optString("label")
        val attachment = j.optJSONObject("attachment")?.let { attachmentOf(it, calendarId) }
        return label to attachment
    }

    /** Free text address -> places on OpenStreetMap, through the server. */
    suspend fun searchPlaces(query: String): List<Place> {
        val json = JSONObject(request("GET", "/api/places?q=" + java.net.URLEncoder.encode(query, "UTF-8")))
        return json.getJSONArray("places").mapObjects {
            Place(it.optString("name"), it.optDouble("lat"), it.optDouble("lon"))
        }
    }

    /** A shared Google Calendar link -> the event behind it, or null when the page hides it. */
    suspend fun resolveEventLink(calendarId: String, url: String): com.payandplan.app.util.CalendarEvent? {
        val text = try {
            request("POST", "/api/calendars/$calendarId/resolve-event", JSONObject().put("url", url))
        } catch (e: ApiException) {
            if (e.code == 404) return null
            throw e
        }
        val j = JSONObject(text)
        if (j.isNull("epochDay")) return null
        return com.payandplan.app.util.CalendarEvent(
            title = j.optString("title").ifBlank { "Event" },
            epochDay = j.getLong("epochDay"),
            minutesOfDay = j.optInt("minutes", 9 * 60),
            location = j.optString("location"),
            notes = listOf("Shared from Google Calendar", url, j.optString("notes")).filter { it.isNotBlank() }.joinToString("\n")
        )
    }

    /** Copies another item's photo onto this one; null when the source has none. */
    suspend fun copyItemPhoto(calendarId: String, itemId: String, sourceItemId: String): Attachment? {
        val text = try {
            request("POST", "/api/calendars/$calendarId/items/$itemId/photo-from/$sourceItemId", JSONObject())
        } catch (e: ApiException) {
            if (e.code == 404) return null
            throw e
        }
        return attachmentOf(JSONObject(text), calendarId)
    }

    /** STOP pressed: tells the server to drop the model call for that job. */
    suspend fun stopReceipt(jobId: String) {
        request("POST", "/api/receipt-jobs/$jobId/stop", JSONObject())
    }

    suspend fun readReceipt(calendarId: String, file: File, mime: String, today: Long, jobId: String): ReceiptResult =
        withContext(Dispatchers.IO) {
            val boundary = "----payplan${System.currentTimeMillis()}"
            val conn = (URL("${base()}/api/calendars/$calendarId/receipt").openConnection() as HttpURLConnection)
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 180000
            conn.setRequestProperty("Authorization", "Bearer ${prefs.token}")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            // STOP on the phone cancels the coroutine; closing the socket is what actually ends the wait
            val onCancel = coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException) runCatching { conn.disconnect() }
            }
            try {
                BufferedOutputStream(conn.outputStream).use { out ->
                    out.write("--$boundary\r\n".toByteArray())
                    out.write("Content-Disposition: form-data; name=\"today\"\r\n\r\n$today\r\n".toByteArray())
                    out.write("--$boundary\r\n".toByteArray())
                    out.write("Content-Disposition: form-data; name=\"jobId\"\r\n\r\n$jobId\r\n".toByteArray())
                    out.write("--$boundary\r\n".toByteArray())
                    out.write(
                        "Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n".toByteArray()
                    )
                    out.write("Content-Type: $mime\r\n\r\n".toByteArray())
                    file.inputStream().use { it.copyTo(out) }
                    out.write("\r\n--$boundary--\r\n".toByteArray())
                }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    val message = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    throw ApiException(message?.takeIf { it.isNotBlank() } ?: "HTTP $code", code)
                }
                val j = JSONObject(text)
                val items = j.optJSONArray("items") ?: JSONArray()
                ReceiptResult(
                    store = j.optString("store").ifBlank { "Receipt" },
                    date = j.stringOrNull("date"),
                    epochDay = j.optLong("epochDay"),
                    totalCents = j.optLong("totalCents"),
                    items = (0 until items.length()).map { i ->
                        val it = items.getJSONObject(i)
                        ReceiptItem(it.optString("name"), it.optString("quantity"), it.longOrNull("priceCents"))
                    },
                    attachment = j.optJSONObject("attachment")?.let { attachmentOf(it, calendarId) }
                )
            } finally {
                onCancel?.dispose()
                conn.disconnect()
            }
        }

    suspend fun deleteAttachment(id: String) {
        runCatching { request("DELETE", "/api/attachments/$id") }
    }

    suspend fun download(id: String, target: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(attachmentUrl(id)).openConnection() as HttpURLConnection)
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            try {
                if (conn.responseCode !in 200..299) return@runCatching false
                target.parentFile?.mkdirs()
                conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
                true
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------ mapping

    private fun paymentOf(j: JSONObject, calendarId: String) = Payment(
        id = j.getString("id"),
        calendarId = calendarId,
        seriesId = j.optString("seriesId"),
        ownerUserId = j.stringOrNull("ownerUserId"),
        createdByUserId = j.stringOrNull("createdByUserId"),
        title = j.optString("title"),
        amountCents = j.optLong("amountCents"),
        currency = j.optString("currency", "EUR"),
        colorIndex = j.optInt("colorIndex"),
        category = j.optString("category"),
        dueDate = j.optLong("dueDate"),
        dueTimeMinutes = j.optInt("dueTimeMinutes", 540),
        recurrence = j.optString("recurrence", "NONE"),
        recurrenceEndDate = j.longOrNull("recurrenceEndDate"),
        notes = j.optString("notes"),
        status = j.optString("status", "PENDING"),
        paidAt = j.longOrNull("paidAt"),
        paidAmountCents = j.longOrNull("paidAmountCents"),
        paidByUserId = j.stringOrNull("paidByUserId"),
        remindDaysBefore = j.optInt("remindDaysBefore"),
        nagMinutes = j.optInt("nagMinutes", 60),
        alarmEnabled = j.optInt("alarmEnabled", 1) == 1,
        requireReceipt = j.optInt("requireReceipt", 1) == 1,
        installmentIndex = j.optInt("installmentIndex"),
        installmentCount = j.optInt("installmentCount"),
        visibility = j.optString("visibility", "SHARED"),
        shoppingListId = j.stringOrNull("shoppingListId"),
        kind = j.optString("kind", "BILL"),
        location = j.optString("location"),
        durationMinutes = j.optInt("durationMinutes"),
        latitude = if (j.isNull("latitude")) null else j.optDouble("latitude"),
        longitude = if (j.isNull("longitude")) null else j.optDouble("longitude"),
        createdAt = j.optLong("createdAt"),
        updatedAt = j.optLong("updatedAt"),
        deletedAt = j.longOrNull("deletedAt"),
        pendingSync = false
    )

    private fun paymentJson(p: Payment) = JSONObject()
        .put("id", p.id)
        .put("seriesId", p.seriesId)
        .put("ownerUserId", p.ownerUserId)
        .put("createdByUserId", p.createdByUserId)
        .put("title", p.title)
        .put("amountCents", p.amountCents)
        .put("currency", p.currency)
        .put("colorIndex", p.colorIndex)
        .put("category", p.category)
        .put("dueDate", p.dueDate)
        .put("dueTimeMinutes", p.dueTimeMinutes)
        .put("recurrence", p.recurrence)
        .put("recurrenceEndDate", p.recurrenceEndDate)
        .put("notes", p.notes)
        .put("status", p.status)
        .put("paidAt", p.paidAt)
        .put("paidAmountCents", p.paidAmountCents)
        .put("paidByUserId", p.paidByUserId)
        .put("remindDaysBefore", p.remindDaysBefore)
        .put("nagMinutes", p.nagMinutes)
        .put("alarmEnabled", p.alarmEnabled)
        .put("requireReceipt", p.requireReceipt)
        .put("installmentIndex", p.installmentIndex)
        .put("installmentCount", p.installmentCount)
        .put("visibility", p.visibility)
        .put("shoppingListId", p.shoppingListId)
        .put("kind", p.kind)
        .put("location", p.location)
        .put("durationMinutes", p.durationMinutes)
        .put("latitude", p.latitude ?: JSONObject.NULL)
        .put("longitude", p.longitude ?: JSONObject.NULL)
        .put("createdAt", p.createdAt)
        .put("updatedAt", p.updatedAt)
        .put("deletedAt", p.deletedAt)

    private fun noteOf(j: JSONObject, calendarId: String) = DayNote(
        id = j.getString("id"),
        calendarId = calendarId,
        epochDay = j.optLong("epochDay"),
        text = j.optString("text"),
        updatedAt = j.optLong("updatedAt"),
        deletedAt = j.longOrNull("deletedAt"),
        pendingSync = false
    )

    private fun noteJson(n: DayNote) = JSONObject()
        .put("id", n.id).put("epochDay", n.epochDay).put("text", n.text)
        .put("updatedAt", n.updatedAt).put("deletedAt", n.deletedAt)

    fun attachmentOf(j: JSONObject, calendarId: String) = Attachment(
        id = j.getString("id"),
        calendarId = calendarId,
        ownerType = j.optString("ownerType", OwnerType.PAYMENT),
        paymentId = j.stringOrNull("paymentId"),
        epochDay = j.longOrNull("epochDay"),
        itemId = j.stringOrNull("itemId"),
        noteId = j.stringOrNull("noteId"),
        fileName = j.optString("fileName"),
        mime = j.optString("mime"),
        size = j.optLong("size"),
        isReceipt = j.optInt("isReceipt") == 1 || j.optBoolean("isReceipt"),
        uploadedBy = j.stringOrNull("uploadedBy"),
        localPath = null,
        createdAt = j.optLong("createdAt"),
        updatedAt = j.optLong("updatedAt"),
        deletedAt = j.longOrNull("deletedAt"),
        pendingUpload = false
    )

    private fun listOf(j: JSONObject, calendarId: String) = ShoppingList(
        id = j.getString("id"),
        calendarId = calendarId,
        title = j.optString("title"),
        notes = j.optString("notes"),
        colorIndex = j.optInt("colorIndex"),
        dueDate = j.longOrNull("dueDate"),
        dueTimeMinutes = j.optInt("dueTimeMinutes", 18 * 60),
        assignedToUserId = j.stringOrNull("assignedToUserId"),
        createdByUserId = j.stringOrNull("createdByUserId"),
        budgetCents = j.longOrNull("budgetCents"),
        actualCents = j.longOrNull("actualCents"),
        status = j.optString("status", "OPEN"),
        doneAt = j.longOrNull("doneAt"),
        doneByUserId = j.stringOrNull("doneByUserId"),
        paymentId = j.stringOrNull("paymentId"),
        visibility = j.optString("visibility", "SHARED"),
        createdAt = j.optLong("createdAt"),
        updatedAt = j.optLong("updatedAt"),
        deletedAt = j.longOrNull("deletedAt"),
        pendingSync = false
    )

    private fun listJson(l: ShoppingList) = JSONObject()
        .put("id", l.id).put("title", l.title).put("notes", l.notes)
        .put("colorIndex", l.colorIndex).put("dueDate", l.dueDate)
        .put("dueTimeMinutes", l.dueTimeMinutes)
        .put("assignedToUserId", l.assignedToUserId).put("createdByUserId", l.createdByUserId)
        .put("budgetCents", l.budgetCents).put("actualCents", l.actualCents)
        .put("status", l.status).put("doneAt", l.doneAt).put("doneByUserId", l.doneByUserId)
        .put("paymentId", l.paymentId).put("visibility", l.visibility)
        .put("createdAt", l.createdAt).put("updatedAt", l.updatedAt).put("deletedAt", l.deletedAt)

    private fun freeNoteOf(j: JSONObject, calendarId: String) = Note(
        id = j.getString("id"),
        calendarId = calendarId,
        title = j.optString("title"),
        body = j.optString("body"),
        category = j.optString("category"),
        colorIndex = j.optInt("colorIndex"),
        pinned = j.optInt("pinned") == 1 || j.optBoolean("pinned"),
        ownerUserId = j.stringOrNull("ownerUserId"),
        createdByUserId = j.stringOrNull("createdByUserId"),
        visibility = j.optString("visibility", "SHARED"),
        createdAt = j.optLong("createdAt"),
        updatedAt = j.optLong("updatedAt"),
        deletedAt = j.longOrNull("deletedAt"),
        pendingSync = false
    )

    private fun freeNoteJson(n: Note) = JSONObject()
        .put("id", n.id).put("title", n.title).put("body", n.body)
        .put("category", n.category).put("colorIndex", n.colorIndex).put("pinned", n.pinned)
        .put("ownerUserId", n.ownerUserId).put("createdByUserId", n.createdByUserId)
        .put("visibility", n.visibility)
        .put("createdAt", n.createdAt).put("updatedAt", n.updatedAt).put("deletedAt", n.deletedAt)

    private fun itemOf(j: JSONObject, calendarId: String) = ShoppingItem(
        id = j.getString("id"),
        listId = j.optString("listId"),
        calendarId = calendarId,
        text = j.optString("text"),
        quantity = j.optString("quantity"),
        checked = j.optInt("checked") == 1 || j.optBoolean("checked"),
        priceCents = j.longOrNull("priceCents"),
        sortIndex = j.optInt("sortIndex"),
        createdAt = j.optLong("createdAt"),
        updatedAt = j.optLong("updatedAt"),
        deletedAt = j.longOrNull("deletedAt"),
        pendingSync = false
    )

    private fun itemJson(i: ShoppingItem) = JSONObject()
        .put("id", i.id).put("listId", i.listId).put("text", i.text).put("quantity", i.quantity)
        .put("checked", i.checked).put("priceCents", i.priceCents).put("sortIndex", i.sortIndex)
        .put("createdAt", i.createdAt).put("updatedAt", i.updatedAt).put("deletedAt", i.deletedAt)
}

private fun <T> JSONArray?.mapObjects(block: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(block) }
}
