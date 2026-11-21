package com.example.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.example.R
import com.example.SandeshApplication
import com.example.oem.PermissionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SandeshQuickTileService : TileService() {

    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onTileAdded() {
        super.onTileAdded()
        persistTileAddedState(true)
        updateTileState()
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        persistTileAddedState(false)
    }

    override fun onStartListening() {
        super.onStartListening()
        persistTileAddedState(true)
        updateTileState()
    }

    private fun persistTileAddedState(added: Boolean) {
        val prefs = (applicationContext as? SandeshApplication)?.container?.vaultPreferences ?: return
        tileScope.launch {
            prefs.setQuickTileAdded(added)
        }
    }

    override fun onClick() {
        super.onClick()
        val isOn = PermissionManager.isAccessibilityServiceEnabled(this)
        val hasJit = JitAccessibilityController.hasWriteSecureSettings(this)

        when {
            isOn -> {
                JitAccessibilityController.disableServiceSafely(this)
                updateTileState()
                Toast.makeText(
                    this,
                    "Sandesh Auto-Dispatch paused — safe for banking & UPI apps",
                    Toast.LENGTH_SHORT
                ).show()
            }

            hasJit -> {
                updateTileState()
                Toast.makeText(
                    this,
                    "Sandesh Auto-Dispatch JIT is armed (stays OFF until dispatch)",
                    Toast.LENGTH_SHORT
                ).show()
            }

            else -> {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pi = PendingIntent.getActivity(
                        this,
                        1042,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    startActivityAndCollapse(pi)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                }
            }
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isOn = PermissionManager.isAccessibilityServiceEnabled(this)
        val hasJit = JitAccessibilityController.hasWriteSecureSettings(this)

        tile.label = getString(R.string.accessibility_service_label)
        if (isOn) {
            tile.state = Tile.STATE_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "ON • Tap to pause"
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = if (hasJit) "JIT Idle (Banking Safe)" else "Paused • Tap to enable"
            }
        }
        tile.updateTile()
    }

    companion object {
        fun requestTileRefresh(context: Context) {
            runCatching {
                requestListeningState(
                    context.applicationContext,
                    ComponentName(context.applicationContext, SandeshQuickTileService::class.java)
                )
            }
        }
    }
}
