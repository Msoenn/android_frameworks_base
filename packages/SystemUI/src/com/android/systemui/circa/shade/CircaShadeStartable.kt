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

import android.app.StatusBarManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.input.InputManager
import android.os.Binder
import android.os.RemoteException
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.android.internal.statusbar.IStatusBarService
import com.android.systemui.CoreStartable
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.shared.system.TaskStackChangeListener
import com.android.systemui.shared.system.TaskStackChangeListeners
import com.android.systemui.statusbar.CommandQueue
import java.io.PrintWriter
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Circa: turns SystemUI's shade into the stock Wear OS tray.
 *
 * When enabled ([isEnabled]):
 * * the phone shade can no longer be pulled down (`StatusBarManager.DISABLE_EXPAND`, the same
 *   switch lock-task mode uses), so nothing of the phone QS / notification panel shows;
 * * every request to open or close the shade (`StatusBarManager.expandNotificationsPanel()` /
 *   `expandSettingsPanel()` / `collapsePanels()`, `cmd statusbar …`) goes to [CircaTray] instead;
 * * a swipe down from the top edge opens the tray at its quick-settings end and a swipe up from
 *   the bottom edge at its notifications end ([CircaEdgeSwipe]);
 * * a new notification peeks as a card from the bottom ([CircaHeadsUp]);
 * * the tray closes when the screen turns off, on ACTION_CLOSE_SYSTEM_DIALOGS (home, stem/crown
 *   press) and when another task comes to the front.
 */
@SysUISingleton
class CircaShadeStartable
@Inject
constructor(
    @Application private val context: Context,
    private val commandQueue: CommandQueue,
    private val barService: IStatusBarService,
    private val inputManager: InputManager,
    private val broadcastDispatcher: BroadcastDispatcher,
    @Main private val mainExecutor: Executor,
    private val tray: CircaTray,
    private val theaterMode: CircaTheaterMode,
    private val headsUp: CircaHeadsUp,
) : CoreStartable {

    private val disableToken = Binder()
    private var enabled = false
    private var edgeSwipe: CircaEdgeSwipe? = null

    private val commandQueueCallbacks =
        object : CommandQueue.Callbacks {
            override fun animateExpandNotificationsPanel() {
                tray.open(CircaTray.End.NOTIFICATIONS)
            }

            override fun animateExpandSettingsPanel(subPanel: String?) {
                tray.open(CircaTray.End.QUICK_SETTINGS)
            }

            override fun animateCollapsePanels(flags: Int, force: Boolean) {
                tray.close()
            }

            override fun toggleNotificationsPanel() {
                if (tray.isOpen) tray.close() else tray.open(CircaTray.End.NOTIFICATIONS)
            }

            override fun toggleQuickSettingsPanel() {
                if (tray.isOpen) tray.close() else tray.open(CircaTray.End.QUICK_SETTINGS)
            }
        }

    private val closeReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                tray.close()
            }
        }

    private val taskListener =
        object : TaskStackChangeListener {
            override fun onTaskStackChanged() {
                // An app or the launcher came up (crown press, a notification's or tile's
                // activity): the tray must not stay on top of it. Ignore the burst right after
                // opening, when the tray's own window change can be reported as a stack change.
                if (SystemClock.uptimeMillis() - tray.openedAtMillis > OPEN_GRACE_MILLIS) {
                    tray.close()
                }
            }
        }

    override fun start() {
        enabled = isEnabled(context)
        if (!enabled) return
        Log.i(TAG, "Circa Wear shade enabled")

        tray.init()
        theaterMode.start()
        headsUp.start()
        try {
            barService.disable(StatusBarManager.DISABLE_EXPAND, disableToken, context.packageName)
        } catch (e: RemoteException) {
            Log.w(TAG, "Could not disable the phone shade", e)
        }
        commandQueue.addCallback(commandQueueCallbacks)
        broadcastDispatcher.registerReceiver(
            closeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            },
            mainExecutor,
        )
        TaskStackChangeListeners.getInstance().registerTaskStackListener(taskListener)
        edgeSwipe =
            CircaEdgeSwipe(context, inputManager, context.displayId) { end ->
                if (tray.isOpen) {
                    // Open tray: only a swipe up from the bottom edge closes it; a pull down from
                    // the top edge does nothing (it must not dismiss what it just opened).
                    if (end == CircaTray.End.NOTIFICATIONS) {
                        tray.close()
                        true
                    } else {
                        false
                    }
                } else {
                    tray.open(end)
                    true
                }
            }.also { it.start() }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        // Reading edgeSwipe here also keeps R8 from dropping the field as write-only, which let the
        // gesture monitor be garbage collected (its input channel closed a second after boot).
        pw.println(
            "CircaShadeStartable: enabled=$enabled trayOpen=${tray.isOpen} " +
                "headsUp=${headsUp.isShowing} " +
                "edgeSwipe=${edgeSwipe?.describe()}"
        )
    }

    companion object {
        private const val TAG = "CircaShade"
        /** Settings.Global switch for testing without a product overlay (read at start). */
        private const val SETTING_ENABLED = "circa_wear_shade"
        private const val OPEN_GRACE_MILLIS = 600L

        /** The Circa shade replaces the phone shade: product overlay, or the test property. */
        fun isEnabled(context: Context): Boolean =
            context.resources.getBoolean(R.bool.config_circaWearShade) ||
                Settings.Global.getInt(context.contentResolver, SETTING_ENABLED, 0) != 0
    }
}
