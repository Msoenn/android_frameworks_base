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

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.compose.ComposeInitializer
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Circa's heads-up ("peek"), the stock Wear behaviour (audit A03): a new notification slides a
 * round card up from the bottom of the face for a few seconds.
 *
 * * tap: opens the notification (like tapping its card in the tray);
 * * swipe up: opens the notifications screen;
 * * swipe down or sideways: dismisses the peek only (the notification stays in the tray);
 * * nothing: the card slides away after [HIDE_MILLIS].
 *
 * Which notifications peek ([shouldPeek]): screen interactive (not asleep, not AOD), theater mode
 * off, tray closed, importance at least DEFAULT (Wear has no sound-only tier, and the WatchLink
 * mirror channel is DEFAULT), not an ongoing or group-summary or alert-once update, and not
 * filtered by Do Not Disturb (`matchesInterruptionFilter` / the peek-suppressed visual effect).
 * Content is redacted like the tray's while a secure keyguard is showing.
 *
 * Its own window (not the tray's) so it never takes focus from the app under it.
 */
@SysUISingleton
class CircaHeadsUp
@Inject
constructor(
    @Application private val context: Context,
    private val windowManager: WindowManager,
    private val tray: CircaTray,
    private val notifications: CircaNotifications,
    private val notifPipeline: NotifPipeline,
    private val broadcastDispatcher: BroadcastDispatcher,
    @Main private val mainExecutor: Executor,
) {
    /** One showing of the card; a new id restarts the slide-in and the timer. */
    private data class Peek(val id: Int, val item: CircaNotification, val redacted: Boolean, val accent: Color)

    private val peek = mutableStateOf<Peek?>(null)
    private var counter = 0
    private var root: RootView? = null
    private val lastPeekMillis = HashMap<String, Long>()

    private val listener =
        object : NotifCollectionListener {
            override fun onEntryAdded(entry: NotificationEntry) = consider(entry, update = false)

            override fun onEntryUpdated(entry: NotificationEntry) = consider(entry, update = true)

            override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
                lastPeekMillis.remove(entry.key)
                if (peek.value?.item?.key == entry.key) hide()
            }
        }

    private val screenOffReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = hide()
        }

    fun start() {
        notifPipeline.addCollectionListener(listener)
        broadcastDispatcher.registerReceiver(
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            mainExecutor,
        )
    }

    val isShowing: Boolean
        get() = peek.value != null

    private fun consider(entry: NotificationEntry, update: Boolean) {
        if (!shouldPeek(entry, update)) return
        lastPeekMillis[entry.key] = SystemClock.uptimeMillis()
        show(
            Peek(
                id = ++counter,
                item = notifications.toItem(entry),
                redacted = notifications.isRedacted(),
                accent = tray.accent(),
            )
        )
    }

    internal fun shouldPeek(entry: NotificationEntry, update: Boolean): Boolean {
        val n = entry.sbn.notification
        val r = entry.ranking
        val power = context.getSystemService(PowerManager::class.java)
        if (tray.isOpen) return false
        if (!power.isInteractive) return false
        if (Settings.Global.getInt(context.contentResolver, Settings.Global.THEATER_MODE_ON, 0) != 0) {
            return false
        }
        if (r.importance < NotificationManager.IMPORTANCE_DEFAULT) return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (n.suppressAlertingDueToGrouping()) return false
        if (r.isSuspended || !r.matchesInterruptionFilter()) return false
        if (r.suppressedVisualEffects and NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK != 0) {
            return false
        }
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0 &&
            r.importance < NotificationManager.IMPORTANCE_HIGH) {
            return false
        }
        if (update) {
            if (n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0) return false
            val last = lastPeekMillis[entry.key]
            if (last != null && SystemClock.uptimeMillis() - last < UPDATE_QUIET_MILLIS) return false
        }
        return true
    }

    private fun show(p: Peek) {
        val view = root ?: createWindow().also { root = it }
        peek.value = p
        view.visibility = View.VISIBLE
    }

    fun hide() {
        if (peek.value == null) return
        peek.value = null
        root?.visibility = View.GONE
    }

    private fun scale(): Float = context.resources.displayMetrics.widthPixels / WIDTH_DP

    private fun createWindow(): RootView {
        val view = RootView(context)
        view.visibility = View.GONE
        val lp =
            WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    (scale() * WINDOW_HEIGHT_DP).roundToInt(),
                    WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT,
                )
                .apply {
                    title = "CircaHeadsUp"
                    accessibilityTitle = "Notification"
                    gravity = Gravity.BOTTOM
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    fitInsetsTypes = 0
                    setTrustedOverlay()
                }
        windowManager.addView(view, lp)
        return view
    }

    @SuppressLint("ViewConstructor")
    private inner class RootView(context: Context) : FrameLayout(context) {
        init {
            addView(
                ComposeView(context).apply {
                    setContent {
                        val density = Density(scale(), LocalDensity.current.fontScale)
                        CompositionLocalProvider(LocalDensity provides density) {
                            val current by peek
                            current?.let { p ->
                                key(p.id) {
                                    HeadsUpCard(
                                        peek = p,
                                        onOpen = {
                                            notifications.open(p.item)
                                            hide()
                                        },
                                        onOpenTray = {
                                            hide()
                                            tray.open(CircaTray.Page.NOTIFICATIONS)
                                        },
                                        onGone = ::hide,
                                        trayOpen = { tray.isOpen },
                                    )
                                }
                            }
                        }
                    }
                }
            )
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            ComposeInitializer.onAttachedToWindow(this)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            ComposeInitializer.onDetachedFromWindow(this)
        }
    }

    @Composable
    private fun HeadsUpCard(
        peek: Peek,
        onOpen: () -> Unit,
        onOpenTray: () -> Unit,
        onGone: () -> Unit,
        trayOpen: () -> Boolean,
    ) {
        val colors = remember(peek.accent) { CircaColors(peek.accent) }
        val item = peek.item
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        val hideBelow = with(density) { (CARD_HEIGHT_DP + BOTTOM_MARGIN_DP + 8).dp.toPx() }
        val slide = remember { Animatable(hideBelow) }
        val dragX = remember { Animatable(0f) }
        var touching by remember { mutableStateOf(false) }
        val onGoneNow by rememberUpdatedState(onGone)
        val onOpenTrayNow by rememberUpdatedState(onOpenTray)
        val upPx = with(density) { SWIPE_UP_DP.dp.toPx() }
        val downPx = with(density) { SWIPE_DOWN_DP.dp.toPx() }
        val sidePx = with(density) { SWIPE_SIDE_DP.dp.toPx() }

        LaunchedEffect(Unit) { slide.animateTo(0f, tween(SLIDE_MILLIS, easing = FastOutSlowInEasing)) }
        // The timer runs only while the finger is up; it restarts after a cancelled gesture.
        LaunchedEffect(touching) {
            if (touching) return@LaunchedEffect
            kotlinx.coroutines.delay(HIDE_MILLIS)
            slide.animateTo(hideBelow, tween(SLIDE_MILLIS))
            onGoneNow()
        }
        LaunchedEffect(Unit) {
            snapshotFlow { trayOpen() }.collect { if (it) onGoneNow() }
        }

        val description =
            if (peek.redacted) "${item.appName}, notification"
            else listOfNotNull(item.appName, item.title, item.text).joinToString(", ")

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                modifier =
                    Modifier.padding(bottom = BOTTOM_MARGIN_DP.dp)
                        .offset { IntOffset(dragX.value.roundToInt(), slide.value.roundToInt()) }
                        .width(CARD_WIDTH_DP.dp)
                        .height(CARD_HEIGHT_DP.dp)
                        .clip(RoundedCornerShape(CARD_RADIUS_DP.dp))
                        .background(colors.surface)
                        .pointerInput(Unit) {
                            var totalX = 0f
                            var totalY = 0f
                            detectDragGestures(
                                onDragStart = {
                                    touching = true
                                    totalX = 0f
                                    totalY = 0f
                                },
                                onDragEnd = {
                                    touching = false
                                    scope.launch {
                                        when {
                                            abs(totalY) >= abs(totalX) && totalY <= -upPx -> {
                                                onOpenTrayNow()
                                            }
                                            abs(totalY) >= abs(totalX) && totalY >= downPx -> {
                                                slide.animateTo(hideBelow, tween(SLIDE_MILLIS))
                                                onGoneNow()
                                            }
                                            abs(totalX) >= abs(totalY) && abs(totalX) >= sidePx -> {
                                                dragX.animateTo(
                                                    if (totalX > 0) hideBelow * 2 else -hideBelow * 2,
                                                    tween(SLIDE_MILLIS),
                                                )
                                                onGoneNow()
                                            }
                                            else -> {
                                                launch { dragX.animateTo(0f, tween(150)) }
                                                slide.animateTo(0f, tween(150))
                                            }
                                        }
                                    }
                                },
                                onDragCancel = {
                                    touching = false
                                    scope.launch {
                                        launch { dragX.animateTo(0f, tween(150)) }
                                        slide.animateTo(0f, tween(150))
                                    }
                                },
                            ) { change, amount ->
                                change.consume()
                                totalX += amount.x
                                totalY += amount.y
                                scope.launch {
                                    if (abs(totalY) >= abs(totalX)) {
                                        // Follow a downward drag; an upward one only a little.
                                        slide.snapTo((slide.value + amount.y).coerceAtLeast(-upPx / 2))
                                    } else {
                                        dragX.snapTo(dragX.value + amount.x)
                                    }
                                }
                            }
                        }
                        .combinedClickable(role = Role.Button, onClick = onOpen)
                        .semantics(mergeDescendants = true) { contentDescription = description }
                        .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(item.icon, colors)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = item.appName.toString(),
                        color = colors.onSurface,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (peek.redacted) {
                    Text(
                        text = "Unlock to view",
                        color = colors.onSurfaceVariant,
                        fontSize = 14.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    item.title?.let {
                        Text(
                            text = it.toString(),
                            color = colors.accent,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    item.text?.let {
                        Text(
                            text = it.toString(),
                            color = colors.onSurface,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    private companion object {
        /** The card is laid out for a 200 dp wide round screen, like the tray (CircaTrayDensity). */
        const val WIDTH_DP = 200f
        const val CARD_WIDTH_DP = 164
        const val CARD_HEIGHT_DP = 100
        const val CARD_RADIUS_DP = 40
        /** The circle is only ~125 dp wide this far up from the bezel; see the corner maths in shade.md. */
        const val BOTTOM_MARGIN_DP = 20
        const val WINDOW_HEIGHT_DP = CARD_HEIGHT_DP + BOTTOM_MARGIN_DP + 6
        const val SWIPE_UP_DP = 28
        const val SWIPE_DOWN_DP = 24
        const val SWIPE_SIDE_DP = 44
        const val SLIDE_MILLIS = 260
        const val HIDE_MILLIS = 5000L
        /** An update of a notification that already peeked must wait this long to peek again. */
        const val UPDATE_QUIET_MILLIS = 10_000L
    }
}
