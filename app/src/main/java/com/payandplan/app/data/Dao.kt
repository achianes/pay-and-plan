package com.payandplan.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PaymentDao {

    @Query(
        """SELECT * FROM payments
           WHERE calendarId = :calendarId AND deletedAt IS NULL AND dueDate BETWEEN :from AND :to
           ORDER BY dueDate, dueTimeMinutes"""
    )
    fun observeBetween(calendarId: String, from: Long, to: Long): Flow<List<Payment>>

    @Query(
        """SELECT * FROM payments
           WHERE calendarId = :calendarId AND deletedAt IS NULL AND dueDate = :day
           ORDER BY dueTimeMinutes"""
    )
    fun observeForDay(calendarId: String, day: Long): Flow<List<Payment>>

    @Query(
        """SELECT * FROM payments
           WHERE calendarId = :calendarId AND deletedAt IS NULL
           ORDER BY dueDate DESC, dueTimeMinutes DESC"""
    )
    fun observeAll(calendarId: String): Flow<List<Payment>>

    @Query("SELECT * FROM payments WHERE id = :id")
    fun observeById(id: String): Flow<Payment?>

    @Query("SELECT * FROM payments WHERE id = :id")
    suspend fun getById(id: String): Payment?

    @Query("SELECT * FROM payments WHERE deletedAt IS NULL AND status = 'PENDING' AND alarmEnabled = 1")
    suspend fun getOpenWithAlarm(): List<Payment>

    @Query(
        """SELECT * FROM payments WHERE seriesId = :seriesId AND deletedAt IS NULL
           ORDER BY dueDate DESC LIMIT 1"""
    )
    suspend fun lastInSeries(seriesId: String): Payment?

    @Query("SELECT COUNT(*) FROM payments WHERE seriesId = :seriesId AND deletedAt IS NULL AND dueDate >= :fromDay")
    suspend fun countFromDay(seriesId: String, fromDay: Long): Int

    @Query("SELECT DISTINCT seriesId FROM payments WHERE deletedAt IS NULL AND recurrence != 'NONE'")
    suspend fun activeSeries(): List<String>

    @Query(
        """SELECT * FROM payments WHERE seriesId = :seriesId AND dueDate > :afterDay
           AND status = 'PENDING' AND deletedAt IS NULL"""
    )
    suspend fun futureInSeries(seriesId: String, afterDay: Long): List<Payment>

    @Query("SELECT * FROM payments WHERE seriesId = :seriesId AND deletedAt IS NULL")
    suspend fun wholeSeries(seriesId: String): List<Payment>

    @Query("SELECT * FROM payments WHERE pendingSync = 1")
    suspend fun pending(): List<Payment>

    @Query("UPDATE payments SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun clearPending(ids: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(payment: Payment)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(payments: List<Payment>)

    @Update
    suspend fun update(payment: Payment)
}

@Dao
interface AttachmentDao {

    @Query(
        """SELECT * FROM attachments WHERE deletedAt IS NULL AND ownerType = 'PAYMENT'
           AND paymentId = :paymentId ORDER BY createdAt DESC"""
    )
    fun observeForPayment(paymentId: String): Flow<List<Attachment>>

    @Query(
        """SELECT * FROM attachments WHERE deletedAt IS NULL AND ownerType = 'PAYMENT'
           AND paymentId = :paymentId ORDER BY createdAt DESC"""
    )
    suspend fun getForPayment(paymentId: String): List<Attachment>

    @Query(
        """SELECT * FROM attachments WHERE deletedAt IS NULL AND ownerType = 'DAY'
           AND calendarId = :calendarId AND epochDay = :day ORDER BY createdAt DESC"""
    )
    fun observeForDay(calendarId: String, day: Long): Flow<List<Attachment>>

    @Query(
        """SELECT DISTINCT epochDay FROM attachments WHERE deletedAt IS NULL AND ownerType = 'DAY'
           AND calendarId = :calendarId AND epochDay BETWEEN :from AND :to"""
    )
    fun observeDaysWithFiles(calendarId: String, from: Long, to: Long): Flow<List<Long>>

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun getById(id: String): Attachment?

    @Query(
        """SELECT * FROM attachments WHERE deletedAt IS NULL AND ownerType = 'NOTE'
           AND noteId = :noteId ORDER BY createdAt DESC"""
    )
    fun observeForNote(noteId: String): Flow<List<Attachment>>

    @Query(
        """SELECT * FROM attachments WHERE deletedAt IS NULL AND ownerType = 'NOTE'
           AND calendarId = :calendarId ORDER BY createdAt DESC"""
    )
    fun observeNoteFiles(calendarId: String): Flow<List<Attachment>>

    /** Product photos of the whole calendar, newest first: one lookup for a whole list. */
    @Query(
        """SELECT * FROM attachments WHERE deletedAt IS NULL AND ownerType = 'ITEM'
           AND calendarId = :calendarId ORDER BY createdAt DESC"""
    )
    fun observeItemPhotos(calendarId: String): Flow<List<Attachment>>

    @Query("SELECT * FROM attachments WHERE pendingUpload = 1 AND deletedAt IS NULL")
    suspend fun pendingUploads(): List<Attachment>

    @Query("SELECT * FROM attachments WHERE itemId = :itemId AND deletedAt IS NULL")
    suspend fun forItem(itemId: String): List<Attachment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachment: Attachment)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(attachments: List<Attachment>)

    @Update
    suspend fun update(attachment: Attachment)

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun hardDelete(id: String)
}

@Dao
interface DayNoteDao {

    @Query("SELECT * FROM day_notes WHERE calendarId = :calendarId AND epochDay = :day AND deletedAt IS NULL")
    fun observe(calendarId: String, day: Long): Flow<DayNote?>

    @Query("SELECT * FROM day_notes WHERE calendarId = :calendarId AND epochDay = :day")
    suspend fun get(calendarId: String, day: Long): DayNote?

    @Query(
        """SELECT epochDay FROM day_notes WHERE calendarId = :calendarId AND deletedAt IS NULL
           AND text != '' AND epochDay BETWEEN :from AND :to"""
    )
    fun observeDaysWithNotes(calendarId: String, from: Long, to: Long): Flow<List<Long>>

    @Query("SELECT * FROM day_notes WHERE pendingSync = 1")
    suspend fun pending(): List<DayNote>

    @Query("UPDATE day_notes SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun clearPending(ids: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: DayNote)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(notes: List<DayNote>)
}

@Dao
interface NoteDao {

    @Query(
        """SELECT * FROM notes WHERE calendarId = :calendarId AND deletedAt IS NULL
           ORDER BY pinned DESC, updatedAt DESC"""
    )
    fun observeAll(calendarId: String): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observeById(id: String): Flow<Note?>

    @Query("SELECT * FROM notes WHERE pendingSync = 1")
    suspend fun pending(): List<Note>

    @Query("UPDATE notes SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun clearPending(ids: List<String>)

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: String): Note?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: Note)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(notes: List<Note>)
}

@Dao
interface ShoppingDao {

    @Query("SELECT * FROM shopping_lists WHERE calendarId = :calendarId AND deletedAt IS NULL ORDER BY status, updatedAt DESC")
    fun observeLists(calendarId: String): Flow<List<ShoppingList>>

    @Query("SELECT * FROM shopping_lists WHERE id = :id")
    fun observeList(id: String): Flow<ShoppingList?>

    @Query("SELECT * FROM shopping_lists WHERE id = :id")
    suspend fun getList(id: String): ShoppingList?

    @Query("SELECT * FROM shopping_items WHERE listId = :listId AND deletedAt IS NULL ORDER BY sortIndex")
    fun observeItems(listId: String): Flow<List<ShoppingItem>>

    @Query("SELECT * FROM shopping_items WHERE listId = :listId AND deletedAt IS NULL ORDER BY sortIndex")
    suspend fun getItems(listId: String): List<ShoppingItem>

    @Query("SELECT * FROM shopping_items WHERE id = :id")
    suspend fun getItem(id: String): ShoppingItem?

    /**
     * Everything anyone in this calendar has ever put on a list, most used first.
     * Deleted rows are kept on purpose: they are still worth suggesting.
     */
    @Query(
        """SELECT text FROM shopping_items
           WHERE calendarId = :calendarId AND text != ''
           GROUP BY text COLLATE NOCASE
           ORDER BY COUNT(*) DESC, MAX(updatedAt) DESC
           LIMIT 300"""
    )
    fun observeItemSuggestions(calendarId: String): Flow<List<String>>

    @Query("SELECT * FROM shopping_lists WHERE pendingSync = 1")
    suspend fun pendingLists(): List<ShoppingList>

    @Query("SELECT * FROM shopping_items WHERE pendingSync = 1")
    suspend fun pendingItems(): List<ShoppingItem>

    @Query("UPDATE shopping_lists SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun clearPendingLists(ids: List<String>)

    @Query("UPDATE shopping_items SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun clearPendingItems(ids: List<String>)

    /** What the household has already called this code, newest first: the product book. */
    @Query(
        """SELECT * FROM shopping_items
           WHERE calendarId = :calendarId AND barcode = :barcode AND deletedAt IS NULL
           ORDER BY updatedAt DESC"""
    )
    suspend fun itemsWithBarcode(calendarId: String, barcode: String): List<ShoppingItem>

    /** Every live item in the calendar except the one asked about; the photo cache reads it. */
    @Query("SELECT * FROM shopping_items WHERE calendarId = :calendarId AND id != :exceptId AND deletedAt IS NULL")
    suspend fun itemsNamed(calendarId: String, exceptId: String): List<ShoppingItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertList(list: ShoppingList)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLists(lists: List<ShoppingList>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItem(item: ShoppingItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<ShoppingItem>)
}
