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

package com.android.server.circa;

import android.content.ContentResolver;
import android.provider.Settings;
import android.util.Slog;

/**
 * One-shot migration that gives a device with existing data the privacy defaults a fresh device
 * gets from the Circa SettingsProvider overlay. Guarded by Settings.Global
 * {@code circa_privacy_defaults_v1}; Circa Settings runs the same migration with the same guard and
 * rules (apps/settings model/PrivacyModel.kt), and whichever runs first wins.
 *
 * <ul>
 * <li>captive_portal_mode 0, ble_scan_always_enabled 0, wifi_wakeup_enabled 0: written when unset
 *     or exactly "1" (the old platform default, not a choice anyone made in Circa).
 * <li>non_persistent_mac_randomization_force_enabled 1, wifi_scan_always_enabled 0: written only
 *     when unset.
 * </ul>
 */
final class CircaPrivacyDefaults {
    private static final String TAG = "CircaPrivacyDefaults";

    static final String GUARD = "circa_privacy_defaults_v1";

    private static final String[][] UNSET_OR_ONE = {
            {Settings.Global.CAPTIVE_PORTAL_MODE, "0"},
            {Settings.Global.BLE_SCAN_ALWAYS_AVAILABLE, "0"},
            {Settings.Global.WIFI_WAKEUP_ENABLED, "0"},
    };

    private static final String[][] UNSET_ONLY = {
            {"non_persistent_mac_randomization_force_enabled", "1"},
            {Settings.Global.WIFI_SCAN_ALWAYS_AVAILABLE, "0"},
    };

    private CircaPrivacyDefaults() {}

    static void migrate(ContentResolver cr) {
        if ("1".equals(Settings.Global.getString(cr, GUARD))) {
            return;
        }
        for (String[] kv : UNSET_OR_ONE) {
            String cur = Settings.Global.getString(cr, kv[0]);
            if (cur == null || "1".equals(cur.trim())) {
                put(cr, kv[0], kv[1], cur);
            }
        }
        for (String[] kv : UNSET_ONLY) {
            String cur = Settings.Global.getString(cr, kv[0]);
            if (cur == null) {
                put(cr, kv[0], kv[1], null);
            }
        }
        Settings.Global.putString(cr, GUARD, "1");
        Slog.i(TAG, "privacy defaults applied");
    }

    private static void put(ContentResolver cr, String key, String value, String old) {
        Settings.Global.putString(cr, key, value);
        Slog.i(TAG, key + ": " + old + " -> " + value);
    }
}
