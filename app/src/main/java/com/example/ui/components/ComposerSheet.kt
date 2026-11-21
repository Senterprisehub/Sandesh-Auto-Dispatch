package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.data.local.MessageTemplateEntity
import com.example.data.model.MediaAttachmentType
import com.example.data.model.RecurrenceType
import com.example.data.model.WhatsAppInstance
import com.example.oem.PermissionManager
import com.example.ui.viewmodel.ComposerDraft
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScheduleComposerSheet(
    draft: ComposerDraft,
    instances: List<WhatsAppInstance>,
    templates: List<MessageTemplateEntity>,
    onUpdateDraft: ((ComposerDraft) -> ComposerDraft) -> Unit,
    onStageMedia: (Uri, MediaAttachmentType?) -> Unit,
    onContactPicked: (Uri) -> Unit,
    onPickFromWhatsApp: (isGroupMode: Boolean) -> Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showAccessibilityPrompt by remember { mutableStateOf(false) }

    val visualMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onStageMedia(uri, null)
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onStageMedia(uri, MediaAttachmentType.DOCUMENT)
    }

    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { uri ->
        if (uri != null) {
            // PickContact returns ContactsContract.Contacts URI; query phone number via CommonDataKinds.Phone if permitted
            val hasPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
            if (hasPerm) {
                val phoneUri = resolveFirstPhoneUri(context, uri)
                if (phoneUri != null) {
                    onContactPicked(phoneUri)
                }
            }
        }
    }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            contactPickerLauncher.launch(null)
        }
    }

    val formattedDate = remember(draft.scheduledTimeMillis) {
        SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault()).format(Date(draft.scheduledTimeMillis))
    }
    val formattedTime = remember(draft.scheduledTimeMillis) {
        SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(draft.scheduledTimeMillis))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (draft.editingId == 0L) "Schedule Message" else "Edit Schedule",
                    style = MaterialTheme.typography.headlineSmall
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_composer_button")
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close composer")
                }
            }

            // 1. Target WhatsApp / Business / Cloned Instance
            Text(
                text = "Target WhatsApp Instance",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                instances.forEach { instance ->
                    val selected = draft.selectedInstance?.uniqueKey == instance.uniqueKey
                    FilterChip(
                        selected = selected,
                        onClick = { onUpdateDraft { it.copy(selectedInstance = instance) } },
                        label = { Text(instance.displayLabel) },
                        modifier = Modifier.testTag("instance_chip_${instance.packageName}_${instance.userSerialNumber}")
                    )
                }
            }

            // 2. Recipient Mode: Individual Phone vs Group/Contact Search
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !draft.isGroup,
                    onClick = { onUpdateDraft { it.copy(isGroup = false) } },
                    leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    label = { Text("Phone Number") },
                    modifier = Modifier.testTag("mode_phone_chip")
                )
                FilterChip(
                    selected = draft.isGroup,
                    onClick = { onUpdateDraft { it.copy(isGroup = true) } },
                    leadingIcon = { Icon(Icons.Filled.Group, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    label = { Text("Group / Name") },
                    modifier = Modifier.testTag("mode_group_chip")
                )
            }

            if (!draft.isGroup) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = draft.phoneNumber,
                        onValueChange = { valStr -> onUpdateDraft { it.copy(phoneNumber = valStr) } },
                        label = { Text("WhatsApp Phone (+91 or 10 digits)") },
                        placeholder = { Text("98765 43210") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("phone_number_input")
                    )
                    FilledTonalIconButton(
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.READ_CONTACTS
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                contactPickerLauncher.launch(null)
                            } else {
                                contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("pick_contact_button")
                    ) {
                        Icon(Icons.Filled.Contacts, contentDescription = "Pick from device contacts")
                    }
                    FilledTonalIconButton(
                        onClick = {
                            if (!onPickFromWhatsApp(false)) {
                                showAccessibilityPrompt = true
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("pick_whatsapp_contact_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Chat,
                            contentDescription = "Pick contact & phone number from WhatsApp"
                        )
                    }
                }
                Text(
                    text = "Tip: Tap the Contacts icon for Phonebook, or the Chat icon to open ${draft.selectedInstance?.displayLabel ?: "WhatsApp"} and auto-fetch both Name & Phone Number.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = draft.recipientName,
                    onValueChange = { valStr -> onUpdateDraft { it.copy(recipientName = valStr) } },
                    label = { Text("Contact Display Name (auto-fetched from WhatsApp)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("recipient_name_input")
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = draft.recipientName,
                        onValueChange = { valStr -> onUpdateDraft { it.copy(recipientName = valStr) } },
                        label = { Text("Exact WhatsApp Group or Contact Name") },
                        placeholder = { Text("Family Group or Office Updates") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("group_name_input")
                    )
                    FilledTonalIconButton(
                        onClick = {
                            if (!onPickFromWhatsApp(true)) {
                                showAccessibilityPrompt = true
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("pick_whatsapp_group_button")
                    ) {
                        Icon(
                            Icons.Filled.Forum,
                            contentDescription = "Pick group directly from WhatsApp"
                        )
                    }
                }
                Text(
                    text = "Tap the Chat icon to open ${draft.selectedInstance?.displayLabel ?: "WhatsApp"}, search your group, and auto-capture its exact title.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 3. Quick Template Chips
            if (templates.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    templates.take(6).forEach { tpl ->
                        AssistChip(
                            onClick = {
                                onUpdateDraft {
                                    val nextText = if (it.messageBody.isBlank()) tpl.body else "${it.messageBody}\n${tpl.body}"
                                    it.copy(messageBody = nextText)
                                }
                            },
                            label = { Text("+ ${tpl.title}") },
                            modifier = Modifier.testTag("quick_template_chip_${tpl.id}")
                        )
                    }
                }
            }

            // 4. Text / Media Caption Input
            OutlinedTextField(
                value = draft.messageBody,
                onValueChange = { valStr -> onUpdateDraft { it.copy(messageBody = valStr) } },
                label = {
                    Text(
                        if (draft.mediaType == MediaAttachmentType.NONE) "Message Text"
                        else "Caption (Optional)"
                    )
                },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("message_body_input")
            )

            // 5. Text & Media Attachments Bar (Photo Picker & Document Picker)
            Text(
                text = "Media Attachment (Optional)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        visualMediaLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("attach_photo_video_button")
                ) {
                    Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Photo / Video")
                }
                OutlinedButton(
                    onClick = {
                        documentPickerLauncher.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("attach_document_button")
                ) {
                    Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Document")
                }
            }

            if (draft.mediaType != MediaAttachmentType.NONE && !draft.mediaPath.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (draft.mediaType == MediaAttachmentType.IMAGE) {
                            AsyncImage(
                                model = File(draft.mediaPath),
                                contentDescription = "Selected image preview",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        } else {
                            Icon(
                                imageVector = if (draft.mediaType == MediaAttachmentType.VIDEO) {
                                    Icons.Filled.VideoLibrary
                                } else {
                                    Icons.Filled.Description
                                },
                                contentDescription = "Attachment icon",
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = draft.mediaDisplayName ?: "Attachment",
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1
                            )
                            Text(
                                text = draft.mediaType.displayLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        IconButton(
                            onClick = {
                                onUpdateDraft {
                                    it.copy(
                                        mediaType = MediaAttachmentType.NONE,
                                        mediaPath = null,
                                        mediaMimeType = null,
                                        mediaDisplayName = null
                                    )
                                }
                            },
                            modifier = Modifier.testTag("remove_media_button")
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove attachment")
                        }
                    }
                }
            }

            HorizontalDivider()

            // 6. Date, Time & Recurrence
            Text(
                text = "Date, Time & Recurrence",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("pick_date_button")
                ) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(formattedDate, style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = { showTimePicker = true },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("pick_time_button")
                ) {
                    Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(formattedTime, style = MaterialTheme.typography.labelMedium)
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                RecurrenceType.entries.forEach { rec ->
                    FilterChip(
                        selected = draft.recurrence == rec,
                        onClick = { onUpdateDraft { it.copy(recurrence = rec) } },
                        leadingIcon = if (rec != RecurrenceType.ONCE) {
                            { Icon(Icons.Filled.Repeat, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        label = { Text(rec.displayLabel) },
                        modifier = Modifier.testTag("recurrence_chip_${rec.name}")
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("save_schedule_button")
            ) {
                Text(if (draft.editingId == 0L) "Arm Scheduled Dispatch" else "Update Schedule")
            }
        }
    }

    if (showDatePicker) {
        val currentLocal = Calendar.getInstance().apply { timeInMillis = draft.scheduledTimeMillis }
        val initialUtc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(
                currentLocal.get(Calendar.YEAR),
                currentLocal.get(Calendar.MONTH),
                currentLocal.get(Calendar.DAY_OF_MONTH)
            )
        }.timeInMillis

        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialUtc)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selectedUtcMillis ->
                            val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                                timeInMillis = selectedUtcMillis
                            }
                            val updatedLocal = Calendar.getInstance().apply {
                                timeInMillis = draft.scheduledTimeMillis
                                set(Calendar.YEAR, utcCal.get(Calendar.YEAR))
                                set(Calendar.MONTH, utcCal.get(Calendar.MONTH))
                                set(Calendar.DAY_OF_MONTH, utcCal.get(Calendar.DAY_OF_MONTH))
                            }
                            onUpdateDraft { it.copy(scheduledTimeMillis = updatedLocal.timeInMillis) }
                        }
                        showDatePicker = false
                    }
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val cal = Calendar.getInstance().apply { timeInMillis = draft.scheduledTimeMillis }
        val timePickerState = rememberTimePickerState(
            initialHour = cal.get(Calendar.HOUR_OF_DAY),
            initialMinute = cal.get(Calendar.MINUTE),
            is24Hour = false
        )
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val updated = Calendar.getInstance().apply {
                            timeInMillis = draft.scheduledTimeMillis
                            set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                            set(Calendar.MINUTE, timePickerState.minute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        onUpdateDraft { it.copy(scheduledTimeMillis = updated.timeInMillis) }
                        showTimePicker = false
                    }
                ) { Text("Set Time") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            },
            title = { Text("Select Dispatch Time") },
            text = { TimePicker(state = timePickerState) }
        )
    }

    if (showAccessibilityPrompt) {
        AlertDialog(
            onDismissRequest = { showAccessibilityPrompt = false },
            title = { Text("Enable Sandesh Auto-Dispatch") },
            text = {
                Text("Sandesh needs Sandesh Auto-Dispatch enabled (or JIT Auto-Toggle granted in Settings) to capture the selected contact's name and phone number (or group title) from ${draft.selectedInstance?.displayLabel ?: "WhatsApp"}.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAccessibilityPrompt = false
                        PermissionManager.openAccessibilitySettings(context)
                    },
                    modifier = Modifier.testTag("picker_open_accessibility_button")
                ) {
                    Text("Open Accessibility Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccessibilityPrompt = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun resolveFirstPhoneUri(context: android.content.Context, contactUri: Uri): Uri? {
    val contactId = contactUri.lastPathSegment ?: return null
    val cursor = context.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Phone._ID),
        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
        arrayOf(contactId),
        null
    ) ?: return null
    cursor.use {
        if (it.moveToFirst()) {
            val phoneId = it.getLong(0)
            return Uri.withAppendedPath(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                phoneId.toString()
            )
        }
    }
    return null
}
