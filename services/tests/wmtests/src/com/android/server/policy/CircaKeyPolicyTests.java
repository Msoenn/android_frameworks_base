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

package com.android.server.policy;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.platform.test.annotations.Presubmit;

import org.junit.Test;

/**
 * Tests for {@link CircaKeyPolicy}.
 *
 * Build/Install/Run:
 *  atest WmTests:CircaKeyPolicyTests
 */
@Presubmit
public class CircaKeyPolicyTests {

    @Test
    public void stemShortPress_onHome_showsAppList() {
        assertEquals(CircaKeyPolicy.StemAction.SHOW_APP_LIST,
                CircaKeyPolicy.stemShortPressAction(ACTIVITY_TYPE_HOME));
    }

    @Test
    public void stemShortPress_inApp_goesHome() {
        assertEquals(CircaKeyPolicy.StemAction.GO_HOME,
                CircaKeyPolicy.stemShortPressAction(ACTIVITY_TYPE_STANDARD));
        assertEquals(CircaKeyPolicy.StemAction.GO_HOME,
                CircaKeyPolicy.stemShortPressAction(ACTIVITY_TYPE_UNDEFINED));
    }

    @Test
    public void powerShortPress_opensRecentsOnlyWhenEnabledAwakeAndUnlocked() {
        assertTrue(CircaKeyPolicy.powerShortPressOpensRecents(true, true, false));
        assertFalse(CircaKeyPolicy.powerShortPressOpensRecents(false, true, false));
        assertFalse(CircaKeyPolicy.powerShortPressOpensRecents(true, false, false));
        assertFalse(CircaKeyPolicy.powerShortPressOpensRecents(true, true, true));
    }

    @Test
    public void appListIntent_isExplicitToHomePackage() {
        Intent i = CircaKeyPolicy.buildAppListIntent("org.example.home");
        assertEquals(Intent.ACTION_ALL_APPS, i.getAction());
        assertEquals("org.example.home", i.getPackage());
        assertTrue((i.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }

    @Test
    public void recentsIntent_usesContractActionAndPackage() {
        Intent i = CircaKeyPolicy.buildRecentsIntent("org.example.home");
        assertEquals("org.circa.intent.action.SHOW_RECENTS", i.getAction());
        assertEquals("org.example.home", i.getPackage());
    }

    @Test
    public void intents_doNotPinResolverOrMissingPackage() {
        assertNull(CircaKeyPolicy.buildRecentsIntent("android").getPackage());
        assertNull(CircaKeyPolicy.buildAppListIntent(null).getPackage());
    }
}
