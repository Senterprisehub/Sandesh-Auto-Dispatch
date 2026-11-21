package com.example.dispatch

import android.app.KeyguardManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.example.SandeshApplication
import com.example.data.local.ScheduledMessageEntity
import com.example.oem.PermissionManager
import com.example.service.JitAccessibilityController
import com.example.service.WhatsAppAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

class DispatchForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workerRunning = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        val active = DispatchCoordinator.finishAndClear()
        DispatchCoordinator.clearAll()
        workerRunning.set(false)
        if (active != null) {
            val app = applicationContext as? SandeshApplication
            serviceScope.launch {
                val retryOrNext = app?.container?.repository?.recordDispatchOutcome(
                    scheduleId = active.scheduleId,
                    success = false,
                    durationMillis = System.currentTimeMillis() - active.startTimeMillis,
                    detailMessage = "Foreground service timeout before completion",
                    allowRetry = true
                )
                if (retryOrNext != null) {
                    AlarmScheduler.schedule(applicationContext, retryOrNext)
                }
            }
        }
        JitAccessibilityController.ensureIdleOffIfJitEnabled(applicationContext)
        stopSelf(startId)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val scheduleId = intent?.getLongExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, -1L) ?: -1L
        val wakeIntent = Intent(this, WakeUnlockActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val notif = NotificationHelper.buildDispatchForegroundNotification(
            context = this,
            recipientLabel = "recipient",
            wakeIntent = wakeIntent
        )
        ServiceCompat.startForeground(
            this,
            NotificationHelper.NOTIF_ID_FOREGROUND,
            notif,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            } else {
                0
            }
        )

        if (scheduleId > 0L) {
            DispatchCoordinator.enqueueSchedule(scheduleId)
        }

        if (!DispatchCoordinator.hasActiveOrQueuedWork()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (workerRunning.compareAndSet(false, true)) {
            serviceScope.launch {
                processDispatchQueue(wakeIntent)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun processDispatchQueue(wakeIntent: Intent) {
        val app = applicationContext as? SandeshApplication
        if (app == null) {
            DispatchCoordinator.clearAll()
            workerRunning.set(false)
            stopSelf()
            return
        }
        val container = app.container
        var jitEnabledForBatch = false

        try {
            container.repository.recoverStuckDispatching()

            if (!PermissionManager.isAccessibilityServiceEnabled(this) &&
                JitAccessibilityController.hasWriteSecureSettings(this)
            ) {
                jitEnabledForBatch = JitAccessibilityController.enableServiceTemporarily(this)
                if (jitEnabledForBatch) {
                    var bindWait = 0L
                    while (bindWait < 1500L) {
                        val enabled = PermissionManager.isAccessibilityServiceEnabled(this)
                        val bound = WhatsAppAccessibilityService.isServiceBound()
                        if (enabled && (bound || bindWait >= 900L)) break
                        delay(100L)
                        bindWait += 100L
                    }
                }
            }

            while (true) {
                val nextId = DispatchCoordinator.dequeueNextSchedule() ?: break
                executeSingleDispatch(app, nextId, wakeIntent)
                if (DispatchCoordinator.pendingCount() > 0) {
                    delay(450L)
                }
            }
        } finally {
            runCatching {
                container.repository.runStorageHygiene()
                AlarmScheduler.hydrateAlarmWindow(applicationContext, container.repository)
            }
            workerRunning.set(false)
            if (jitEnabledForBatch || JitAccessibilityController.hasWriteSecureSettings(this)) {
                delay(250L)
                JitAccessibilityController.ensureIdleOffIfJitEnabled(applicationContext)
            }
            stopSelf()
        }
    }

    private suspend fun executeSingleDispatch(
        app: SandeshApplication,
        scheduleId: Long,
        wakeIntent: Intent
    ) {
        val container = app.container
        val item = container.repository.getScheduleById(scheduleId) ?: return
        val recipientLabel = item.recipientName.ifBlank { item.phoneNumber }

        if (!PermissionManager.isAccessibilityServiceEnabled(this)) {
            val next = container.repository.recordDispatchOutcome(
                scheduleId = item.id,
                success = false,
                durationMillis = 0L,
                detailMessage = "Accessibility Service disabled. Enable it in Settings -> Permission Manager.",
                allowRetry = true
            )
            if (next != null) AlarmScheduler.schedule(this, next)
            NotificationHelper.showResultNotification(
                context = this,
                scheduleId = item.id,
                recipientLabel = recipientLabel,
                success = false,
                detail = "Accessibility Service is off"
            )
            return
        }

        container.repository.markDispatching(item.id)

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val wasLockedInitially = (keyguardManager?.isKeyguardLocked == true) || !(powerManager?.isInteractive ?: true)

        val decryptedCreds = container.vaultPreferences.decryptForDispatch()
        val normalizedPhone = PhoneNormalizer.normalizeForWhatsApp(item.phoneNumber)

        val session = ActiveDispatchSession(
            scheduleId = item.id,
            targetPackage = item.targetPackage,
            targetUserSerial = item.targetUserSerial,
            isCloneTarget = item.targetUserSerial != 0L,
            targetInstanceLabel = item.targetInstanceLabel,
            recipientName = item.recipientName,
            normalizedPhone = normalizedPhone,
            isGroup = item.isGroup,
            messageBody = item.messageBody,
            hasMedia = item.hasMedia,
            wasLockedInitially = wasLockedInitially,
            credentials = decryptedCreds
        )
        DispatchCoordinator.startSession(session)

        runCatching { startActivity(wakeIntent) }

        if (wasLockedInitially) {
            var waited = 0L
            while (waited < 8500L && (keyguardManager?.isKeyguardLocked == true)) {
                delay(350L)
                waited += 350L
            }
            delay(350L)
        } else {
            delay(350L)
        }

        val launched = launchWhatsAppTarget(item, normalizedPhone)
        if (!launched) {
            val finished = DispatchCoordinator.finishAndClear()
            val duration = finished?.let { System.currentTimeMillis() - it.startTimeMillis } ?: 0L
            val next = container.repository.recordDispatchOutcome(
                scheduleId = item.id,
                success = false,
                durationMillis = duration,
                detailMessage = "Could not launch ${item.targetInstanceLabel}. Verify the app is installed.",
                allowRetry = true
            )
            if (next != null) AlarmScheduler.schedule(this, next)
            NotificationHelper.showResultNotification(
                context = this,
                scheduleId = item.id,
                recipientLabel = recipientLabel,
                success = false,
                detail = "Could not open ${item.targetInstanceLabel}"
            )
            return
        }

        var elapsed = 0L
        while (elapsed < 38_000L && DispatchCoordinator.current()?.scheduleId == item.id) {
            delay(500L)
            elapsed += 500L
        }

        if (DispatchCoordinator.current()?.scheduleId == item.id) {
            val timedOut = DispatchCoordinator.finishAndClear()
            val duration = timedOut?.let { System.currentTimeMillis() - it.startTimeMillis } ?: 38_000L
            val next = container.repository.recordDispatchOutcome(
                scheduleId = item.id,
                success = false,
                durationMillis = duration,
                detailMessage = "Timed out before WhatsApp Send button could be triggered",
                allowRetry = true
            )
            if (next != null) AlarmScheduler.schedule(this, next)
            NotificationHelper.showResultNotification(
                context = this,
                scheduleId = item.id,
                recipientLabel = recipientLabel,
                success = false,
                detail = next?.let { "Retrying automatically in 60s" }
                    ?: "Dispatch timed out — verify screen unlock & WhatsApp layout"
            )
        }
    }

    private fun launchWhatsAppTarget(
        item: ScheduledMessageEntity,
        normalizedPhone: String
    ): Boolean {
        val app = applicationContext as SandeshApplication
        val isClone = item.targetUserSerial != 0L

        if (item.hasMedia && !item.mediaPath.isNullOrBlank()) {
            val fileUri = app.container.mediaStorage.getFileProviderUri(item.mediaPath) ?: return false
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = item.mediaMimeType ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, fileUri)
                if (item.messageBody.isNotBlank()) {
                    putExtra(Intent.EXTRA_TEXT, item.messageBody)
                }
                setPackage(item.targetPackage)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            runCatching {
                grantUriPermission(item.targetPackage, fileUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return runCatching { startActivity(sendIntent); true }.getOrDefault(false)
        }

        if (!isClone && !item.isGroup && normalizedPhone.isNotBlank()) {
            val encodedText = URLEncoder.encode(item.messageBody, "UTF-8")
            val uri = Uri.parse("whatsapp://send?phone=$normalizedPhone&text=$encodedText")
            val waIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(item.targetPackage)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val started = runCatching { startActivity(waIntent); true }.getOrDefault(false)
            if (started) return true
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, item.messageBody)
            setPackage(item.targetPackage)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return runCatching { startActivity(shareIntent); true }.getOrDefault(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        DispatchCoordinator.clearAll()
        workerRunning.set(false)
        JitAccessibilityController.ensureIdleOffIfJitEnabled(applicationContext)
        serviceScope.cancel()
    }
}
