package com.musicshare.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Single source of truth for the Poweramp-inspired control system. Every interactive surface in
 * the app routes through one of these so heights, shapes, padding, typography and the
 * "active == orange" colour language stay identical everywhere.
 *
 * Emphasis is carried by the orange fill (PrimaryButton), not by left/right position, so callers
 * can keep buttons in natural reading order. The accent is read live from [MaterialTheme.colorScheme]
 * .primary (pinned to Poweramp orange and album-stable), so these controls move with the dynamic
 * album-art theme for free. The only non-accent chroma is [MsTokens.DestructivePlate] /
 * [MsTokens.DestructiveLabel], reserved for irreversible actions so danger never reads like the accent.
 */
object MsTokens {
    val RadiusControl = 10.dp
    val RadiusCard = 20.dp
    val RadiusCardInner = 16.dp
    val Height = 48.dp
    val HeightCompact = 44.dp
    val ChipHeight = 36.dp
    val ContentPaddingH = 20.dp
    val ContentPaddingHCompact = 12.dp
    val RowSpacing = 12.dp
    val ChipSpacing = 8.dp

    val Shape: Shape = RoundedCornerShape(RadiusControl)

    /**
     * Reserved danger colours, intentionally not album-derived. The plate is an opaque dark red so
     * the button controls its own background regardless of the album art behind it, and the lighter
     * [DestructiveLabel] clears AA contrast on it (~6.3:1) while staying clearly distinct from the
     * orange accent (opaque-bright-orange Primary vs opaque-dark-red Destructive).
     */
    val DestructivePlate = Color(0xFF4A1A1E)
    val DestructiveLabel = Color(0xFFFF8A85)

    const val SecondaryContainerAlpha = 0.14f
    const val SelectedChipAlpha = 0.22f
    const val UnselectedChipLabelAlpha = 0.70f
    const val StatusLabelAlpha = 0.85f
    const val SwitchUncheckedTrackAlpha = 0.16f
    const val SwitchUncheckedThumbAlpha = 0.85f
    const val StatusDotPendingAlpha = 0.45f

    /** Reuses Theme.kt's [disabledTextAlpha] (0.38f) so disabled foregrounds dim uniformly. */
    const val DisabledForegroundAlpha = disabledTextAlpha
    const val DisabledContainerAlpha = 0.30f

    val LabelStyle: TextStyle
        @Composable get() = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium)

    val ChipLabelStyle: TextStyle
        @Composable get() = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium)

    /** Flat, Poweramp-style: no resting or pressed shadow on any filled button. */
    val FlatElevation: ButtonElevation
        @Composable get() = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp,
            disabledElevation = 0.dp,
        )
}

@Composable
private fun MsLabel(text: String, maxLines: Int = 1) {
    Text(text, style = MsTokens.LabelStyle, maxLines = maxLines, softWrap = maxLines > 1)
}

/**
 * Highest emphasis: the single saturated orange action per surface. When [loading] is true the
 * button keeps its full-brightness enabled appearance, shows a leading spinner, and ignores taps
 * (so the spinner and label never drift to different brightnesses the way a disabled state would).
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    maxLines: Int = 1,
) {
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = { if (!loading) onClick() },
        enabled = enabled,
        modifier = modifier.heightIn(min = MsTokens.Height),
        shape = MsTokens.Shape,
        elevation = MsTokens.FlatElevation,
        contentPadding = PaddingValues(horizontal = MsTokens.ContentPaddingH),
        colors = ButtonDefaults.buttonColors(
            containerColor = scheme.primary,
            contentColor = scheme.onPrimary,
            disabledContainerColor = scheme.primary.copy(alpha = MsTokens.DisabledContainerAlpha),
            disabledContentColor = scheme.onPrimary.copy(alpha = MsTokens.DisabledForegroundAlpha),
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = scheme.onPrimary,
            )
            Spacer(Modifier.width(8.dp))
        }
        MsLabel(text, maxLines)
    }
}

/** Supporting non-destructive actions: a translucent white plate, no border, no accent. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
    maxLines: Int = 1,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = if (compact) MsTokens.HeightCompact else MsTokens.Height),
        shape = MsTokens.Shape,
        elevation = MsTokens.FlatElevation,
        contentPadding = PaddingValues(
            horizontal = if (compact) MsTokens.ContentPaddingHCompact else MsTokens.ContentPaddingH,
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = musicShareTextColor.copy(alpha = MsTokens.SecondaryContainerAlpha),
            contentColor = musicShareTextColor,
            disabledContainerColor = musicShareTextColor.copy(
                alpha = MsTokens.SecondaryContainerAlpha * MsTokens.DisabledContainerAlpha,
            ),
            disabledContentColor = musicShareTextColor.copy(alpha = MsTokens.DisabledForegroundAlpha),
        ),
    ) { MsLabel(text, maxLines) }
}

/** Irreversible/stop actions: a reserved red tint + red label, deliberately distinct from the accent. */
@Composable
fun DestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
    maxLines: Int = 1,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = if (compact) MsTokens.HeightCompact else MsTokens.Height),
        shape = MsTokens.Shape,
        elevation = MsTokens.FlatElevation,
        contentPadding = PaddingValues(
            horizontal = if (compact) MsTokens.ContentPaddingHCompact else MsTokens.ContentPaddingH,
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = MsTokens.DestructivePlate,
            contentColor = MsTokens.DestructiveLabel,
            disabledContainerColor = MsTokens.DestructivePlate.copy(alpha = MsTokens.DisabledContainerAlpha),
            disabledContentColor = MsTokens.DestructiveLabel.copy(alpha = MsTokens.DisabledForegroundAlpha),
        ),
    ) { MsLabel(text, maxLines) }
}

/** Lowest emphasis: a ghost text action. [compact] drops the min height for inline slots. */
@Composable
fun TextActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
    maxLines: Int = 1,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = if (compact) modifier else modifier.heightIn(min = MsTokens.HeightCompact),
        shape = MsTokens.Shape,
        contentPadding = PaddingValues(horizontal = MsTokens.ContentPaddingHCompact),
        colors = ButtonDefaults.textButtonColors(
            contentColor = musicShareTextColor,
            disabledContentColor = musicShareTextColor.copy(alpha = MsTokens.DisabledForegroundAlpha),
        ),
    ) { MsLabel(text, maxLines) }
}

/**
 * Flat chip. State-reflecting toggles get the orange wash when [selected]; momentary preset
 * triggers always pass `selected = false` and therefore stay transparent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MsFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = MsTokens.ChipHeight),
        shape = MsTokens.Shape,
        label = { Text(label, style = MsTokens.ChipLabelStyle, maxLines = 1) },
        border = null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = musicShareTextColor.copy(alpha = MsTokens.UnselectedChipLabelAlpha),
            selectedContainerColor = scheme.primary.copy(alpha = MsTokens.SelectedChipAlpha),
            selectedLabelColor = musicShareTextColor,
            disabledContainerColor = Color.Transparent,
            disabledLabelColor = musicShareTextColor.copy(alpha = MsTokens.DisabledForegroundAlpha),
        ),
    )
}

/** Non-interactive status label with a leading state dot (orange == good, dim white == pending). */
@Composable
fun StatusChip(
    label: String,
    good: Boolean,
    modifier: Modifier = Modifier,
) {
    val dotColor = if (good) {
        MaterialTheme.colorScheme.primary
    } else {
        musicShareTextColor.copy(alpha = MsTokens.StatusDotPendingAlpha)
    }
    Row(
        modifier = modifier.heightIn(min = MsTokens.ChipHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(6.dp).background(dotColor, CircleShape))
        Text(
            label,
            style = MsTokens.ChipLabelStyle,
            color = musicShareTextColor.copy(alpha = MsTokens.StatusLabelAlpha),
            maxLines = 1,
        )
    }
}

/** Switch where the ON state is the same orange as a selected chip and the primary button. */
@Composable
fun MsSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = scheme.onPrimary,
            checkedTrackColor = scheme.primary,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = musicShareTextColor.copy(alpha = MsTokens.SwitchUncheckedThumbAlpha),
            uncheckedTrackColor = musicShareTextColor.copy(alpha = MsTokens.SwitchUncheckedTrackAlpha),
            uncheckedBorderColor = Color.Transparent,
            disabledCheckedTrackColor = scheme.primary.copy(alpha = MsTokens.DisabledForegroundAlpha),
            disabledUncheckedTrackColor = musicShareTextColor.copy(
                alpha = MsTokens.SwitchUncheckedTrackAlpha * MsTokens.DisabledForegroundAlpha,
            ),
        ),
    )
}

/** Equal-weight button row with the single standard gap. Fixes the old ad-hoc 1.2f/0.8f weights. */
@Composable
fun ActionRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MsTokens.RowSpacing),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
