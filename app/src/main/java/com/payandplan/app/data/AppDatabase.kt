package com.payandplan.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        Payment::class, Attachment::class, DayNote::class,
        ShoppingList::class, ShoppingItem::class, Note::class
    ],
    version = 6,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun paymentDao(): PaymentDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun dayNoteDao(): DayNoteDao
    abstract fun shoppingDao(): ShoppingDao
    abstract fun noteDao(): NoteDao

    companion object {
        /** Keep in step with the @Database version above. */
        const val VERSION = 6

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "payandplan.db"
            ).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}
