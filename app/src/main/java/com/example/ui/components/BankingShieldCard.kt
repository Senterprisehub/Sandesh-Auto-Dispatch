package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.oem.PermissionReport
import com.example.service.JitAccessibilityController
import com.example.service.QuickTileInstallResult
import com.example.service.QuickTileInstaller
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalStatusColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BankingShieldCard(
    permissionReport: PermissionReport,
    isQuickTileAdded: Boolean = false,
    onRequestAddQuickTileResult: (QuickTileInstallResult) -> Unit = {},
    onMarkQuickTileAdded: (Boolean) -> Unit = {},
    onTestJitCycle: () -> Unit,
    onPauseAccessibilityNow: () -> Unit,
    onCommandCopied: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val statusColors = LocalStatusColors.current
    val adbCommand = remember(context.packageName) {
        JitAccessibilityController.buildAdbGrantCommand(context)
    }
    var showTileGuideDialog by remember { mutableStateOf(false) }

    val (pillBg, pillFg, pillText) = when {
        permissionReport.isJitAutoToggleActive && !permissionReport.isAccessibilityCurrentlyOn -> Triple(
            statusColors.successContainer,
            statusColors.onSuccessContainer,
            "JIT ARMED • IDLE OFF"
        )
        permissionReport.isAccessibilityCurrentlyOn -> Triple(
            statusColors.warningContainer,
            statusColors.onWarningContainer,
            "ACTIVE • RUNNING"
        )
        else -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            "MANUAL • QUICK TILE"
        )
    }

    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("banking_upi_shield_card")
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.AccountBalance,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text("Banking & UPI Conflict Shield", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "Sandesh Auto-Dispatch protection for GPay, PhonePe, Paytm, YONO & bank apps",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = pillBg
                ) {
                    Text(
                        text = pillText,
                        style = MaterialTheme.typography.labelMedium,
                        color = pillFg,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                if (isQuickTileAdded) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = statusColors.successContainer,
                        modifier = Modifier.testTag("quick_tile_added_badge")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "Quick Settings tile added",
                                tint = statusColors.onSuccessContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Quick Tile Added ✓",
                                style = MaterialTheme.typography.labelMedium,
                                color = statusColors.onSuccessContainer
                            )
                        }
                    }
                }
            }

            Text(
                text = "1. Package Scoping: Restricted strictly to WhatsApp, Keyguard & Cloner windows.\n" +
                    "2. Quick Settings Tile: Pin \"Sandesh Auto-Dispatch\" in Android Quick Settings to pause in 1 tap anytime.\n" +
                    "3. Zero-Footprint JIT Mode (Optional): Grant once via ADB or Shizuku so Accessibility stays OFF while idle and turns ON only during dispatch or WhatsApp pick.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = adbCommand,
                    fontFamily = JetBrainsMonoFontFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(12.dp)
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!isQuickTileAdded) {
                    Button(
                        onClick = {
                            if (QuickTileInstaller.supportsOneTapPrompt()) {
                                QuickTileInstaller.requestAddTile(context) { result ->
                                    if (result == QuickTileInstallResult.UNSUPPORTED_SDK ||
                                        result == QuickTileInstallResult.FAILED
                                    ) {
                                        showTileGuideDialog = true
                                    } else {
                                        onRequestAddQuickTileResult(result)
                                    }
                                }
                            } else {
                                showTileGuideDialog = true
                            }
                        },
                        modifier = Modifier.testTag("add_quick_tile_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DashboardCustomize,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Add to Quick Settings")
                    }
                }

                FilledTonalButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("Sandesh JIT ADB Command", adbCommand))
                        onCommandCopied("ADB grant command copied")
                    },
                    modifier = Modifier.testTag("copy_adb_jit_button")
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy ADB Command")
                }

                if (permissionReport.isJitAutoToggleActive) {
                    FilledTonalButton(
                        onClick = onTestJitCycle,
                        modifier = Modifier.testTag("test_jit_cycle_button")
                    ) {
                        Icon(Icons.Filled.PlayCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Test JIT Cycle")
                    }
                }

                if (permissionReport.isAccessibilityCurrentlyOn) {
                    OutlinedButton(
                        onClick = onPauseAccessibilityNow,
                        modifier = Modifier.testTag("pause_accessibility_now_button")
                    ) {
                        Icon(Icons.Filled.PauseCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pause Accessibility Now")
                    }
                }
            }
        }
    }

    if (showTileGuideDialog) {
        AlertDialog(
            onDismissRequest = { showTileGuideDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Filled.DashboardCustomize,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text("Add Sandesh to Quick Settings")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Your Android version or ROM uses manual Quick Settings placement. Pin the tile in 3 quick steps:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "1. Swipe down twice from the top of your screen to open Android Quick Settings.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "2. Tap the Edit (pencil) icon on the Quick Settings panel.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "3. Find and drag \"Sandesh Auto-Dispatch\" into your active Quick Settings tiles.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onMarkQuickTileAdded(true)
                        showTileGuideDialog = false
                    },
                    modifier = Modifier.testTag("confirm_quick_tile_added_button")
                ) {
                    Text("I've Added It")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showTileGuideDialog = false },
                    modifier = Modifier.testTag("dismiss_quick_tile_guide_button")
                ) {
                    Text("Close")
                }
            },
            modifier = Modifier.testTag("quick_tile_guide_dialog")
        )
    }
}
