package com.example.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScheduleSend
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.ScheduleSend
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.local.ScheduledMessageEntity
import com.example.data.model.MediaAttachmentType
import com.example.data.model.RecurrenceType
import com.example.data.model.ScheduleStatus
import com.example.dispatch.PhoneNormalizer
import com.example.dispatch.RecurrenceCalculator
import com.example.oem.PermissionReport
import com.example.ui.theme.LocalStatusColors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class QueueFilter(val label: String) {
    UPCOMING("Upcoming"),
    RECURRING("Recurring"),
    ALL("All")
}

@Composable
fun QueueScreen(
    schedules: List<ScheduledMessageEntity>,
    permissionReport: PermissionReport,
    onOpenPermissions: () -> Unit,
    onEditSchedule: (ScheduledMessageEntity) -> Unit,
    onTogglePause: (ScheduledMessageEntity) -> Unit,
    onSendNow: (ScheduledMessageEntity) -> Unit,
    onDeleteSchedule: (ScheduledMessageEntity) -> Unit,
    onCreateFirst: () -> Unit,
    modifier: Modifier = Modifier
) {
    var activeFilter by remember { mutableStateOf(QueueFilter.UPCOMING) }

    val filtered = remember(schedules, activeFilter) {
        when (activeFilter) {
            QueueFilter.UPCOMING -> schedules.filter {
                it.statusEnum == ScheduleStatus.PENDING ||
                    it.statusEnum == ScheduleStatus.DISPATCHING ||
                    it.statusEnum == ScheduleStatus.PAUSED
            }
            QueueFilter.RECURRING -> schedules.filter { it.recurrenceEnum != RecurrenceType.ONCE }
            QueueFilter.ALL -> schedules
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            ReadinessBannerCard(
                report = permissionReport,
                onOpenPermissions = onOpenPermissions,
                modifier = Modifier.widthIn(max = 680.dp)
            )
        }

        item {
            Row(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QueueFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = activeFilter == filter,
                        onClick = { activeFilter = filter },
                        label = { Text(filter.label) },
                        modifier = Modifier.testTag("queue_filter_${filter.name}")
                    )
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                EmptyQueueCard(
                    onCreateFirst = onCreateFirst,
                    modifier = Modifier.widthIn(max = 680.dp)
                )
            }
        } else {
            items(filtered, key = { it.id }) { item ->
                ScheduleItemCard(
                    item = item,
                    onEdit = { onEditSchedule(item) },
                    onTogglePause = { onTogglePause(item) },
                    onSendNow = { onSendNow(item) },
                    onDelete = { onDeleteSchedule(item) },
                    modifier = Modifier.widthIn(max = 680.dp)
                )
            }
        }
    }
}

@Composable
private fun ReadinessBannerCard(
    report: PermissionReport,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusColors = LocalStatusColors.current
    val ready = report.isCoreReady

    Surface(
        onClick = onOpenPermissions,
        shape = RoundedCornerShape(18.dp),
        color = if (ready) statusColors.successContainer else statusColors.warningContainer,
        modifier = modifier
            .fillMaxWidth()
            .testTag("readiness_banner_card")
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = if (ready) Icons.Filled.CheckCircle else Icons.Filled.Shield,
                    contentDescription = "Device readiness status",
                    tint = if (ready) statusColors.onSuccessContainer else statusColors.onWarningContainer
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            ready && report.isJitAutoToggleActive && !report.isAccessibilityCurrentlyOn ->
                                "Automation Armed • Banking Safe (${report.readinessPercent}%)"
                            ready ->
                                "Automation Engine Armed (${report.readinessPercent}%)"
                            else ->
                                "Permission Setup Needed (${report.requiredGrantedCount}/${report.requiredTotalCount} core)"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (ready) statusColors.onSuccessContainer else statusColors.onWarningContainer
                    )
                    Text(
                        text = when {
                            ready && report.isJitAutoToggleActive && !report.isAccessibilityCurrentlyOn ->
                                "JIT Idle: Accessibility stays OFF until scheduled send • Tap for Settings"
                            ready ->
                                "Tap to inspect Permission Manager & Banking/UPI Shield"
                            else ->
                                "Tap to open Permission Manager for Accessibility, Exact Alarms & Battery"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ready) statusColors.onSuccessContainer else statusColors.onWarningContainer
                    )
                }
            }
            LinearProgressIndicator(
                progress = { report.readinessPercent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            )
        }
    }
}

@Composable
private fun ScheduleItemCard(
    item: ScheduledMessageEntity,
    onEdit: () -> Unit,
    onTogglePause: () -> Unit,
    onSendNow: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusColors = LocalStatusColors.current
    val status = item.statusEnum
    val dateFormat = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()) }

    val (badgeBg, badgeFg) = when (status) {
        ScheduleStatus.PENDING -> statusColors.infoContainer to statusColors.onInfoContainer
        ScheduleStatus.DISPATCHING -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        ScheduleStatus.SENT -> statusColors.successContainer to statusColors.onSuccessContainer
        ScheduleStatus.PAUSED -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        ScheduleStatus.FAILED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }

    ElevatedCard(
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("schedule_card_${item.id}")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.recipientName.ifBlank { PhoneNormalizer.formatDisplayPhone(item.phoneNumber) },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!item.isGroup && item.recipientName.isNotBlank() && item.phoneNumber.isNotBlank()) {
                        Text(
                            text = PhoneNormalizer.formatDisplayPhone(item.phoneNumber),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = badgeBg
                ) {
                    Text(
                        text = status.displayLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeFg,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // Instance + Media + Recurrence Metadata Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(item.targetInstanceLabel) }
                )
                if (item.recurrenceEnum != RecurrenceType.ONCE) {
                    AssistChip(
                        onClick = {},
                        leadingIcon = { Icon(Icons.Filled.Repeat, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text(item.recurrenceEnum.displayLabel) }
                    )
                }
                if (item.hasMedia) {
                    val icon = when (item.mediaTypeEnum) {
                        MediaAttachmentType.IMAGE -> Icons.Filled.Image
                        MediaAttachmentType.VIDEO -> Icons.Filled.VideoLibrary
                        else -> Icons.Filled.AttachFile
                    }
                    AssistChip(
                        onClick = {},
                        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text(item.mediaDisplayName ?: item.mediaTypeEnum.displayLabel) }
                    )
                }
            }

            if (item.hasMedia && item.mediaTypeEnum == MediaAttachmentType.IMAGE && !item.mediaPath.isNullOrBlank()) {
                AsyncImage(
                    model = File(item.mediaPath),
                    contentDescription = "Scheduled image preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            }

            if (item.messageBody.isNotBlank()) {
                Text(
                    text = item.messageBody,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!item.lastError.isNullOrBlank() && (status == ScheduleStatus.FAILED || (status == ScheduleStatus.PENDING && item.retryCount > 0))) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = if (status == ScheduleStatus.FAILED) "Failure reason" else "Retry notice",
                        tint = if (status == ScheduleStatus.FAILED) MaterialTheme.colorScheme.error else statusColors.onWarningContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = item.lastError,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status == ScheduleStatus.FAILED) MaterialTheme.colorScheme.error else statusColors.onWarningContainer
                    )
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = dateFormat.format(Date(item.effectiveTriggerMillis)),
                        style = MaterialTheme.typography.labelMedium
                    )
                    if (status == ScheduleStatus.PENDING) {
                        val countdown = RecurrenceCalculator.formatCountdown(item.effectiveTriggerMillis)
                        Text(
                            text = if (item.retryCount > 0 && item.retryAtMillis > 0L) {
                                "Retry 1/1 • $countdown"
                            } else {
                                countdown
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (item.retryCount > 0) statusColors.onWarningContainer else MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onTogglePause,
                        modifier = Modifier.testTag("toggle_pause_button_${item.id}")
                    ) {
                        Icon(
                            imageVector = if (status == ScheduleStatus.PAUSED) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = if (status == ScheduleStatus.PAUSED) "Resume schedule" else "Pause schedule"
                        )
                    }
                    IconButton(
                        onClick = onSendNow,
                        modifier = Modifier.testTag("send_now_button_${item.id}")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send now")
                    }
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.testTag("edit_schedule_button_${item.id}")
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit schedule")
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("delete_schedule_button_${item.id}")
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete schedule",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyQueueCard(
    onCreateFirst: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ScheduleSend,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "No scheduled dispatches yet",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "Schedule text, photos, videos, or PDFs for WhatsApp, Business, or Cloned apps—even overnight while locked.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            FilledTonalButton(
                onClick = onCreateFirst,
                colors = ButtonDefaults.filledTonalButtonColors(),
                modifier = Modifier.testTag("empty_create_schedule_button")
            ) {
                Text("Schedule First Message")
            }
        }
    }
}
