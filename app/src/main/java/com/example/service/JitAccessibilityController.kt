package com.example.service

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.dispatch.DispatchCoordinator
import com.example.dispatch.WhatsAppPickerCoordinator

object JitAccessibilityController {

    fun serviceComponent(context: Context): ComponentName =
        ComponentName(context.packageName, WhatsAppAccessibilityService::class.java.name)

    fun hasWriteSecureSettings(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED

    fun buildAdbGrantCommand(context: Context): String =
        "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    fun addComponentToEnabledList(existing: String?, component: ComponentName): String {
        val full = component.flattenToString()
        val short = component.flattenToShortString()
        val entries = existing.orEmpty()
            .split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { entry ->
                entry == full || entry == short || ComponentName.unflattenFromString(entry) == component
            }
            .toMutableList()
        entries.add(full)
        return entries.joinToString(":")
    }

    fun removeComponentFromEnabledList(existing: String?, component: ComponentName): String {
        val full = component.flattenToString()
        val short = component.flattenToShortString()
        return existing.orEmpty()
            .split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { entry ->
                entry == full || entry == short || ComponentName.unflattenFromString(entry) == component
            }
            .joinToString(":")
    }

    fun enableServiceTemporarily(context: Context): Boolean {
        if (!hasWriteSecureSettings(context)) return false
        val appContext = context.applicationContext
        val component = serviceComponent(appContext)
        return runCatching {
            val cr = appContext.contentResolver
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val updated = addComponentToEnabledList(current, component)
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            SandeshQuickTileService.requestTileRefresh(appContext)
            true
        }.getOrDefault(false)
    }

    fun disableServiceSafely(context: Context): Boolean {
        val appContext = context.applicationContext
        val selfDisabled = WhatsAppAccessibilityService.requestSelfDisable()

        val settingsRemoved = if (hasWriteSecureSettings(appContext)) {
            val component = serviceComponent(appContext)
            runCatching {
                val cr = appContext.contentResolver
                val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                val updated = removeComponentFromEnabledList(current, component)
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
                Settings.Secure.putInt(
                    cr,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    if (updated.isBlank()) 0 else 1
                )
                true
            }.getOrDefault(false)
        } else {
            false
        }

        SandeshQuickTileService.requestTileRefresh(appContext)
        return selfDisabled || settingsRemoved
    }

    fun ensureIdleOffIfJitEnabled(context: Context) {
        if (!hasWriteSecureSettings(context)) return
        if (!DispatchCoordinator.hasActiveOrQueuedWork() && WhatsAppPickerCoordinator.currentSession() == null) {
            disableServiceSafely(context)
        }
    }
}
