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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.res.R
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
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
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
 * stream below. Swiping right, BACK, or an up-swipe from the bottom edge band (CircaEdgeSwipe)
 * dismisses it; scrolling or pulling past the quick-settings top does not.
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
        val panelHeight = quickSettingsHeight(quickSettings.tiles.value.size, screenHeight)
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
        var editing by remember { mutableStateOf(false) }
        LaunchedEffect(editing) { if (!editing) focusRequester.requestFocus() }
        val dragOffset = remember { Animatable(0f) }
        val enter =
            remember { Animatable(if (session.end == CircaTray.End.QUICK_SETTINGS) -1f else 1f) }
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            enter.animateTo(0f, tween(220))
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
                    scope.launch { listState.scrollBy(delta) }
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
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "quick_settings") {
                    QuickSettingsPanel(
                        quickSettings,
                        colors,
                        panelHeight,
                        screenHeight,
                        onClose = close,
                        onEdit = { editing = true },
                    )
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
        if (editing) {
            EditTilesScreen(quickSettings, colors, screenHeight, onDone = { editing = false })
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

        val strokePx = 3.dp.toPx()
        val inset = 2.dp.toPx() + strokePx / 2f
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
 * Splits the tiles into rows of three; when that would leave a single tile in the last row, the
 * first and last rows get two instead (7 tiles = 2-3-2), a honeycomb that fits the circle.
 */
internal fun <T> tileRows(tiles: List<T>): List<List<T>> {
    if (tiles.size >= 4 && tiles.size % 3 == 1) {
        val middle = tiles.subList(2, tiles.size - 2)
        return listOf(tiles.take(2)) + middle.chunked(3) + listOf(tiles.takeLast(2))
    }
    return tiles.chunked(3)
}

/** Grid height for [rowCount] rows. */
private fun gridHeight(rowCount: Int): Dp =
    if (rowCount == 0) 0.dp else TILE_SIZE * rowCount + TILE_GAP * (rowCount - 1)

/**
 * Top of the grid: centred on the first screen when it fits (stock centres its 3 + 3 grid), but a
 * full row of three never starts above [GRID_TOP], where the circle would cut its corners.
 */
private fun gridTop(rows: List<List<*>>, screenHeight: Dp): Dp {
    val centred = (screenHeight - gridHeight(rows.size)) / 2
    val min = if ((rows.firstOrNull()?.size ?: 0) >= 3) GRID_TOP else GRID_TOP_NARROW
    return maxOf(centred, min)
}

/** Height of the quick-settings item: the grid, the phone pill, Edit. */
internal fun quickSettingsHeight(tileCount: Int, screenHeight: Dp): Dp {
    val rows = tileRows(List(tileCount) { it })
    val content =
        gridTop(rows, screenHeight) + gridHeight(rows.size) + PILL_GAP + PILL_HEIGHT + TILE_GAP +
            PILL_HEIGHT + QS_BOTTOM_SPACE
    return maxOf(screenHeight + QS_EXTRA_HEIGHT, content)
}

/** A row of two may start higher: it is narrower than the circle's chord up there. */
private val GRID_TOP_NARROW = 16.dp

private val PILL_HEIGHT = 24.dp
private val PILL_GAP = 4.dp
/**
 * Space under the Edit pill: more than [STREAM_TOP], so when the tray opens at the notifications end
 * (first card at STREAM_TOP) the pills are above the screen instead of peeking over the card.
 */
private val QS_BOTTOM_SPACE = STREAM_TOP + 16.dp

/**
 * Stock's quick-settings grid (decisions.md "Quick settings A"), driven by the user's real tile
 * list: round buttons, three per row, in the order of `sysui_qs_tiles`; then the phone pill and
 * the Edit pill. Tap toggles, long press opens the tile's settings page.
 */
@Composable
private fun QuickSettingsPanel(
    qs: CircaQuickSettings,
    colors: CircaColors,
    height: Dp,
    screenHeight: Dp,
    onClose: () -> Unit,
    onEdit: () -> Unit,
) {
    val tiles = qs.tiles.value
    val rows = tileRows(tiles)
    Box(Modifier.fillMaxWidth().height(height)) {
        Column(
            modifier =
                Modifier.align(Alignment.TopCenter).padding(top = gridTop(rows, screenHeight)),
            verticalArrangement = Arrangement.spacedBy(TILE_GAP),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (row in rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                    for (tile in row) TileButton(tile, qs, colors, onClose)
                }
            }
            Spacer(Modifier.height(PILL_GAP - TILE_GAP))
            PhonePill(
                connected = qs.phoneConnected.value,
                colors = colors,
                onClick = {
                    qs.openBluetoothSettings()
                    onClose()
                },
            )
            SmallPill(
                icon = CircaSymbols.Edit,
                text = stringResource(R.string.circa_edit_tiles),
                colors = colors,
                onClick = onEdit,
            )
        }
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

/** Material Symbols for the tiles we know; other tiles draw their own icon. */
private fun symbolFor(spec: String): ImageVector? =
    when (spec) {
        "dnd",
        "modes_dnd" -> CircaSymbols.DoNotDisturb
        "theater" -> CircaSymbols.Theaters
        "bt" -> CircaSymbols.Bluetooth
        "wifi",
        "internet" -> CircaSymbols.Wifi
        "battery" -> CircaSymbols.BatterySaver
        "circa_brightness" -> CircaSymbols.Brightness
        "circa_settings" -> CircaSymbols.Settings
        "airplane" -> CircaSymbols.Airplane
        "location" -> CircaSymbols.Location
        "flashlight" -> CircaSymbols.FlashlightOn
        "rotation" -> CircaSymbols.ScreenRotation
        "hotspot" -> CircaSymbols.WifiTethering
        "saver" -> CircaSymbols.DataSaverOn
        "dark" -> CircaSymbols.DarkMode
        "screenrecord" -> CircaSymbols.ScreenRecord
        "cast" -> CircaSymbols.Cast
        "nfc" -> CircaSymbols.Nfc
        "night" -> CircaSymbols.Nightlight
        "inversion" -> CircaSymbols.InvertColors
        "qr_code_scanner" -> CircaSymbols.QrCodeScanner
        "mictoggle" -> CircaSymbols.Mic
        "cameratoggle" -> CircaSymbols.CameraVideo
        "hearing_devices" -> CircaSymbols.Hearing
        "alarm" -> CircaSymbols.Alarm
        "caffeine" -> CircaSymbols.Coffee
        "vpn" -> CircaSymbols.VpnKey
        "cell" -> CircaSymbols.SignalCellularAlt
        else -> null
    }

/** A tile's icon: the Material Symbol when we have one, else the tile's own drawable, tinted. */
@Composable
private fun TileIcon(
    spec: String,
    tileIcon: QSTile.Icon?,
    fallback: Drawable?,
    tint: Color,
    size: Dp,
) {
    val symbol = symbolFor(spec)
    if (symbol != null) {
        Icon(imageVector = symbol, contentDescription = null, tint = tint, modifier = Modifier.size(size))
        return
    }
    val context = LocalContext.current
    val bitmap =
        remember(tileIcon, fallback) {
            val d = fallback ?: runCatching { tileIcon?.getDrawable(context) }.getOrNull()
            d?.let { runCatching { it.toBitmap(64, 64).asImageBitmap() }.getOrNull() }
        }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier.size(size),
        )
    } else {
        Box(Modifier.size(size))
    }
}

@Composable
private fun TileButton(tile: CircaTile, qs: CircaQuickSettings, colors: CircaColors, onClose: () -> Unit) {
    when (tile.spec) {
        "battery" -> BatteryButton(tile, qs, colors, onClose)
        "circa_brightness" -> BrightnessButton(tile, colors, onClose)
        else -> {
            val state = tile.state.value
            RoundButton(
                label = tile.label.value.toString(),
                stateDescription = state.name.lowercase(),
                background = state.background(colors),
                onClick = { tile.toggle() },
                onLongClick = {
                    if (tile.longPress()) onClose()
                },
            ) {
                TileIcon(tile.spec, tile.icon.value, null, state.tint(colors), TILE_ICON)
            }
        }
    }
}

@Composable
private fun RoundButton(
    label: String,
    background: Color,
    stateDescription: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
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
                .semantics(mergeDescendants = true) {
                    contentDescription =
                        if (stateDescription != null) "$label, $stateDescription" else label
                },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** Battery: the level under the icon; tap toggles battery saver (accent while on). */
@Composable
private fun BatteryButton(
    tile: CircaTile,
    qs: CircaQuickSettings,
    colors: CircaColors,
    onClose: () -> Unit,
) {
    val saverState = tile.state.value
    // Battery saver is unavailable while charging; the tile still shows the level normally.
    val saver = if (saverState == CircaToggle.UNAVAILABLE) CircaToggle.OFF else saverState
    val level = qs.batteryLevel.intValue
    val tint = saver.tint(colors)
    RoundButton(
        label = tile.label.value.toString(),
        stateDescription = if (level >= 0) "battery $level percent" else null,
        background = saver.background(colors),
        onClick = { if (saverState != CircaToggle.UNAVAILABLE) tile.toggle() },
        onLongClick = {
            if (tile.longPress()) onClose()
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

/**
 * Brightness is a level, not a switch: a ring shows the level (from the tile's "NN%" secondary
 * label), a tap steps to the next level.
 */
@Composable
private fun BrightnessButton(tile: CircaTile, colors: CircaColors, onClose: () -> Unit) {
    val secondary = tile.secondaryLabel.value?.toString()
    val percent = secondary?.removeSuffix("%")?.toIntOrNull()
    val auto = secondary != null && percent == null
    RoundButton(
        label = tile.label.value.toString(),
        stateDescription = secondary,
        background = colors.surface,
        onClick = { tile.toggle() },
        onLongClick = {
            if (tile.longPress()) onClose()
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
            if (percent != null && percent > 0) {
                drawArc(
                    color = colors.accent,
                    startAngle = -90f,
                    sweepAngle = 3.6f * percent.coerceIn(0, 100),
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

/** Stock's pill under the grid: the phone connection, read-only; a tap opens Bluetooth settings. */
@Composable
private fun PhonePill(connected: Boolean, colors: CircaColors, onClick: () -> Unit) {
    SmallPill(
        icon = if (connected) CircaSymbols.SmartphoneOutlined else CircaSymbols.SmartphoneOffOutlined,
        text = if (connected) "Connected" else "Disconnected",
        colors = colors,
        dim = !connected,
        description = if (connected) "Phone connected" else "Phone disconnected",
        onClick = onClick,
    )
}

@Composable
private fun SmallPill(
    icon: ImageVector,
    text: String,
    colors: CircaColors,
    dim: Boolean = false,
    description: String = text,
    onClick: () -> Unit,
) {
    val tint = if (dim) colors.outline else colors.onSurface
    Row(
        modifier =
            Modifier.height(PILL_HEIGHT)
                .clip(CircleShape)
                .background(colors.surface)
                .combinedClickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 9.dp)
                .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        Text(text = text, color = tint, fontSize = 10.sp, maxLines = 1)
    }
}

// ---- edit mode ----------------------------------------------------------------------------------

/**
 * Round edit mode for the tile list: the current tiles in order, each with move-up / move-down /
 * remove, then every tile that can be added with "+". Changes go straight to SystemUI's tile list
 * (CurrentTilesInteractor -> `sysui_qs_tiles`), so they persist and the grid follows.
 */
@Composable
private fun EditTilesScreen(
    qs: CircaQuickSettings,
    colors: CircaColors,
    screenHeight: Dp,
    onDone: () -> Unit,
) {
    val tiles = qs.tiles.value
    val specs = tiles.map { it.spec }
    var available by remember { mutableStateOf<List<CircaAvailableTile>?>(null) }
    LaunchedEffect(specs) { qs.loadAvailableTiles { available = it } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 30.dp, bottom = 70.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
            Modifier.fillMaxSize()
                .background(Color.Black)
                .onRotaryScrollEvent { e ->
                    scope.launch { listState.scrollBy(e.verticalScrollPixels) }
                    true
                }
                .focusRequester(focusRequester)
                .focusable(),
    ) {
        item(key = "title") {
            Text(
                text = stringResource(R.string.circa_edit_tiles),
                color = colors.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        itemsIndexed(tiles, key = { _, t -> "cur:" + t.spec }) { index, t ->
            EditRow(
                spec = t.spec,
                label = t.label.value.toString(),
                tileIcon = t.icon.value,
                icon = null,
                colors = colors,
                modifier = Modifier.edgeTransform(listState, "cur:" + t.spec, screenHeight),
            ) {
                if (index > 0) {
                    EditAction(CircaSymbols.ArrowUpward, "Move up", t.label.value.toString(), colors) { qs.moveTile(index, -1) }
                }
                if (index < tiles.size - 1) {
                    EditAction(CircaSymbols.ArrowDownward, "Move down", t.label.value.toString(), colors) {
                        qs.moveTile(index, 1)
                    }
                }
                EditAction(CircaSymbols.Remove, "Remove", t.label.value.toString(), colors) { qs.removeTile(t.spec) }
            }
        }
        item(key = "add_header") {
            Text(
                text = stringResource(R.string.circa_add_tiles),
                color = colors.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
            )
        }
        items(available ?: emptyList(), key = { "add:" + it.spec }) { a ->
            EditRow(
                spec = a.spec,
                label = a.label.toString(),
                tileIcon = null,
                icon = a.icon,
                colors = colors,
                modifier = Modifier.edgeTransform(listState, "add:" + a.spec, screenHeight),
            ) {
                EditAction(CircaSymbols.Add, "Add", a.label.toString(), colors, accent = true) { qs.addTile(a.spec) }
            }
        }
        item(key = "done") {
            Row(
                modifier =
                    Modifier.padding(top = 10.dp)
                        .height(36.dp)
                        .clip(CircleShape)
                        .background(colors.accent)
                        .combinedClickable(role = Role.Button, onClick = onDone)
                        .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = CircaSymbols.Check,
                    contentDescription = null,
                    tint = colors.onAccent,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.circa_edit_done),
                    color = colors.onAccent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun EditRow(
    spec: String,
    label: String,
    tileIcon: QSTile.Icon?,
    icon: Drawable?,
    colors: CircaColors,
    modifier: Modifier,
    actions: @Composable () -> Unit,
) {
    // Two lines - the name on top, the actions under it - so names are not cut by the buttons.
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surface)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TileIcon(spec, tileIcon, icon, colors.onSurface, 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                color = colors.onSurface,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

@Composable
private fun EditAction(
    icon: ImageVector,
    label: String,
    tileName: String,
    colors: CircaColors,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier.size(30.dp)
                .clip(CircleShape)
                .background(if (accent) colors.accent else colors.surfaceHigh)
                .combinedClickable(role = Role.Button, onClickLabel = label, onClick = onClick)
                .semantics { contentDescription = "$label $tileName" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (accent) colors.onAccent else colors.onSurface,
            modifier = Modifier.size(18.dp),
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
                .semantics(mergeDescendants = true) {
                    if (item.clearable) dismiss { onDismiss(); true }
                    contentDescription =
                        if (redacted) "${item.appName}, notification"
                        else listOfNotNull(item.appName, item.title, item.text).joinToString(", ")
                }
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
internal fun AppIcon(icon: Drawable?, colors: CircaColors) {
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
