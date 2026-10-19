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

import android.util.Slog;

/**
 * Every Circa callback runs on a system_server thread; an exception escaping one would take
 * system_server down (and, if it repeats at boot, the device into a crash loop). Callbacks go
 * through {@link #run} instead, which logs and drops the failure.
 */
final class CircaSafe {
    private CircaSafe() {}

    static void run(String tag, String what, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            Slog.wtf(tag, what + " failed", e);
        }
    }
}
