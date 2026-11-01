package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.diagnostic.CrashReportData
import com.example.diagnostic.UniversalCrashLogger
import com.example.ui.theme.RenderSAColors
import com.example.ui.theme.RenderSADimens
import com.example.ui.theme.RenderSAShapes

@Composable
fun UniversalErrorHoldScreen(
    report: CrashReportData,
    closeActionLabel: String,
    onCloseOrReturn: () -> Unit,
    onClearLog: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var statusFeedbackText by remember { mutableStateOf<String?>(null) }
    var copiedSuccess by remember { mutableStateOf(false) }

    val tabTitles = listOf(
        stringResource(R.string.log_tab_all),
        stringResource(R.string.log_tab_error),
        stringResource(R.string.log_tab_wine_box64),
        stringResource(R.string.log_tab_rootfs),
        stringResource(R.string.log_tab_checkpoints),
        stringResource(R.string.log_tab_logcat)
    )

    val displayedLogContent = remember(report, selectedTab) {
        when (selectedTab) {
            0 -> report.fullReportText
            1 -> report.primaryErrorSection
            2 -> report.processOutputSection
            3 -> report.rootFsDiagnosticSection
            4 -> report.checkpointsSection
            5 -> report.logcatSection
            else -> report.fullReportText
        }
    }

    val verticalScrollState = rememberScrollState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(RenderSAColors.ScreenBackgroundBrush)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = RenderSADimens.ScreenHorizontalPadding,
                    vertical = RenderSADimens.SpaceS
                ),
            verticalArrangement = Arrangement.spacedBy(RenderSADimens.SpaceS)
        ) {
            // Kartu Header Ringkasan Error & Tombol Salin Log
            LayeredGroupCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(RenderSADimens.SpaceM),
                    verticalArrangement = Arrangement.spacedBy(RenderSADimens.SpaceXS)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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
                                text = report.category,
                                style = MaterialTheme.typography.labelSmall,
                                color = RenderSAColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Text(
                            text = report.timestamp,
                            style = MaterialTheme.typography.bodySmall,
                            color = RenderSAColors.TextSecondary,
                            maxLines = 1
                        )
                    }

                    Text(
                        text = report.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = RenderSAColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = report.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = RenderSAColors.TextSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (!statusFeedbackText.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RenderSAShapes.Tag)
                                .background(RenderSAColors.SurfaceInteractive)
                                .border(
                                    RenderSADimens.BorderStrokeWidth,
                                    RenderSAColors.BorderInteractive,
                                    RenderSAShapes.Tag
                                )
                                .padding(
                                    horizontal = RenderSADimens.SpaceS,
                                    vertical = RenderSADimens.SpaceXS
                                )
                        ) {
                            Text(
                                text = statusFeedbackText.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = RenderSAColors.TextPrimary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(RenderSADimens.SpaceXXS))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(RenderSADimens.SpaceXS)
                    ) {
                        val copyBtnLabel = if (copiedSuccess) {
                            stringResource(R.string.action_copy_copied_confirm)
                        } else {
                            stringResource(R.string.action_copy_full_log)
                        }

                        PrimaryActionButton(
                            text = copyBtnLabel,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                copyTextToClipboard(context, report.fullReportText)
                                copiedSuccess = true
                                statusFeedbackText = context.getString(
                                    R.string.msg_log_copied_clipboard,
                                    report.fullReportText.length
                                )
                            },
                            modifier = Modifier.weight(1.2f),
                            tag = "btn_copy_crash_log"
                        )

                        SecondaryActionButton(
                            text = stringResource(R.string.action_save_log_file),
                            onClick = {
                                val savedPath = UniversalCrashLogger.saveReportToDownloadOrAppStorage(
                                    context,
                                    report
                                )
                                statusFeedbackText = context.getString(
                                    R.string.msg_log_saved_path,
                                    savedPath
                                )
                            },
                            modifier = Modifier.weight(1f),
                            tag = "btn_save_crash_log"
                        )

                        SecondaryActionButton(
                            text = closeActionLabel,
                            onClick = onCloseOrReturn,
                            modifier = Modifier.weight(1f),
                            tag = "btn_close_crash_hold"
                        )
                    }

                    if (onClearLog != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Box(
                                modifier = Modifier
                                    .testTag("btn_clear_crash_log")
                                    .heightIn(min = 36.dp)
                                    .clip(RenderSAShapes.Tag)
                                    .background(RenderSAColors.SurfaceInteractive)
                                    .border(
                                        RenderSADimens.BorderStrokeWidth,
                                        RenderSAColors.BorderSubtle,
                                        RenderSAShapes.Tag
                                    )
                                    .clickable(onClick = onClearLog)
                                    .padding(
                                        horizontal = RenderSADimens.SpaceM,
                                        vertical = RenderSADimens.SpaceXXS
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.action_clear_crash_log),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = RenderSAColors.TextSecondary
                                )
                            }
                        }
                    }
                }
            }

            // Baris Tab Filter Kategori Log
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(RenderSADimens.SpaceXS)
            ) {
                tabTitles.forEachIndexed { idx, label ->
                    val isSelected = selectedTab == idx
                    val bg = if (isSelected) {
                        RenderSAColors.AccentSubtleBg
                    } else {
                        RenderSAColors.SurfaceInteractive
                    }
                    val border = if (isSelected) {
                        RenderSAColors.AccentBorder
                    } else {
                        RenderSAColors.BorderSubtle
                    }

                    Box(
                        modifier = Modifier
                            .testTag("log_filter_tab_$idx")
                            .heightIn(min = 40.dp)
                            .clip(RenderSAShapes.Tag)
                            .background(bg)
                            .border(
                                RenderSADimens.BorderStrokeWidth,
                                border,
                                RenderSAShapes.Tag
                            )
                            .clickable { selectedTab = idx }
                            .padding(
                                horizontal = RenderSADimens.SpaceM,
                                vertical = RenderSADimens.SpaceXS
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSelected) {
                                RenderSAColors.TextPrimary
                            } else {
                                RenderSAColors.TextSecondary
                            }
                        )
                    }
                }
            }

            // Area Isi Log Monospace yang Dapat Di-scroll & Diblok
            LayeredGroupCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(verticalScrollState)
                            .padding(RenderSADimens.SpaceM)
                    ) {
                        Text(
                            text = displayedLogContent,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = RenderSAColors.TextPrimary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

private fun copyTextToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText("RenderSA Crash & Diagnostic Log", text)
    cm.setPrimaryClip(clip)
}
