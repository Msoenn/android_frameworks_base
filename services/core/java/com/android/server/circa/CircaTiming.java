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
 * Duration and deadline arithmetic of the Circa timers, kept free of Android classes so it can be
 * unit tested on the host.
 */
public final class CircaTiming {

    /** No Circa delay is longer than a week. */
    public static final long MAX_DELAY_MS = 7L * 24 * 60 * 60 * 1000;

    /**
     * An alarm may be delivered a little before its trigger time; anything earlier than this before
     * the deadline is a stale alarm from a schedule that has since been replaced.
     */
    public static final long EARLY_TOLERANCE_MS = 1000;

    private CircaTiming() {}

    /**
     * A duration setting: unset or unparsable = {@code def}; 0 or negative = 0 (off); otherwise
     * between {@code min} and {@link #MAX_DELAY_MS}.
     */
    public static long parseDurationMs(String raw, long def, long min) {
        long v = def;
        if (raw != null) {
            try {
                v = Long.parseLong(raw.trim());
            } catch (NumberFormatException e) {
                v = def;
            }
        }
        if (v <= 0) {
            return 0;
        }
        return Math.min(Math.max(v, min), MAX_DELAY_MS);
    }

    /** {@code now + delay}, saturating instead of overflowing. */
    public static long deadline(long now, long delayMs) {
        if (delayMs <= 0) {
            return now;
        }
        return now > Long.MAX_VALUE - delayMs ? Long.MAX_VALUE : now + delayMs;
    }

    /** Whether an alarm delivered at {@code now} is the one armed for {@code deadline}. */
    public static boolean isDue(long now, long deadline) {
        return now >= deadline - EARLY_TOLERANCE_MS;
    }
}
