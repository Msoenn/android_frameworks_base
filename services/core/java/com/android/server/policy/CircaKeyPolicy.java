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

import android.app.WindowConfiguration;
import android.content.Intent;

/**
 * Circa watch button policy helpers used by {@link PhoneWindowManager}.
 *
 * <p>The behaviours themselves are selected by framework-res values that default to stock Android:
 * <ul>
 *   <li>{@code config_shortPressOnStemPrimaryBehavior = 3} ({@link #SHORT_PRESS_PRIMARY_CIRCA}):
 *       crown short press is "smart": on home it opens the app list, anywhere else it goes home.
 *   <li>{@code config_longPressOnStemPrimaryBehavior = 2} ({@link
 *       #LONG_PRESS_PRIMARY_GLOBAL_ACTIONS}): crown long press opens the power menu.
 *   <li>{@code config_circaPowerShortPressOpensRecents = true}: a short press of the side button
 *       while the screen is on and the keyguard is not showing opens Recents instead of sleeping.
 * </ul>
 *
 * <p>Both launcher-facing intents are sent explicitly to the package of the current HOME role
 * holder, so there is never a chooser even when several launchers are installed.
 */
final class CircaKeyPolicy {
    /** Crown short press: app list when on home, home otherwise. */
    static final int SHORT_PRESS_PRIMARY_CIRCA = 3;

    /** Crown long press: show the global actions (power) menu. */
    static final int LONG_PRESS_PRIMARY_GLOBAL_ACTIONS = 2;

    /**
     * Action of the intent sent to the home app to show its Recents page. Handled by an activity
     * (category DEFAULT) of the home app; delivered with {@code FLAG_ACTIVITY_NEW_TASK |
     * FLAG_ACTIVITY_RESET_TASK_IF_NEEDED}.
     */
    static final String ACTION_SHOW_RECENTS = "org.circa.intent.action.SHOW_RECENTS";

    private CircaKeyPolicy() {}

    /**
     * @return whether a showing keyguard should block a Circa button behaviour (the crown's short
     *         press, the side button's Recents). Only a keyguard that actually has a credential (a
     *         PIN) blocks: Circa's launcher shows its watch face over an insecure keyguard with
     *         {@code showWhenLocked}, so there is nothing to unlock and the buttons must work.
     *         Mirrors AOSP's own test in {@code SHORT_PRESS_POWER_GO_TO_SLEEP}.
     */
    static boolean keyguardBlocksCircaButton(boolean keyguardOn, boolean keyguardSecure) {
        return keyguardOn && keyguardSecure;
    }

    /**
     * @return whether a crown press must be ignored right now: theater mode is on and the display
     *         is not awake, so only the power key may wake the device (stock Wear). Without this the
     *         crown's short press would open the app list on a dark screen (and start an activity
     *         behind it) even though the crown is no longer allowed to wake the panel.
     */
    static boolean crownSuppressedByTheaterMode(boolean theaterModeOn, boolean displayAwake) {
        return theaterModeOn && !displayAwake;
    }

    /** The action to take for a crown short press. */
    enum StemAction { GO_HOME, SHOW_APP_LIST }

    /** @return what a crown short press should do, given the type of the focused root task. */
    static StemAction stemShortPressAction(int focusedActivityType) {
        return focusedActivityType == WindowConfiguration.ACTIVITY_TYPE_HOME
                ? StemAction.SHOW_APP_LIST : StemAction.GO_HOME;
    }

    /**
     * @return whether the power key's short press should open Recents (rather than the configured
     *         behaviour)
     */
    static boolean powerShortPressOpensRecents(boolean configEnabled, boolean interactive,
            boolean keyguardShowing) {
        return configEnabled && interactive && !keyguardShowing;
    }

    /** Builds the intent that opens the home app's app list ({@code ACTION_ALL_APPS}). */
    static Intent buildAppListIntent(String homePackage) {
        return addTarget(new Intent(Intent.ACTION_ALL_APPS), homePackage);
    }

    /** Builds the intent that opens the home app's Recents page. */
    static Intent buildRecentsIntent(String homePackage) {
        return addTarget(new Intent(ACTION_SHOW_RECENTS), homePackage);
    }

    private static Intent addTarget(Intent intent, String homePackage) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        // "android" is the ResolverActivity: no default home is set, so do not pin a package.
        if (homePackage != null && !"android".equals(homePackage)) {
            intent.setPackage(homePackage);
        }
        return intent;
    }
}
