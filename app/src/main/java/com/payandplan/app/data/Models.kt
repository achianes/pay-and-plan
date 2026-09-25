package com.payandplan.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

enum class Recurrence(val label: String) {
    NONE("One time"),
    DAILY("Every day"),
    WEEKLY("Every week"),
    BIWEEKLY("Every 2 weeks"),
    MONTHLY("Every month"),
    QUARTERLY("Every 3 months"),
    SEMIANNUAL("Every 6 months"),
    YEARLY("Every year");

    companion object {
        fun from(name: String?): Recurrence = entries.firstOrNull { it.name == name } ?: NONE
    }
}

enum class PayStatus { PENDING, PAID, SKIPPED, SUSPENDED;
    companion object {
        fun from(name: String?): PayStatus = entries.firstOrNull { it.name == name } ?: PENDING
    }
}

/**
 * A bill has an amount and gets paid, an appointment just happens with an optional cost,
 * and an income is money expected to arrive for somebody.
 */
enum class EntryKind { BILL, APPOINTMENT, INCOME, REMINDER;
    companion object {
        fun from(name: String?): EntryKind = entries.firstOrNull { it.name == name } ?: BILL
    }
}

object Visibility {
    const val SHARED = "SHARED"
    const val PRIVATE = "PRIVATE"
}

object OwnerType {
    const val PAYMENT = "PAYMENT"
    const val DAY = "DAY"
    const val ITEM = "ITEM"
    const val NOTE = "NOTE"
}

@Entity(
    tableName = "payments",
    indices = [Index("dueDate"), Index("seriesId"), Index("status"), Index("calendarId")]
)
data class Payment(
    @PrimaryKey val id: String = newId(),
    val calendarId: String = "",
    val seriesId: String = "",
    /** who has to pay it / whose appointment it is */
    val ownerUserId: String? = null,
    val createdByUserId: String? = null,
    val title: String = "",
    val amountCents: Long = 0,
    val currency: String = "EUR",
    val colorIndex: Int = 0,
    val category: String = "",
    /** epoch day of the due date */
    val dueDate: Long = 0,
    /** minutes from midnight for the due time / alarm time */
    val dueTimeMinutes: Int = 9 * 60,
    val recurrence: String = Recurrence.NONE.name,
    val recurrenceEndDate: Long? = null,
    val notes: String = "",
    val status: String = PayStatus.PENDING.name,
    val paidAt: Long? = null,
    val paidAmountCents: Long? = null,
    val paidByUserId: String? = null,
    /** how many days before the due date the first reminder fires */
    val remindDaysBefore: Int = 0,
    /** minutes between nagging repeats while still unpaid. 0 = single shot */
    val nagMinutes: Int = 60,
    val alarmEnabled: Boolean = true,
    val requireReceipt: Boolean = true,
    val installmentIndex: Int = 0,
    val installmentCount: Int = 0,
    val visibility: String = Visibility.SHARED,
    val shoppingListId: String? = null,
    val kind: String = EntryKind.BILL.name,
    val location: String = "",
    val durationMinutes: Int = 0,
    /** where the appointment is, when it was picked on the map */
    val latitude: Double? = null,
    val longitude: Double? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val deletedAt: Long? = null,
    // ---- local only ----
    val snoozedUntil: Long? = null,
    val pendingSync: Boolean = true
) {
    val recurrenceEnum: Recurrence get() = Recurrence.from(recurrence)
    val statusEnum: PayStatus get() = PayStatus.from(status)
    val kindEnum: EntryKind get() = EntryKind.from(kind)
    val isAppointment: Boolean get() = kindEnum == EntryKind.APPOINTMENT
    val isIncome: Boolean get() = kindEnum == EntryKind.INCOME
    val isReminder: Boolean get() = kindEnum == EntryKind.REMINDER
    val isBill: Boolean get() = !isAppointment && !isIncome && !isReminder
    val isPaid: Boolean get() = statusEnum == PayStatus.PAID
    val isSkipped: Boolean get() = statusEnum == PayStatus.SKIPPED
    /** kept, but out of every total and silent until resumed */
    val isSuspended: Boolean get() = statusEnum == PayStatus.SUSPENDED
    val isOpen: Boolean get() = statusEnum == PayStatus.PENDING
    val isPrivate: Boolean get() = visibility == Visibility.PRIVATE
    val isInstallment: Boolean get() = installmentCount > 0
    val installmentLabel: String
        get() = if (isInstallment) "Installment $installmentIndex/$installmentCount" else ""
}

@Entity(
    tableName = "attachments",
    indices = [Index("ownerType", "paymentId"), Index("epochDay"), Index("calendarId")]
)
data class Attachment(
    @PrimaryKey val id: String = newId(),
    val calendarId: String = "",
    /** PAYMENT or DAY */
    val ownerType: String = OwnerType.PAYMENT,
    val paymentId: String? = null,
    val epochDay: Long? = null,
    /** set when the file is the photo of a shopping item */
    val itemId: String? = null,
    /** set when the file hangs off a note */
    val noteId: String? = null,
    val fileName: String = "",
    val mime: String = "",
    val size: Long = 0,
    val isReceipt: Boolean = false,
    val uploadedBy: String? = null,
    /** absolute path of the local copy, null when it only lives on the server */
    val localPath: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val deletedAt: Long? = null,
    val pendingUpload: Boolean = false
)

@Entity(tableName = "day_notes", indices = [Index("calendarId", "epochDay", unique = true)])
data class DayNote(
    @PrimaryKey val id: String = newId(),
    val calendarId: String = "",
    val epochDay: Long = 0,
    val text: String = "",
    val updatedAt: Long = 0L,
    val deletedAt: Long? = null,
    val pendingSync: Boolean = true
)

@Entity(tableName = "shopping_lists", indices = [Index("calendarId")])
data class ShoppingList(
    @PrimaryKey val id: String = newId(),
    val calendarId: String = "",
    val title: String = "",
    val notes: String = "",
    val colorIndex: Int = 2,
    val dueDate: Long? = null,
    /** minutes from midnight, when the shopping is expected to happen */
    val dueTimeMinutes: Int = 18 * 60,
    /** the person expected to do the shopping and pay for it */
    val assignedToUserId: String? = null,
    val createdByUserId: String? = null,
    val budgetCents: Long? = null,
    val actualCents: Long? = null,
    val status: String = "OPEN",
    val doneAt: Long? = null,
    val doneByUserId: String? = null,
    val paymentId: String? = null,
    val visibility: String = Visibility.SHARED,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val deletedAt: Long? = null,
    val pendingSync: Boolean = true
) {
    val isDone: Boolean get() = status == "DONE"
}

@Entity(tableName = "shopping_items", indices = [Index("listId"), Index("calendarId")])
data class ShoppingItem(
    @PrimaryKey val id: String = newId(),
    val listId: String = "",
    val calendarId: String = "",
    val text: String = "",
    val quantity: String = "",
    /** the code that was scanned, empty when the item was typed by hand */
    val barcode: String = "",
    val checked: Boolean = false,
    val priceCents: Long? = null,
    val sortIndex: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val deletedAt: Long? = null,
    val pendingSync: Boolean = true
)

/** A note with no date: recipes, prompts, links, voice memos, photos. */
@Entity(tableName = "notes", indices = [Index("calendarId"), Index("category")])
data class Note(
    @PrimaryKey val id: String = newId(),
    val calendarId: String = "",
    val title: String = "",
    val body: String = "",
    val category: String = "",
    val colorIndex: Int = 4,
    val pinned: Boolean = false,
    /** who the note is meant for, null = everybody on the calendar */
    val ownerUserId: String? = null,
    val createdByUserId: String? = null,
    val visibility: String = Visibility.SHARED,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val deletedAt: Long? = null,
    val pendingSync: Boolean = true
) {
    val isPrivate: Boolean get() = visibility == Visibility.PRIVATE
}

/** People are owned by the server; this is only a cache so the UI can paint names. */
data class Member(
    val id: String,
    val name: String,
    val email: String,
    val colorIndex: Int,
    val role: String
)

data class CalendarSpace(
    val id: String,
    val name: String,
    val colorIndex: Int,
    val currency: String,
    val ownerUserId: String,
    val inviteCode: String,
    val members: List<Member>
)

/** Lightweight row used to paint the month grid. */
data class DayStat(
    val dueDate: Long,
    val total: Long,
    val openCount: Int,
    val paidCount: Int,
    val colorIndex: Int
)
