package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.provider.ContactsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.SandeshApplication
import com.example.data.local.DispatchLogEntity
import com.example.data.local.MessageTemplateEntity
import com.example.data.local.ScheduledMessageEntity
import com.example.data.media.StagedMedia
import com.example.data.model.LockCredentialType
import com.example.data.model.MediaAttachmentType
import com.example.data.model.RecurrenceType
import com.example.data.model.ScheduleStatus
import com.example.data.model.WhatsAppInstance
import com.example.dispatch.AlarmScheduler
import com.example.dispatch.DispatchCoordinator
import com.example.dispatch.PhoneNormalizer
import com.example.dispatch.RecurrenceCalculator
import com.example.dispatch.WhatsAppPickerCoordinator
import com.example.oem.OemDetector
import com.example.oem.OemProfile
import com.example.oem.PermissionManager
import com.example.oem.PermissionReport
import com.example.oem.WhatsAppInstanceDiscovery
import com.example.security.VaultUiSummary
import com.example.service.JitAccessibilityController
import com.example.service.WhatsAppAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ComposerDraft(
    val editingId: Long = 0L,
    val recipientName: String = "",
    val phoneNumber: String = "",
    val isGroup: Boolean = false,
    val selectedInstance: WhatsAppInstance? = null,
    val messageBody: String = "",
    val mediaType: MediaAttachmentType = MediaAttachmentType.NONE,
    val mediaPath: String? = null,
    val mediaMimeType: String? = null,
    val mediaDisplayName: String? = null,
    val scheduledTimeMillis: Long = System.currentTimeMillis() + 15 * 60_000L,
    val recurrence: RecurrenceType = RecurrenceType.ONCE
)

data class SandeshUiState(
    val schedules: List<ScheduledMessageEntity> = emptyList(),
    val templates: List<MessageTemplateEntity> = emptyList(),
    val logs: List<DispatchLogEntity> = emptyList(),
    val instances: List<WhatsAppInstance> = emptyList(),
    val permissionReport: PermissionReport = PermissionReport(emptyList(), 0, 1, 0),
    val vaultSummary: VaultUiSummary = VaultUiSummary(),
    val oemProfile: OemProfile = OemDetector.detectOem()
)

class SandeshViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SandeshApplication
    private val repository = app.container.repository
    private val mediaStorage = app.container.mediaStorage
    private val vaultPrefs = app.container.vaultPreferences

    private val _instances = MutableStateFlow(WhatsAppInstanceDiscovery.discoverInstalledInstances(app))
    private val _permissionRefreshTick = MutableStateFlow(0)
    private val _oemProfile = MutableStateFlow(OemDetector.detectOem())

    private val _uiMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val uiMessages: SharedFlow<String> = _uiMessages.asSharedFlow()

    private val _composerDraft = MutableStateFlow<ComposerDraft?>(null)
    val composerDraft: StateFlow<ComposerDraft?> = _composerDraft.asStateFlow()

    val uiState: StateFlow<SandeshUiState> = combine(
        repository.schedules,
        repository.templates,
        repository.dispatchLogs,
        vaultPrefs.vaultSummary,
        combine(_instances, _permissionRefreshTick, _oemProfile) { inst, _, oem -> inst to oem }
    ) { schedules, templates, logs, vault, (instances, oem) ->
        SandeshUiState(
            schedules = schedules,
            templates = templates,
            logs = logs,
            instances = instances,
            permissionReport = PermissionManager.evaluateAll(app, vault.oemShieldAcknowledged),
            vaultSummary = vault,
            oemProfile = oem
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = SandeshUiState(
            instances = _instances.value,
            permissionReport = PermissionManager.evaluateAll(app, false),
            oemProfile = _oemProfile.value
        )
    )

    init {
        viewModelScope.launch {
            WhatsAppPickerCoordinator.pickedResults.collect { picked ->
                _composerDraft.value = _composerDraft.value?.let { draft ->
                    draft.copy(
                        recipientName = picked.displayName.ifBlank { draft.recipientName },
                        phoneNumber = if (picked.isGroup) "" else picked.phoneNumber.ifBlank { draft.phoneNumber },
                        isGroup = picked.isGroup
                    )
                }
                val label = picked.displayName.ifBlank { picked.phoneNumber }
                if (label.isNotBlank()) {
                    _uiMessages.tryEmit("Captured from WhatsApp: $label")
                }
            }
        }
    }

    fun refreshPermissionsAndInstances() {
        _instances.value = WhatsAppInstanceDiscovery.discoverInstalledInstances(app)
        _permissionRefreshTick.value++
    }

    fun cancelLingeringWhatsAppPicker() {
        WhatsAppPickerCoordinator.cancelSession()
        if (JitAccessibilityController.hasWriteSecureSettings(app) && !DispatchCoordinator.hasActiveOrQueuedWork()) {
            JitAccessibilityController.ensureIdleOffIfJitEnabled(app)
            refreshPermissionsAndInstances()
        }
    }

    fun notifyMessage(message: String) {
        _uiMessages.tryEmit(message)
    }

    fun setQuickTileAdded(added: Boolean) {
        viewModelScope.launch {
            vaultPrefs.setQuickTileAdded(added)
            if (added) {
                _uiMessages.tryEmit("Sandesh Auto-Dispatch Quick Tile marked active")
            }
        }
    }

    fun onQuickTileInstallResult(result: com.example.service.QuickTileInstallResult) {
        viewModelScope.launch {
            when (result) {
                com.example.service.QuickTileInstallResult.ADDED -> {
                    vaultPrefs.setQuickTileAdded(true)
                    _uiMessages.tryEmit("Sandesh Auto-Dispatch added to Quick Settings")
                }
                com.example.service.QuickTileInstallResult.ALREADY_ADDED -> {
                    vaultPrefs.setQuickTileAdded(true)
                    _uiMessages.tryEmit("Sandesh Auto-Dispatch is already in Quick Settings")
                }
                com.example.service.QuickTileInstallResult.DISMISSED -> {
                    _uiMessages.tryEmit("Quick Settings prompt dismissed — tap Add anytime")
                }
                com.example.service.QuickTileInstallResult.UNSUPPORTED_SDK,
                com.example.service.QuickTileInstallResult.FAILED -> Unit
            }
        }
    }

    fun pauseAccessibilityNow() {
        val paused = JitAccessibilityController.disableServiceSafely(app)
        refreshPermissionsAndInstances()
        _uiMessages.tryEmit(
            if (paused) "Sandesh Auto-Dispatch paused — safe for banking & UPI apps"
            else "Accessibility Service is already OFF"
        )
    }

    fun testJitAutoToggle() {
        if (!JitAccessibilityController.hasWriteSecureSettings(app)) {
            _uiMessages.tryEmit("Grant WRITE_SECURE_SETTINGS via ADB first to test JIT Auto-Toggle")
            return
        }
        viewModelScope.launch {
            val enabled = JitAccessibilityController.enableServiceTemporarily(app)
            if (!enabled) {
                _uiMessages.tryEmit("Could not toggle Accessibility via Settings.Secure")
                return@launch
            }
            refreshPermissionsAndInstances()
            delay(1200L)
            val boundOk = PermissionManager.isAccessibilityServiceEnabled(app) ||
                WhatsAppAccessibilityService.isServiceBound()
            JitAccessibilityController.disableServiceSafely(app)
            delay(200L)
            refreshPermissionsAndInstances()
            _uiMessages.tryEmit(
                if (boundOk) "JIT Cycle Verified: Auto-ON bound & returned to Idle OFF"
                else "JIT Cycle toggled OFF; verify Sandesh is allowed in Restricted Settings"
            )
        }
    }

    /**
     * Launches the selected WhatsApp / Business / Cloned app so the user can search and
     * select a contact or group. Auto-enables JIT Accessibility when WRITE_SECURE_SETTINGS is granted.
     */
    fun startWhatsAppPicker(isGroupMode: Boolean): Boolean {
        val canUseAccessibility = PermissionManager.isAccessibilityServiceEnabled(app) ||
            JitAccessibilityController.enableServiceTemporarily(app)
        if (!canUseAccessibility) {
            return false
        }
        val draft = _composerDraft.value ?: return false
        val target = draft.selectedInstance ?: _instances.value.firstOrNull() ?: return false
        val launched = WhatsAppPickerCoordinator.launchSelectedWhatsAppForPicking(
            context = app,
            instance = target,
            isGroupMode = isGroupMode
        )
        if (!launched) {
            JitAccessibilityController.ensureIdleOffIfJitEnabled(app)
            _uiMessages.tryEmit("Could not open ${target.displayLabel}. Verify it is installed.")
        }
        return true
    }

    fun openNewComposer(prefillTemplate: MessageTemplateEntity? = null) {
        val defaultInstance = _instances.value.firstOrNull()
        _composerDraft.value = ComposerDraft(
            selectedInstance = defaultInstance,
            messageBody = prefillTemplate?.body.orEmpty(),
            scheduledTimeMillis = System.currentTimeMillis() + 10 * 60_000L
        )
        if (prefillTemplate != null) {
            viewModelScope.launch { repository.incrementTemplateUsage(prefillTemplate.id) }
        }
    }

    fun openEditComposer(entity: ScheduledMessageEntity) {
        val matched = _instances.value.firstOrNull {
            it.packageName == entity.targetPackage && it.userSerialNumber == entity.targetUserSerial
        } ?: _instances.value.firstOrNull()
        _composerDraft.value = ComposerDraft(
            editingId = entity.id,
            recipientName = entity.recipientName,
            phoneNumber = entity.phoneNumber,
            isGroup = entity.isGroup,
            selectedInstance = matched,
            messageBody = entity.messageBody,
            mediaType = entity.mediaTypeEnum,
            mediaPath = entity.mediaPath,
            mediaMimeType = entity.mediaMimeType,
            mediaDisplayName = entity.mediaDisplayName,
            scheduledTimeMillis = entity.scheduledTimeMillis.coerceAtLeast(System.currentTimeMillis() + 60_000L),
            recurrence = entity.recurrenceEnum
        )
    }

    fun closeComposer() {
        _composerDraft.value = null
    }

    fun updateDraft(transform: (ComposerDraft) -> ComposerDraft) {
        _composerDraft.value = _composerDraft.value?.let(transform)
    }

    fun stagePickedMedia(uri: Uri, forcedType: MediaAttachmentType? = null) {
        viewModelScope.launch {
            val result = mediaStorage.stagePickedMedia(uri, forcedType)
            result.onSuccess { staged: StagedMedia ->
                updateDraft { draft ->
                    draft.copy(
                        mediaType = staged.type,
                        mediaPath = staged.localPath,
                        mediaMimeType = staged.mimeType,
                        mediaDisplayName = staged.displayName
                    )
                }
                _uiMessages.tryEmit("Attached ${staged.displayName}")
            }.onFailure {
                _uiMessages.tryEmit("Could not attach selected file")
            }
        }
    }

    fun resolvePickedContact(contactUri: Uri) {
        viewModelScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.query(
                        contactUri,
                        arrayOf(
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                        ),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val name = cursor.getString(0).orEmpty()
                            val phone = cursor.getString(1).orEmpty()
                            name to phone
                        } else null
                    }
                }.getOrNull()
            }
            if (resolved != null) {
                updateDraft {
                    it.copy(
                        recipientName = resolved.first,
                        phoneNumber = resolved.second,
                        isGroup = false
                    )
                }
            } else {
                _uiMessages.tryEmit("Selected contact has no phone number")
            }
        }
    }

    fun saveComposerSchedule() {
        val draft = _composerDraft.value ?: return
        val instance = draft.selectedInstance ?: _instances.value.firstOrNull() ?: return

        if (!draft.isGroup && !PhoneNormalizer.isValidPhoneInput(draft.phoneNumber)) {
            _uiMessages.tryEmit("Enter a valid 10-digit Indian or international phone number")
            return
        }
        if (draft.isGroup && draft.recipientName.isBlank()) {
            _uiMessages.tryEmit("Enter exact WhatsApp Group or Contact name")
            return
        }
        val hasMedia = draft.mediaType != MediaAttachmentType.NONE && !draft.mediaPath.isNullOrBlank()
        if (draft.messageBody.isBlank() && !hasMedia) {
            _uiMessages.tryEmit("Enter a message or attach media")
            return
        }

        val now = System.currentTimeMillis()
        val targetTime = if (draft.scheduledTimeMillis <= now) now + 60_000L else draft.scheduledTimeMillis

        viewModelScope.launch {
            val anchorDay = RecurrenceCalculator.extractDayOfMonth(targetTime)
            val entity = ScheduledMessageEntity(
                id = draft.editingId,
                recipientName = draft.recipientName.trim(),
                phoneNumber = if (draft.isGroup) "" else draft.phoneNumber.trim(),
                isGroup = draft.isGroup,
                targetPackage = instance.packageName,
                targetUserSerial = instance.userSerialNumber,
                targetInstanceLabel = instance.displayLabel,
                messageBody = draft.messageBody.trim(),
                mediaType = draft.mediaType.name,
                mediaPath = draft.mediaPath,
                mediaMimeType = draft.mediaMimeType,
                mediaDisplayName = draft.mediaDisplayName,
                scheduledTimeMillis = targetTime,
                recurrence = draft.recurrence.name,
                status = ScheduleStatus.PENDING.name,
                retryCount = 0,
                retryAtMillis = 0L,
                anchorDayOfMonth = anchorDay
            )
            repository.saveSchedule(entity)
            AlarmScheduler.hydrateAlarmWindow(app, repository)
            _composerDraft.value = null
            _uiMessages.tryEmit(
                "Scheduled for ${RecurrenceCalculator.formatCountdown(targetTime)}"
            )
        }
    }

    fun togglePauseResume(entity: ScheduledMessageEntity) {
        viewModelScope.launch {
            if (entity.statusEnum == ScheduleStatus.PAUSED) {
                val now = System.currentTimeMillis()
                val nextTime = if (entity.scheduledTimeMillis > now) {
                    entity.scheduledTimeMillis
                } else {
                    RecurrenceCalculator.computeNextOccurrence(
                        scheduledTimeMillis = entity.scheduledTimeMillis,
                        recurrence = entity.recurrenceEnum,
                        nowMillis = now,
                        anchorDayOfMonth = entity.anchorDayOfMonth
                    ) ?: (now + 60_000L)
                }
                val resumed = entity.copy(
                    scheduledTimeMillis = nextTime,
                    status = ScheduleStatus.PENDING.name,
                    retryCount = 0,
                    retryAtMillis = 0L
                )
                repository.saveSchedule(resumed)
                AlarmScheduler.hydrateAlarmWindow(app, repository)
                _uiMessages.tryEmit("Resumed schedule")
            } else {
                AlarmScheduler.cancel(app, entity.id)
                repository.saveSchedule(entity.copy(status = ScheduleStatus.PAUSED.name, retryAtMillis = 0L))
                AlarmScheduler.hydrateAlarmWindow(app, repository)
                _uiMessages.tryEmit("Schedule paused")
            }
        }
    }

    fun triggerImmediateDispatch(entity: ScheduledMessageEntity) {
        viewModelScope.launch {
            val armed = entity.copy(
                scheduledTimeMillis = System.currentTimeMillis() + 2500L,
                status = ScheduleStatus.PENDING.name,
                retryCount = 0,
                retryAtMillis = 0L
            )
            repository.saveSchedule(armed)
            AlarmScheduler.schedule(app, armed)
            _uiMessages.tryEmit("Launching dispatch in 2 seconds...")
        }
    }

    fun deleteSchedule(entity: ScheduledMessageEntity) {
        viewModelScope.launch {
            AlarmScheduler.cancel(app, entity.id)
            repository.deleteSchedule(entity)
            AlarmScheduler.hydrateAlarmWindow(app, repository)
            _uiMessages.tryEmit("Schedule deleted")
        }
    }

    fun saveTemplate(id: Long, title: String, category: String, body: String) {
        if (title.isBlank() || body.isBlank()) {
            _uiMessages.tryEmit("Template title and message body are required")
            return
        }
        viewModelScope.launch {
            repository.saveTemplate(
                MessageTemplateEntity(
                    id = id,
                    title = title.trim(),
                    category = category.trim().ifBlank { "General" },
                    body = body.trim()
                )
            )
            _uiMessages.tryEmit("Template saved")
        }
    }

    fun deleteTemplate(id: Long) {
        viewModelScope.launch {
            repository.deleteTemplate(id)
            _uiMessages.tryEmit("Template removed")
        }
    }

    fun clearHistoryLogs() {
        viewModelScope.launch {
            repository.clearHistoryLogs()
            _uiMessages.tryEmit("Dispatch history cleared")
        }
    }

    fun saveVaultCredential(type: LockCredentialType, secret: String) {
        viewModelScope.launch {
            vaultPrefs.saveDeviceCredential(type, secret)
            _uiMessages.tryEmit(
                if (type == LockCredentialType.NONE) "Lock credential cleared"
                else "Encrypted ${type.displayLabel} saved to Keystore Vault"
            )
        }
    }

    fun saveWhatsAppLockPin(pin: String) {
        viewModelScope.launch {
            vaultPrefs.saveWhatsAppLockPin(pin)
            _uiMessages.tryEmit(if (pin.isBlank()) "WhatsApp lock PIN cleared" else "WhatsApp PIN encrypted")
        }
    }

    fun setAutoRelock(enabled: Boolean) {
        viewModelScope.launch { vaultPrefs.setAutoRelock(enabled) }
    }

    fun markOemShieldAcknowledged(acknowledged: Boolean) {
        viewModelScope.launch {
            vaultPrefs.setOemShieldAcknowledged(acknowledged)
            refreshPermissionsAndInstances()
        }
    }

    fun clearVault() {
        viewModelScope.launch {
            vaultPrefs.clearAllCredentials()
            _uiMessages.tryEmit("Vault wiped")
        }
    }

    fun exportBackupToUri(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openOutputStream(uri)?.use { out ->
                        repository.exportBackupToStream(out)
                    } ?: error("Cannot open destination file")
                }
            }
            result.onSuccess {
                _uiMessages.tryEmit("Backup exported successfully (streaming)")
            }.onFailure {
                _uiMessages.tryEmit("Export failed: ${it.message}")
            }
        }
    }

    fun importBackupFromUri(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val count = app.contentResolver.openInputStream(uri)?.use { input ->
                        repository.importBackupFromStream(input).getOrThrow()
                    } ?: error("Cannot read selected backup file")
                    AlarmScheduler.hydrateAlarmWindow(app, repository)
                    count
                }
            }
            result.onSuccess { count ->
                _uiMessages.tryEmit("Restored $count items & hydrated alarm window")
            }.onFailure {
                _uiMessages.tryEmit("Restore failed: ${it.message}")
            }
        }
    }
}
