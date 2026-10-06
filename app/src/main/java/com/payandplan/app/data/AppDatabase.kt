package com.payandplan.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Payment::class, Attachment::class, DayNote::class,
        ShoppingList::class, ShoppingItem::class, Note::class,
        BankRule::class, BankMovement::class, NotificationSample::class, BankLink::class
    ],
    version = 11,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun paymentDao(): PaymentDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun dayNoteDao(): DayNoteDao
    abstract fun shoppingDao(): ShoppingDao
    abstract fun noteDao(): NoteDao
    abstract fun bankDao(): BankDao

    companion object {
        /** Keep in step with the @Database version above. */
        const val VERSION = 11

        /**
         * The bank rules, the movements waiting to be checked and the shop links live only on
         * this phone: nothing on the server knows about them. A thrown-away database takes
         * them with it, so every new version gets a migration that keeps what is there.
         */
        private val MIGRATIONS = arrayOf(
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `bank_links` (" +
                            "`id` TEXT NOT NULL, `shop` TEXT NOT NULL, `label` TEXT NOT NULL, " +
                            "`seriesId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`id`))"
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_bank_links_shop` " +
                            "ON `bank_links` (`shop`)"
                    )
                }
            },
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE `bank_rules` ADD COLUMN `butNot` TEXT NOT NULL DEFAULT ''"
                    )
                }
            },
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE `payments` ADD COLUMN `groupKey` TEXT NOT NULL DEFAULT ''"
                    )
                }
            }
        )

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "payandplan.db"
            ).addMigrations(*MIGRATIONS)
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }

        /** Lets a restored copy replace the file underneath. */
        fun close() = synchronized(this) {
            instance?.close()
            instance = null
        }
    }
}
