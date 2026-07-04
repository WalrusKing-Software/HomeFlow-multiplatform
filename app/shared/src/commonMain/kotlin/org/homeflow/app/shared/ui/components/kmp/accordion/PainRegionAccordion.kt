package org.homeflow.app.shared.ui.components.kmp.accordion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// Data shapes — the presentation model this component renders. Callers adapt
// their own domain types (e.g. the app's PainRegionDto / PainLocationDto) into
// these; the component stays decoupled from the data source.
// ---------------------------------------------------------------------------

data class PainLocation(
    val id: String,
    val label: String,
)

data class PainRegion(
    val id: String,
    val label: String,
    val locations: List<PainLocation>,
)

data class PainLocationRating(
    val locationId: String,
    val severity: Int?, // null = selected but unrated ("None")
)

data class EditingState(
    val locationId: String,
    val severity: Int?, // null = "None" selected; draft value while the editor is open
)

// ---------------------------------------------------------------------------
// Color tokens. Defaults are derived from the active Material 3 color scheme
// (see accordionColorsFromTheme) so the component follows the app theme and
// dark mode. defaultAccordionColors() keeps a fixed palette for previews/tests.
// ---------------------------------------------------------------------------

data class AccordionColors(
    val base100: Color,
    val base300: Color,
    val baseContent: Color,
    val muted: Color,
    val primary: Color,
    val primaryContent: Color,
    val neutral: Color,
    val neutralContent: Color,
)

fun defaultAccordionColors() = AccordionColors(
    base100 = Color(0xFFFFFFFF),
    base300 = Color(0xFFE0E0E0),
    baseContent = Color(0xFF1A1A1A),
    muted = Color(0xFF8A8A8A),
    primary = Color(0xFF3B6FD6),
    primaryContent = Color(0xFFFFFFFF),
    neutral = Color(0xFF4A4A4A),
    neutralContent = Color(0xFFFFFFFF),
)

/** AccordionColors mapped from the active [MaterialTheme] color scheme. */
@Composable
fun accordionColorsFromTheme(): AccordionColors {
    val scheme = MaterialTheme.colorScheme
    return AccordionColors(
        base100 = scheme.surface,
        base300 = scheme.outlineVariant,
        baseContent = scheme.onSurface,
        muted = scheme.onSurfaceVariant,
        primary = scheme.primary,
        primaryContent = scheme.onPrimary,
        neutral = scheme.secondary,
        neutralContent = scheme.onSecondary,
    )
}

// ---------------------------------------------------------------------------
// Root composable — hoisted state: regions/selected/editing come in, callbacks
// go out. No business logic (persistence, validation, network) lives in here.
// ---------------------------------------------------------------------------

@Composable
fun PainRegionAccordion(
    regions: List<PainRegion>,
    selected: List<PainLocationRating>,
    editing: EditingState?,
    onOpenEditor: (locationId: String) -> Unit,
    onSetDraftSeverity: (severity: Int?) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
    onRemove: (locationId: String) -> Unit,
    severityOf: (locationId: String) -> Int?,
    colors: AccordionColors = accordionColorsFromTheme(),
    modifier: Modifier = Modifier,
) {
    fun isSelected(locationId: String) = selected.any { it.locationId == locationId }
    fun countFor(region: PainRegion) = region.locations.count { isSelected(it.id) }
    fun isEditing(locationId: String) = editing?.locationId == locationId

    // Open/closed state per region, seeded open for any region that already
    // has a selection.
    var openRegions by remember {
        mutableStateOf(regions.filter { countFor(it) > 0 }.map { it.id }.toSet())
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(width = 1.dp, color = colors.base300, shape = RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(colors.base100),
    ) {
        regions.forEachIndexed { index, region ->
            val isOpen = openRegions.contains(region.id)

            RegionRow(
                region = region,
                isOpen = isOpen,
                count = countFor(region),
                showDivider = index != regions.lastIndex,
                colors = colors,
                onToggle = {
                    openRegions = if (isOpen) openRegions - region.id else openRegions + region.id
                },
            ) {
                LocationsFlow(
                    locations = region.locations,
                    editing = editing,
                    isSelected = ::isSelected,
                    isEditing = ::isEditing,
                    severityOf = severityOf,
                    onOpenEditor = onOpenEditor,
                    onSetDraftSeverity = onSetDraftSeverity,
                    onCommit = onCommit,
                    onCancel = onCancel,
                    onRemove = onRemove,
                    colors = colors,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// One accordion row: clickable header (region label + count badge + chevron)
// plus an animated expand/collapse of the body.
// ---------------------------------------------------------------------------

@Composable
private fun RegionRow(
    region: PainRegion,
    isOpen: Boolean,
    count: Int,
    showDivider: Boolean,
    colors: AccordionColors,
    onToggle: () -> Unit,
    body: @Composable () -> Unit,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (isOpen) 90f else 0f,
        animationSpec = tween(120),
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "▸",
                color = colors.primary,
                fontSize = 13.sp,
                modifier = Modifier.rotate(chevronRotation),
            )
            Text(
                text = region.label,
                color = colors.baseContent,
                fontWeight = FontWeight.SemiBold,
            )
            if (count > 0) {
                Box(
                    modifier = Modifier
                        .padding(start = 0.dp)
                        .wrapContentWidth(),
                ) {
                    CountBadge(count = count, colors = colors)
                }
            }
        }

        AnimatedVisibility(
            visible = isOpen,
            enter = expandVertically(animationSpec = tween(140)),
            exit = shrinkVertically(animationSpec = tween(120)),
        ) {
            Box(modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                body()
            }
        }

        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.base300)
                    .padding(top = 1.dp), // 1px hairline
            )
        }
    }
}

@Composable
private fun CountBadge(count: Int, colors: AccordionColors) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(colors.primary)
            .widthIn(min = 22.dp)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = count.toString(), color = colors.primaryContent, fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------------------
// The wrapping "chips + inline editor" row. FlowRow naturally wraps chips, and
// the editor is forced onto its own full-width line via fillMaxWidth().
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocationsFlow(
    locations: List<PainLocation>,
    editing: EditingState?,
    isSelected: (String) -> Boolean,
    isEditing: (String) -> Boolean,
    severityOf: (String) -> Int?,
    onOpenEditor: (String) -> Unit,
    onSetDraftSeverity: (Int?) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
    onRemove: (String) -> Unit,
    colors: AccordionColors,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        locations.forEach { loc ->
            if (isEditing(loc.id)) {
                InlineEditor(
                    location = loc,
                    draftSeverity = editing?.severity,
                    onSetDraftSeverity = onSetDraftSeverity,
                    onCommit = onCommit,
                    onCancel = onCancel,
                    colors = colors,
                    // Forces a line break before/after, mimicking flex: 1 1 100%.
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LocationChip(
                    location = loc,
                    selected = isSelected(loc.id),
                    severity = severityOf(loc.id),
                    colors = colors,
                    onClick = { onOpenEditor(loc.id) },
                    onRemove = { onRemove(loc.id) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Collapsed chip. Tapping the body opens the editor; a selected chip also shows
// a trailing "×" that removes (deselects) the location.
// ---------------------------------------------------------------------------

@Composable
private fun LocationChip(
    location: PainLocation,
    selected: Boolean,
    severity: Int?,
    colors: AccordionColors,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val bg = if (selected) colors.primary else colors.base100
    val border = if (selected) colors.primary else colors.base300
    val content = if (selected) colors.primaryContent else colors.baseContent

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(width = 1.dp, color = border, shape = RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = location.label, color = content, fontSize = 14.sp)
        if (selected) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.primaryContent)
                    .widthIn(min = 22.dp)
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = severity?.toString() ?: "—",
                    color = colors.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            // Trailing remove affordance. Its own clickable consumes the tap so
            // the chip body's onClick (open editor) does not also fire.
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onRemove)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "✕", color = colors.primaryContent, fontSize = 12.sp)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Expanded inline editor: label, slider, "None" toggle, x/10 readout,
// circle confirm/cancel buttons. Full row width.
// ---------------------------------------------------------------------------

@Composable
private fun InlineEditor(
    location: PainLocation,
    draftSeverity: Int?,
    onSetDraftSeverity: (Int?) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
    colors: AccordionColors,
    modifier: Modifier = Modifier,
) {
    val isNone = draftSeverity == null
    // Thumb rests at 5 while "None" is active.
    val sliderValue = (draftSeverity ?: 5).toFloat()

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .border(width = 1.dp, color = colors.primary, shape = RoundedCornerShape(999.dp))
            .background(colors.base100)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = location.label,
            color = colors.baseContent,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )

        Slider(
            value = sliderValue,
            onValueChange = { onSetDraftSeverity(it.toInt()) },
            valueRange = 1f..10f,
            steps = 8, // 8 intermediate steps -> 10 discrete positions (1..10)
            modifier = Modifier
                .widthIn(min = 64.dp)
                .weight(1f),
        )

        NoneToggle(
            isNone = isNone,
            colors = colors,
            onClick = { onSetDraftSeverity(if (isNone) 5 else null) },
        )

        Readout(value = draftSeverity, colors = colors)

        CircleButton(
            symbol = "✓",
            background = colors.primary,
            border = colors.primary,
            content = colors.primaryContent,
            onClick = onCommit,
        )
        CircleButton(
            symbol = "✕",
            background = colors.base100,
            border = colors.base300,
            content = colors.baseContent,
            onClick = onCancel,
        )
    }
}

@Composable
private fun NoneToggle(isNone: Boolean, colors: AccordionColors, onClick: () -> Unit) {
    val bg = if (isNone) colors.neutral else colors.base100
    val border = if (isNone) colors.neutral else colors.base300
    val content = if (isNone) colors.neutralContent else colors.muted

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(width = 1.dp, color = border, shape = RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text = "None", color = content, fontSize = 12.sp)
    }
}

@Composable
private fun Readout(value: Int?, colors: AccordionColors) {
    val color = if (value == null) colors.muted else colors.primary
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = value?.toString() ?: "—",
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
        )
        Text(text = "/10", color = colors.muted, fontSize = 11.sp)
    }
}

@Composable
private fun CircleButton(
    symbol: String,
    background: Color,
    border: Color,
    content: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .border(width = 1.dp, color = border, shape = CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = symbol, color = content, fontSize = 14.sp)
    }
}
