package com.example.dispatch

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import com.example.MainActivity
import com.example.data.model.WhatsAppInstance
import com.example.oem.WhatsAppInstanceDiscovery
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicReference

data class PickedWhatsAppRecipient(
    val displayName: String,
    val phoneNumber: String,
    val isGroup: Boolean
)

data class ActivePickerSession(
    val targetPackage: String,
    val targetUserSerial: Long,
    val isCloneTarget: Boolean,
    val isGroupMode: Boolean,
    val startTimeMillis: Long = System.currentTimeMillis(),
    @Volatile var capturedChatName: String = "",
    @Volatile var hasTappedHeaderForInfo: Boolean = false,
    @Volatile var hasScrolledInfo: Boolean = false,
    @Volatile var hasResolvedClonePicker: Boolean = false,
    @Volatile var lastActionTimestamp: Long = 0L
)

object WhatsAppPickerCoordinator {

    private const val PICKER_TIMEOUT_MS = 90_000L

    private val activeSession = AtomicReference<ActivePickerSession?>(null)
    private val _pickedResults = MutableSharedFlow<PickedWhatsAppRecipient>(extraBufferCapacity = 4)
    val pickedResults: SharedFlow<PickedWhatsAppRecipient> = _pickedResults.asSharedFlow()

    fun startSession(instance: WhatsAppInstance, isGroupMode: Boolean) {
        activeSession.set(
            ActivePickerSession(
                targetPackage = instance.packageName,
                targetUserSerial = instance.userSerialNumber,
                isCloneTarget = instance.isClone,
                isGroupMode = isGroupMode
            )
        )
    }

    fun currentSession(): ActivePickerSession? {
        val session = activeSession.get() ?: return null
        if (System.currentTimeMillis() - session.startTimeMillis > PICKER_TIMEOUT_MS) {
            activeSession.compareAndSet(session, null)
            return null
        }
        return session
    }

    fun emitPickedAndFinish(result: PickedWhatsAppRecipient) {
        activeSession.set(null)
        _pickedResults.tryEmit(result)
    }

    fun cancelSession() {
        activeSession.set(null)
    }

    fun launchSelectedWhatsAppForPicking(
        context: Context,
        instance: WhatsAppInstance,
        isGroupMode: Boolean
    ): Boolean {
        startSession(instance, isGroupMode)

        if (instance.isClone) {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            val userHandle = WhatsAppInstanceDiscovery.resolveUserHandle(context, instance.userSerialNumber)
            val activities = runCatching {
                launcherApps?.getActivityList(instance.packageName, userHandle)
            }.getOrNull().orEmpty()
            val mainActivity = activities.firstOrNull()
            if (launcherApps != null && mainActivity != null) {
                val launched = runCatching {
                    launcherApps.startMainActivity(mainActivity.componentName, userHandle, null, null)
                    true
                }.getOrDefault(false)
                if (launched) return true
            }
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(instance.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (launchIntent != null) {
            val launched = runCatching { context.startActivity(launchIntent); true }.getOrDefault(false)
            if (launched) return true
        }

        cancelSession()
        return false
    }

    fun returnToSandesh(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        runCatching { context.startActivity(intent) }
    }

    /**
     * Extracts the best E.164 or 10+ digit phone number candidate from visible WhatsApp
     * Contact Info or Conversation header text nodes.
     */
    fun extractPhoneNumberFromTexts(texts: List<String>): String? {
        val phonePattern = Regex("""(?<!\d)(?:\+\d{1,3}[\s\-]?)?(?:\(?\d{2,5}\)?[\s\-]?)?\d{4,5}[\s\-]?\d{4,6}(?!\d)""")
        val candidates = mutableListOf<Pair<Int, String>>()

        for (raw in texts) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.length > 40) continue
            // Skip timestamps, member counts, or dates containing letters
            if (trimmed.count { it.isLetter() } > 0) continue

            val matches = phonePattern.findAll(trimmed)
            for (match in matches) {
                val candidate = match.value.trim()
                val digits = candidate.replace(Regex("[^0-9]"), "")
                if (digits.length in 10..15 && PhoneNormalizer.isValidPhoneInput(candidate)) {
                    val priority = when {
                        candidate.startsWith("+") -> 3
                        candidate.contains(" ") || candidate.contains("-") -> 2
                        else -> 1
                    }
                    candidates += priority to candidate
                }
            }
        }

        return candidates.maxByOrNull { it.first }?.second
    }

    fun looksLikePhoneNumber(title: String): Boolean {
        val trimmed = title.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isLetter() }) return false
        val digits = trimmed.replace(Regex("[^0-9]"), "")
        return digits.length in 10..15 && PhoneNormalizer.isValidPhoneInput(trimmed)
    }
}
