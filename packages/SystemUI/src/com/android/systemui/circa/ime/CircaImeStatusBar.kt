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

package com.android.systemui.circa.ime

import android.app.StatusBarManager
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Binder
import android.os.RemoteException
import android.util.Log
import android.view.Display
import com.android.internal.statusbar.IStatusBarService
import com.android.systemui.CoreStartable
import com.android.systemui.circa.shade.CircaShadeStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.CommandQueue
import java.io.PrintWriter
import javax.inject.Inject

/**
 * Circa: hides the status bar (clock, notification icons, battery) while the Circa Keyboard is
 * showing, so SystemUI's clock/battery cannot draw over the top of the full-screen round keyboard
 * (docs/watch-ui/circa/keyboard.md, "Known gaps").
 *
 * The IME window cannot do this itself: [android.inputmethodservice.InputMethodService] creates its
 * window with FLAG_NOT_FOCUSABLE, so it can never become the focused window, and the system bar
 * visibility is taken from the focused window
 * (`WindowManagerService`'s `InsetsPolicy#getStatusControlTarget`). SystemUI owns the status bar
 * window and hides the bar's items instead, through the supported "external caller" path: the
 * `StatusBarManager` disable flags that [com.android.systemui.statusbar.phone.fragment.
 * CollapsedStatusBarFragment] already applies (DISABLE_CLOCK | DISABLE_NOTIFICATION_ICONS |
 * DISABLE_SYSTEM_INFO). The flags are released the moment the IME goes away (and automatically on
 * SystemUI restart, through binder death).
 *
 * Everything is inert unless the Circa flag is on ([CircaShadeStartable.isEnabled]).
 */
@SysUISingleton
class CircaImeStatusBar
@Inject
constructor(
    @Application private val context: Context,
    private val commandQueue: CommandQueue,
    private val barService: IStatusBarService,
) : CoreStartable {

    private val disableToken = Binder()
    private var enabled = false
    private var hidden = false

    private val commandQueueCallbacks =
        object : CommandQueue.Callbacks {
            override fun setImeWindowStatus(
                displayId: Int,
                vis: Int,
                backDisposition: Int,
                showImeSwitcher: Boolean,
            ) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                setStatusBarHidden((vis and InputMethodService.IME_VISIBLE) != 0)
            }
        }

    override fun start() {
        enabled = CircaShadeStartable.isEnabled(context)
        if (!enabled) return
        Log.i(TAG, "hiding the status bar while the IME is showing")
        commandQueue.addCallback(commandQueueCallbacks)
    }

    private fun setStatusBarHidden(hidden: Boolean) {
        if (hidden == this.hidden) return
        this.hidden = hidden
        try {
            barService.disable(
                if (hidden) HIDE_FLAGS else StatusBarManager.DISABLE_NONE,
                disableToken,
                context.packageName,
            )
        } catch (e: RemoteException) {
            Log.w(TAG, "could not change the status bar visibility", e)
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("CircaImeStatusBar: enabled=$enabled hidden=$hidden")
    }

    companion object {
        private const val TAG = "CircaImeStatusBar"

        /** Everything the collapsed status bar draws: clock, notification icons, system icons. */
        private const val HIDE_FLAGS = StatusBarManager.DISABLE_CLOCK or
            StatusBarManager.DISABLE_NOTIFICATION_ICONS or
            StatusBarManager.DISABLE_SYSTEM_INFO
    }
}
