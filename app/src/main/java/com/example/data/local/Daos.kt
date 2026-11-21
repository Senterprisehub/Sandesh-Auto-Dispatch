package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledMessageDao {
    @Query("SELECT * FROM scheduled_messages WHERE status IN ('PENDING', 'DISPATCHING', 'PAUSED') ORDER BY scheduledTimeMillis ASC")
    fun observeActive(): Flow<List<ScheduledMessageEntity>>

    @Query("SELECT * FROM scheduled_messages WHERE status NOT IN ('PENDING', 'DISPATCHING', 'PAUSED') ORDER BY updatedAtMillis DESC LIMIT 200")
    fun observeRecentCompleted(): Flow<List<ScheduledMessageEntity>>

    @Query("SELECT * FROM scheduled_messages ORDER BY scheduledTimeMillis ASC")
    fun observeAll(): Flow<List<ScheduledMessageEntity>>

    @Query("SELECT * FROM scheduled_messages WHERE status = :status ORDER BY scheduledTimeMillis ASC")
    suspend fun getByStatus(status: String): List<ScheduledMessageEntity>

    @Query("SELECT * FROM scheduled_messages WHERE status = 'PENDING' ORDER BY CASE WHEN retryAtMillis > 0 THEN retryAtMillis ELSE scheduledTimeMillis END ASC LIMIT :limit")
    suspend fun getUpcomingPendingWindow(limit: Int = 250): List<ScheduledMessageEntity>

    @Query("SELECT * FROM scheduled_messages WHERE status = 'PENDING' AND (CASE WHEN retryAtMillis > 0 THEN retryAtMillis ELSE scheduledTimeMillis END) <= :nowMillis ORDER BY CASE WHEN retryAtMillis > 0 THEN retryAtMillis ELSE scheduledTimeMillis END ASC")
    suspend fun getOverduePending(nowMillis: Long): List<ScheduledMessageEntity>

    @Query("SELECT * FROM scheduled_messages WHERE status = 'DISPATCHING' AND updatedAtMillis < :cutoffMillis")
    suspend fun getStaleDispatching(cutoffMillis: Long): List<ScheduledMessageEntity>

    @Query("SELECT * FROM scheduled_messages WHERE status = 'FAILED' AND mediaPath IS NOT NULL AND updatedAtMillis < :cutoffMillis")
    suspend fun getFailedMediaBefore(cutoffMillis: Long): List<ScheduledMessageEntity>

    @Query("UPDATE scheduled_messages SET mediaType = 'NONE', mediaPath = NULL, mediaMimeType = NULL, mediaDisplayName = NULL, updatedAtMillis = :nowMillis WHERE id = :id")
    suspend fun clearMediaFields(id: Long, nowMillis: Long)

    @Query("SELECT * FROM scheduled_messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ScheduledMessageEntity?

    @Query("SELECT * FROM scheduled_messages")
    suspend fun getAllSnapshot(): List<ScheduledMessageEntity>

    @Query("SELECT * FROM scheduled_messages ORDER BY id ASC LIMIT :limit OFFSET :offset")
    suspend fun getSnapshotPage(limit: Int, offset: Int): List<ScheduledMessageEntity>

    @Query("SELECT COUNT(*) FROM scheduled_messages WHERE mediaPath = :path AND id != :excludingId")
    suspend fun countMediaReferences(path: String, excludingId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ScheduledMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<ScheduledMessageEntity>)

    @Update
    suspend fun update(entity: ScheduledMessageEntity)

    @Query("DELETE FROM scheduled_messages WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface MessageTemplateDao {
    @Query("SELECT * FROM message_templates ORDER BY usageCount DESC, updatedAtMillis DESC")
    fun observeAll(): Flow<List<MessageTemplateEntity>>

    @Query("SELECT * FROM message_templates")
    suspend fun getAllSnapshot(): List<MessageTemplateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MessageTemplateEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<MessageTemplateEntity>)

    @Update
    suspend fun update(entity: MessageTemplateEntity)

    @Query("UPDATE message_templates SET usageCount = usageCount + 1, updatedAtMillis = :now WHERE id = :id")
    suspend fun incrementUsage(id: Long, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM message_templates WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface DispatchLogDao {
    @Query("SELECT * FROM dispatch_logs ORDER BY executedAtMillis DESC LIMIT 200")
    fun observeRecent(): Flow<List<DispatchLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DispatchLogEntity): Long

    @Query("DELETE FROM dispatch_logs WHERE executedAtMillis < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long): Int

    @Query("DELETE FROM dispatch_logs")
    suspend fun clearAll()
}
