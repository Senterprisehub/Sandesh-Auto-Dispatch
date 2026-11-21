package com.example.oem

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import com.example.data.model.WhatsAppInstance

object WhatsAppInstanceDiscovery {

    const val PKG_WHATSAPP = "com.whatsapp"
    const val PKG_WHATSAPP_BUSINESS = "com.whatsapp.w4b"

    private val candidatePackages = listOf(
        PKG_WHATSAPP,
        PKG_WHATSAPP_BUSINESS
    )

    fun discoverInstalledInstances(context: Context): List<WhatsAppInstance> {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        val primaryUser = Process.myUserHandle()
        val profiles = userManager?.userProfiles ?: listOf(primaryUser)

        val discovered = mutableListOf<WhatsAppInstance>()
        val seenKeys = mutableSetOf<String>()

        for (userHandle in profiles) {
            val serial = userManager?.getSerialNumberForUser(userHandle) ?: 0L
            val isCloneProfile = userHandle != primaryUser

            for (pkg in candidatePackages) {
                val activities = runCatching {
                    launcherApps?.getActivityList(pkg, userHandle)
                }.getOrNull().orEmpty()

                val installedInProfile = activities.isNotEmpty() || (!isCloneProfile && isPackageInstalled(context, pkg))
                if (installedInProfile) {
                    val isBusiness = pkg == PKG_WHATSAPP_BUSINESS
                    val baseName = if (isBusiness) "WhatsApp Business" else "WhatsApp"
                    val label = if (isCloneProfile) "$baseName (Clone)" else baseName
                    val key = "$pkg:$serial"
                    if (seenKeys.add(key)) {
                        discovered += WhatsAppInstance(
                            uniqueKey = key,
                            packageName = pkg,
                            userSerialNumber = serial,
                            displayLabel = label,
                            isBusiness = isBusiness,
                            isClone = isCloneProfile
                        )
                    }
                }
            }
        }

        if (discovered.isEmpty()) {
            // Provide standard selectable default even on emulators without WhatsApp installed
            discovered += WhatsAppInstance(
                uniqueKey = "$PKG_WHATSAPP:0",
                packageName = PKG_WHATSAPP,
                userSerialNumber = 0L,
                displayLabel = "WhatsApp",
                isBusiness = false,
                isClone = false
            )
        }
        return discovered
    }

    fun resolveUserHandle(context: Context, userSerialNumber: Long): UserHandle {
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        return userManager?.getUserForSerialNumber(userSerialNumber) ?: Process.myUserHandle()
    }

    private fun isPackageInstalled(context: Context, pkg: String): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            true
        }.getOrElse {
            @Suppress("DEPRECATION")
            runCatching {
                context.packageManager.getPackageInfo(pkg, 0)
                true
            }.getOrDefault(false)
        }
    }
}
