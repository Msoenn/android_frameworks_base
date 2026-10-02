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

import android.content.Context
import android.hardware.input.InputManager
import android.os.Looper
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.InputMonitor
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import com.android.systemui.res.R
import kotlin.math.abs

/**
 * The stock Wear edge swipes, system-wide: a swipe down that starts in the top edge band opens
 * the tray at its quick-settings end, a swipe up that starts in the bottom edge band opens it at
 * its notifications end.
 *
 * A gesture monitor (the mechanism the back gesture uses) sees every touch on the display. Once a
 * vertical edge swipe passes the touch slop it pilfers the pointers, so the app below gets
 * ACTION_CANCEL and never sees the swipe. Horizontal movement first (back gesture, pagers)
 * abandons the candidate.
 *
 * Conflict with the gesture-navigation home swipe: that swipe also starts at the bottom edge and
 * is detected by the recents provider's own monitor (Launcher3 Quickstep). Circa ships without
 * Quickstep (fork-plan A3) and uses the crown for home, so there is nothing to fight on the
 * watch; with Quickstep installed both monitors race and whichever pilfers first wins (this one
 * pilfers at the plain touch slop).
 */
class CircaEdgeSwipe(
    private val context: Context,
    private val inputManager: InputManager,
    private val displayId: Int,
    /** Returns false when the swipe is not taken (e.g. the tray is already open). */
    private val onSwipe: (CircaTray.End) -> Boolean,
) {
    private enum class Candidate { NONE, TOP, BOTTOM }

    private var monitor: InputMonitor? = null
    private var receiver: InputEventReceiver? = null

    private var candidate = Candidate.NONE
    private var downX = 0f
    private var downY = 0f

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val topBand = context.resources.getDimension(R.dimen.circa_shade_edge_top)
    private val bottomBand = context.resources.getDimension(R.dimen.circa_shade_edge_bottom)
    private val windowManager = context.getSystemService(WindowManager::class.java)

    fun start() {
        if (monitor != null) return
        val m = inputManager.monitorGestureInput("circa-shade-edge-swipe", displayId)
        monitor = m
        receiver =
            object : InputEventReceiver(m.inputChannel, Looper.getMainLooper()) {
                override fun onInputEvent(event: InputEvent) {
                    if (event is MotionEvent) onMotionEvent(event)
                    finishInputEvent(event, false)
                }
            }
    }

    fun stop() {
        receiver?.dispose()
        receiver = null
        monitor?.dispose()
        monitor = null
    }

    private fun onMotionEvent(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                val height = windowManager?.maximumWindowMetrics?.bounds?.height() ?: 0
                candidate =
                    when {
                        height <= 0 -> Candidate.NONE
                        downY <= topBand -> Candidate.TOP
                        downY >= height - bottomBand -> Candidate.BOTTOM
                        else -> Candidate.NONE
                    }
            }
            MotionEvent.ACTION_MOVE -> {
                if (candidate == Candidate.NONE) return
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    candidate = Candidate.NONE
                    return
                }
                val triggered =
                    when (candidate) {
                        Candidate.TOP -> dy > touchSlop && dy > abs(dx)
                        Candidate.BOTTOM -> -dy > touchSlop && -dy > abs(dx)
                        Candidate.NONE -> false
                    }
                if (triggered) {
                    val end =
                        if (candidate == Candidate.TOP) {
                            CircaTray.End.QUICK_SETTINGS
                        } else {
                            CircaTray.End.NOTIFICATIONS
                        }
                    candidate = Candidate.NONE
                    if (onSwipe(end)) monitor?.pilferPointers()
                }
            }
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> candidate = Candidate.NONE
        }
    }
}
