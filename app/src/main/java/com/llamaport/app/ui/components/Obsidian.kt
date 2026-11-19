package com.llamaport.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.llamaport.app.ui.motion.LocalReducedMotion
import com.llamaport.app.ui.motion.MotionTokens
import com.llamaport.app.ui.motion.pressable
import com.llamaport.app.ui.theme.EyebrowStyle
import com.llamaport.app.ui.theme.MonoNumeric
import com.llamaport.app.ui.theme.ObsidianTheme
import com.llamaport.app.ui.theme.PillShape

/**
 * App-wide canvas: near-black with one very weak silver light source in the
 * upper right. No blur, no expensive shader — a single vertical + radial fill.
 */
@Composable
fun ObsidianBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = ObsidianTheme.palette
    Box(
        modifier = modifier.background(
            Brush.verticalGradient(
                0f to palette.surface,
                0.45f to palette.background,
                1f to palette.background,
            ),
        ),
    ) {
        Canvas(Modifier.fillMaxWidth()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        palette.silver.copy(alpha = if (palette.isDark) 0.035f else 0.05f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.78f, -size.width * 0.08f),
                    radius = size.width * 0.85f,
                ),
            )
        }
        content()
    }
}

/** 1 dp border line that separates surfaces without a visible box. */
@Composable
fun Hairline(
    modifier: Modifier = Modifier,
    color: Color = ObsidianTheme.palette.hairline,
) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** Tiny uppercase technical label with a leading silver tick. */
@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = ObsidianTheme.palette.textMuted,
    tick: Boolean = true,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (tick) {
            Box(
                Modifier
                    .size(width = 10.dp, height = 1.dp)
                    .background(color.copy(alpha = 0.7f)),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text.uppercase(),
            style = EyebrowStyle,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Round state indicator; optionally breathing (loading / generation). */
@Composable
fun StateDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 7.dp,
    pulse: Boolean = false,
) {
    val reduced = LocalReducedMotion.current
    val alpha = if (pulse && !reduced) {
        val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "stateDot")
        val value by infinite.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = tween(720, easing = androidx.compose.animation.core.LinearEasing),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "stateDotAlpha",
        )
        value
    } else {
        1f
    }
    Box(modifier.size(size)) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(
                color = color.copy(alpha = 0.16f * alpha),
                radius = this.size.minDimension * 1.5f,
            )
            drawCircle(color = color.copy(alpha = alpha), radius = this.size.minDimension * 0.5f)
        }
    }
}

/**
 * Base card surface with a hairline border. [active] lifts the card, adds a
 * silver rim and a very faint top sheen.
 */
@Composable
fun ObsidianCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    active: Boolean = false,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    background: Color? = null,
    borderColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = ObsidianTheme.palette
    val bg = background ?: if (active) palette.nested else palette.card
    val border = borderColor ?: if (active) {
        palette.silver.copy(alpha = 0.42f)
    } else {
        palette.hairline
    }
    val base = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(
            if (active) {
                Brush.verticalGradient(listOf(bg, palette.card))
            } else {
                Brush.verticalGradient(listOf(bg, bg))
            },
        )
        .border(1.dp, border, shape)
    val clickable = if (onClick != null) {
        base.pressable(enabled = enabled, onClick = onClick)
    } else {
        base
    }
    Column(clickable.padding(contentPadding), content = content)
}

/** Circular / squircle icon button used in bars and card corners. */
@Composable
fun ObsidianIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = ObsidianTheme.palette.textSecondary,
    container: Color = Color.Transparent,
    borderColor: Color? = null,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    shape: Shape = MaterialTheme.shapes.small,
) {
    val palette = ObsidianTheme.palette
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
            .clip(shape)
            .background(container)
            .then(
                if (borderColor != null) {
                    Modifier.border(1.dp, borderColor, shape)
                } else {
                    Modifier
                },
            )
            .pressable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

enum class ObsidianButtonStyle { Primary, Secondary, Ghost }

/** Brand button: silver fill for the single primary action, hairlines for the rest. */
@Composable
fun ObsidianButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: ObsidianButtonStyle = ObsidianButtonStyle.Primary,
    leadingIcon: ImageVector? = null,
    fillWidth: Boolean = true,
) {
    val palette = ObsidianTheme.palette
    val background = when (style) {
        ObsidianButtonStyle.Primary -> palette.silver
        ObsidianButtonStyle.Secondary -> palette.nested
        ObsidianButtonStyle.Ghost -> Color.Transparent
    }
    val contentColor = when (style) {
        ObsidianButtonStyle.Primary -> if (palette.isDark) Color(0xFF111214) else Color(0xFFF7F7F9)
        ObsidianButtonStyle.Secondary -> palette.textPrimary
        ObsidianButtonStyle.Ghost -> palette.textSecondary
    }
    val shape = MaterialTheme.shapes.small
    Box(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clip(shape)
            .background(background)
            .then(
                if (style == ObsidianButtonStyle.Secondary) {
                    Modifier.border(1.dp, palette.hairlineStrong, shape)
                } else {
                    Modifier
                },
            )
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(17.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Compact technical pill; [selected] gives it a silver rim. */
@Composable
fun ObsidianChip(
    text: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    leading: (@Composable RowScope.() -> Unit)? = null,
) {
    val palette = ObsidianTheme.palette
    val bg = if (selected) palette.elevated else palette.nested
    val border = if (selected) palette.silver.copy(alpha = 0.45f) else palette.hairline
    val base = modifier
        .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
        .clip(PillShape)
        .background(bg)
        .border(1.dp, border, PillShape)
    val clickable = if (onClick != null) {
        base.pressable(enabled = enabled, onClick = onClick)
    } else {
        base
    }
    Row(
        clickable.padding(horizontal = 11.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke(this)
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) palette.textPrimary else palette.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Compact switch with an animated silver thumb and a soft active halo. */
@Composable
fun ObsidianSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val palette = ObsidianTheme.palette
    val reduced = LocalReducedMotion.current
    val haptics = LocalHapticFeedback.current
    val spec = if (reduced) snap<Color>() else tween<Color>(MotionTokens.Durations.SHORT)
    val track by animateColorAsState(
        targetValue = if (checked) palette.silver else palette.nested,
        animationSpec = spec,
        label = "switchTrack",
    )
    val thumb by animateColorAsState(
        targetValue = if (checked) {
            if (palette.isDark) palette.background else Color(0xFFF7F7F9)
        } else {
            palette.textMuted
        },
        animationSpec = spec,
        label = "switchThumb",
    )
    val offset by animateDpAsState(
        targetValue = if (checked) 20.dp else 2.dp,
        animationSpec = if (reduced) snap() else tween(MotionTokens.Durations.SHORT),
        label = "switchOffset",
    )
    val border = if (checked) Color.Transparent else palette.hairlineStrong
    Box(
        modifier = modifier
            .size(width = 44.dp, height = 26.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .clip(PillShape)
            .background(track)
            .border(1.dp, border, PillShape)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onCheckedChange(it)
                },
            )
            .padding(2.dp),
    ) {
        Box(
            Modifier
                .offset(x = offset)
                .size(22.dp)
                .background(thumb, CircleShape),
        )
    }
}

/**
 * Premium slider: 3 dp track, silver active segment, optional step ticks and a
 * jewelled thumb. Values are read in the draw phase so dragging does not
 * recompose the surrounding settings panel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObsidianSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val palette = ObsidianTheme.palette
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().height(40.dp),
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        thumb = { SliderThumb(palette.silver, palette.card) },
        track = { state ->
            Canvas(Modifier.fillMaxWidth().height(40.dp)) {
                val inset = 10.dp.toPx()
                val width = size.width - inset * 2f
                if (width <= 0f) return@Canvas
                val h = 3.dp.toPx()
                val y = (size.height - h) / 2f
                val range = state.valueRange
                val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
                val fraction = ((state.value - range.start) / span).coerceIn(0f, 1f)
                val radius = CornerRadius(h / 2f, h / 2f)
                drawRoundRect(
                    color = palette.elevated,
                    topLeft = Offset(inset, y),
                    size = Size(width, h),
                    cornerRadius = radius,
                )
                if (fraction > 0f) {
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                palette.silverDim,
                                palette.silver,
                            ),
                            startX = inset,
                            endX = inset + width,
                        ),
                        topLeft = Offset(inset, y),
                        size = Size(width * fraction, h),
                        cornerRadius = radius,
                    )
                }
                if (state.steps > 0) {
                    val count = state.steps + 2
                    for (i in 0 until count) {
                        val x = inset + width * (i / (count - 1).toFloat())
                        drawCircle(
                            color = if (x <= inset + width * fraction) {
                                palette.silver.copy(alpha = 0.75f)
                            } else {
                                palette.textMuted.copy(alpha = 0.55f)
                            },
                            radius = 1.1.dp.toPx(),
                            center = Offset(x, size.height / 2f),
                        )
                    }
                }
                // Bright leading point on the track.
                drawCircle(
                    color = palette.silver,
                    radius = 2.4.dp.toPx(),
                    center = Offset(inset + width * fraction, size.height / 2f),
                )
            }
        },
    )
}

@Composable
private fun SliderThumb(accent: Color, core: Color) {
    Box(
        Modifier
            .size(20.dp)
            .background(core, CircleShape)
            .border(1.5.dp, accent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(6.dp).background(accent, CircleShape))
    }
}

/**
 * Text input for chat and editors. Focus is expressed by a silver rim and a
 * faint inner top highlight rather than a floating Material label.
 */
@Composable
fun ObsidianTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = 5,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    shape: Shape = MaterialTheme.shapes.small,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val palette = ObsidianTheme.palette
    val reduced = LocalReducedMotion.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border by animateColorAsState(
        targetValue = when {
            !enabled -> palette.hairline
            focused -> palette.silver.copy(alpha = 0.55f)
            else -> palette.hairlineStrong.copy(alpha = 0.65f)
        },
        animationSpec = if (reduced) snap() else tween(MotionTokens.Durations.MICRO),
        label = "textFieldBorder",
    )
    val background = if (focused) palette.nested else palette.card
    Row(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(1.dp, border, shape)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke(this)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            textStyle = textStyle.copy(color = palette.textPrimary),
            cursorBrush = SolidColor(palette.silver),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            interactionSource = interaction,
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = textStyle,
                            color = palette.textMuted,
                            maxLines = maxLines,
                        )
                    }
                    inner()
                }
            },
        )
        trailing?.invoke(this)
    }
}

/** Small monospace value, used for every changing technical number. */
@Composable
fun MonoValue(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = ObsidianTheme.palette.textSecondary,
    style: TextStyle = MonoNumeric,
) {
    Text(text = text, style = style, color = color, modifier = modifier, maxLines = 1)
}

/** A tiny silver dot bullet. */
@Composable
fun SilverBullet(modifier: Modifier = Modifier, color: Color = ObsidianTheme.palette.silverDim) {
    Box(modifier.size(4.dp).background(color, CircleShape))
}

/** Section container used by sheets and settings: large radius, hairline rim. */
@Composable
fun ObsidianSection(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = ObsidianTheme.palette
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(palette.card)
            .border(1.dp, palette.hairline, MaterialTheme.shapes.large)
            .padding(16.dp),
        content = content,
    )
}

/** Faux outline chip used to label a single fact on a card. */
@Composable
fun ObsidianTag(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = ObsidianTheme.palette.textSecondary,
) {
    val palette = ObsidianTheme.palette
    Box(
        modifier
            .clip(RoundedCornerShape(7.dp))
            .background(palette.elevated.copy(alpha = 0.7f))
            .border(1.dp, palette.hairline, RoundedCornerShape(7.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(text = text, style = EyebrowStyle, color = color, maxLines = 1)
    }
}

/** Convenience border for a rounded surface built outside [ObsidianCard]. */
fun Modifier.obsidianBorder(
    color: Color,
    shape: Shape,
    width: Dp = 1.dp,
): Modifier = border(BorderStroke(width, color), shape)