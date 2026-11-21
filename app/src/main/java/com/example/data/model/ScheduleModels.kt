package com.example.data.model

import com.squareup.moshi.JsonClass

enum class RecurrenceType(val displayLabel: String) {
    ONCE("One-time"),
    DAILY("Daily"),
    WEEKLY("Weekly"),
    MONTHLY("Monthly")
}

enum class ScheduleStatus(val displayLabel: String) {
    PENDING("Armed"),
    DISPATCHING("Sending"),
    SENT("Sent"),
    FAILED("Failed"),
    PAUSED("Paused")
}

enum class MediaAttachmentType(val displayLabel: String) {
    NONE("Text Only"),
    IMAGE("Image"),
    VIDEO("Video"),
    DOCUMENT("Document")
}

enum class LockCredentialType(val displayLabel: String) {
    NONE("Swipe / No Lock"),
    PIN("Numeric PIN"),
    PASSWORD("Text Password"),
    PATTERN("3x3 Pattern")
}

data class WhatsAppInstance(
    val uniqueKey: String,
    val packageName: String,
    val userSerialNumber: Long,
    val displayLabel: String,
    val isBusiness: Boolean,
    val isClone: Boolean
)

@JsonClass(generateAdapter = true)
data class BackupScheduleDto(
    val recipientName: String,
    val phoneNumber: String,
    val isGroup: Boolean,
    val targetPackage: String,
    val targetUserSerial: Long,
    val targetInstanceLabel: String,
    val messageBody: String,
    val scheduledTimeMillis: Long,
    val recurrence: String,
    val status: String,
    val anchorDayOfMonth: Int = 0
)

@JsonClass(generateAdapter = true)
data class BackupTemplateDto(
    val title: String,
    val category: String,
    val body: String
)

@JsonClass(generateAdapter = true)
data class SandeshBackupPayload(
    val version: Int = 1,
    val exportedAtMillis: Long = System.currentTimeMillis(),
    val schedules: List<BackupScheduleDto>,
    val templates: List<BackupTemplateDto>
)
