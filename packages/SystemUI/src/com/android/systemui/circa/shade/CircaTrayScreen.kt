/*
 * Copyright (C) 2026 The Circa Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.circa.shade

import android.graphics.drawable.Drawable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Layout constants, ported from the launcher prototype (apps/launcher ui/Tray.kt), which were
// measured against stock Wear OS 5 screenshots on the 384 x 384 / 320 dpi (192 dp) round panel.

/** Stock's quick-settings buttons are ~53 dp circles with ~5 dp between them. */
private val TILE_SIZE = 52.dp
private val TILE_GAP = 5.dp
private val TILE_ICON = 26.dp

/**
 * Vertical offset of the panel content. The prototype's +18 dp was measured inside Wear's
 * TransformingLazyColumn, which centres the (screen + 48 dp) panel, i.e. starts it 24 dp above the
 * screen top; this plain column starts it at the top, so the same picture needs 18 - 24 dp.
 */
private val QS_SHIFT = (-6).dp
private val QS_EXTRA_HEIGHT = 48.dp

/** Top of the 3 + 3 grid; the phone pill sits right under it, inside the circle's bottom chord. */
private val GRID_TOP = 52.dp + QS_SHIFT

/** Where the first card's top sits when the tray opens at the notifications end. */
private val STREAM_TOP = 40.dp
private val STREAM_BOTTOM_SPACE = 30.dp
private val STREAM_SHORT_BOTTOM_SPACE = 120.dp

/** Fraction of the width a horizontal drag must cover to dismiss the tray / a card. */
private const val DISMISS_FRACTION = 0.25f
private const val CARD_DISMISS_FRACTION = 0.35f
private const val SETTLE_MILLIS = 180

/** Pulling down this far past the quick-settings top dismisses the tray (stock behaviour). */
private val PULL_DISMISS = 56.dp

/**
 * The tray is laid out for a 200 dp wide screen whatever the system density is (the watch runs
 * `wm density 192`, i.e. 320 dp across, for phone apps), so its geometry matches stock Wear and the
 * launcher prototype, which uses the same rule (apps/launcher model/Density.kt).
 */
private const val TARGET_WIDTH_DP = 200f

@Composable
internal fun CircaTrayDensity(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val base = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val density =
            if (widthPx > 0f) Density(widthPx / TARGET_WIDTH_DP, base.fontScale) else base
        CompositionLocalProvider(LocalDensity provides density) { content() }
    }
}

/** The stock Wear dark palette (Wear Material 3 defaults), with the configurable accent. */
internal class CircaColors(val accent: Color) {
    val onAccent = Color(0xFF1B1B21)
    val surfaceLow = Color(0xFF1C1D21)
    val surface = Color(0xFF2F3036)
    val surfaceHigh = Color(0xFF3A3B42)
    val onSurface = Color(0xFFE3E3E8)
    val onSurfaceVariant = Color(0xFFC7C6CD)
    val outline = Color(0xFF8F9099)
    val outlineVariant = Color(0xFF46464C)
    val accentContainer = lerp(surface, accent, 0.30f)
}

/**
 * The tray: one vertically scrolling column, quick settings at the top and the notification
 * stream below. Swiping right (or BACK), scrolling past the quick-settings top with the crown,
 * or pulling down past it by touch, dismisses it.
 */
@Composable
internal fun CircaTrayScreen(
    session: CircaTray.Session,
    quickSettings: CircaQuickSettings,
    notifications: CircaNotifications,
    onClose: () -> Unit,
) {
    val colors = remember(session.accent) { CircaColors(session.accent) }
    val close by rememberUpdatedState(onClose)
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val screenHeight = maxHeight
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val panelHeight = screenHeight + QS_EXTRA_HEIGHT
        val startOffset =
            if (session.end == CircaTray.End.NOTIFICATIONS) {
                with(density) { (panelHeight - STREAM_TOP).roundToPx() }
            } else {
                0
            }
        val listState =
            rememberLazyListState(
                initialFirstVisibleItemIndex = 0,
                initialFirstVisibleItemScrollOffset = startOffset,
            )
        val focusRequester = remember { FocusRequester() }
        val dragOffset = remember { Animatable(0f) }
        val enter =
            remember { Animatable(if (session.end == CircaTray.End.QUICK_SETTINGS) -1f else 1f) }
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            enter.animateTo(0f, tween(220))
        }

        val pullDismissPx = with(density) { PULL_DISMISS.toPx() }
        val pullToDismiss = remember {
            object : NestedScrollConnection {
                var pulled = 0f

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source != NestedScrollSource.UserInput) return Offset.Zero
                    pulled = if (available.y > 0f) pulled + available.y else 0f
                    if (pulled > pullDismissPx) {
                        pulled = 0f
                        close()
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    pulled = 0f
                    return Velocity.Zero
                }
            }
        }

        val items = notifications.items.value
        val now = rememberClock()

        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    translationY = enter.value * size.height * 0.2f
                    alpha = 1f - abs(enter.value)
                }
                .offset { IntOffset(dragOffset.value.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state =
                        rememberDraggableState { delta ->
                            scope.launch {
                                dragOffset.snapTo((dragOffset.value + delta).coerceAtLeast(0f))
                            }
                        },
                    onDragStopped = {
                        if (dragOffset.value >= screenWidthPx * DISMISS_FRACTION) {
                            dragOffset.animateTo(screenWidthPx, tween(SETTLE_MILLIS))
                            close()
                        } else {
                            dragOffset.animateTo(0f, tween(SETTLE_MILLIS))
                        }
                    },
                )
                .onRotaryScrollEvent { event ->
                    val delta = event.verticalScrollPixels
                    if (delta < 0f && !listState.canScrollBackward) {
                        close()
                    } else {
                        scope.launch { listState.scrollBy(delta) }
                    }
                    true
                }
                .focusRequester(focusRequester)
                .focusable()
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize().nestedScroll(pullToDismiss),
            ) {
                item(key = "quick_settings") {
                    QuickSettingsPanel(quickSettings, colors, panelHeight, onClose = close)
                }
                if (items.isEmpty()) {
                    item(key = "stream_empty") {
                        Box(
                            Modifier.fillMaxWidth().height(60.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "No notifications",
                                color = colors.onSurfaceVariant,
                                fontSize = 14.sp,
                            )
                        }
                    }
                } else {
                    items(items, key = { it.key }) { item ->
                        NotificationCard(
                            item = item,
                            redacted = session.redacted,
                            nowMillis = now,
                            colors = colors,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .edgeTransform(listState, item.key, screenHeight),
                            onOpen = {
                                notifications.open(item)
                                close()
                            },
                            onDismiss = { notifications.dismiss(item) },
                        )
                    }
                }
                item(key = "stream_bottom") {
                    Box(
                        Modifier.height(
                            if (items.size <= 1) STREAM_SHORT_BOTTOM_SPACE else STREAM_BOTTOM_SPACE
                        )
                    )
                }
            }
            ScrollIndicator(listState, colors, Modifier.fillMaxSize())
        }
    }
}

/**
 * Cards shrink and fade as they run into the round bezel at the top and bottom of the screen,
 * the way Wear's TransformingLazyColumn does it, instead of being clipped by the circle.
 */
private fun Modifier.edgeTransform(state: LazyListState, key: Any, viewport: Dp): Modifier =
    graphicsLayer {
        val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
            ?: return@graphicsLayer
        val viewportPx = viewport.toPx()
        val zone = viewportPx * 0.22f
        val top = info.offset.toFloat()
        val bottom = top + info.size
        val intoTop = zone - top
        val intoBottom = bottom - (viewportPx - zone)
        val into = max(intoTop, intoBottom).coerceAtLeast(0f)
        val f = (into / (zone + info.size * 0.5f)).coerceIn(0f, 1f)
        val scale = 1f - 0.22f * f
        scaleX = scale
        scaleY = scale
        alpha = 1f - 0.7f * f
        transformOrigin =
            if (intoTop > intoBottom) TransformOrigin(0.5f, 1f) else TransformOrigin(0.5f, 0f)
    }

/** Stock's curved scroll indicator on the right edge of the circle. */
@Composable
private fun ScrollIndicator(state: LazyListState, colors: CircaColors, modifier: Modifier) {
    Canvas(modifier) {
        val info = state.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) return@Canvas
        val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
        val averageSize = visible.sumOf { it.size }.toFloat() / visible.size
        val total = max(viewport, averageSize * info.totalItemsCount)
        val first = visible.first()
        val scrolled = first.index * averageSize - first.offset
        val visibleFraction = (viewport / total).coerceIn(0.1f, 1f)
        val position = (scrolled / max(1f, total - viewport)).coerceIn(0f, 1f)

        val strokePx = 4.dp.toPx()
        val inset = 6.dp.toPx() + strokePx / 2f
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        val topLeft = Offset(inset, inset)
        val sweepTotal = 50f
        val start = -sweepTotal / 2f
        drawArc(
            color = colors.outlineVariant,
            startAngle = start,
            sweepAngle = sweepTotal,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(strokePx, cap = StrokeCap.Round),
        )
        val thumb = sweepTotal * visibleFraction
        drawArc(
            color = colors.onSurface,
            startAngle = start + (sweepTotal - thumb) * position,
            sweepAngle = thumb,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(strokePx, cap = StrokeCap.Round),
        )
    }
}

// ---- quick settings -----------------------------------------------------------------------------

/**
 * Stock's quick-settings grid, fixed (decisions.md "Quick settings A"): a status row on top
 * (airplane, location, auto brightness; tappable), then 3 + 3 round buttons that all sit inside
 * the circle - DND, Bluetooth, Wi-Fi; battery (% inside, tap = battery saver), brightness (a
 * level ring, tap = next step), Settings - and the phone-connection pill.
 * Long press on a toggle opens its settings page.
 */
@Composable
private fun QuickSettingsPanel(
    qs: CircaQuickSettings,
    colors: CircaColors,
    height: Dp,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(height)) {
        Row(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 22.dp + QS_SHIFT),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusPip(CircaSymbols.Airplane, "Airplane mode", qs.airplane.state.value, colors) {
                qs.airplane.toggle()
            }
            StatusPip(CircaSymbols.Location, "Location", qs.location.state.value, colors) {
                qs.location.toggle()
            }
            StatusPip(
                CircaSymbols.BrightnessAuto,
                "Auto brightness",
                if (qs.autoBrightness.value) CircaToggle.ON else CircaToggle.OFF,
                colors,
            ) {
                qs.toggleAutoBrightness()
            }
        }
        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = GRID_TOP),
            verticalArrangement = Arrangement.spacedBy(TILE_GAP),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                TileButton(qs.dnd, CircaSymbols.DoNotDisturb, "Do Not Disturb", colors, onClose)
                TileButton(qs.bluetooth, CircaSymbols.Bluetooth, "Bluetooth", colors, onClose)
                TileButton(qs.wifi, CircaSymbols.Wifi, "Wi-Fi", colors, onClose)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                BatteryButton(qs, colors, onClose)
                BrightnessButton(qs, colors, onClose)
                RoundButton(
                    icon = CircaSymbols.Settings,
                    label = "Settings",
                    background = colors.surface,
                    tint = colors.onSurface,
                    onClick = {
                        qs.openSettings()
                        onClose()
                    },
                )
            }
        }
        PhonePill(
            connected = qs.phoneConnected.value,
            colors = colors,
            modifier =
                Modifier.align(Alignment.TopCenter)
                    .padding(top = GRID_TOP + TILE_SIZE * 2 + TILE_GAP + 4.dp),
            onClick = {
                qs.openBluetoothSettings()
                onClose()
            },
        )
    }
}

private fun CircaToggle.background(c: CircaColors): Color =
    when (this) {
        CircaToggle.ON -> c.accent
        CircaToggle.OFF -> c.surface
        CircaToggle.UNAVAILABLE -> c.surfaceLow
    }

private fun CircaToggle.tint(c: CircaColors): Color =
    when (this) {
        CircaToggle.ON -> c.onAccent
        CircaToggle.OFF -> c.onSurface
        CircaToggle.UNAVAILABLE -> c.outlineVariant
    }

@Composable
private fun TileButton(
    tile: CircaTile,
    icon: ImageVector,
    label: String,
    colors: CircaColors,
    onClose: () -> Unit,
) {
    val state = tile.state.value
    RoundButton(
        icon = icon,
        label = label,
        stateDescription = state.name.lowercase(),
        background = state.background(colors),
        tint = state.tint(colors),
        enabled = tile.tile != null,
        onClick = { tile.toggle() },
        onLongClick = {
            tile.openSettings()
            onClose()
        },
    )
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    label: String,
    background: Color,
    tint: Color,
    stateDescription: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier =
            Modifier.size(TILE_SIZE)
                .clip(CircleShape)
                .background(background)
                .combinedClickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClickLabel = label,
                    onLongClick = onLongClick,
                    onClick = onClick,
                )
                .semantics {
                    contentDescription =
                        if (stateDescription != null) "$label, $stateDescription" else label
                },
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) {
            content()
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(TILE_ICON),
            )
        }
    }
}

/** Battery: the level as text under the icon; tap toggles battery saver (accent while on). */
@Composable
private fun BatteryButton(qs: CircaQuickSettings, colors: CircaColors, onClose: () -> Unit) {
    val saverState = qs.batterySaver.state.value
    // Battery saver is unavailable while charging; the tile still shows the level normally.
    val saver = if (saverState == CircaToggle.UNAVAILABLE) CircaToggle.OFF else saverState
    val level = qs.batteryLevel.intValue
    val tint = saver.tint(colors)
    RoundButton(
        icon = CircaSymbols.Battery,
        label = "Battery saver",
        stateDescription = if (level >= 0) "battery $level percent" else null,
        background = saver.background(colors),
        tint = tint,
        onClick = { if (saverState != CircaToggle.UNAVAILABLE) qs.batterySaver.toggle() },
        onLongClick = {
            qs.openBatterySettings()
            onClose()
        },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector =
                    if (saver == CircaToggle.ON) CircaSymbols.BatterySaver else CircaSymbols.Battery,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = if (level >= 0) "$level%" else " ",
                color = tint,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/** Brightness is a level, not a switch: a ring shows the level, a tap steps to the next one. */
@Composable
private fun BrightnessButton(qs: CircaQuickSettings, colors: CircaColors, onClose: () -> Unit) {
    val auto = qs.autoBrightness.value
    val fraction = qs.brightness.intValue / CircaQuickSettings.BRIGHTNESS_MAX.toFloat()
    RoundButton(
        icon = CircaSymbols.Brightness,
        label = "Brightness",
        stateDescription = if (auto) "automatic" else "${(fraction * 100).roundToInt()} percent",
        background = colors.surface,
        tint = colors.onSurface,
        onClick = { qs.cycleBrightness() },
        onLongClick = {
            qs.openDisplaySettings()
            onClose()
        },
    ) {
        Canvas(Modifier.size(TILE_SIZE)) {
            val w = 3.dp.toPx()
            val arcSize = Size(size.width - w, size.height - w)
            val topLeft = Offset(w / 2f, w / 2f)
            drawArc(
                color = colors.outlineVariant,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(w),
            )
            if (!auto && fraction > 0f) {
                drawArc(
                    color = colors.accent,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(w, cap = StrokeCap.Round),
                )
            }
        }
        Icon(
            imageVector = if (auto) CircaSymbols.BrightnessAuto else CircaSymbols.Brightness,
            contentDescription = null,
            tint = colors.onSurface,
            modifier = Modifier.size(TILE_ICON),
        )
    }
}

/** A small status pill of the top row: lit in the accent while on; a tap toggles it. */
@Composable
private fun StatusPip(
    icon: ImageVector,
    label: String,
    state: CircaToggle,
    colors: CircaColors,
    onClick: () -> Unit,
) {
    val on = state == CircaToggle.ON
    Box(
        modifier =
            Modifier.size(width = 30.dp, height = 20.dp)
                .clip(CircleShape)
                .background(if (on) colors.accentContainer else colors.surfaceLow)
                .combinedClickable(
                    enabled = state != CircaToggle.UNAVAILABLE,
                    role = Role.Button,
                    onClick = onClick,
                )
                .semantics { contentDescription = if (on) "$label on" else "$label off" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (on) colors.accent else colors.outline,
            modifier = Modifier.size(13.dp),
        )
    }
}

/** Stock's pill under the grid: the phone connection, read-only; a tap opens Bluetooth settings. */
@Composable
private fun PhonePill(
    connected: Boolean,
    colors: CircaColors,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val tint = if (connected) colors.onSurface else colors.outline
    Row(
        modifier =
            modifier
                .height(24.dp)
                .clip(CircleShape)
                .background(colors.surface)
                .combinedClickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 9.dp)
                .semantics {
                    contentDescription = if (connected) "Phone connected" else "Phone disconnected"
                },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector =
                if (connected) {
                    CircaSymbols.SmartphoneOutlined
                } else {
                    CircaSymbols.SmartphoneOffOutlined
                },
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = if (connected) "Connected" else "Disconnected",
            color = tint,
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

// ---- notifications ------------------------------------------------------------------------------

/**
 * One notification card in stock's shape (Wear Material 3 AppCard): app icon, app name and age on
 * the first line, the title in the accent colour and up to two lines of text. Tap opens it; a
 * horizontal swipe dismisses it unless it is not clearable (ongoing).
 */
@Composable
private fun NotificationCard(
    item: CircaNotification,
    redacted: Boolean,
    nowMillis: Long,
    colors: CircaColors,
    modifier: Modifier,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val dismissPx = with(density) { 170.dp.toPx() } * CARD_DISMISS_FRACTION
    val dragOffset = remember(item.key) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Column(
        modifier =
            modifier
                .offset { IntOffset(dragOffset.value.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    enabled = item.clearable,
                    state =
                        rememberDraggableState { delta ->
                            scope.launch { dragOffset.snapTo(dragOffset.value + delta) }
                        },
                    onDragStopped = {
                        if (abs(dragOffset.value) >= dismissPx) {
                            onDismiss()
                        } else {
                            dragOffset.animateTo(0f, tween(SETTLE_MILLIS))
                        }
                    },
                )
                .clip(RoundedCornerShape(26.dp))
                .background(colors.surface)
                .combinedClickable(role = Role.Button, onClick = onOpen)
                .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(item.icon, colors)
            Spacer(Modifier.width(8.dp))
            Text(
                text = item.appName.toString(),
                color = colors.onSurface,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = formatAge(nowMillis, item.postTimeMillis),
                color = colors.onSurfaceVariant,
                fontSize = 14.sp,
                maxLines = 1,
            )
        }
        if (redacted) {
            Text(
                text = "Unlock to view",
                color = colors.onSurfaceVariant,
                fontSize = 15.sp,
                maxLines = 1,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            item.title?.let {
                Text(
                    text = it.toString(),
                    color = colors.accent,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            item.text?.let {
                Text(
                    text = it.toString(),
                    color = colors.onSurface,
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The notification's small icon on a light disc, as stock's cards show it. */
@Composable
private fun AppIcon(icon: Drawable?, colors: CircaColors) {
    val bitmap =
        remember(icon) {
            icon?.let { runCatching { it.toBitmap(48, 48).asImageBitmap() }.getOrNull() }
        }
    Box(
        modifier = Modifier.size(24.dp).clip(CircleShape).background(colors.onSurfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                colorFilter = ColorFilter.tint(colors.surface),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** "Now", "5m", "2h", "3d", as stock labels a card's age. */
private fun formatAge(nowMillis: Long, postTimeMillis: Long): String {
    val age = ((nowMillis - postTimeMillis) / 1000L).coerceAtLeast(0L)
    return when {
        age < 60 -> "Now"
        age < 3600 -> "${age / 60}m"
        age < 86400 -> "${age / 3600}h"
        else -> "${age / 86400}d"
    }
}

/** Wall clock for the cards' age labels, refreshed while the tray is open. */
@Composable
private fun rememberClock(): Long =
    produceState(initialValue = System.currentTimeMillis()) {
            while (true) {
                delay(30_000)
                value = System.currentTimeMillis()
            }
        }
        .value
