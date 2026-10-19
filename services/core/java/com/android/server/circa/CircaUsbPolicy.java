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

/**
 * The decision table of {@link CircaUsbPortGuard}, kept free of Android classes so it can be unit
 * tested on the host.
 *
 * <p>"Charging only when locked" follows GrapheneOS's MODE_CHARGING_ONLY_WHEN_LOCKED and the AOSP
 * UsbDataAdvancedProtectionHook: a connection that exists when the device locks keeps its data until
 * it is unplugged, new connections while locked get no data, and before the first unlock after boot
 * data is off even with a cable already in. Unlocking turns data back on.
 */
public final class CircaUsbPolicy {

    /** Charging only: data signalling always off. (The original toggle's "off" value.) */
    public static final int MODE_CHARGING_ONLY = 0;
    /** Data always allowed (the platform default). */
    public static final int MODE_ON = 1;
    /** Data off while locked, except for a connection made before locking (after first unlock). */
    public static final int MODE_CHARGING_ONLY_WHEN_LOCKED = 2;

    /** What the guard should do with the data signal. */
    public enum Action {
        /** Release Circa's disable request (data on unless someone else disabled it). */
        ENABLE,
        /** Request data off. */
        DISABLE,
        /** Leave the current state alone. */
        KEEP,
    }

    private CircaUsbPolicy() {}

    /** Unknown or unparsable values fall back to {@code def}. */
    public static int parseMode(String raw, int def) {
        if (raw == null) {
            return def;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            switch (v) {
                case MODE_CHARGING_ONLY:
                case MODE_ON:
                case MODE_CHARGING_ONLY_WHEN_LOCKED:
                    return v;
                default:
                    return def;
            }
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * @param mode one of the MODE_ constants
     * @param locked the keyguard is showing and secure
     * @param unlockedSinceBoot the user's credential-encrypted storage is unlocked
     * @param portConnected a partner (cable, charger or host) is attached to a USB port
     * @param disabledByUs Circa's disable request is currently in effect
     */
    public static Action decide(int mode, boolean locked, boolean unlockedSinceBoot,
            boolean portConnected, boolean disabledByUs) {
        switch (mode) {
            case MODE_CHARGING_ONLY:
                return Action.DISABLE;
            case MODE_CHARGING_ONLY_WHEN_LOCKED:
                if (!locked) {
                    return Action.ENABLE;
                }
                if (!unlockedSinceBoot) {
                    // Before first unlock: no data, cable or not.
                    return Action.DISABLE;
                }
                if (portConnected && !disabledByUs) {
                    // The connection was made while unlocked: it keeps its data until unplugged.
                    return Action.KEEP;
                }
                return Action.DISABLE;
            case MODE_ON:
            default:
                return Action.ENABLE;
        }
    }

    /**
     * Whether the guard calls the HAL for {@code action}.
     *
     * @param disabledByUs Circa's disable request is in effect
     * @param pending the last request failed or has not been confirmed yet (and on the first run)
     * @param portEvent this evaluation was triggered by a port change: a disable is applied again
     *     then, so "charging only" also holds for whatever was just plugged in
     */
    public static boolean shouldApply(Action action, boolean disabledByUs, boolean pending,
            boolean portEvent) {
        switch (action) {
            case DISABLE:
                return !disabledByUs || pending || portEvent;
            case ENABLE:
                return disabledByUs || pending;
            case KEEP:
            default:
                return false;
        }
    }

    /**
     * Whether the port status contradicts the last request, so it should be sent again.
     *
     * @param wantEnabled the last request allowed data
     * @param forceDisabled a port reports DATA_STATUS_DISABLED_FORCE
     */
    public static boolean statusMismatch(boolean wantEnabled, boolean forceDisabled) {
        return wantEnabled == forceDisabled;
    }
}
