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

package com.android.systemui.circa.keyguard

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.circa.shade.CircaTrayDensity
import com.android.systemui.compose.ComposeInitializer
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.policy.BatteryController
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * The watch face the keyguard shows when nothing else can: one opaque black window above the phone
 * keyguard (TYPE_KEYGUARD_DIALOG: above the notification shade that hosts the keyguard and the
 * bouncer, below the Circa tray), drawn by SystemUI itself so it works before the first unlock after
 * boot, when the launcher (credential-encrypted storage) cannot run. It copies the launcher's "big
 * digital" face: time, date and a battery ring. No phone lockscreen content shows through it.
 *
 * Any completed touch asks for the PIN ([onInteract]); the startable shows and hides the window.
 */
@SysUISingleton
class CircaKeyguardFace
@Inject
constructor(
    @Application private val context: Context,
    private val windowManager: WindowManager,
    private val batteryController: BatteryController,
) {
    /** Called when the user touches the face (tap or swipe that was not taken by a gesture). */
    var onInteract: () -> Unit = {}

    private val now = mutableLongStateOf(System.currentTimeMillis())
    private val battery = mutableIntStateOf(-1)
    private val charging = androidx.compose.runtime.mutableStateOf(false)
    private var root: RootView? = null

    private val batteryCallback =
        object : BatteryController.BatteryStateChangeCallback {
            override fun onBatteryLevelChanged(level: Int, pluggedIn: Boolean, isCharging: Boolean) {
                battery.intValue = level
                charging.value = isCharging
            }
        }

    private val timeReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                now.longValue = System.currentTimeMillis()
            }
        }

    val isShown: Boolean
        get() = root?.visibility == View.VISIBLE

    fun show() {
        val view = root ?: createWindow().also { root = it }
        if (view.visibility == View.VISIBLE) return
        now.longValue = System.currentTimeMillis()
        batteryController.addCallback(batteryCallback)
        context.registerReceiver(
            timeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_LOCALE_CHANGED)
            },
        )
        view.visibility = View.VISIBLE
    }

    fun hide() {
        val view = root ?: return
        if (view.visibility != View.VISIBLE) return
        view.visibility = View.GONE
        batteryController.removeCallback(batteryCallback)
        try {
            context.unregisterReceiver(timeReceiver)
        } catch (_: IllegalArgumentException) {}
    }

    private fun createWindow(): RootView {
        val view = RootView(context)
        view.visibility = View.GONE
        val lp =
            WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.OPAQUE,
                )
                .apply {
                    title = "CircaKeyguardFace"
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
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(
                ComposeView(context).apply {
                    setContent {
                        CircaTrayDensity {
                            FaceContent(now.longValue, battery.intValue, charging.value)
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

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            // A completed touch: a tap or any swipe. A gesture another monitor took (the tray's
            // edge swipe pilfers its pointers) arrives as CANCEL and does not count.
            if (event.actionMasked == MotionEvent.ACTION_UP) onInteract()
            return true
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean = onTouchEvent(ev)
    }
}

@Composable
private fun FaceContent(timeMillis: Long, batteryLevel: Int, charging: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val date = Date(timeMillis)
    val is24 = DateFormat.is24HourFormat(context)
    val timeText = DateFormat.format(if (is24) "H:mm" else "h:mm", date).toString()
    val ampm = if (is24) "" else DateFormat.format("a", date).toString().uppercase()
    val dateText =
        DateFormat.format(
                DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMM"),
                date,
            )
            .toString()
    val ink = Color(0xFFE3E3E8)
    val dim = Color(0xFFC7C6CD)
    val accent = Color(0xFF8AB4F8)

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.Bottom) {
                Text(timeText, color = ink, fontSize = 52.sp, fontWeight = FontWeight.Light)
                if (ampm.isNotEmpty()) {
                    Text(
                        " $ampm",
                        color = dim,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            Text(dateText, color = dim, fontSize = 12.sp)
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
            if (batteryLevel >= 0) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val stroke = 4.dp.toPx()
                        val inset = stroke / 2
                        val arcSize = Size(size.width - stroke, size.height - stroke)
                        drawArc(
                            color = Color(0xFF2F3036),
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(stroke, cap = StrokeCap.Round),
                        )
                        drawArc(
                            color = if (charging) Color(0xFF7FD99B) else accent,
                            startAngle = -90f,
                            sweepAngle = 360f * batteryLevel / 100f,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(stroke, cap = StrokeCap.Round),
                        )
                    }
                    Text("$batteryLevel", color = ink, fontSize = 12.sp)
                }
            }
        }
    }
}

