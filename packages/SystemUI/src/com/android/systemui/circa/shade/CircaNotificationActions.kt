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

package com.android.systemui.circa.shade

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Whether a card shows its action buttons: never while the content is redacted (a secure
 * keyguard; action titles such as "Reply to Alice" are content too), except a call's Accept /
 * Reject, which work locked (decision 2026-10-19).
 */
internal fun showsActions(item: CircaNotification, redacted: Boolean): Boolean =
    item.actions.isNotEmpty() && (!redacted || item.isCall)

/**
 * One action button in the Wear style: a full-round pill, one line, the reply action filled with
 * the accent (Wear's primary button), the others on the raised surface.
 */
@Composable
internal fun ActionPill(
    title: CharSequence,
    primary: Boolean,
    colors: CircaColors,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    fontSize: TextUnit = 15.sp,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            modifier
                .height(height)
                .clip(CircleShape)
                .background(if (primary) colors.accent else colors.surfaceHigh)
                .combinedClickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = title.toString() }
                .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.toString(),
            color = if (primary) colors.onAccent else colors.onSurface,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * One of a tray card's buttons: a full-width pill under the card (a list item of its own, so it
 * shrinks into the bezel separately from the card).
 */
@Composable
internal fun TrayActionPill(action: CircaAction, colors: CircaColors, modifier: Modifier, onClick: () -> Unit) {
    ActionPill(action.title, action.isReply, colors, modifier, onClick = onClick)
}

/** The heads-up card's buttons: one row of at most two compact pills. */
@Composable
internal fun HeadsUpActions(
    item: CircaNotification,
    colors: CircaColors,
    onAction: (CircaAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (a in item.actions.take(HEADS_UP_ACTIONS)) {
            ActionPill(
                a.title,
                a.isReply,
                colors,
                Modifier.weight(1f),
                height = 30.dp,
                fontSize = 13.sp,
            ) {
                onAction(a)
            }
        }
    }
}

/** Heads-up cards have room for two buttons (decision 2026-10-19). */
internal const val HEADS_UP_ACTIONS = 2
