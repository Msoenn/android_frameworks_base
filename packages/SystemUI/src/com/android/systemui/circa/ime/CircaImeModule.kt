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

import com.android.systemui.CoreStartable
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

/**
 * Circa: hides the status bar while the IME is showing. Always bound; [CircaImeStatusBar.start]
 * returns at once unless the Circa flag (`config_circaWearShade` / Settings.Global
 * `circa_wear_shade`) is set, so every other build keeps the stock status bar.
 */
@Module
interface CircaImeModule {
    @Binds
    @IntoMap
    @ClassKey(CircaImeStatusBar::class)
    fun bindCircaImeStatusBar(startable: CircaImeStatusBar): CoreStartable
}
