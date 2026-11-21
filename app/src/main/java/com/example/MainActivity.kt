package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.ScheduleComposerSheet
import com.example.ui.navigation.SandeshDestination
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.QueueScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.TemplatesScreen
import com.example.ui.theme.SandeshTheme
import com.example.ui.viewmodel.SandeshViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: SandeshViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SandeshTheme {
                SandeshAppRoot(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SandeshAppRoot(viewModel: SandeshViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val composerDraft by viewModel.composerDraft.collectAsStateWithLifecycle()
    var currentDestination by rememberSaveable { mutableStateOf(SandeshDestination.QUEUE) }
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler(enabled = currentDestination != SandeshDestination.QUEUE) {
        currentDestination = SandeshDestination.QUEUE
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermissionsAndInstances()
        viewModel.cancelLingeringWhatsAppPicker()
    }

    LaunchedEffect(Unit) {
        viewModel.uiMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = "Sandesh",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            floatingActionButton = {
                if (currentDestination == SandeshDestination.QUEUE || currentDestination == SandeshDestination.TEMPLATES) {
                    FloatingActionButton(
                        onClick = { viewModel.openNewComposer() },
                        modifier = Modifier.testTag("fab_new_schedule")
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Schedule new WhatsApp message")
                    }
                }
            },
            bottomBar = {
                if (!useNavigationRail) {
                    NavigationBar {
                        SandeshDestination.entries.forEach { dest ->
                            val selected = currentDestination == dest
                            NavigationBarItem(
                                selected = selected,
                                onClick = { currentDestination = dest },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = dest.label
                                    )
                                },
                                label = { Text(dest.label) },
                                modifier = Modifier.testTag("nav_tab_${dest.name}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail) {
                    NavigationRail {
                        SandeshDestination.entries.forEach { dest ->
                            val selected = currentDestination == dest
                            NavigationRailItem(
                                selected = selected,
                                onClick = { currentDestination = dest },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = dest.label
                                    )
                                },
                                label = { Text(dest.label) },
                                modifier = Modifier.testTag("rail_tab_${dest.name}")
                            )
                        }
                    }
                }

                when (currentDestination) {
                    SandeshDestination.QUEUE -> QueueScreen(
                        schedules = uiState.schedules,
                        permissionReport = uiState.permissionReport,
                        onOpenPermissions = { currentDestination = SandeshDestination.SETTINGS },
                        onEditSchedule = { viewModel.openEditComposer(it) },
                        onTogglePause = { viewModel.togglePauseResume(it) },
                        onSendNow = { viewModel.triggerImmediateDispatch(it) },
                        onDeleteSchedule = { viewModel.deleteSchedule(it) },
                        onCreateFirst = { viewModel.openNewComposer() },
                        modifier = Modifier.weight(1f)
                    )

                    SandeshDestination.TEMPLATES -> TemplatesScreen(
                        templates = uiState.templates,
                        onUseTemplate = { viewModel.openNewComposer(it) },
                        onSaveTemplate = { id, title, cat, body ->
                            viewModel.saveTemplate(id, title, cat, body)
                        },
                        onDeleteTemplate = { viewModel.deleteTemplate(it) },
                        modifier = Modifier.weight(1f)
                    )

                    SandeshDestination.HISTORY -> HistoryScreen(
                        logs = uiState.logs,
                        onClearHistory = { viewModel.clearHistoryLogs() },
                        modifier = Modifier.weight(1f)
                    )

                    SandeshDestination.SETTINGS -> SettingsScreen(
                        permissionReport = uiState.permissionReport,
                        oemProfile = uiState.oemProfile,
                        vaultSummary = uiState.vaultSummary,
                        instances = uiState.instances,
                        onRefreshAll = { viewModel.refreshPermissionsAndInstances() },
                        onOemShieldAckChanged = { viewModel.markOemShieldAcknowledged(it) },
                        onQuickTileInstallResult = { viewModel.onQuickTileInstallResult(it) },
                        onMarkQuickTileAdded = { viewModel.setQuickTileAdded(it) },
                        onTestJitCycle = { viewModel.testJitAutoToggle() },
                        onPauseAccessibilityNow = { viewModel.pauseAccessibilityNow() },
                        onNotifyMessage = { viewModel.notifyMessage(it) },
                        onSaveDeviceCredential = { type, secret ->
                            viewModel.saveVaultCredential(type, secret)
                        },
                        onSaveWhatsAppPin = { viewModel.saveWhatsAppLockPin(it) },
                        onSetAutoRelock = { viewModel.setAutoRelock(it) },
                        onClearVault = { viewModel.clearVault() },
                        onExportJson = { viewModel.exportBackupToUri(it) },
                        onImportJson = { viewModel.importBackupFromUri(it) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    composerDraft?.let { draft ->
        ScheduleComposerSheet(
            draft = draft,
            instances = uiState.instances,
            templates = uiState.templates,
            onUpdateDraft = { viewModel.updateDraft(it) },
            onStageMedia = { uri, type -> viewModel.stagePickedMedia(uri, type) },
            onContactPicked = { uri -> viewModel.resolvePickedContact(uri) },
            onPickFromWhatsApp = { isGroupMode -> viewModel.startWhatsAppPicker(isGroupMode) },
            onSave = { viewModel.saveComposerSchedule() },
            onDismiss = { viewModel.closeComposer() }
        )
    }
}
