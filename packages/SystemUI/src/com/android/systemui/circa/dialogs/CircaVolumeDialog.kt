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

package com.android.systemui.circa.dialogs

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.compose.ComposeInitializer
import com.android.systemui.circa.shade.CircaTray
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.VolumeDialog
import com.android.systemui.plugins.VolumeDialogController
import com.android.systemui.res.R
import com.android.systemui.volume.CsdWarningDialog
import com.android.systemui.volume.SafetyWarningDialog
import java.util.Optional
import javax.inject.Inject
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Circa's volume indicator (audit A08), used instead of the phone's vertical slider panel when the
 * Circa flag is on (VolumeModule.provideVolumeDialog). A round page: a 270 degree arc around the
 * circle (gap at the bottom) that shows the level of the active stream, the stream name and level in
 * the middle, and two big minus/plus buttons. Everything lies inside the inscribed circle.
 *
 * * volume keys / the framework ask for it through [VolumeDialogController.Callbacks.onShowRequested];
 * * the crown adjusts one step per notch while it is shown (the window is focusable for that),
 *   dragging along the arc sets the level, minus/plus step it;
 * * it goes away after [HIDE_MILLIS] without input, on a tap outside the controls, on BACK/screen-off.
 *
 * Safety (headphone loudness) warnings are forwarded to the stock dialogs, which the Circa dialog
 * theme turns into round pages.
 */
@SysUISingleton
class CircaVolumeDialog
@Inject
constructor(
    @Application private val context: Context,
    private val windowManager: WindowManager,
    private val controller: VolumeDialogController,
    private val csdFactory: CsdWarningDialog.Factory,
    private val tray: CircaTray,
    @Main private val handler: Handler,
) : VolumeDialog {
    private var root: RootView? = null
    private var stream by mutableIntStateOf(AudioManager.STREAM_MUSIC)
    private var level by mutableIntStateOf(0)
    private var min by mutableIntStateOf(0)
    private var max by mutableIntStateOf(1)
    private var muted by mutableStateOf(false)
    /** Bumped on every showing and every input: restarts the hide timer. */
    private var activity by mutableIntStateOf(0)
    private var latest: VolumeDialogController.State? = null

    private val callbacks =
        object : VolumeDialogController.Callbacks {
            override fun onShowRequested(reason: Int, keyguardLocked: Boolean, lockTaskModeState: Int) {
                show()
            }

            override fun onDismissRequested(reason: Int) = hide()

            override fun onStateChanged(newState: VolumeDialogController.State) {
                latest = newState
                readStream()
            }

            override fun onLayoutDirectionChanged(layoutDirection: Int) {}

            override fun onConfigurationChanged() {}

            override fun onShowVibrateHint() {}

            override fun onShowSilentHint() {}

            override fun onScreenOff() = hide()

            override fun onShowSafetyWarning(flags: Int) {
                val dialog =
                    object : SafetyWarningDialog(context, controller.audioManager) {
                        override fun cleanUp() {}
                    }
                dialog.show()
            }

            override fun onAccessibilityModeChanged(showA11yStream: Boolean?) {}

            override fun onCaptionComponentStateChanged(isComponentEnabled: Boolean?, fromTooltip: Boolean?) {}

            override fun onCaptionEnabledStateChanged(isEnabled: Boolean?, checkBeforeSwitch: Boolean?) {}

            override fun onShowCsdWarning(csdWarning: Int, durationMs: Int) {
                csdFactory.create(csdWarning, {}, Optional.empty()).show()
            }

            override fun onVolumeChangedFromKey() {}
        }

    override fun init(windowType: Int, callback: VolumeDialog.Callback?) {
        controller.addCallback(callbacks, handler)
        controller.getState()
    }

    override fun destroy() {
        controller.removeCallback(callbacks)
        hide()
    }

    private fun activeStream(): Int {
        val s = latest?.activeStream ?: VolumeDialogController.State.NO_ACTIVE_STREAM
        return if (s == VolumeDialogController.State.NO_ACTIVE_STREAM) AudioManager.STREAM_MUSIC else s
    }

    private fun readStream() {
        val st = latest ?: return
        val s = activeStream()
        val ss = st.states.get(s) ?: return
        stream = s
        min = ss.levelMin
        max = maxOf(ss.levelMax, ss.levelMin + 1)
        level = ss.level
        muted = ss.muted
    }

    private fun show() {
        if (tray.isOpen) return
        readStream()
        val view = root ?: createWindow().also { root = it }
        view.visibility = View.VISIBLE
        activity++
        controller.notifyVisible(true)
    }

    private fun hide() {
        val view = root ?: return
        if (view.visibility != View.VISIBLE) return
        view.visibility = View.GONE
        controller.notifyVisible(false)
    }

    /** Sets the active stream to [target] (clamped), and shows it. */
    private fun changeLevel(target: Int) {
        val clamped = target.coerceIn(min, max)
        level = clamped
        controller.setActiveStream(stream, false)
        controller.setStreamVolume(stream, clamped, true)
        activity++
    }

    private fun scale(): Float = context.resources.displayMetrics.widthPixels / WIDTH_DP

    private fun createWindow(): RootView {
        val view = RootView(context)
        view.visibility = View.GONE
        val lp =
            WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                    // Focusable so the crown's rotary events reach it while it is shown.
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT,
                )
                .apply {
                    title = "CircaVolume"
                    accessibilityTitle = "Volume"
                    gravity = Gravity.CENTER
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
                        CompositionLocalProvider(LocalDensity provides density) { VolumeScreen() }
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

        override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
            if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                if (event.action == android.view.KeyEvent.ACTION_UP) hide()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    private fun streamName(): Int =
        when (stream) {
            AudioManager.STREAM_RING -> R.string.circa_volume_ring
            AudioManager.STREAM_ALARM -> R.string.circa_volume_alarm
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.STREAM_BLUETOOTH_SCO -> R.string.circa_volume_call
            AudioManager.STREAM_NOTIFICATION -> R.string.circa_volume_notification
            AudioManager.STREAM_SYSTEM -> R.string.circa_volume_system
            else -> R.string.circa_volume_media
        }

    @Composable
    private fun VolumeScreen() {
        val accent = remember { tray.accent() }
        val focus = remember { FocusRequester() }
        var crown by remember { mutableStateOf(0f) }
        LaunchedEffect(activity) {
            val seen = activity
            // Every showing: the window was GONE (and unfocused) since the last one.
            runCatching { focus.requestFocus() }
            delay(HIDE_MILLIS)
            if (seen == activity) hide()
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
        val fraction = ((level - min).toFloat() / (max - min)).coerceIn(0f, 1f)
        val name = context.getString(streamName())
        Box(
            Modifier.fillMaxSize()
                .background(Color(0xFF000000))
                .pointerInput(Unit) { detectTapGestures { hide() } }
                .onRotaryScrollEvent { e ->
                    crown += e.verticalScrollPixels
                    val step = (crown / CROWN_PIXELS_PER_STEP).toInt()
                    if (step != 0) {
                        crown -= step * CROWN_PIXELS_PER_STEP
                        changeLevel(level + step)
                    }
                    true
                }
                .focusRequester(focus)
                .focusable()
                .semantics { contentDescription = "$name volume $level of $max" },
            contentAlignment = Alignment.Center,
        ) {
            // The arc: 270 degrees, gap at the bottom, drag along it to set the level.
            Canvas(
                Modifier.size(ARC_DIAMETER_DP.dp).pointerInput(min, max) {
                    fun at(p: Offset) {
                        val c = Offset(size.width / 2f, size.height / 2f)
                        // degrees clockwise from the arc start (135 deg = bottom left), 0..360
                        var deg = Math.toDegrees(atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble())).toFloat()
                        deg = ((deg - START_DEG) % 360f + 360f) % 360f
                        val f = if (deg <= SWEEP_DEG) deg / SWEEP_DEG else if (deg - SWEEP_DEG < (360f - SWEEP_DEG) / 2f) 1f else 0f
                        changeLevel(min + (f * (max - min)).roundToInt())
                    }
                    detectDragGestures(onDragStart = { at(it) }) { change, _ ->
                        change.consume()
                        at(change.position)
                    }
                }
            ) {
                val stroke = ARC_STROKE_DP.dp.toPx()
                val inset = stroke / 2f
                val tl = Offset(inset, inset)
                val sz = Size(size.width - stroke, size.height - stroke)
                drawArc(Color(0xFF3A3B42), START_DEG, SWEEP_DEG, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
                if (fraction > 0f) {
                    drawArc(
                        if (muted) Color(0xFF8F9099) else accent,
                        START_DEG, SWEEP_DEG * fraction, false, tl, sz,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(name, color = Color(0xFFC7C6CD), fontSize = 12.sp)
                Text(
                    if (muted && level == 0) "0" else "$level",
                    color = Color(0xFFE3E3E8),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.size(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepButton("−", "Volume down", accent) { changeLevel(level - 1) }
                    Spacer(Modifier.width(14.dp))
                    StepButton("+", "Volume up", accent) { changeLevel(level + 1) }
                }
            }
        }
    }

    @Composable
    private fun StepButton(label: String, description: String, accent: Color, onClick: () -> Unit) {
        Box(
            Modifier.size(BUTTON_DP.dp)
                .clip(CircleShape)
                .background(Color(0xFF2F3036))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .semantics {
                    contentDescription = description
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = accent, fontSize = 24.sp, fontWeight = FontWeight.Medium)
        }
    }

    companion object {
        /** Design width: 1 dp = 384/200 px on the 384 px panel. */
        private const val WIDTH_DP = 200f
        /** Outer diameter of the arc: 2 x 90 dp = 346 px, 19 px short of the rim. */
        private const val ARC_DIAMETER_DP = 180
        private const val ARC_STROKE_DP = 12f
        private const val START_DEG = 135f
        private const val SWEEP_DEG = 270f
        private const val BUTTON_DP = 40
        private const val CROWN_PIXELS_PER_STEP = 24f
        private const val HIDE_MILLIS = 3000L
    }
}
