package com.example.oem

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

enum class IndianOemBrand(val displayName: String) {
    XIAOMI("Xiaomi / Redmi / POCO (MIUI / HyperOS)"),
    SAMSUNG("Samsung (One UI)"),
    VIVO("Vivo / iQOO (FuntouchOS / OriginOS)"),
    OPPO("Oppo (ColorOS)"),
    REALME("Realme (Realme UI)"),
    ONEPLUS("OnePlus (OxygenOS)"),
    MOTOROLA("Motorola"),
    NOTHING("Nothing OS"),
    GOOGLE("Google Pixel"),
    OTHER("Android Device")
}

data class OemProfile(
    val brand: IndianOemBrand,
    val manufacturerLabel: String,
    val shieldSteps: List<String>,
    val supportsDualAppsChooser: Boolean
)

object OemDetector {

    fun detectOem(): OemProfile {
        val mfg = Build.MANUFACTURER.orEmpty().lowercase()
        val brandRaw = Build.BRAND.orEmpty().lowercase()
        val combined = "$mfg $brandRaw"

        val brand = when {
            combined.contains("xiaomi") || combined.contains("redmi") || combined.contains("poco") -> IndianOemBrand.XIAOMI
            combined.contains("samsung") -> IndianOemBrand.SAMSUNG
            combined.contains("vivo") || combined.contains("iqoo") -> IndianOemBrand.VIVO
            combined.contains("realme") -> IndianOemBrand.REALME
            combined.contains("oneplus") -> IndianOemBrand.ONEPLUS
            combined.contains("oppo") -> IndianOemBrand.OPPO
            combined.contains("motorola") || combined.contains("moto") -> IndianOemBrand.MOTOROLA
            combined.contains("nothing") -> IndianOemBrand.NOTHING
            combined.contains("google") -> IndianOemBrand.GOOGLE
            else -> IndianOemBrand.OTHER
        }

        val steps = when (brand) {
            IndianOemBrand.XIAOMI -> listOf(
                "Enable Autostart for Sandesh in Security -> Permissions -> Autostart",
                "Allow 'Display pop-up windows while running in the background' in Other Permissions",
                "Set Battery Saver to 'No restrictions' and lock Sandesh in Recent Apps"
            )
            IndianOemBrand.VIVO -> listOf(
                "Enable 'Allow Background Power Consumption' in iManager / Battery settings",
                "Turn on 'Autostart' and 'Display on Lock Screen' in App Permissions",
                "Disable Sleep Standby Optimization in Advanced Battery settings"
            )
            IndianOemBrand.OPPO, IndianOemBrand.REALME, IndianOemBrand.ONEPLUS -> listOf(
                "Enable 'Allow Auto-launch' and 'Allow Background Activity' in App Management",
                "Disable 'Sleep Standby Optimization' under Battery -> More Settings",
                "Allow 'Display over other apps / Background pop-up' if prompted"
            )
            IndianOemBrand.SAMSUNG -> listOf(
                "Set Battery usage for Sandesh to 'Unrestricted' in App Info -> Battery",
                "Remove Sandesh from 'Sleeping apps' and 'Deep sleeping apps' in Device Care",
                "Enable 'Appear on top' for reliable wake relay over lock screen"
            )
            else -> listOf(
                "Set Battery usage to 'Unrestricted' in App Info -> Battery",
                "Keep Accessibility Service enabled and grant Exact Alarm permission",
                "Disable Adaptive Battery throttling if overnight dispatches are delayed"
            )
        }

        return OemProfile(
            brand = brand,
            manufacturerLabel = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
            shieldSteps = steps,
            supportsDualAppsChooser = brand in setOf(
                IndianOemBrand.XIAOMI,
                IndianOemBrand.SAMSUNG,
                IndianOemBrand.VIVO,
                IndianOemBrand.OPPO,
                IndianOemBrand.REALME,
                IndianOemBrand.ONEPLUS
            )
        )
    }

    fun launchOemBackgroundSettings(context: Context): Boolean {
        val candidates = listOf(
            // Xiaomi / Redmi / POCO Autostart & Other Permissions
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", context.packageName),
            // Oppo / Realme / OnePlus Startup & Power
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
            // Vivo / iQOO Background Startup
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
            // Samsung Device Care / App Battery
            Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"))
        )

        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolved = context.packageManager.resolveActivity(intent, 0)
            if (resolved != null) {
                val launched = runCatching { context.startActivity(intent); true }.getOrDefault(false)
                if (launched) return true
            }
        }

        // Universal fallback: App Details page where Battery & Permissions live
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(fallback); true }.getOrDefault(false)
    }
}
