package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        ScheduledMessageEntity::class,
        MessageTemplateEntity::class,
        DispatchLogEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class SandeshDatabase : RoomDatabase() {
    abstract fun scheduledMessageDao(): ScheduledMessageDao
    abstract fun messageTemplateDao(): MessageTemplateDao
    abstract fun dispatchLogDao(): DispatchLogDao

    companion object {
        @Volatile
        private var INSTANCE: SandeshDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scheduled_messages ADD COLUMN retryAtMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE scheduled_messages ADD COLUMN anchorDayOfMonth INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_scheduled_messages_status_scheduledTimeMillis " +
                        "ON scheduled_messages(status, scheduledTimeMillis)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_dispatch_logs_executedAtMillis " +
                        "ON dispatch_logs(executedAtMillis)"
                )
            }
        }

        fun getInstance(context: Context): SandeshDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(appContext: Context): SandeshDatabase {
            return Room.databaseBuilder(
                appContext,
                SandeshDatabase::class.java,
                "sandesh_scheduler.db"
            )
                .addMigrations(MIGRATION_1_2)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        CoroutineScope(Dispatchers.IO).launch {
                            seedStarterTemplates(getInstance(appContext).messageTemplateDao())
                        }
                    }
                })
                .build()
        }

        private suspend fun seedStarterTemplates(dao: MessageTemplateDao) {
            val starters = listOf(
                MessageTemplateEntity(
                    title = "Payment Reminder",
                    category = "Business",
                    body = "Namaste! Gentle reminder regarding your pending invoice. Please share confirmation once completed. Thank you!"
                ),
                MessageTemplateEntity(
                    title = "Appointment Check-in",
                    category = "Work",
                    body = "Hi! Confirming our scheduled meeting today. Please let me know if any timing adjustment is needed."
                ),
                MessageTemplateEntity(
                    title = "Birthday / Wishes",
                    category = "Personal",
                    body = "Wishing you a very Happy Birthday! May the year ahead bring health, joy, and success."
                )
            )
            dao.insertAll(starters)
        }
    }
}
