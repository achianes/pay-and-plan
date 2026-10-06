package com.payandplan.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * What the phone is told to look for. One rule per wording a bank uses: the app it comes
 * from, a few words that appear in the notification, and whether that wording means money
 * leaving or money arriving.
 */
@Entity(tableName = "bank_rules", indices = [Index("packageName")])
data class BankRule(
    @PrimaryKey val id: String = newId(),
    val packageName: String = "",
    val appLabel: String = "",
    /** "pagamento accettato", "nuovo bonifico ricevuto", ... */
    val phrase: String = "",
    val kind: String = MoneyKind.OUT,
    val enabled: Boolean = true,
    val createdAt: Long = 0L
)

object MoneyKind {
    const val OUT = "OUT"
    const val IN = "IN"
}

object MovementStatus {
    const val PENDING = "PENDING"
    const val MATCHED = "MATCHED"
    const val IGNORED = "IGNORED"
}

/**
 * A payment or an arrival the phone read in a notification, waiting to be matched with what
 * the calendar expected. Nothing here ever leaves the phone: it is only used to tick off
 * entries that do travel.
 */
@Entity(tableName = "bank_movements", indices = [Index("status"), Index("happenedAt")])
data class BankMovement(
    @PrimaryKey val id: String = newId(),
    val packageName: String = "",
    val appLabel: String = "",
    val title: String = "",
    val text: String = "",
    val amountCents: Long = 0,
    val kind: String = MoneyKind.OUT,
    val happenedAt: Long = 0L,
    val status: String = MovementStatus.PENDING,
    val matchedPaymentId: String? = null,
    val matchedAt: Long? = null,
    /** true when the app ticked it off by itself, because figure and wording agreed */
    val auto: Boolean = false
) {
    val isPending: Boolean get() = status == MovementStatus.PENDING
    val isIncome: Boolean get() = kind == MoneyKind.IN
}

/**
 * The last notifications that went by, kept only so a rule can be taught from a real one
 * instead of typed blind. Old ones are dropped; the list can be emptied at any time.
 */
@Entity(tableName = "notification_samples", indices = [Index("seenAt")])
data class NotificationSample(
    @PrimaryKey val id: String = newId(),
    val packageName: String = "",
    val appLabel: String = "",
    val title: String = "",
    val text: String = "",
    val seenAt: Long = 0L
)

/**
 * "That shop is this bill." Once somebody says a payment from CENTRO SPORTIVO closes the
 * taekwondo entry, the next one closes it by itself, whatever the calendar expected.
 */
@Entity(tableName = "bank_links", indices = [Index(value = ["shop"], unique = true)])
data class BankLink(
    @PrimaryKey val id: String = newId(),
    /** the shop's name, stripped down so spelling and decorations do not matter */
    val shop: String = "",
    val label: String = "",
    val seriesId: String = "",
    val createdAt: Long = 0L
)

@Dao
interface BankDao {

    // ---------------------------------------------------------------- learnt links

    @Query("SELECT * FROM bank_links WHERE shop = :shop LIMIT 1")
    suspend fun linkFor(shop: String): BankLink?

    @Query("SELECT * FROM bank_links ORDER BY label")
    fun observeLinks(): Flow<List<BankLink>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLink(link: BankLink)

    @Query("DELETE FROM bank_links WHERE id = :id")
    suspend fun deleteLink(id: String)

    // ---------------------------------------------------------------- rules

    @Query("SELECT * FROM bank_rules ORDER BY appLabel, phrase")
    fun observeRules(): Flow<List<BankRule>>

    @Query("SELECT * FROM bank_rules WHERE enabled = 1")
    suspend fun enabledRules(): List<BankRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: BankRule)

    @Query("DELETE FROM bank_rules WHERE id = :id")
    suspend fun deleteRule(id: String)

    // ---------------------------------------------------------------- movements

    @Query("SELECT * FROM bank_movements WHERE status = 'PENDING' ORDER BY happenedAt DESC")
    fun observePending(): Flow<List<BankMovement>>

    @Query("SELECT * FROM bank_movements ORDER BY happenedAt DESC LIMIT 60")
    fun observeRecent(): Flow<List<BankMovement>>

    @Query("SELECT * FROM bank_movements WHERE status = 'PENDING' ORDER BY happenedAt")
    suspend fun stillWaiting(): List<BankMovement>

    @Query("SELECT * FROM bank_movements WHERE id = :id")
    suspend fun movement(id: String): BankMovement?

    /** The same notification twice (banks repeat them) must not become two movements. */
    @Query(
        """SELECT COUNT(*) FROM bank_movements
           WHERE packageName = :packageName AND amountCents = :amountCents
             AND happenedAt > :since AND text = :text"""
    )
    suspend fun seenAlready(packageName: String, amountCents: Long, text: String, since: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMovement(movement: BankMovement)

    @Query("DELETE FROM bank_movements WHERE happenedAt < :before AND status != 'PENDING'")
    suspend fun forgetOlderThan(before: Long)

    // ---------------------------------------------------------------- samples

    @Query("SELECT * FROM notification_samples ORDER BY seenAt DESC LIMIT 60")
    fun observeSamples(): Flow<List<NotificationSample>>

    /** What this app has said lately: a new rule is tried on these at once. */
    @Query(
        """SELECT * FROM notification_samples WHERE packageName = :packageName
           ORDER BY seenAt DESC LIMIT 20"""
    )
    suspend fun samplesFor(packageName: String): List<NotificationSample>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSample(sample: NotificationSample)

    @Query("DELETE FROM notification_samples")
    suspend fun clearSamples()

    @Query(
        """DELETE FROM notification_samples WHERE id NOT IN
           (SELECT id FROM notification_samples ORDER BY seenAt DESC LIMIT 60)"""
    )
    suspend fun trimSamples()
}
