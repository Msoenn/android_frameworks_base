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

import static com.android.server.circa.CircaTiming.MAX_DELAY_MS;
import static com.android.server.circa.CircaTiming.deadline;
import static com.android.server.circa.CircaTiming.isDue;
import static com.android.server.circa.CircaTiming.parseDurationMs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CircaTimingTest {
    private static final long DEF = 18L * 3600 * 1000;
    private static final long MIN = 20_000;

    @Test
    public void parse_unsetOrGarbageIsTheDefault() {
        assertEquals(DEF, parseDurationMs(null, DEF, MIN));
        assertEquals(DEF, parseDurationMs("18h", DEF, MIN));
    }

    @Test
    public void parse_zeroOrNegativeIsOff() {
        assertEquals(0, parseDurationMs("0", DEF, MIN));
        assertEquals(0, parseDurationMs("-5", DEF, MIN));
    }

    @Test
    public void parse_clampsToMinAndAWeek() {
        assertEquals(MIN, parseDurationMs("5", DEF, MIN));
        assertEquals(60_000, parseDurationMs(" 60000 ", DEF, MIN));
        assertEquals(MAX_DELAY_MS, parseDurationMs(Long.toString(Long.MAX_VALUE), DEF, MIN));
    }

    @Test
    public void deadline_saturatesInsteadOfOverflowing() {
        assertEquals(1500, deadline(1000, 500));
        assertEquals(Long.MAX_VALUE, deadline(Long.MAX_VALUE - 10, 500));
    }

    /** Armed for 60 s, re-armed 30 s later for another 60 s: the first alarm's delivery is stale. */
    @Test
    public void staleAlarm_fromAReplacedScheduleIsNotDue() {
        long first = deadline(0, 60_000);
        long second = deadline(30_000, 60_000);
        assertTrue(isDue(first, first));
        assertFalse(isDue(first, second));
        assertTrue(isDue(second, second));
        assertTrue(isDue(second - 500, second)); // a slightly early delivery still counts
    }
}
