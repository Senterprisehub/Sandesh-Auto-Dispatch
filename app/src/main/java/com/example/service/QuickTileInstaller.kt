package com.example.service

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.example.R

enum class QuickTileInstallResult {
    ADDED,
    ALREADY_ADDED,
    DISMISSED,
    UNSUPPORTED_SDK,
    FAILED
}

object QuickTileInstaller {

    fun supportsOneTapPrompt(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.TIRAMISU

    fun mapRequestResultCode(resultCode: Int): QuickTileInstallResult = when (resultCode) {
        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> QuickTileInstallResult.ADDED
        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> QuickTileInstallResult.ALREADY_ADDED
        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> QuickTileInstallResult.DISMISSED
        else -> QuickTileInstallResult.FAILED
    }

    /**
     * Triggers Android 13+ (API 33+) native 1-tap system bottom sheet to pin
     * Sandesh Auto-Dispatch into Quick Settings, or returns [QuickTileInstallResult.UNSUPPORTED_SDK]
     * on Android 12 and below so the UI can show the guided fallback dialog.
     */
    fun requestAddTile(
        context: Context,
        onResult: (QuickTileInstallResult) -> Unit
    ) {
        if (!supportsOneTapPrompt()) {
            onResult(QuickTileInstallResult.UNSUPPORTED_SDK)
            return
        }
        requestAddTileApi33(context, onResult)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestAddTileApi33(
        context: Context,
        onResult: (QuickTileInstallResult) -> Unit
    ) {
        val statusBarManager = context.getSystemService(StatusBarManager::class.java)
        if (statusBarManager == null) {
            onResult(QuickTileInstallResult.FAILED)
            return
        }

        val componentName = ComponentName(context, SandeshQuickTileService::class.java)
        val tileLabel = context.getString(R.string.accessibility_service_label)
        val tileIcon = Icon.createWithResource(context, R.drawable.ic_tile_shield)

        runCatching {
            statusBarManager.requestAddTileService(
                componentName,
                tileLabel,
                tileIcon,
                ContextCompat.getMainExecutor(context)
            ) { resultCode ->
                onResult(mapRequestResultCode(resultCode))
            }
        }.onFailure {
            onResult(QuickTileInstallResult.FAILED)
        }
    }
}
