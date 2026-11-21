package com.example.data.repository

import com.example.data.local.DispatchLogDao
import com.example.data.local.DispatchLogEntity
import com.example.data.local.MessageTemplateDao
import com.example.data.local.MessageTemplateEntity
import com.example.data.local.ScheduledMessageDao
import com.example.data.local.ScheduledMessageEntity
import com.example.data.media.MediaStorageManager
import com.example.data.model.BackupScheduleDto
import com.example.data.model.BackupTemplateDto
import com.example.data.model.RecurrenceType
import com.example.data.model.ScheduleStatus
import com.example.dispatch.RecurrenceCalculator
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import okio.source
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

data class HygieneSummary(
    val prunedLogsCount: Int = 0,
    val cleanedFailedMediaCount: Int = 0
)

class SandeshRepository(
    private val scheduleDao: ScheduledMessageDao,
    private val templateDao: MessageTemplateDao,
    private val logDao: DispatchLogDao,
    private val mediaStorage: MediaStorageManager
) {
    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val backupScheduleAdapter = moshi.adapter(BackupScheduleDto::class.java)
    private val backupTemplateAdapter = moshi.adapter(BackupTemplateDto::class.java)

    @Volatile
    private var lastHygieneRunMillis: Long = 0L

    // Indexed active + capped completed flow prevents 36,500-row UI stalls
    val schedules: Flow<List<ScheduledMessageEntity>> = combine(
        scheduleDao.observeActive(),
        scheduleDao.observeRecentCompleted()
    ) { active, completed ->
        (active + completed).sortedBy { it.effectiveTriggerMillis }
    }

    val templates: Flow<List<MessageTemplateEntity>> = templateDao.observeAll()
    val dispatchLogs: Flow<List<DispatchLogEntity>> = logDao.observeRecent()

    suspend fun getScheduleById(id: Long): ScheduledMessageEntity? = scheduleDao.getById(id)

    suspend fun getPendingSchedules(): List<ScheduledMessageEntity> =
        scheduleDao.getByStatus(ScheduleStatus.PENDING.name)

    suspend fun getUpcomingPendingWindow(limit: Int = 250): List<ScheduledMessageEntity> =
        scheduleDao.getUpcomingPendingWindow(limit)

    suspend fun getOverduePending(nowMillis: Long = System.currentTimeMillis()): List<ScheduledMessageEntity> =
        scheduleDao.getOverduePending(nowMillis)

    suspend fun saveSchedule(entity: ScheduledMessageEntity): Long {
        val anchor = if (entity.anchorDayOfMonth in 1..31) {
            entity.anchorDayOfMonth
        } else {
            RecurrenceCalculator.extractDayOfMonth(entity.scheduledTimeMillis)
        }
        val updated = entity.copy(
            anchorDayOfMonth = anchor,
            updatedAtMillis = System.currentTimeMillis()
        )
        return scheduleDao.insert(updated)
    }

    suspend fun markDispatching(id: Long) {
        val item = scheduleDao.getById(id) ?: return
        scheduleDao.update(
            item.copy(
                status = ScheduleStatus.DISPATCHING.name,
                updatedAtMillis = System.currentTimeMillis()
            )
        )
    }

    /**
     * Reverts schedules stranded in DISPATCHING (e.g. after an OEM process kill mid-send)
     * back to PENDING so they can execute or advance cleanly.
     */
    suspend fun recoverStuckDispatching(
        nowMillis: Long = System.currentTimeMillis(),
        staleThresholdMillis: Long = 120_000L
    ): Int {
        val stale = scheduleDao.getStaleDispatching(nowMillis - staleThresholdMillis)
        for (item in stale) {
            scheduleDao.update(
                item.copy(
                    status = ScheduleStatus.PENDING.name,
                    retryAtMillis = nowMillis + 5000L,
                    lastError = "Recovered interrupted dispatch",
                    updatedAtMillis = nowMillis
                )
            )
        }
        return stale.size
    }

    /**
     * Completes a dispatch run:
     * - Inserts an audit log entry.
     * - On transient failure with retryCount == 0 and allowRetry == true, schedules a Smart 1-Time Retry (+60s)
     *   using retryAtMillis so recurring schedules preserve their original anchor clock time.
     * - Otherwise advances recurring schedules using anchorDayOfMonth or marks ONCE as SENT/FAILED.
     */
    suspend fun recordDispatchOutcome(
        scheduleId: Long,
        success: Boolean,
        durationMillis: Long,
        detailMessage: String,
        allowRetry: Boolean = true
    ): ScheduledMessageEntity? {
        val item = scheduleDao.getById(scheduleId) ?: return null
        val now = System.currentTimeMillis()
        val preview = item.messageBody.ifBlank { item.mediaDisplayName ?: "Media attachment" }.take(120)

        logDao.insert(
            DispatchLogEntity(
                scheduleId = item.id,
                recipientSnapshot = item.recipientName.ifBlank { item.phoneNumber },
                instanceLabel = item.targetInstanceLabel,
                messagePreview = preview,
                hasMedia = item.hasMedia,
                executedAtMillis = now,
                isSuccess = success,
                durationMillis = durationMillis,
                detailMessage = detailMessage
            )
        )

        // Smart 1-time retry 60 seconds after transient first failure
        if (!success && allowRetry && item.retryCount == 0) {
            val retryItem = item.copy(
                status = ScheduleStatus.PENDING.name,
                retryCount = 1,
                retryAtMillis = now + 60_000L,
                lastError = "Auto-retrying in 60s: $detailMessage",
                updatedAtMillis = now
            )
            scheduleDao.update(retryItem)
            return retryItem
        }

        val recurrence = item.recurrenceEnum
        if (recurrence != RecurrenceType.ONCE) {
            val anchor = if (item.anchorDayOfMonth in 1..31) {
                item.anchorDayOfMonth
            } else {
                RecurrenceCalculator.extractDayOfMonth(item.scheduledTimeMillis)
            }
            val nextTime = RecurrenceCalculator.computeNextOccurrence(
                scheduledTimeMillis = item.scheduledTimeMillis,
                recurrence = recurrence,
                nowMillis = now,
                anchorDayOfMonth = anchor
            )
            if (nextTime != null) {
                val nextItem = item.copy(
                    scheduledTimeMillis = nextTime,
                    status = ScheduleStatus.PENDING.name,
                    retryCount = 0,
                    retryAtMillis = 0L,
                    anchorDayOfMonth = anchor,
                    lastError = if (success) null else detailMessage,
                    updatedAtMillis = now
                )
                scheduleDao.update(nextItem)
                return nextItem
            }
        }

        val finalStatus = if (success) ScheduleStatus.SENT else ScheduleStatus.FAILED
        scheduleDao.update(
            item.copy(
                status = finalStatus.name,
                retryCount = if (success) 0 else item.retryCount + 1,
                retryAtMillis = 0L,
                lastError = if (success) null else detailMessage,
                updatedAtMillis = now
            )
        )

        if (success && item.hasMedia) {
            cleanupMediaIfUnreferenced(item.mediaPath, excludingId = item.id)
        }
        return null
    }

    /**
     * Enforces 90-day audit log retention and 30-day failed media cleanup.
     */
    suspend fun runStorageHygiene(
        nowMillis: Long = System.currentTimeMillis(),
        force: Boolean = false
    ): HygieneSummary {
        if (!force && nowMillis - lastHygieneRunMillis < 6 * 3600_000L) {
            return HygieneSummary()
        }
        lastHygieneRunMillis = nowMillis

        val logCutoff = nowMillis - 90L * 24L * 3600_000L
        val prunedLogs = logDao.deleteOlderThan(logCutoff)

        val failedMediaCutoff = nowMillis - 30L * 24L * 3600_000L
        val expiredMediaRows = scheduleDao.getFailedMediaBefore(failedMediaCutoff)
        var cleanedMedia = 0
        for (row in expiredMediaRows) {
            cleanupMediaIfUnreferenced(row.mediaPath, excludingId = row.id)
            scheduleDao.clearMediaFields(row.id, nowMillis)
            cleanedMedia++
        }
        return HygieneSummary(prunedLogsCount = prunedLogs, cleanedFailedMediaCount = cleanedMedia)
    }

    suspend fun deleteSchedule(entity: ScheduledMessageEntity) {
        scheduleDao.deleteById(entity.id)
        cleanupMediaIfUnreferenced(entity.mediaPath, excludingId = entity.id)
    }

    private suspend fun cleanupMediaIfUnreferenced(path: String?, excludingId: Long) {
        if (path.isNullOrBlank()) return
        if (scheduleDao.countMediaReferences(path, excludingId) == 0) {
            mediaStorage.deleteFileIfExists(path)
        }
    }

    suspend fun saveTemplate(template: MessageTemplateEntity): Long =
        templateDao.insert(template.copy(updatedAtMillis = System.currentTimeMillis()))

    suspend fun incrementTemplateUsage(id: Long) = templateDao.incrementUsage(id)

    suspend fun deleteTemplate(id: Long) = templateDao.deleteById(id)

    suspend fun clearHistoryLogs() = logDao.clearAll()

    suspend fun exportBackupToStream(outputStream: OutputStream) = withContext(Dispatchers.IO) {
        val templates = templateDao.getAllSnapshot()
        val sink = outputStream.sink().buffer()
        JsonWriter.of(sink).use { writer ->
            writer.indent = "  "
            writer.beginObject()
            writer.name("version").value(2)
            writer.name("exportedAtMillis").value(System.currentTimeMillis())

            writer.name("templates")
            writer.beginArray()
            for (tpl in templates) {
                backupTemplateAdapter.toJson(
                    writer,
                    BackupTemplateDto(
                        title = tpl.title,
                        category = tpl.category,
                        body = tpl.body
                    )
                )
            }
            writer.endArray()

            writer.name("schedules")
            writer.beginArray()
            var offset = 0
            val pageSize = 500
            while (true) {
                val page = scheduleDao.getSnapshotPage(pageSize, offset)
                if (page.isEmpty()) break
                for (item in page) {
                    backupScheduleAdapter.toJson(
                        writer,
                        BackupScheduleDto(
                            recipientName = item.recipientName,
                            phoneNumber = item.phoneNumber,
                            isGroup = item.isGroup,
                            targetPackage = item.targetPackage,
                            targetUserSerial = item.targetUserSerial,
                            targetInstanceLabel = item.targetInstanceLabel,
                            messageBody = item.messageBody,
                            scheduledTimeMillis = item.scheduledTimeMillis,
                            recurrence = item.recurrence,
                            status = item.status,
                            anchorDayOfMonth = item.anchorDayOfMonth
                        )
                    )
                }
                if (page.size < pageSize) break
                offset += pageSize
            }
            writer.endArray()
            writer.endObject()
            writer.flush()
        }
    }

    suspend fun exportBackupJson(): String {
        val out = ByteArrayOutputStream()
        exportBackupToStream(out)
        return out.toString(Charsets.UTF_8.name())
    }

    suspend fun importBackupFromStream(inputStream: InputStream): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            var importedCount = 0
            val templatesBatch = mutableListOf<MessageTemplateEntity>()
            val schedulesBatch = mutableListOf<ScheduledMessageEntity>()
            val now = System.currentTimeMillis()

            val source = inputStream.source().buffer()
            JsonReader.of(source).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "templates" -> {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                val dto = backupTemplateAdapter.fromJson(reader)
                                if (dto != null && dto.title.isNotBlank() && dto.body.isNotBlank()) {
                                    templatesBatch += MessageTemplateEntity(
                                        title = dto.title,
                                        category = dto.category.ifBlank { "General" },
                                        body = dto.body
                                    )
                                    importedCount++
                                }
                            }
                            reader.endArray()
                        }

                        "schedules" -> {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                val dto = backupScheduleAdapter.fromJson(reader) ?: continue
                                val recurrence = runCatching { RecurrenceType.valueOf(dto.recurrence) }
                                    .getOrDefault(RecurrenceType.ONCE)
                                val parsedStatus = runCatching { ScheduleStatus.valueOf(dto.status) }
                                    .getOrDefault(ScheduleStatus.PENDING)
                                val anchor = if (dto.anchorDayOfMonth in 1..31) {
                                    dto.anchorDayOfMonth
                                } else {
                                    RecurrenceCalculator.extractDayOfMonth(dto.scheduledTimeMillis)
                                }
                                val targetTime = if (parsedStatus == ScheduleStatus.PENDING && dto.scheduledTimeMillis <= now) {
                                    RecurrenceCalculator.computeNextOccurrence(
                                        dto.scheduledTimeMillis,
                                        recurrence,
                                        now,
                                        anchor
                                    ) ?: dto.scheduledTimeMillis
                                } else {
                                    dto.scheduledTimeMillis
                                }
                                val finalStatus = if (parsedStatus == ScheduleStatus.PENDING && targetTime <= now) {
                                    ScheduleStatus.PAUSED
                                } else {
                                    parsedStatus
                                }
                                schedulesBatch += ScheduledMessageEntity(
                                    recipientName = dto.recipientName,
                                    phoneNumber = dto.phoneNumber,
                                    isGroup = dto.isGroup,
                                    targetPackage = dto.targetPackage.ifBlank { "com.whatsapp" },
                                    targetUserSerial = dto.targetUserSerial,
                                    targetInstanceLabel = dto.targetInstanceLabel.ifBlank { "WhatsApp" },
                                    messageBody = dto.messageBody,
                                    scheduledTimeMillis = targetTime,
                                    recurrence = recurrence.name,
                                    status = finalStatus.name,
                                    anchorDayOfMonth = anchor
                                )
                                importedCount++
                                if (schedulesBatch.size >= 500) {
                                    scheduleDao.insertAll(schedulesBatch.toList())
                                    schedulesBatch.clear()
                                }
                            }
                            reader.endArray()
                        }

                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }

            if (templatesBatch.isNotEmpty()) templateDao.insertAll(templatesBatch)
            if (schedulesBatch.isNotEmpty()) scheduleDao.insertAll(schedulesBatch)
            importedCount
        }
    }

    suspend fun importBackupJson(json: String): Result<Int> =
        importBackupFromStream(ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)))
}
