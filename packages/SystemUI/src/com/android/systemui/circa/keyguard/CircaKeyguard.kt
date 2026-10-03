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
import android.graphics.Color
import android.provider.Settings
import com.android.systemui.res.R

/**
 * The Circa keyguard switch and the PIN pad palette (stock Wear OS: lavender keys, dark digits, pure
 * black behind). Static helpers because the pad's views (NumPadKey, NumPadAnimator, ...) are plain
 * views without dependency injection.
 */
object CircaKeyguard {
    /** Settings.Global switch for testing without a product overlay (read per call, cheap). */
    private const val SETTING_ENABLED = "circa_keyguard"

    @JvmStatic
    fun isEnabled(context: Context): Boolean =
        context.resources.getBoolean(R.bool.config_circaKeyguard) ||
            Settings.Global.getInt(context.contentResolver, SETTING_ENABLED, 0) != 0

    /** Resting key colour (digits, delete, enter). */
    @JvmStatic fun keyColor(context: Context): Int = context.getColor(R.color.circa_pin_key)

    /** Key colour while pressed. */
    @JvmStatic
    fun keyPressedColor(context: Context): Int = context.getColor(R.color.circa_pin_key_pressed)

    /** Digit / icon colour on a resting key. */
    @JvmStatic fun onKeyColor(context: Context): Int = context.getColor(R.color.circa_pin_on_key)

    /** The delete key's icon (it has no pill). */
    @JvmStatic
    fun deleteIconColor(context: Context): Int = context.getColor(R.color.circa_pin_delete)

    /** The bouncer's backdrop. */
    @JvmStatic fun backdropColor(): Int = Color.BLACK
}
