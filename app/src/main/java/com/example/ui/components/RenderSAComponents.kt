package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.ui.theme.RenderSAColors
import com.example.ui.theme.RenderSADimens
import com.example.ui.theme.RenderSAMotion
import com.example.ui.theme.RenderSAShapes

data class SelectionOption(
    val id: String,
    val title: String,
    val subtitle: String = ""
)

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                 top = RenderSADimens.SpaceM,
                 bottom = RenderSADimens.SpaceXS,
                 start = RenderSADimens.SpaceXXS,
                 end = RenderSADimens.SpaceXXS
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = RenderSAColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (trailingContent != null) {
            trailingContent()
        }
    }
}

@Composable
fun LayeredGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RenderSAShapes.Card)
            .background(RenderSAColors.SurfaceCard)
            .border(
                width = RenderSADimens.BorderStrokeWidth,
                color = RenderSAColors.BorderSubtle,
                shape = RenderSAShapes.Card
            ),
        content = content
    )
}

@Composable
fun GroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = RenderSADimens.SpaceM),
        thickness = RenderSADimens.DividerThickness,
        color = RenderSAColors.DividerSubtle
    )
}

@Composable
fun StatusPillItem(
    label: String,
    valueText: String,
    isReady: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RenderSAShapes.Tag)
            .background(RenderSAColors.SurfaceCard)
            .border(
                width = RenderSADimens.BorderStrokeWidth,
                color = RenderSAColors.BorderSubtle,
                shape = RenderSAShapes.Tag
            )
            .padding(
                horizontal = RenderSADimens.SpaceS,
                vertical = RenderSADimens.SpaceXS
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RenderSADimens.SpaceXS)
    ) {
        Box(
            modifier = Modifier
                .size(RenderSADimens.StatusDotSize)
                .clip(CircleShape)
                .background(
                    if (isReady) RenderSAColors.StatusReadyDot else RenderSAColors.StatusWarningDot
                )
        )
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = RenderSAColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge,
                color = RenderSAColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    tag: String = ""
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) RenderSAMotion.PRESS_SCALE else 1f,
        animationSpec = RenderSAMotion.fastTween(),
        label = "primary_btn_scale"
    )

    val bgColor = if (enabled) RenderSAColors.AccentPrimary else RenderSAColors.SurfaceInteractive
    val borderColor = if (enabled) RenderSAColors.AccentBorder else RenderSAColors.BorderSubtle
    val textColor = if (enabled) RenderSAColors.TextPrimary else RenderSAColors.TextMuted

    Box(
        modifier = modifier
            .let { if (tag.isNotEmpty()) it.testTag(tag) else it }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .heightIn(min = RenderSADimens.TouchTargetMin)
            .clip(RenderSAShapes.Button)
            .background(bgColor)
            .border(RenderSADimens.BorderStrokeWidth, borderColor, RenderSAShapes.Button)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = RenderSADimens.SpaceM, vertical = RenderSADimens.SpaceS),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(RenderSADimens.IconSizeStandard)
                )
                Spacer(modifier = Modifier.width(RenderSADimens.SpaceXS))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun SecondaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    tag: String = ""
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) RenderSAMotion.PRESS_SCALE else 1f,
        animationSpec = RenderSAMotion.fastTween(),
        label = "secondary_btn_scale"
    )

    val textColor = if (enabled) RenderSAColors.TextPrimary else RenderSAColors.TextMuted

    Box(
        modifier = modifier
            .let { if (tag.isNotEmpty()) it.testTag(tag) else it }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .heightIn(min = RenderSADimens.TouchTargetMin)
            .clip(RenderSAShapes.Button)
            .background(RenderSAColors.SurfaceInteractive)
            .border(
                RenderSADimens.BorderStrokeWidth,
                RenderSAColors.BorderInteractive,
                RenderSAShapes.Button
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = RenderSADimens.SpaceM, vertical = RenderSADimens.SpaceS),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(RenderSADimens.IconSizeStandard)
                )
                Spacer(modifier = Modifier.width(RenderSADimens.SpaceXS))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun SettingsNavigationRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    valueText: String,
    onClick: () -> Unit,
    tag: String = ""
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) RenderSAMotion.PRESS_SCALE else 1f,
        animationSpec = RenderSAMotion.fastTween(),
        label = "nav_row_scale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (tag.isNotEmpty()) it.testTag(tag) else it }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .heightIn(min = RenderSADimens.RowDetailedMinHeight)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(
                horizontal = RenderSADimens.SpaceM,
                vertical = RenderSADimens.SpaceS
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = RenderSAColors.TextSecondary,
            modifier = Modifier.size(RenderSADimens.IconSizeStandard)
        )
        Spacer(modifier = Modifier.width(RenderSADimens.SpaceS))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = RenderSAColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotEmpty()) {
                Spacer(modifier = Modifier.height(RenderSADimens.SpaceXXS))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = RenderSAColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(RenderSADimens.SpaceXS))
        Text(
            text = valueText,
            style = MaterialTheme.typography.bodyMedium,
            color = RenderSAColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = stringResource(R.string.cd_chevron_select),
            tint = RenderSAColors.TextSecondary,
            modifier = Modifier.size(RenderSADimens.IconSizeStandard)
        )
    }
}

@Composable
fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String = ""
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (tag.isNotEmpty()) it.testTag(tag) else it }
            .heightIn(min = RenderSADimens.RowDetailedMinHeight)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange(!checked)
            }
            .padding(
                horizontal = RenderSADimens.SpaceM,
                vertical = RenderSADimens.SpaceS
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = RenderSAColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotEmpty()) {
                Spacer(modifier = Modifier.height(RenderSADimens.SpaceXXS))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = RenderSAColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(RenderSADimens.SpaceS))
        Switch(
            checked = checked,
            onCheckedChange = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange(it)
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = RenderSAColors.TextPrimary,
                checkedTrackColor = RenderSAColors.AccentPrimary,
                uncheckedThumbColor = RenderSAColors.TextSecondary,
                uncheckedTrackColor = RenderSAColors.SurfaceInteractive,
                uncheckedBorderColor = RenderSAColors.BorderSubtle
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionBottomSheet(
    title: String,
    options: List<SelectionOption>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptic = LocalHapticFeedback.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RenderSAShapes.TopModal,
        containerColor = RenderSAColors.SurfaceModal,
        scrimColor = RenderSAColors.ScrimOverlay,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(RenderSAColors.RadialGlowAccent, Color.Transparent),
                            center = Offset(size.width / 2f, 0f),
                            radius = RenderSADimens.RadialGlowRadius
                        )
                    )
                }
                .padding(bottom = RenderSADimens.SpaceXL)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = RenderSADimens.SpaceM,
                        vertical = RenderSADimens.SpaceS
                    )
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = RenderSAColors.TextPrimary,
                    modifier = Modifier.align(Alignment.Center),
                    textAlign = TextAlign.Center
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(RenderSADimens.TouchTargetMin)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.cd_close_dialog),
                        tint = RenderSAColors.TextSecondary
                    )
                }
            }

            HorizontalDivider(
                thickness = RenderSADimens.DividerThickness,
                color = RenderSAColors.DividerSubtle
            )

            options.forEachIndexed { index, option ->
                val isSelected = option.id == selectedId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = RenderSADimens.RowMinHeight)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(option.id)
                            onDismiss()
                        }
                        .padding(
                            horizontal = RenderSADimens.SpaceL,
                            vertical = RenderSADimens.SpaceS
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = option.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isSelected) RenderSAColors.TextPrimary else RenderSAColors.TextSecondary
                        )
                        if (option.subtitle.isNotEmpty()) {
                            Text(
                                text = option.subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = RenderSAColors.TextSecondary
                            )
                        }
                    }
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                            tint = RenderSAColors.TextPrimary,
                            modifier = Modifier.size(RenderSADimens.IconSizeStandard)
                        )
                    }
                }
                if (index < options.lastIndex) {
                    GroupDivider()
                }
            }
        }
    }
}

@Composable
fun AddServerDialog(
    onDismiss: () -> Unit,
    onConfirm: (address: String, password: String) -> Unit
) {
    var address by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = RenderSADimens.SpaceL),
            shape = RenderSAShapes.Modal,
            color = RenderSAColors.SurfaceModal,
            border = BorderStroke(RenderSADimens.BorderStrokeWidth, RenderSAColors.BorderSubtle)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(RenderSAColors.RadialGlowNeutral, Color.Transparent),
                                center = Offset(size.width / 2f, 0f),
                                radius = RenderSADimens.RadialGlowRadius
                            )
                        )
                    }
                    .padding(RenderSADimens.SpaceL)
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.dialog_add_server_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = RenderSAColors.TextPrimary,
                        modifier = Modifier.align(Alignment.Center),
                        textAlign = TextAlign.Center
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(RenderSADimens.TouchTargetMin)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.cd_close_dialog),
                            tint = RenderSAColors.TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(RenderSADimens.SpaceXS))
                Text(
                    text = stringResource(R.string.dialog_add_server_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = RenderSAColors.TextSecondary
                )

                Spacer(modifier = Modifier.height(RenderSADimens.SpaceM))
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_server_address"),
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.hint_server_address),
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

                Spacer(modifier = Modifier.height(RenderSADimens.SpaceS))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_server_password"),
                    singleLine = true,
                    label = {
                        Text(
                            text = stringResource(R.string.label_server_password),
                            color = RenderSAColors.TextSecondary
                        )
                    },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.hint_server_password),
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

                Spacer(modifier = Modifier.height(RenderSADimens.SpaceL))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(RenderSADimens.SpaceS)
                ) {
                    SecondaryActionButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        tag = "dialog_cancel_button"
                    )
                    PrimaryActionButton(
                        text = stringResource(R.string.action_save),
                        onClick = { onConfirm(address, password) },
                        modifier = Modifier.weight(1f),
                        enabled = address.isNotBlank(),
                        tag = "dialog_save_server_button"
                    )
                }
            }
        }
    }
}

@Composable
fun ConfirmActionDialog(
    title: String,
    description: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = RenderSADimens.SpaceL),
            shape = RenderSAShapes.Modal,
            color = RenderSAColors.SurfaceModal,
            border = BorderStroke(RenderSADimens.BorderStrokeWidth, RenderSAColors.BorderSubtle)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(RenderSAColors.RadialGlowNeutral, Color.Transparent),
                                center = Offset(size.width / 2f, 0f),
                                radius = RenderSADimens.RadialGlowRadius
                            )
                        )
                    }
                    .padding(RenderSADimens.SpaceL)
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = RenderSAColors.TextPrimary,
                        modifier = Modifier.align(Alignment.Center),
                        textAlign = TextAlign.Center
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(RenderSADimens.TouchTargetMin)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.cd_close_dialog),
                            tint = RenderSAColors.TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(RenderSADimens.SpaceS))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = RenderSAColors.TextSecondary
                )

                Spacer(modifier = Modifier.height(RenderSADimens.SpaceL))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(RenderSADimens.SpaceS)
                ) {
                    SecondaryActionButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    PrimaryActionButton(
                        text = confirmText,
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
