package com.example

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.AppConstants
import com.example.diagnostic.CrashReportData
import com.example.diagnostic.UniversalCrashLogger
import com.example.ui.components.ConfirmActionDialog
import com.example.ui.components.UniversalErrorHoldScreen
import com.example.ui.screen.RenderSAMainScreen
import com.example.ui.theme.RenderSATheme
import com.example.util.StoragePathResolver
import com.example.viewmodel.RenderSAUiEvent
import com.example.viewmodel.RenderSAViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: RenderSAViewModel by viewModels()
    private val autoShowCrashOverlayState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val initialPendingCrash = UniversalCrashLogger.checkAndPromotePendingCrash(this)
        if (initialPendingCrash != null) {
            autoShowCrashOverlayState.value = true
        }

        setContent {
            RenderSATheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val activeCrashReport by UniversalCrashLogger.activeCrashReport.collectAsStateWithLifecycle()
                var manualDiagnosticReport by remember { mutableStateOf<CrashReportData?>(null) }
                val autoShowCrashOverlay by autoShowCrashOverlayState

                val snackbarHostState = remember { SnackbarHostState() }
                var showStoragePermissionDialog by remember {
                    mutableStateOf(!StoragePathResolver.hasStoragePermission(this@MainActivity))
                }

                val legacyPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    viewModel.refreshHardwareAndVerification()
                }

                val manageStorageLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) {
                    viewModel.refreshHardwareAndVerification()
                }

                fun requestStorageAccess() {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            manageStorageLauncher.launch(
                                StoragePathResolver.buildManageStorageIntent(this@MainActivity)
                            )
                        } catch (_: Throwable) {
                            manageStorageLauncher.launch(
                                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            )
                        }
                    } else {
                        legacyPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            )
                        )
                    }
                }

                val rootFsPickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) {
                        viewModel.importRootFsFromUri(uri)
                    }
                }

                val gtaSaFolderPickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocumentTree()
                ) { treeUri ->
                    if (treeUri != null) {
                        viewModel.onGtaSaFolderTreeSelected(treeUri)
                    }
                }

                LaunchedEffect(Unit) {
                    viewModel.events.collect { event ->
                        when (event) {
                            is RenderSAUiEvent.ShowMessage -> {
                                snackbarHostState.showSnackbar(event.message)
                            }
                            is RenderSAUiEvent.LaunchDisplayIntent -> {
                                startActivity(event.intent)
                            }
                        }
                    }
                }

                val displayedOverlayReport = manualDiagnosticReport
                    ?: if (autoShowCrashOverlay) activeCrashReport else null

                Box(modifier = Modifier.fillMaxSize()) {
                    RenderSAMainScreen(
                        uiState = uiState,
                        snackbarHostState = snackbarHostState,
                        hasCapturedCrashLog = activeCrashReport != null,
                        capturedCrashTitle = activeCrashReport?.title.orEmpty(),
                        capturedCrashSummary = activeCrashReport?.summary.orEmpty(),
                        onOpenDiagnosticsClick = {
                            val report = activeCrashReport
                                ?: UniversalCrashLogger.generateOnDemandDiagnosticReport(this@MainActivity)
                            manualDiagnosticReport = report
                        },
                        onSelectTab = viewModel::selectTab,
                        onNicknameChange = viewModel::updateNickname,
                        onLaunchSamp = { viewModel.launchGameSession(AppConstants.MODE_SAMP) },
                        onLaunchSingleplayer = { viewModel.launchGameSession(AppConstants.MODE_SINGLEPLAYER) },
                        onAddServer = viewModel::addServer,
                        onSelectServer = viewModel::selectServer,
                        onQueryServer = viewModel::querySingleServer,
                        onDeleteServer = viewModel::removeServer,
                        onRefreshAllServers = viewModel::refreshAllServers,
                        onImportRootFsClick = {
                            rootFsPickerLauncher.launch(arrayOf("*/*"))
                        },
                        onSelectGtaSaFolderClick = {
                            if (!StoragePathResolver.hasStoragePermission(this@MainActivity)) {
                                showStoragePermissionDialog = true
                            } else {
                                gtaSaFolderPickerLauncher.launch(null)
                            }
                        },
                        onRequestStoragePermissionClick = {
                            requestStorageAccess()
                        },
                        onSelectGpuDriver = viewModel::updateGpuDriverMode,
                        onSelectDxvkVersion = viewModel::updateDxvkVersion,
                        onSelectResolution = viewModel::updateScreenResolution,
                        onSelectBox64Preset = viewModel::updateBox64Preset,
                        onToggleCpuAffinity = viewModel::updateCpuAffinityEnabled,
                        onToggleTouchControls = viewModel::updateTouchControlsEnabled,
                        onToggleFpsHud = viewModel::updateFpsHudEnabled
                    )

                    if (displayedOverlayReport != null) {
                        BackHandler {
                            manualDiagnosticReport = null
                            autoShowCrashOverlayState.value = false
                        }

                        UniversalErrorHoldScreen(
                            report = displayedOverlayReport,
                            closeActionLabel = stringResource(R.string.action_close_log_viewer),
                            onCloseOrReturn = {
                                manualDiagnosticReport = null
                                autoShowCrashOverlayState.value = false
                            },
                            onClearLog = {
                                UniversalCrashLogger.clearActiveCrashReport(this@MainActivity)
                                manualDiagnosticReport = null
                                autoShowCrashOverlayState.value = false
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                if (showStoragePermissionDialog && !uiState.hasStoragePermission && displayedOverlayReport == null) {
                    ConfirmActionDialog(
                        title = stringResource(R.string.dialog_storage_perm_title),
                        description = stringResource(R.string.dialog_storage_perm_desc),
                        confirmText = stringResource(R.string.action_open_permission_settings),
                        onDismiss = { showStoragePermissionDialog = false },
                        onConfirm = {
                            showStoragePermissionDialog = false
                            requestStorageAccess()
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshHardwareAndVerification()
        val pendingCrash = UniversalCrashLogger.checkAndPromotePendingCrash(this)
        if (pendingCrash != null) {
            autoShowCrashOverlayState.value = true
        }
    }
}
