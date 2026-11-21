package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.model.MediaAttachmentType
import com.example.data.model.RecurrenceType
import com.example.data.model.ScheduleStatus

@Entity(
    tableName = "scheduled_messages",
    indices = [Index(value = ["status", "scheduledTimeMillis"])]
)
data class ScheduledMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipientName: String,
    val phoneNumber: String,
    val isGroup: Boolean = false,
    val targetPackage: String = "com.whatsapp",
    val targetUserSerial: Long = 0L,
    val targetInstanceLabel: String = "WhatsApp",
    val messageBody: String,
    val mediaType: String = MediaAttachmentType.NONE.name,
    val mediaPath: String? = null,
    val mediaMimeType: String? = null,
    val mediaDisplayName: String? = null,
    val scheduledTimeMillis: Long,
    val recurrence: String = RecurrenceType.ONCE.name,
    val status: String = ScheduleStatus.PENDING.name,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val retryAtMillis: Long = 0L,
    val anchorDayOfMonth: Int = 0,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val recurrenceEnum: RecurrenceType
        get() = runCatching { RecurrenceType.valueOf(recurrence) }.getOrDefault(RecurrenceType.ONCE)

    val statusEnum: ScheduleStatus
        get() = runCatching { ScheduleStatus.valueOf(status) }.getOrDefault(ScheduleStatus.PENDING)

    val mediaTypeEnum: MediaAttachmentType
        get() = runCatching { MediaAttachmentType.valueOf(mediaType) }.getOrDefault(MediaAttachmentType.NONE)

    val hasMedia: Boolean
        get() = mediaTypeEnum != MediaAttachmentType.NONE && !mediaPath.isNullOrBlank()

    val effectiveTriggerMillis: Long
        get() = if (retryAtMillis > 0L) retryAtMillis else scheduledTimeMillis
}

@Entity(tableName = "message_templates")
data class MessageTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val category: String,
    val body: String,
    val usageCount: Int = 0,
    val updatedAtMillis: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "dispatch_logs",
    indices = [Index(value = ["executedAtMillis"])]
)
data class DispatchLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scheduleId: Long,
    val recipientSnapshot: String,
    val instanceLabel: String,
    val messagePreview: String,
    val hasMedia: Boolean,
    val executedAtMillis: Long = System.currentTimeMillis(),
    val isSuccess: Boolean,
    val durationMillis: Long = 0L,
    val detailMessage: String
)
