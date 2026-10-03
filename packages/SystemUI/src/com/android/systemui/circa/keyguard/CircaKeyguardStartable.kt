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

import android.content.Context
import android.os.Handler
import android.util.Log
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.policy.KeyguardStateController
import java.io.PrintWriter
import javax.inject.Inject

/**
 * Circa: the keyguard of a watch (docs/watch-ui/circa/keyguard.md).
 *
 * When enabled ([CircaKeyguard.isEnabled]) the keyguard shows no phone lockscreen. While the
 * keyguard is up and nothing occludes it (the launcher's face is an occluding activity once it can
 * run), [CircaKeyguardFace] covers it with a watch face; a touch on it asks for the PIN (the round
 * bouncer), the same as the crown (PhoneWindowManager asks the keyguard to dismiss) and the
 * launcher's gestures. The face hides while the bouncer is up, while dozing (the launcher's ambient
 * dream owns the screen then) and as soon as the keyguard goes away.
 */
@SysUISingleton
class CircaKeyguardStartable
@Inject
constructor(
    @Application private val context: Context,
    private val keyguardStateController: KeyguardStateController,
    private val statusBarStateController: StatusBarStateController,
    private val activityStarter: ActivityStarter,
    @Main private val handler: Handler,
    private val face: CircaKeyguardFace,
) : CoreStartable {

    private var enabled = false

    private val evaluateRunnable = Runnable { evaluate() }

    private val keyguardCallback =
        object : KeyguardStateController.Callback {
            override fun onKeyguardShowingChanged() = changed()

            override fun onPrimaryBouncerShowingChanged() = changed()

            override fun onKeyguardGoingAwayChanged() = changed()

            override fun onKeyguardFadingAwayChanged() = changed()
        }

    private val stateListener =
        object : StatusBarStateController.StateListener {
            override fun onDozingChanged(isDozing: Boolean) = changed()
        }

    override fun start() {
        enabled = CircaKeyguard.isEnabled(context)
        if (!enabled) return
        Log.i(TAG, "Circa keyguard enabled")
        face.onInteract = {
            // Any completed touch needs the PIN. Nothing to run after it: the launcher's own
            // gestures (crown, long press) carry their destinations, a plain touch just unlocks.
            activityStarter.dismissKeyguardThenExecute({ false }, null, false)
        }
        keyguardStateController.addCallback(keyguardCallback)
        statusBarStateController.addCallback(stateListener)
        evaluate()
    }

    /** A state input changed: re-evaluate now, and again shortly after (the callbacks race). */
    private fun changed() {
        evaluate()
        handler.removeCallbacks(evaluateRunnable)
        handler.postDelayed(evaluateRunnable, SETTLE_MILLIS)
    }

    private fun shouldShowFace(): Boolean =
        keyguardStateController.isShowing &&
            !keyguardStateController.isOccluded &&
            !keyguardStateController.isPrimaryBouncerShowing &&
            !keyguardStateController.isKeyguardGoingAway &&
            !keyguardStateController.isKeyguardFadingAway &&
            !statusBarStateController.isDozing

    private fun evaluate() {
        if (shouldShowFace()) face.show() else face.hide()
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println(
            "CircaKeyguardStartable: enabled=$enabled faceShown=${face.isShown} " +
                "showing=${keyguardStateController.isShowing} " +
                "occluded=${keyguardStateController.isOccluded} " +
                "bouncer=${keyguardStateController.isPrimaryBouncerShowing} " +
                "dozing=${statusBarStateController.isDozing}"
        )
    }

    private companion object {
        const val TAG = "CircaKeyguard"
        const val SETTLE_MILLIS = 250L
    }
}
