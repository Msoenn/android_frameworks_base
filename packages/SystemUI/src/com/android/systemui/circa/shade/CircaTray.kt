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
import android.content.Context
import android.graphics.PixelFormat
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.compose.ComposeInitializer
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import javax.inject.Inject

/**
 * The Wear tray: one full-screen window above the keyguard and the (disabled) phone shade, holding
 * a Compose column with quick settings at the top and the notification stream below them
 * (research/wear-reference-tour.md §5). [open] shows it scrolled to either end; [close] hides it.
 *
 * The window is added once, on the first [open], and then only shown and hidden (root view
 * visibility), so opening is cheap. It is focusable while shown, so the crown's rotary events
 * and BACK (the edge back gesture) reach it.
 */
@SysUISingleton
class CircaTray
@Inject
constructor(
    @Application private val context: Context,
    private val windowManager: WindowManager,
    val quickSettings: CircaQuickSettings,
    val notifications: CircaNotifications,
    private val falsingCollector: FalsingCollector,
) {
    enum class End {
        QUICK_SETTINGS,
        NOTIFICATIONS,
    }

    /** One opening of the tray; a new id resets the scroll position and the enter animation. */
    data class Session(val end: End, val id: Int, val accent: Color, val redacted: Boolean)

    private val session = mutableStateOf<Session?>(null)
    private var sessionCounter = 0
    private var root: RootView? = null

    val isOpen: Boolean
        get() = session.value != null

    /** When the tray last opened (uptime), for ignoring the window's own side effects. */
    var openedAtMillis = 0L
        private set

    fun init() {
        quickSettings.init()
        notifications.init()
    }

    fun open(end: End) {
        val view = root ?: createWindow().also { root = it }
        openedAtMillis = SystemClock.uptimeMillis()
        session.value =
            Session(
                end = end,
                id = ++sessionCounter,
                accent = accent(),
                redacted = notifications.isRedacted(),
            )
        quickSettings.setListening(true)
        view.visibility = View.VISIBLE
    }

    fun close() {
        if (session.value == null) return
        session.value = null
        root?.visibility = View.GONE
        quickSettings.setListening(false)
    }

    private fun accent(): Color {
        val override =
            Settings.Secure.getInt(context.contentResolver, ACCENT_SETTING, 0)
        return Color(if (override != 0) override else context.getColor(R.color.circa_shade_accent))
    }

    private fun createWindow(): RootView {
        val view = RootView(context)
        view.visibility = View.GONE
        val lp =
            WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT,
                )
                .apply {
                    title = "CircaShade"
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
                        val current by session
                        current?.let { s ->
                            key(s.id) {
                                CircaTrayScreen(
                                    session = s,
                                    quickSettings = quickSettings,
                                    notifications = notifications,
                                    onClose = ::close,
                                )
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

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) close()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            // QS tiles run their clicks through the falsing manager; feed it real touches.
            falsingCollector.onTouchEvent(ev)
            return super.dispatchTouchEvent(ev)
        }
    }

    private companion object {
        /** Settings.Secure ARGB int; 0 = use R.color.circa_shade_accent. */
        const val ACCENT_SETTING = "circa_accent_color"
    }
}
