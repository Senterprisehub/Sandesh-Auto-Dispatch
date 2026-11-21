package com.example.oem

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.service.JitAccessibilityController
import com.example.service.WhatsAppAccessibilityService

enum class PermissionCategory(val title: String) {
    CORE("Core Automation"),
    WAKE_ALERTS("Wake & Delivery Alerts"),
    OPTIONAL("Optional Helpers"),
    OEM_SHIELD("OEM Background Shield")
}

enum class PermissionActionType {
    ACCESSIBILITY_SETTINGS,
    EXACT_ALARM_SETTINGS,
    BATTERY_OPTIMIZATION,
    RUNTIME_NOTIFICATIONS,
    FULL_SCREEN_INTENT_SETTINGS,
    RUNTIME_CONTACTS,
    OEM_BACKGROUND_SETTINGS
}

data class DevicePermissionItem(
    val id: String,
    val title: String,
    val description: String,
    val category: PermissionCategory,
    val isGranted: Boolean,
    val isRequired: Boolean,
    val actionLabel: String,
    val actionType: PermissionActionType
)

data class PermissionReport(
    val items: List<DevicePermissionItem>,
    val requiredGrantedCount: Int,
    val requiredTotalCount: Int,
    val readinessPercent: Int,
    val isAccessibilityCurrentlyOn: Boolean = false,
    val isJitAutoToggleActive: Boolean = false
) {
    val isCoreReady: Boolean
        get() = requiredGrantedCount == requiredTotalCount
}

object PermissionManager {

    fun evaluateAll(context: Context, oemShieldAcknowledged: Boolean): PermissionReport {
        val accessibilityActiveNow = isAccessibilityServiceEnabled(context)
        val jitActive = JitAccessibilityController.hasWriteSecureSettings(context)
        val accessibilityReady = accessibilityActiveNow || jitActive
        val exactAlarmOk = canScheduleExactAlarms(context)
        val batteryOk = isIgnoringBatteryOptimizations(context)
        val notificationsOk = areNotificationsEnabled(context)
        val fullScreenOk = canUseFullScreenIntent(context)
        val contactsOk = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        val oemProfile = OemDetector.detectOem()

        val items = buildList {
            add(
                DevicePermissionItem(
                    id = "accessibility",
                    title = "Sandesh Auto-Dispatch Service",
                    description = if (jitActive && !accessibilityActiveNow) {
                        "JIT Auto-Toggle Armed: stays OFF while idle for Banking/UPI safety, auto-enables during dispatch."
                    } else {
                        "Required to unlock keyguard if configured, select cloned WhatsApp targets, and tap Send automatically."
                    },
                    category = PermissionCategory.CORE,
                    isGranted = accessibilityReady,
                    isRequired = true,
                    actionLabel = when {
                        accessibilityActiveNow -> "Active"
                        jitActive -> "JIT Idle"
                        else -> "Enable Service"
                    },
                    actionType = PermissionActionType.ACCESSIBILITY_SETTINGS
                )
            )
            add(
                DevicePermissionItem(
                    id = "exact_alarm",
                    title = "Exact Alarms & Reminders",
                    description = "Prevents Android Doze from delaying scheduled messages by 10-60 minutes.",
                    category = PermissionCategory.CORE,
                    isGranted = exactAlarmOk,
                    isRequired = true,
                    actionLabel = if (exactAlarmOk) "Allowed" else "Allow Exact",
                    actionType = PermissionActionType.EXACT_ALARM_SETTINGS
                )
            )
            add(
                DevicePermissionItem(
                    id = "battery",
                    title = "Unrestricted Battery Usage",
                    description = "Exempts Sandesh from aggressive battery killing overnight on Indian Android ROMs.",
                    category = PermissionCategory.CORE,
                    isGranted = batteryOk,
                    isRequired = true,
                    actionLabel = if (batteryOk) "Unrestricted" else "Exempt App",
                    actionType = PermissionActionType.BATTERY_OPTIMIZATION
                )
            )
            add(
                DevicePermissionItem(
                    id = "notifications",
                    title = "Post Notifications",
                    description = "Needed for the foreground wake bridge and delivery success/failure reports.",
                    category = PermissionCategory.WAKE_ALERTS,
                    isGranted = notificationsOk,
                    isRequired = true,
                    actionLabel = if (notificationsOk) "Allowed" else "Grant Alert",
                    actionType = PermissionActionType.RUNTIME_NOTIFICATIONS
                )
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                add(
                    DevicePermissionItem(
                        id = "full_screen",
                        title = "Full-Screen Wake Alerts",
                        description = "Allows Sandesh to wake the screen cleanly above the lock screen on Android 14+.",
                        category = PermissionCategory.WAKE_ALERTS,
                        isGranted = fullScreenOk,
                        isRequired = true,
                        actionLabel = if (fullScreenOk) "Allowed" else "Allow Wake",
                        actionType = PermissionActionType.FULL_SCREEN_INTENT_SETTINGS
                    )
                )
            }
            add(
                DevicePermissionItem(
                    id = "contacts",
                    title = "Read Contacts (Optional)",
                    description = "Lets you pick recipients directly from your phonebook when composing schedules.",
                    category = PermissionCategory.OPTIONAL,
                    isGranted = contactsOk,
                    isRequired = false,
                    actionLabel = if (contactsOk) "Granted" else "Allow Contacts",
                    actionType = PermissionActionType.RUNTIME_CONTACTS
                )
            )
            add(
                DevicePermissionItem(
                    id = "oem_shield",
                    title = "${oemProfile.brand.displayName} Autostart & Background Pop-up",
                    description = oemProfile.shieldSteps.firstOrNull()
                        ?: "Verify vendor Autostart and Background Pop-up permissions.",
                    category = PermissionCategory.OEM_SHIELD,
                    isGranted = oemShieldAcknowledged,
                    isRequired = false,
                    actionLabel = if (oemShieldAcknowledged) "Verified" else "Open OEM Settings",
                    actionType = PermissionActionType.OEM_BACKGROUND_SETTINGS
                )
            )
        }

        val requiredItems = items.filter { it.isRequired }
        val grantedRequired = requiredItems.count { it.isGranted }
        val totalWeighted = items.size.coerceAtLeast(1)
        val grantedAll = items.count { it.isGranted }
        val pct = ((grantedAll.toFloat() / totalWeighted.toFloat()) * 100f).toInt().coerceIn(0, 100)

        return PermissionReport(
            items = items,
            requiredGrantedCount = grantedRequired,
            requiredTotalCount = requiredItems.size,
            readinessPercent = pct,
            isAccessibilityCurrentlyOn = accessibilityActiveNow,
            isJitAutoToggleActive = jitActive
        )
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val expected = ComponentName(context, WhatsAppAccessibilityService::class.java)
        val enabledServices = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .orEmpty()
        if (enabledServices.any { info ->
                val svcInfo = info.resolveInfo?.serviceInfo
                svcInfo?.packageName == expected.packageName && svcInfo.name == expected.className
            }
        ) {
            return true
        }

        val flat = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return flat.contains(expected.flattenToString()) || flat.contains(expected.flattenToShortString())
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return am.canScheduleExactAlarms()
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun areNotificationsEnabled(context: Context): Boolean {
        val runtimeOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return runtimeOk && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false
        return nm.canUseFullScreenIntent()
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }

    fun openBatteryOptimizationDialog(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching { context.startActivity(intent); true }.getOrDefault(false)
        if (!launched) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }
    }

    fun openFullScreenIntentSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val launched = runCatching { context.startActivity(intent); true }.getOrDefault(false)
            if (!launched) openAppDetailsSettings(context)
        }
    }

    fun openAppDetailsSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}
