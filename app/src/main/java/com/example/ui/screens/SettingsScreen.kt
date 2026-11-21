package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhonelinkSetup
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.model.LockCredentialType
import com.example.data.model.WhatsAppInstance
import com.example.oem.OemDetector
import com.example.oem.OemProfile
import com.example.oem.PermissionActionType
import com.example.oem.PermissionCategory
import com.example.oem.PermissionManager
import com.example.oem.PermissionReport
import com.example.security.VaultUiSummary
import com.example.ui.components.BankingShieldCard
import com.example.ui.components.PatternLockPad
import com.example.ui.theme.LocalStatusColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    permissionReport: PermissionReport,
    oemProfile: OemProfile,
    vaultSummary: VaultUiSummary,
    instances: List<WhatsAppInstance>,
    onRefreshAll: () -> Unit,
    onOemShieldAckChanged: (Boolean) -> Unit,
    onQuickTileInstallResult: (com.example.service.QuickTileInstallResult) -> Unit = {},
    onMarkQuickTileAdded: (Boolean) -> Unit = {},
    onTestJitCycle: () -> Unit,
    onPauseAccessibilityNow: () -> Unit,
    onNotifyMessage: (String) -> Unit,
    onSaveDeviceCredential: (LockCredentialType, String) -> Unit,
    onSaveWhatsAppPin: (String) -> Unit,
    onSetAutoRelock: (Boolean) -> Unit,
    onClearVault: () -> Unit,
    onExportJson: (android.net.Uri) -> Unit,
    onImportJson: (android.net.Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val statusColors = LocalStatusColors.current

    var selectedLockType by remember(vaultSummary.credentialType) {
        mutableStateOf(vaultSummary.credentialType)
    }
    var pinOrPasswordInput by remember { mutableStateOf("") }
    var waLockPinInput by remember { mutableStateOf("") }

    val notificationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { onRefreshAll() }

    val contactsPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { onRefreshAll() }

    val exportDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) onExportJson(uri)
    }

    val importDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onImportJson(uri)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. LIVE PERMISSION MANAGER
        item {
            ElevatedCard(
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()
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
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Filled.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text("Permission Manager", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "${permissionReport.readinessPercent}% ready on ${oemProfile.manufacturerLabel}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(
                            onClick = onRefreshAll,
                            modifier = Modifier.testTag("refresh_permissions_button")
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh permission status")
                        }
                    }

                    PermissionCategory.entries.forEach { category ->
                        val catItems = permissionReport.items.filter { it.category == category }
                        if (catItems.isNotEmpty()) {
                            Text(
                                text = category.title,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            catItems.forEach { perm ->
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("permission_row_${perm.id}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (perm.isGranted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                                            contentDescription = if (perm.isGranted) "Granted" else "Action needed",
                                            tint = if (perm.isGranted) statusColors.onSuccessContainer else MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(perm.title, style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                perm.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        FilledTonalButton(
                                            onClick = {
                                                when (perm.actionType) {
                                                    PermissionActionType.ACCESSIBILITY_SETTINGS ->
                                                        PermissionManager.openAccessibilitySettings(context)
                                                    PermissionActionType.EXACT_ALARM_SETTINGS ->
                                                        PermissionManager.openExactAlarmSettings(context)
                                                    PermissionActionType.BATTERY_OPTIMIZATION ->
                                                        PermissionManager.openBatteryOptimizationDialog(context)
                                                    PermissionActionType.RUNTIME_NOTIFICATIONS -> {
                                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                        } else {
                                                            PermissionManager.openAppDetailsSettings(context)
                                                        }
                                                    }
                                                    PermissionActionType.FULL_SCREEN_INTENT_SETTINGS ->
                                                        PermissionManager.openFullScreenIntentSettings(context)
                                                    PermissionActionType.RUNTIME_CONTACTS ->
                                                        contactsPermLauncher.launch(Manifest.permission.READ_CONTACTS)
                                                    PermissionActionType.OEM_BACKGROUND_SETTINGS ->
                                                        OemDetector.launchOemBackgroundSettings(context)
                                                }
                                            },
                                            modifier = Modifier.testTag("permission_action_${perm.id}")
                                        ) {
                                            Text(perm.actionLabel)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider()
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Android 13+ Sideloaded Accessibility Tip",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = "If Accessibility toggle says 'Restricted setting', open App Info -> tap the 3-dot menu (top-right) -> 'Allow restricted settings', then return and enable Sandesh.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            OutlinedButton(
                                onClick = { PermissionManager.openAppDetailsSettings(context) },
                                modifier = Modifier.testTag("open_app_info_restricted_button")
                            ) {
                                Text("Open App Info")
                            }
                        }
                    }

                    HorizontalDivider()
                    Text(
                        text = "${oemProfile.brand.displayName} Checklist",
                        style = MaterialTheme.typography.titleSmall
                    )
                    oemProfile.shieldSteps.forEachIndexed { idx, step ->
                        Text(
                            text = "${idx + 1}. $step",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = vaultSummary.oemShieldAcknowledged,
                            onCheckedChange = onOemShieldAckChanged,
                            modifier = Modifier.testTag("oem_shield_checkbox")
                        )
                        Text(
                            text = "I have enabled Autostart & Unrestricted Battery in OEM settings",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        // 1B. BANKING & UPI CONFLICT SHIELD (JIT + QUICK TILE + PACKAGE SCOPING)
        item {
            BankingShieldCard(
                permissionReport = permissionReport,
                isQuickTileAdded = vaultSummary.quickTileAdded,
                onRequestAddQuickTileResult = onQuickTileInstallResult,
                onMarkQuickTileAdded = onMarkQuickTileAdded,
                onTestJitCycle = onTestJitCycle,
                onPauseAccessibilityNow = onPauseAccessibilityNow,
                onCommandCopied = onNotifyMessage,
                modifier = Modifier.widthIn(max = 680.dp)
            )
        }

        // 2. KEYSTORE-ENCRYPTED LOCKSCREEN VAULT
        item {
            ElevatedCard(
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text("Encrypted Lock-Screen Vault", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "AES-256-GCM hardware Keystore encryption for unattended overnight unlock",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        LockCredentialType.entries.forEach { type ->
                            FilterChip(
                                selected = selectedLockType == type,
                                onClick = {
                                    selectedLockType = type
                                    pinOrPasswordInput = ""
                                    if (type == LockCredentialType.NONE) {
                                        onSaveDeviceCredential(LockCredentialType.NONE, "")
                                    }
                                },
                                label = { Text(type.displayLabel) },
                                modifier = Modifier.testTag("lock_type_chip_${type.name}")
                            )
                        }
                    }

                    when (selectedLockType) {
                        LockCredentialType.PIN, LockCredentialType.PASSWORD -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = pinOrPasswordInput,
                                    onValueChange = { pinOrPasswordInput = it },
                                    label = {
                                        Text(
                                            if (selectedLockType == LockCredentialType.PIN) "Device PIN"
                                            else "Device Password"
                                        )
                                    },
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = if (selectedLockType == LockCredentialType.PIN) {
                                            KeyboardType.NumberPassword
                                        } else {
                                            KeyboardType.Password
                                        }
                                    ),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f).testTag("device_secret_input")
                                )
                                Button(
                                    onClick = {
                                        if (pinOrPasswordInput.isNotBlank()) {
                                            onSaveDeviceCredential(selectedLockType, pinOrPasswordInput)
                                            pinOrPasswordInput = ""
                                        }
                                    },
                                    modifier = Modifier.testTag("save_device_secret_button")
                                ) {
                                    Text("Encrypt")
                                }
                            }
                        }

                        LockCredentialType.PATTERN -> {
                            PatternLockPad(
                                onSavePattern = { patternSeq ->
                                    onSaveDeviceCredential(LockCredentialType.PATTERN, patternSeq)
                                }
                            )
                        }

                        LockCredentialType.NONE -> {
                            Text(
                                "Swipe-only unlock active. No device PIN or Pattern is stored.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (vaultSummary.hasDeviceCredentialSaved) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Filled.Security,
                                contentDescription = null,
                                tint = statusColors.onSuccessContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                "Encrypted ${vaultSummary.credentialType.displayLabel} active in Keystore",
                                style = MaterialTheme.typography.labelMedium,
                                color = statusColors.onSuccessContainer
                            )
                        }
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = waLockPinInput,
                            onValueChange = { waLockPinInput = it },
                            label = {
                                Text(
                                    if (vaultSummary.hasWhatsAppLockPinSaved) {
                                        "WhatsApp App Lock PIN (Saved)"
                                    } else {
                                        "WhatsApp App Lock PIN (Optional)"
                                    }
                                )
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("wa_pin_input")
                        )
                        FilledTonalButton(
                            onClick = {
                                onSaveWhatsAppPin(waLockPinInput)
                                waLockPinInput = ""
                            },
                            modifier = Modifier.testTag("save_wa_pin_button")
                        ) {
                            Text("Save")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Re-lock screen after sending", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Automatically locks device if it was locked prior to dispatch",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = vaultSummary.autoRelockAfterSend,
                            onCheckedChange = onSetAutoRelock,
                            modifier = Modifier.testTag("auto_relock_switch")
                        )
                    }

                    if (vaultSummary.hasDeviceCredentialSaved || vaultSummary.hasWhatsAppLockPinSaved) {
                        OutlinedButton(
                            onClick = onClearVault,
                            modifier = Modifier.testTag("wipe_vault_button")
                        ) {
                            Icon(Icons.Filled.DeleteForever, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Wipe Encrypted Credentials")
                        }
                    }
                }
            }
        }

        // 3. DISCOVERED WHATSAPP & CLONED APPS
        item {
            ElevatedCard(
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.PhonelinkSetup, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("Detected WhatsApp & Clones", style = MaterialTheme.typography.titleLarge)
                        }
                        IconButton(
                            onClick = onRefreshAll,
                            modifier = Modifier.testTag("refresh_clones_button")
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Rescan installed clones")
                        }
                    }
                    instances.forEach { inst ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(inst.displayLabel, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${inst.packageName} • Profile #${inst.userSerialNumber}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (inst.isClone) {
                                        MaterialTheme.colorScheme.tertiaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.primaryContainer
                                    }
                                ) {
                                    Text(
                                        text = if (inst.isClone) "CLONED" else "PRIMARY",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. OFFLINE JSON BACKUP & RESTORE
        item {
            ElevatedCard(
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Offline JSON Backup & Restore", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Streaming low-memory backup of schedules & templates (without lock credentials). Audit logs >90 days and failed media >30 days auto-prune cleanly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                exportDocLauncher.launch("sandesh_backup_${System.currentTimeMillis()}.json")
                            },
                            modifier = Modifier.weight(1f).testTag("export_json_button")
                        ) {
                            Icon(Icons.Filled.Backup, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Export JSON")
                        }
                        OutlinedButton(
                            onClick = {
                                importDocLauncher.launch(arrayOf("application/json", "text/plain"))
                            },
                            modifier = Modifier.weight(1f).testTag("import_json_button")
                        ) {
                            Icon(Icons.Filled.Restore, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Restore JSON")
                        }
                    }
                }
            }
        }
    }
}
