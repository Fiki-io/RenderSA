package com.example.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.AppConstants
import com.example.data.SavedSampServer
import com.example.ui.components.AddServerDialog
import com.example.ui.components.ConfirmActionDialog
import com.example.ui.components.GroupDivider
import com.example.ui.components.LayeredGroupCard
import com.example.ui.components.PrimaryActionButton
import com.example.ui.components.SecondaryActionButton
import com.example.ui.components.SectionHeader
import com.example.ui.components.SelectionBottomSheet
import com.example.ui.components.SelectionOption
import com.example.ui.components.SettingsNavigationRow
import com.example.ui.components.SettingsToggleRow
import com.example.ui.components.StatusPillItem
import com.example.ui.theme.RenderSAColors
import com.example.ui.theme.RenderSADimens
import com.example.ui.theme.RenderSAMotion
import com.example.ui.theme.RenderSAShapes
import com.example.util.StoragePathResolver
import com.example.viewmodel.RenderSAUiState
import com.example.viewmodel.ServerItemUiState

private enum class ActiveSheet {
    NONE,
    GPU_DRIVER,
    DXVK_VERSION,
    RESOLUTION,
    BOX64_PRESET
}

@Composable
fun RenderSAMainScreen(
    uiState: RenderSAUiState,
    snackbarHostState: SnackbarHostState,
    hasCapturedCrashLog: Boolean = false,
    capturedCrashTitle: String = "",
    capturedCrashSummary: String = "",
    onOpenDiagnosticsClick: () -> Unit = {},
    onSelectTab: (Int) -> Unit,
    onNicknameChange: (String) -> Unit,
    onLaunchSamp: () -> Unit,
    onLaunchSingleplayer: () -> Unit,
    onAddServer: (address: String, password: String) -> Boolean,
    onSelectServer: (String) -> Unit,
    onQueryServer: (SavedSampServer) -> Unit,
    onDeleteServer: (String) -> Unit,
    onRefreshAllServers: () -> Unit,
    onImportRootFsClick: () -> Unit,
    onSelectGtaSaFolderClick: () -> Unit,
    onRequestStoragePermissionClick: () -> Unit,
    onSelectGpuDriver: (String) -> Unit,
    onSelectDxvkVersion: (String) -> Unit,
    onSelectResolution: (String) -> Unit,
    onSelectBox64Preset: (String) -> Unit,
    onToggleCpuAffinity: (Boolean) -> Unit,
    onToggleTouchControls: (Boolean) -> Unit,
    onToggleFpsHud: (Boolean) -> Unit
) {
    var showAddServerDialog by remember { mutableStateOf(false) }
    var serverPendingDeletion by remember { mutableStateOf<String?>(null) }
    var activeSheet by remember { mutableStateOf(ActiveSheet.NONE) }

    if (uiState.selectedTabIndex != 0) {
        BackHandler {
            onSelectTab(0)
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(RenderSAColors.ScreenBackgroundBrush),
        containerColor = RenderSAColors.BackgroundBase,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(RenderSAColors.ScreenBackgroundBrush)
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 720.dp)
                    .padding(horizontal = RenderSADimens.ScreenHorizontalPadding)
            ) {
                TopHeaderAndStatusSection(
                    uiState = uiState,
                    onOpenDiagnosticsClick = onOpenDiagnosticsClick
                )

                if (hasCapturedCrashLog) {
                    CapturedCrashAlertCard(
                        title = capturedCrashTitle.ifBlank {
                            stringResource(R.string.crash_banner_detected_title)
                        },
                        summary = capturedCrashSummary.ifBlank {
                            stringResource(R.string.crash_banner_detected_desc)
                        },
                        onViewAndCopyClick = onOpenDiagnosticsClick
                    )
                }

                TopHorizontalTabBar(
                    selectedTabIndex = uiState.selectedTabIndex,
                    onSelectTab = onSelectTab
                )

                AnimatedVisibility(
                    visible = uiState.isExtractingRootFs || uiState.isPreparingSession,
                    enter = fadeIn(RenderSAMotion.standardTween()),
                    exit = fadeOut(RenderSAMotion.fastTween())
                ) {
                    ActiveOperationProgressCard(uiState = uiState)
                }

                AnimatedContent(
                    targetState = uiState.selectedTabIndex,
                    transitionSpec = {
                        fadeIn(RenderSAMotion.standardTween()) togetherWith
                            fadeOut(RenderSAMotion.fastTween())
                    },
                    label = "main_tab_switch",
                    modifier = Modifier.weight(1f)
                ) { tabIndex ->
                    if (tabIndex == 0) {
                        LauncherAndSampTabContent(
                            uiState = uiState,
                            onNicknameChange = onNicknameChange,
                            onLaunchSamp = onLaunchSamp,
                            onLaunchSingleplayer = onLaunchSingleplayer,
                            onOpenAddServerDialog = { showAddServerDialog = true },
                            onRefreshAllServers = onRefreshAllServers,
                            onSelectServer = onSelectServer,
                            onQueryServer = onQueryServer,
                            onRequestDeleteServer = { serverPendingDeletion = it }
                        )
                    } else {
                        SetupAndEngineConfigTabContent(
                            uiState = uiState,
                            onImportRootFsClick = onImportRootFsClick,
                            onSelectGtaSaFolderClick = onSelectGtaSaFolderClick,
                            onRequestStoragePermissionClick = onRequestStoragePermissionClick,
                            onOpenGpuDriverSheet = { activeSheet = ActiveSheet.GPU_DRIVER },
                            onOpenDxvkSheet = { activeSheet = ActiveSheet.DXVK_VERSION },
                            onOpenResolutionSheet = { activeSheet = ActiveSheet.RESOLUTION },
                            onOpenBox64Sheet = { activeSheet = ActiveSheet.BOX64_PRESET },
                            onToggleCpuAffinity = onToggleCpuAffinity,
                            onToggleTouchControls = onToggleTouchControls,
                            onToggleFpsHud = onToggleFpsHud
                        )
                    }
                }
            }
        }
    }

    if (showAddServerDialog) {
        AddServerDialog(
            onDismiss = { showAddServerDialog = false },
            onConfirm = { addr, pass ->
                val ok = onAddServer(addr, pass)
                if (ok) showAddServerDialog = false
            }
        )
    }

    val pendingDeleteAddr = serverPendingDeletion
    if (pendingDeleteAddr != null) {
        ConfirmActionDialog(
            title = stringResource(R.string.dialog_delete_server_title),
            description = stringResource(R.string.dialog_delete_server_desc, pendingDeleteAddr),
            confirmText = stringResource(R.string.action_confirm_delete),
            onDismiss = { serverPendingDeletion = null },
            onConfirm = {
                onDeleteServer(pendingDeleteAddr)
                serverPendingDeletion = null
            }
        )
    }

    when (activeSheet) {
        ActiveSheet.GPU_DRIVER -> {
            val effectiveUpper = uiState.resolvedGpuDriver.effectiveDriver.uppercase()
            val options = listOf(
                SelectionOption(
                    id = AppConstants.DRIVER_AUTO,
                    title = stringResource(R.string.driver_auto, effectiveUpper)
                ),
                SelectionOption(
                    id = AppConstants.DRIVER_VORTEK,
                    title = stringResource(R.string.driver_vortek)
                ),
                SelectionOption(
                    id = AppConstants.DRIVER_TURNIP,
                    title = stringResource(R.string.driver_turnip)
                ),
                SelectionOption(
                    id = AppConstants.DRIVER_ZINK,
                    title = stringResource(R.string.driver_zink)
                )
            )
            SelectionBottomSheet(
                title = stringResource(R.string.row_gpu_driver_title),
                options = options,
                selectedId = uiState.preferences.gpuDriverMode,
                onSelect = onSelectGpuDriver,
                onDismiss = { activeSheet = ActiveSheet.NONE }
            )
        }

        ActiveSheet.DXVK_VERSION -> {
            val options = listOf(
                SelectionOption(
                    id = AppConstants.DXVK_1_10_3,
                    title = stringResource(R.string.dxvk_1_10_3)
                ),
                SelectionOption(
                    id = AppConstants.DXVK_2_4_1,
                    title = stringResource(R.string.dxvk_2_4_1)
                )
            )
            SelectionBottomSheet(
                title = stringResource(R.string.row_dxvk_title),
                options = options,
                selectedId = uiState.preferences.dxvkVersion,
                onSelect = onSelectDxvkVersion,
                onDismiss = { activeSheet = ActiveSheet.NONE }
            )
        }

        ActiveSheet.RESOLUTION -> {
            val options = listOf(
                SelectionOption(AppConstants.RES_854_480, stringResource(R.string.res_854x480)),
                SelectionOption(AppConstants.RES_960_540, stringResource(R.string.res_960x540)),
                SelectionOption(AppConstants.RES_1280_720, stringResource(R.string.res_1280x720)),
                SelectionOption(AppConstants.RES_1600_900, stringResource(R.string.res_1600x900)),
                SelectionOption(AppConstants.RES_1920_1080, stringResource(R.string.res_1920x1080))
            )
            SelectionBottomSheet(
                title = stringResource(R.string.row_resolution_title),
                options = options,
                selectedId = uiState.preferences.screenResolution,
                onSelect = onSelectResolution,
                onDismiss = { activeSheet = ActiveSheet.NONE }
            )
        }

        ActiveSheet.BOX64_PRESET -> {
            val options = listOf(
                SelectionOption(
                    id = AppConstants.BOX64_PERFORMANCE,
                    title = stringResource(R.string.box64_performance)
                ),
                SelectionOption(
                    id = AppConstants.BOX64_BALANCED,
                    title = stringResource(R.string.box64_balanced)
                ),
                SelectionOption(
                    id = AppConstants.BOX64_STABILITY,
                    title = stringResource(R.string.box64_stability)
                )
            )
            SelectionBottomSheet(
                title = stringResource(R.string.row_box64_preset_title),
                options = options,
                selectedId = uiState.preferences.box64Preset,
                onSelect = onSelectBox64Preset,
                onDismiss = { activeSheet = ActiveSheet.NONE }
            )
        }

        ActiveSheet.NONE -> Unit
    }
}

@Composable
private fun TopHeaderAndStatusSection(
    uiState: RenderSAUiState,
    onOpenDiagnosticsClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = RenderSADimens.SpaceS, bottom = RenderSADimens.SpaceXS)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    color = RenderSAColors.TextPrimary
                )
                Text(
                    text = stringResource(R.string.header_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = RenderSAColors.TextSecondary
                )
            }

            Box(
                modifier = Modifier
                    .testTag("btn_open_diagnostics")
                    .heightIn(min = 38.dp)
                    .clip(RenderSAShapes.Tag)
                    .background(RenderSAColors.SurfaceInteractive)
                    .border(
                        RenderSADimens.BorderStrokeWidth,
                        RenderSAColors.BorderInteractive,
                        RenderSAShapes.Tag
                    )
                    .clickable(onClick = onOpenDiagnosticsClick)
                    .padding(
                        horizontal = RenderSADimens.SpaceM,
                        vertical = RenderSADimens.SpaceXS
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.action_open_diagnostics),
                    style = MaterialTheme.typography.labelMedium,
                    color = RenderSAColors.TextPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(RenderSADimens.SpaceS))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RenderSADimens.SpaceXS)
        ) {
            val rootFsStatusText = if (uiState.isExtractingRootFs) {
                stringResource(R.string.status_extracting, uiState.extractionProgressPercent)
            } else if (uiState.rootFsVerification.isInstalled) {
                stringResource(R.string.status_ready)
            } else {
                stringResource(R.string.status_missing)
            }

            StatusPillItem(
                label = stringResource(R.string.status_rootfs_label),
                valueText = rootFsStatusText,
                isReady = uiState.rootFsVerification.isInstalled,
                modifier = Modifier.weight(1f)
            )

            StatusPillItem(
                label = stringResource(R.string.status_gtasa_label),
                valueText = if (uiState.gtaSaVerification.isReadyForSingleplayer) {
                    stringResource(R.string.status_ready)
                } else {
                    stringResource(R.string.status_missing)
                },
                isReady = uiState.gtaSaVerification.isReadyForSingleplayer,
                modifier = Modifier.weight(1f)
            )

            StatusPillItem(
                label = stringResource(R.string.status_samp_label),
                valueText = if (uiState.gtaSaVerification.isReadyForSamp) {
                    stringResource(R.string.status_ready)
                } else {
                    stringResource(R.string.status_missing)
                },
                isReady = uiState.gtaSaVerification.isReadyForSamp,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun CapturedCrashAlertCard(
    title: String,
    summary: String,
    onViewAndCopyClick: () -> Unit
) {
    LayeredGroupCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = RenderSADimens.SpaceXXS)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(RenderSADimens.SpaceM),
            verticalArrangement = Arrangement.spacedBy(RenderSADimens.SpaceXS)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = RenderSAColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = RenderSAColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            PrimaryActionButton(
                text = stringResource(R.string.action_view_crash_log),
                onClick = onViewAndCopyClick,
                modifier = Modifier.fillMaxWidth(),
                tag = "btn_banner_view_crash_log"
            )
        }
    }
}

@Composable
private fun TopHorizontalTabBar(
    selectedTabIndex: Int,
    onSelectTab: (Int) -> Unit
) {
    val tabs = listOf(
        stringResource(R.string.tab_launcher),
        stringResource(R.string.tab_engine_config)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = RenderSADimens.SpaceXS),
        horizontalArrangement = Arrangement.spacedBy(RenderSADimens.SpaceL)
    ) {
        tabs.forEachIndexed { index, label ->
            val isSelected = selectedTabIndex == index
            Column(
                modifier = Modifier
                    .testTag("tab_button_$index")
                    .heightIn(min = RenderSADimens.TouchTargetMin)
                    .clickable { onSelectTab(index) }
                    .padding(vertical = RenderSADimens.SpaceXS),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isSelected) RenderSAColors.TextPrimary else RenderSAColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(RenderSADimens.SpaceXXS))
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(2.dp)
                        .background(
                            if (isSelected) RenderSAColors.AccentPrimary else RenderSAColors.BackgroundBase
                        )
                )
            }
        }
    }
}

@Composable
private fun ActiveOperationProgressCard(uiState: RenderSAUiState) {
    val animatedProgress by animateFloatAsState(
        targetValue = (uiState.extractionProgressPercent / 100f).coerceIn(0f, 1f),
        animationSpec = RenderSAMotion.standardTween(),
        label = "rootfs_extract_progress"
    )

    LayeredGroupCard(
        modifier = Modifier.padding(vertical = RenderSADimens.SpaceXS)
    ) {
        Column(modifier = Modifier.padding(RenderSADimens.SpaceM)) {
            Text(
                text = uiState.statusBannerMessage.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                color = RenderSAColors.TextPrimary
            )
            Spacer(modifier = Modifier.height(RenderSADimens.SpaceXS))
            if (uiState.isExtractingRootFs) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth(),
                    color = RenderSAColors.AccentPrimary,
                    trackColor = RenderSAColors.SurfaceInteractive
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = RenderSAColors.AccentPrimary,
                    trackColor = RenderSAColors.SurfaceInteractive
                )
            }
        }
    }
}

@Composable
private fun LauncherAndSampTabContent(
    uiState: RenderSAUiState,
    onNicknameChange: (String) -> Unit,
    onLaunchSamp: () -> Unit,
    onLaunchSingleplayer: () -> Unit,
    onOpenAddServerDialog: () -> Unit,
    onRefreshAllServers: () -> Unit,
    onSelectServer: (String) -> Unit,
    onQueryServer: (SavedSampServer) -> Unit,
    onRequestDeleteServer: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = RenderSADimens.SpaceXL)
    ) {
        item {
            SectionHeader(title = stringResource(R.string.section_identity))
            LayeredGroupCard {
                Column(modifier = Modifier.padding(RenderSADimens.SpaceM)) {
                    Text(
                        text = stringResource(R.string.label_nickname),
                        style = MaterialTheme.typography.labelLarge,
                        color = RenderSAColors.TextPrimary
                    )
                    Spacer(modifier = Modifier.height(RenderSADimens.SpaceXS))
                    OutlinedTextField(
                        value = uiState.preferences.nickname,
                        onValueChange = onNicknameChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("nickname_input"),
                        singleLine = true,
                        placeholder = {
                            Text(
                                text = stringResource(R.string.hint_nickname),
                                color = RenderSAColors.TextMuted
                            )
                        },
                        shape = RenderSAShapes.Input,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = RenderSAColors.TextPrimary,
                            unfocusedTextColor = RenderSAColors.TextPrimary,
                            focusedBorderColor = RenderSAColors.AccentPrimary,
                            unfocusedBorderColor = RenderSAColors.BorderInteractive,
                            focusedContainerColor = RenderSAColors.SurfaceCard,
                            unfocusedContainerColor = RenderSAColors.SurfaceCard
                        )
                    )
                    Spacer(modifier = Modifier.height(RenderSADimens.SpaceXS))
                    Text(
                        text = stringResource(R.string.desc_nickname),
                        style = MaterialTheme.typography.bodyMedium,
                        color = RenderSAColors.TextSecondary
                    )
                }
            }
        }

        item {
            SectionHeader(title = stringResource(R.string.section_launch_actions))
            LayeredGroupCard {
                Column(modifier = Modifier.padding(RenderSADimens.SpaceM)) {
                    val selectedAddr = uiState.preferences.selectedServerAddress
                    Text(
                        text = if (selectedAddr.isNotBlank()) {
                            stringResource(R.string.desc_launch_samp_target, selectedAddr)
                        } else {
                            stringResource(R.string.desc_launch_samp_no_target)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = RenderSAColors.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(RenderSADimens.SpaceS))
                    PrimaryActionButton(
                        text = stringResource(R.string.action_launch_samp),
                        onClick = onLaunchSamp,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isPreparingSession && !uiState.isExtractingRootFs,
                        icon = Icons.Outlined.PlayArrow,
                        tag = "launch_samp_button"
                    )
                    Spacer(modifier = Modifier.height(RenderSADimens.SpaceS))
                    SecondaryActionButton(
                        text = stringResource(R.string.action_launch_gtasa),
                        onClick = onLaunchSingleplayer,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isPreparingSession && !uiState.isExtractingRootFs,
                        icon = Icons.Outlined.PlayArrow,
                        tag = "launch_gtasa_button"
                    )
                }
            }
        }

        item {
            SectionHeader(
                title = stringResource(R.string.section_servers),
                trailingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(RenderSADimens.SpaceXS)
                    ) {
                        if (uiState.serverItems.isNotEmpty()) {
                            SecondaryActionButton(
                                text = stringResource(R.string.action_refresh_servers),
                                onClick = onRefreshAllServers,
                                icon = Icons.Outlined.Refresh,
                                tag = "refresh_servers_button"
                            )
                        }
                        SecondaryActionButton(
                            text = stringResource(R.string.action_add_server),
                            onClick = onOpenAddServerDialog,
                            icon = Icons.Outlined.Add,
                            tag = "add_server_button"
                        )
                    }
                }
            )
        }

        if (uiState.serverItems.isEmpty()) {
            item {
                LayeredGroupCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(RenderSADimens.SpaceL)
                    ) {
                        Text(
                            text = stringResource(R.string.empty_servers_text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = RenderSAColors.TextSecondary
                        )
                    }
                }
            }
        } else {
            items(
                items = uiState.serverItems,
                key = { it.server.address }
            ) { item ->
                val isSelected = item.server.address == uiState.preferences.selectedServerAddress
                SampServerRowCard(
                    item = item,
                    isSelected = isSelected,
                    onSelect = { onSelectServer(item.server.address) },
                    onQuery = { onQueryServer(item.server) },
                    onDelete = { onRequestDeleteServer(item.server.address) },
                    modifier = Modifier.padding(bottom = RenderSADimens.SpaceS)
                )
            }
        }
    }
}

@Composable
private fun SampServerRowCard(
    item: ServerItemUiState,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onQuery: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isSelected) RenderSAColors.AccentBorder else RenderSAColors.BorderSubtle
    val bgColor = if (isSelected) RenderSAColors.SurfaceCardElevated else RenderSAColors.SurfaceCard

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RenderSAShapes.Card)
            .background(bgColor)
            .border(RenderSADimens.BorderStrokeWidth, borderColor, RenderSAShapes.Card)
            .clickable(onClick = onSelect)
            .padding(RenderSADimens.SpaceM)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val displayTitle = item.queryResult?.hostname?.takeIf { it.isNotBlank() }
                    ?: item.server.address
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = RenderSAColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(RenderSADimens.SpaceXXS))
                Text(
                    text = item.server.address,
                    style = MaterialTheme.typography.labelMedium,
                    color = RenderSAColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .clip(RenderSAShapes.Tag)
                        .background(RenderSAColors.AccentSubtleBg)
                        .border(
                            RenderSADimens.BorderStrokeWidth,
                            RenderSAColors.AccentBorder,
                            RenderSAShapes.Tag
                        )
                        .padding(
                            horizontal = RenderSADimens.SpaceS,
                            vertical = RenderSADimens.SpaceXXS
                        )
                ) {
                    Text(
                        text = stringResource(R.string.server_selected_tag),
                        style = MaterialTheme.typography.labelMedium,
                        color = RenderSAColors.TextPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(RenderSADimens.SpaceS))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val query = item.queryResult
                if (item.isQuerying) {
                    Text(
                        text = stringResource(R.string.server_status_querying),
                        style = MaterialTheme.typography.bodyMedium,
                        color = RenderSAColors.TextSecondary
                    )
                } else if (query != null && query.online) {
                    val playersStr = stringResource(
                        R.string.server_status_online,
                        query.players,
                        query.maxPlayers
                    )
                    val pingStr = stringResource(R.string.server_ping_ms, query.pingMs)
                    Text(
                        text = "$playersStr  |  $pingStr",
                        style = MaterialTheme.typography.bodyMedium,
                        color = RenderSAColors.TextPrimary
                    )
                    if (query.gamemode.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.server_gamemode_format, query.gamemode),
                            style = MaterialTheme.typography.labelMedium,
                            color = RenderSAColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.server_status_offline),
                        style = MaterialTheme.typography.bodyMedium,
                        color = RenderSAColors.TextSecondary
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onQuery,
                    modifier = Modifier.size(RenderSADimens.TouchTargetMin)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = stringResource(R.string.cd_query_server),
                        tint = RenderSAColors.TextSecondary
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(RenderSADimens.TouchTargetMin)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.cd_delete_server),
                        tint = RenderSAColors.TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupAndEngineConfigTabContent(
    uiState: RenderSAUiState,
    onImportRootFsClick: () -> Unit,
    onSelectGtaSaFolderClick: () -> Unit,
    onRequestStoragePermissionClick: () -> Unit,
    onOpenGpuDriverSheet: () -> Unit,
    onOpenDxvkSheet: () -> Unit,
    onOpenResolutionSheet: () -> Unit,
    onOpenBox64Sheet: () -> Unit,
    onToggleCpuAffinity: (Boolean) -> Unit,
    onToggleTouchControls: (Boolean) -> Unit,
    onToggleFpsHud: (Boolean) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = RenderSADimens.SpaceXL)
    ) {
        item {
            SectionHeader(title = stringResource(R.string.section_setup_files))
            LayeredGroupCard {
                Column(modifier = Modifier.padding(RenderSADimens.SpaceM)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(RenderSADimens.SpaceS)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Storage,
                            contentDescription = null,
                            tint = RenderSAColors.TextSecondary,
                            modifier = Modifier.size(RenderSADimens.IconSizeStandard)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.row_rootfs_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = RenderSAColors.TextPrimary
                            )
                            Spacer(modifier = Modifier.height(RenderSADimens.SpaceXXS))
                            Text(
                                text = if (uiState.rootFsVerification.isInstalled) {
                                    stringResource(R.string.row_rootfs_desc_ready)
                                } else {
                                    stringResource(R.string.row_rootfs_desc_missing)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = RenderSAColors.TextSecondary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(RenderSADimens.SpaceS))
                    if (uiState.rootFsVerification.isInstalled) {
                        SecondaryActionButton(
                            text = stringResource(R.string.action_reimport_rootfs),
                            onClick = onImportRootFsClick,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.isExtractingRootFs,
                            tag = "import_rootfs_button"
                        )
                    } else {
                        PrimaryActionButton(
                            text = stringResource(R.string.action_import_rootfs),
                            onClick = onImportRootFsClick,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.isExtractingRootFs,
                            tag = "import_rootfs_button"
                        )
                    }
                }

                GroupDivider()

                Column(modifier = Modifier.padding(RenderSADimens.SpaceM)) {
                    val gtaCheck = uiState.gtaSaVerification
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(RenderSADimens.SpaceS)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            tint = RenderSAColors.TextSecondary,
                            modifier = Modifier.size(RenderSADimens.IconSizeStandard)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.row_gtasa_dir_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = RenderSAColors.TextPrimary
                            )
                            Spacer(modifier = Modifier.height(RenderSADimens.SpaceXXS))
                            Text(
                                text = if (gtaCheck.directoryPath.isNotBlank()) {
                                    stringResource(R.string.row_gtasa_dir_desc_path, gtaCheck.directoryPath)
                                } else {
                                    stringResource(R.string.row_gtasa_dir_desc_missing)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = RenderSAColors.TextSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (!uiState.hasStoragePermission || gtaCheck.requiresStoragePermission) {
                        Spacer(modifier = Modifier.height(RenderSADimens.SpaceXS))
                        Text(
                            text = stringResource(R.string.verify_storage_perm_required),
                            style = MaterialTheme.typography.labelMedium,
                            color = RenderSAColors.TextPrimary
                        )
                        Spacer(modifier = Modifier.height(RenderSADimens.SpaceS))
                        PrimaryActionButton(
                            text = stringResource(R.string.action_grant_storage_permission),
                            onClick = onRequestStoragePermissionClick,
                            modifier = Modifier.fillMaxWidth(),
                            tag = "grant_storage_permission_button"
                        )
                    } else if (gtaCheck.directoryPath.isNotBlank()) {
                        Spacer(modifier = Modifier.height(RenderSADimens.SpaceXS))
                        val exeLabel = if (gtaCheck.gtaSaExeExists) {
                            stringResource(
                                R.string.verify_gtasa_exe_ok,
                                StoragePathResolver.formatBytesReadable(gtaCheck.gtaSaExeSizeBytes)
                            )
                        } else {
                            stringResource(R.string.verify_gtasa_exe_missing)
                        }
                        val sampLabel = if (gtaCheck.sampDllExists) {
                            stringResource(
                                R.string.verify_samp_dll_ok,
                                StoragePathResolver.formatBytesReadable(gtaCheck.sampDllSizeBytes)
                            )
                        } else {
                            stringResource(R.string.verify_samp_dll_missing)
                        }
                        Text(
                            text = "$exeLabel  |  $sampLabel",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (gtaCheck.isReadyForSingleplayer) {
                                RenderSAColors.TextPrimary
                            } else {
                                RenderSAColors.TextSecondary
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(RenderSADimens.SpaceS))
                    SecondaryActionButton(
                        text = stringResource(R.string.action_select_gtasa_dir),
                        onClick = onSelectGtaSaFolderClick,
                        modifier = Modifier.fillMaxWidth(),
                        tag = "select_gtasa_dir_button"
                    )
                }
            }
        }

        item {
            SectionHeader(title = stringResource(R.string.section_graphics_engine))
            LayeredGroupCard {
                val driverDisplay = when (uiState.preferences.gpuDriverMode) {
                    AppConstants.DRIVER_VORTEK -> "Vortek"
                    AppConstants.DRIVER_TURNIP -> "Turnip"
                    AppConstants.DRIVER_ZINK -> "Zink"
                    else -> "Auto (${uiState.resolvedGpuDriver.effectiveDriver.uppercase()})"
                }
                SettingsNavigationRow(
                    icon = Icons.Outlined.Layers,
                    title = stringResource(R.string.row_gpu_driver_title),
                    subtitle = stringResource(R.string.row_gpu_driver_desc),
                    valueText = driverDisplay,
                    onClick = onOpenGpuDriverSheet,
                    tag = "row_gpu_driver"
                )
                GroupDivider()
                SettingsNavigationRow(
                    icon = Icons.Outlined.DeveloperBoard,
                    title = stringResource(R.string.row_dxvk_title),
                    subtitle = stringResource(R.string.row_dxvk_desc),
                    valueText = "DXVK ${uiState.preferences.dxvkVersion}",
                    onClick = onOpenDxvkSheet,
                    tag = "row_dxvk_version"
                )
                GroupDivider()
                SettingsNavigationRow(
                    icon = Icons.Outlined.AspectRatio,
                    title = stringResource(R.string.row_resolution_title),
                    subtitle = stringResource(R.string.row_resolution_desc),
                    valueText = uiState.preferences.screenResolution,
                    onClick = onOpenResolutionSheet,
                    tag = "row_resolution"
                )
            }
        }

        item {
            SectionHeader(title = stringResource(R.string.section_cpu_audio_engine))
            LayeredGroupCard {
                SettingsNavigationRow(
                    icon = Icons.Outlined.Memory,
                    title = stringResource(R.string.row_box64_preset_title),
                    subtitle = stringResource(R.string.row_box64_preset_desc),
                    valueText = uiState.preferences.box64Preset,
                    onClick = onOpenBox64Sheet,
                    tag = "row_box64_preset"
                )
                GroupDivider()
                SettingsToggleRow(
                    title = stringResource(R.string.row_cpu_affinity_title),
                    subtitle = stringResource(R.string.row_cpu_affinity_desc),
                    checked = uiState.preferences.cpuAffinityEnabled,
                    onCheckedChange = onToggleCpuAffinity,
                    tag = "toggle_cpu_affinity"
                )
                GroupDivider()
                SettingsToggleRow(
                    title = stringResource(R.string.row_touch_controls_title),
                    subtitle = stringResource(R.string.row_touch_controls_desc),
                    checked = uiState.preferences.touchControlsEnabled,
                    onCheckedChange = onToggleTouchControls,
                    tag = "toggle_touch_controls"
                )
                GroupDivider()
                SettingsToggleRow(
                    title = stringResource(R.string.row_fps_hud_title),
                    subtitle = stringResource(R.string.row_fps_hud_desc),
                    checked = uiState.preferences.fpsHudEnabled,
                    onCheckedChange = onToggleFpsHud,
                    tag = "toggle_fps_hud"
                )
            }
        }

        item {
            SectionHeader(title = stringResource(R.string.section_hardware_telemetry))
            LayeredGroupCard {
                val topo = uiState.cpuTopology
                val gpu = uiState.resolvedGpuDriver
                TelemetryInfoRow(
                    label = stringResource(R.string.telemetry_gpu_label),
                    value = "${gpu.vkDeviceName} (Vulkan ${gpu.vkApiVersionFormatted})"
                )
                GroupDivider()
                TelemetryInfoRow(
                    label = stringResource(R.string.telemetry_cpu_label),
                    value = stringResource(
                        R.string.telemetry_cpu_format,
                        topo.totalCores,
                        topo.pCoreCount,
                        topo.maxFreqMhz,
                        topo.pCoreMaskHex,
                        topo.eCoreMaskHex
                    )
                )
                GroupDivider()
                TelemetryInfoRow(
                    label = stringResource(R.string.telemetry_audio_label),
                    value = stringResource(R.string.telemetry_audio_value)
                )
                GroupDivider()
                TelemetryInfoRow(
                    label = stringResource(R.string.telemetry_input_label),
                    value = stringResource(R.string.telemetry_input_value)
                )
            }
        }
    }
}

@Composable
private fun TelemetryInfoRow(
    label: String,
    value: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = RenderSADimens.SpaceM,
                vertical = RenderSADimens.SpaceS
            )
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = RenderSAColors.TextSecondary
        )
        Spacer(modifier = Modifier.height(RenderSADimens.SpaceXXS))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = RenderSAColors.TextPrimary
        )
    }
}
