package com.example

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.SandeshDatabase
import com.example.data.local.ScheduledMessageEntity
import com.example.data.media.MediaStorageManager
import com.example.data.model.MediaAttachmentType
import com.example.data.model.RecurrenceType
import com.example.data.model.ScheduleStatus
import com.example.data.repository.SandeshRepository
import com.example.dispatch.PhoneNormalizer
import com.example.dispatch.RecurrenceCalculator
import com.example.oem.PermissionManager
import com.example.service.JitAccessibilityController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `app name matches Sandesh`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Sandesh", appName)
    }

    @Test
    fun `phone normalizer handles Indian 10 digit and trunk formats`() {
        assertEquals("919876543210", PhoneNormalizer.normalizeForWhatsApp("98765 43210"))
        assertEquals("919876543210", PhoneNormalizer.normalizeForWhatsApp("09876543210"))
        assertEquals("919876543210", PhoneNormalizer.normalizeForWhatsApp("+91-98765-43210"))
        assertEquals("447911123456", PhoneNormalizer.normalizeForWhatsApp("+44 7911 123456"))
        assertTrue(PhoneNormalizer.isValidPhoneInput("9876543210"))
    }

    @Test
    fun `recurrence calculator advances daily weekly and monthly strictly into future`() {
        val base = 1_700_000_000_000L
        assertNull(RecurrenceCalculator.computeNextOccurrence(base, RecurrenceType.ONCE, base + 1000L))

        val nextDaily = RecurrenceCalculator.computeNextOccurrence(base, RecurrenceType.DAILY, base + 60_000L)
        assertNotNull(nextDaily)
        assertTrue(nextDaily!! > base + 60_000L)

        val nextWeekly = RecurrenceCalculator.computeNextOccurrence(base, RecurrenceType.WEEKLY, base + 60_000L)
        assertNotNull(nextWeekly)
        assertTrue(nextWeekly!! >= base + 7L * 24L * 3600_000L)
    }

    @Test
    fun `repository saves recurring media schedule and exports json backup`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = SandeshDatabase.getInstance(context)
        val repo = SandeshRepository(
            db.scheduledMessageDao(),
            db.messageTemplateDao(),
            db.dispatchLogDao(),
            MediaStorageManager(context)
        )

        val now = System.currentTimeMillis()
        val id = repo.saveSchedule(
            ScheduledMessageEntity(
                recipientName = "Aarav Sharma",
                phoneNumber = "9876543210",
                targetPackage = "com.whatsapp.w4b",
                targetInstanceLabel = "WhatsApp Business",
                messageBody = "Invoice attached",
                mediaType = MediaAttachmentType.DOCUMENT.name,
                mediaDisplayName = "invoice.pdf",
                scheduledTimeMillis = now + 120_000L,
                recurrence = RecurrenceType.DAILY.name,
                status = ScheduleStatus.PENDING.name
            )
        )
        assertTrue(id > 0)

        val nextRecurring = repo.recordDispatchOutcome(
            scheduleId = id,
            success = true,
            durationMillis = 1200L,
            detailMessage = "Sent"
        )
        assertNotNull(nextRecurring)
        assertEquals(ScheduleStatus.PENDING.name, nextRecurring!!.status)

        val backupJson = repo.exportBackupJson()
        assertTrue(backupJson.contains("Aarav Sharma"))
        assertTrue(backupJson.contains("WhatsApp Business"))
    }

    @Test
    fun `permission manager evaluates core and optional permissions`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val report = PermissionManager.evaluateAll(context, oemShieldAcknowledged = false)
        assertTrue(report.items.any { it.id == "accessibility" })
        assertTrue(report.items.any { it.id == "exact_alarm" })
        assertTrue(report.items.any { it.id == "battery" })
        assertTrue(report.items.any { it.id == "oem_shield" })
    }

    @Test
    fun `whatsapp picker coordinator extracts phone numbers cleanly from contact info texts`() {
        val sampleTexts = listOf(
            "Aarav Sharma",
            "Last seen today at 12:30 PM",
            "Media, links, and docs",
            "124",
            "+91 98765 43210",
            "Hey there! I am using WhatsApp."
        )
        assertEquals(
            "+91 98765 43210",
            com.example.dispatch.WhatsAppPickerCoordinator.extractPhoneNumberFromTexts(sampleTexts)
        )
        assertTrue(com.example.dispatch.WhatsAppPickerCoordinator.looksLikePhoneNumber("+91 98765 43210"))
        assertTrue(!com.example.dispatch.WhatsAppPickerCoordinator.looksLikePhoneNumber("Aarav Sharma"))
    }

    @Test
    fun `jit controller preserves other accessibility services during add and remove`() {
        val talkBack = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"
        val bitwarden = "com.x8bit.bitwarden/.Accessibility.AccessibilityService"
        val sandesh = ComponentName("com.aistudio.sandeshscheduler.kmpqzx", "com.example.service.WhatsAppAccessibilityService")

        val combined = JitAccessibilityController.addComponentToEnabledList("$talkBack:$bitwarden", sandesh)
        assertEquals("$talkBack:$bitwarden:${sandesh.flattenToString()}", combined)

        // Adding again should not duplicate
        val deduped = JitAccessibilityController.addComponentToEnabledList(combined, sandesh)
        assertEquals(combined, deduped)

        // Removing short or full name removes only Sandesh
        val shortSandesh = sandesh.flattenToShortString()
        val afterRemove = JitAccessibilityController.removeComponentFromEnabledList("$talkBack:$shortSandesh:$bitwarden", sandesh)
        assertEquals("$talkBack:$bitwarden", afterRemove)
    }

    @Test
    fun `jit controller toggles Settings Secure safely and marks PermissionManager JIT ready while idle`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)

        val talkBack = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, talkBack)
        Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)

        // Initially idle OFF, PermissionManager still recognizes JIT readiness
        val idleReport = PermissionManager.evaluateAll(app, oemShieldAcknowledged = false)
        assertTrue(idleReport.isJitAutoToggleActive)
        assertFalse(idleReport.isAccessibilityCurrentlyOn)
        val accItem = idleReport.items.first { it.id == "accessibility" }
        assertTrue(accItem.isGranted)
        assertEquals("JIT Idle", accItem.actionLabel)

        // Temporarily enable
        assertTrue(JitAccessibilityController.enableServiceTemporarily(app))
        assertTrue(PermissionManager.isAccessibilityServiceEnabled(app))
        val activeServices = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        assertTrue(activeServices.contains(talkBack))

        // Disable safely and confirm TalkBack remains active
        assertTrue(JitAccessibilityController.disableServiceSafely(app))
        assertFalse(PermissionManager.isAccessibilityServiceEnabled(app))
        val afterDisable = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        assertEquals(talkBack, afterDisable)
        assertEquals(1, Settings.Secure.getInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0))
    }

    @Test
    fun `fifo dispatch coordinator deduplicates and drains in arrival order`() {
        com.example.dispatch.DispatchCoordinator.clearAll()
        assertTrue(com.example.dispatch.DispatchCoordinator.enqueueSchedule(101L))
        assertTrue(com.example.dispatch.DispatchCoordinator.enqueueSchedule(102L))
        assertFalse(com.example.dispatch.DispatchCoordinator.enqueueSchedule(101L))
        assertEquals(2, com.example.dispatch.DispatchCoordinator.pendingCount())
        assertEquals(101L, com.example.dispatch.DispatchCoordinator.dequeueNextSchedule())
        assertEquals(102L, com.example.dispatch.DispatchCoordinator.dequeueNextSchedule())
        assertNull(com.example.dispatch.DispatchCoordinator.dequeueNextSchedule())
    }

    @Test
    fun `monthly recurrence preserves day 31 anchor after February short month`() {
        val jan31 = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.JANUARY, 31, 9, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        val feb = RecurrenceCalculator.computeNextOccurrence(jan31, RecurrenceType.MONTHLY, jan31 + 1000L, anchorDayOfMonth = 31)!!
        val febCal = java.util.Calendar.getInstance().apply { timeInMillis = feb }
        assertEquals(java.util.Calendar.FEBRUARY, febCal.get(java.util.Calendar.MONTH))
        assertEquals(28, febCal.get(java.util.Calendar.DAY_OF_MONTH))

        val mar = RecurrenceCalculator.computeNextOccurrence(feb, RecurrenceType.MONTHLY, feb + 1000L, anchorDayOfMonth = 31)!!
        val marCal = java.util.Calendar.getInstance().apply { timeInMillis = mar }
        assertEquals(java.util.Calendar.MARCH, marCal.get(java.util.Calendar.MONTH))
        assertEquals(31, marCal.get(java.util.Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `smart 1-time auto-retry arms 60s retry then fails on second error and cleans 90d logs`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = SandeshDatabase.getInstance(context)
        val repo = SandeshRepository(
            db.scheduledMessageDao(),
            db.messageTemplateDao(),
            db.dispatchLogDao(),
            MediaStorageManager(context)
        )

        val pastTime = System.currentTimeMillis() - 20 * 60_000L
        val id = repo.saveSchedule(
            ScheduledMessageEntity(
                recipientName = "Vikram",
                phoneNumber = "9876543210",
                messageBody = "Hello",
                scheduledTimeMillis = pastTime,
                recurrence = RecurrenceType.ONCE.name,
                status = ScheduleStatus.PENDING.name
            )
        )

        // First transient failure -> schedules retryCount = 1 at ~now + 60s
        val retryItem = repo.recordDispatchOutcome(id, success = false, durationMillis = 1000L, detailMessage = "UI lag", allowRetry = true)
        assertNotNull(retryItem)
        assertEquals(ScheduleStatus.PENDING.name, retryItem!!.status)
        assertEquals(1, retryItem.retryCount)
        assertTrue(retryItem.retryAtMillis > System.currentTimeMillis())

        // Even if original scheduledTimeMillis has elapsed, hydrateAlarmWindow must preserve active +60s retry
        com.example.dispatch.AlarmScheduler.hydrateAlarmWindow(context, repo)
        val preservedRetry = repo.getScheduleById(id)!!
        assertEquals(ScheduleStatus.PENDING.name, preservedRetry.status)
        assertEquals(1, preservedRetry.retryCount)
        assertTrue(preservedRetry.retryAtMillis > System.currentTimeMillis())

        // Second failure -> transitions cleanly to FAILED
        val afterSecondFail = repo.recordDispatchOutcome(id, success = false, durationMillis = 1000L, detailMessage = "UI lag 2", allowRetry = true)
        assertNull(afterSecondFail)
        assertEquals(ScheduleStatus.FAILED.name, repo.getScheduleById(id)!!.status)

        // Insert 95-day-old log and verify hygiene prunes it while retaining recent logs
        val oldTs = System.currentTimeMillis() - 95L * 24L * 3600_000L
        db.dispatchLogDao().insert(
            com.example.data.local.DispatchLogEntity(
                scheduleId = id,
                recipientSnapshot = "Old",
                instanceLabel = "WhatsApp",
                messagePreview = "Old log",
                hasMedia = false,
                executedAtMillis = oldTs,
                isSuccess = true,
                detailMessage = "Old"
            )
        )
        val hygiene = repo.runStorageHygiene(force = true)
        assertTrue(hygiene.prunedLogsCount >= 1)
    }

    @Test
    fun `rolling alarm window caps OS registrations at 250 despite 350 pending schedules`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = SandeshDatabase.getInstance(context)
        val repo = SandeshRepository(
            db.scheduledMessageDao(),
            db.messageTemplateDao(),
            db.dispatchLogDao(),
            MediaStorageManager(context)
        )
        val now = System.currentTimeMillis()
        for (i in 1..350) {
            repo.saveSchedule(
                ScheduledMessageEntity(
                    recipientName = "R$i",
                    phoneNumber = "9876543210",
                    messageBody = "Batch $i",
                    scheduledTimeMillis = now + i * 60_000L,
                    status = ScheduleStatus.PENDING.name
                )
            )
        }
        val registered = com.example.dispatch.AlarmScheduler.hydrateAlarmWindow(context, repo)
        assertEquals(com.example.dispatch.AlarmScheduler.MAX_ACTIVE_ALARMS_WINDOW, registered)
    }

    @Test
    fun `quick tile installer checks SDK gating maps OS results and persists VaultPreferences state`() = runTest {
        assertFalse(com.example.service.QuickTileInstaller.supportsOneTapPrompt(android.os.Build.VERSION_CODES.S))
        assertTrue(com.example.service.QuickTileInstaller.supportsOneTapPrompt(android.os.Build.VERSION_CODES.TIRAMISU))

        assertEquals(
            com.example.service.QuickTileInstallResult.ADDED,
            com.example.service.QuickTileInstaller.mapRequestResultCode(android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED)
        )
        assertEquals(
            com.example.service.QuickTileInstallResult.ALREADY_ADDED,
            com.example.service.QuickTileInstaller.mapRequestResultCode(android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED)
        )
        assertEquals(
            com.example.service.QuickTileInstallResult.DISMISSED,
            com.example.service.QuickTileInstaller.mapRequestResultCode(android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED)
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = com.example.security.VaultPreferences(context, com.example.security.KeystoreCryptoManager())
        prefs.setQuickTileAdded(true)
        assertTrue(prefs.vaultSummary.first().quickTileAdded)
        prefs.setQuickTileAdded(false)
        assertFalse(prefs.vaultSummary.first().quickTileAdded)
    }
}
