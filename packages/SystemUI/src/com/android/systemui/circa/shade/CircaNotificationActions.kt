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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
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
    horizontalPadding: Dp = 10.dp,
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
                .padding(horizontal = horizontalPadding),
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

/**
 * The heads-up card's buttons: one row of at most two compact pills. Each pill is as wide as its
 * label needs (the spare width shared out), and both labels shrink together from 13 sp down to
 * [HEADS_UP_MIN_SP] when that is what it takes to fit both. If the two still do not fit, only the
 * first button is shown, full width (the tray still offers every action).
 */
@Composable
internal fun HeadsUpActions(
    item: CircaNotification,
    colors: CircaColors,
    onAction: (CircaAction) -> Unit,
) {
    val actions = item.actions.take(HEADS_UP_ACTIONS)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        val layout =
            remember(actions.map { it.title.toString() }, constraints.maxWidth, density) {
                headsUpLayout(actions.map { it.title.toString() }, constraints.maxWidth, density) {
                    text,
                    sp ->
                    measurer
                        .measure(
                            text,
                            TextStyle(fontSize = sp.sp, fontWeight = FontWeight.Medium),
                            maxLines = 1,
                        )
                        .size
                        .width
                }
            }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(HEADS_UP_GAP),
        ) {
            for ((n, a) in actions.take(layout.count).withIndex()) {
                ActionPill(
                    a.title,
                    a.isReply,
                    colors,
                    Modifier.weight(layout.weights[n]),
                    height = 30.dp,
                    fontSize = layout.sp.sp,
                    horizontalPadding = HEADS_UP_PAD,
                ) {
                    onAction(a)
                }
            }
        }
    }
}

/** How the heads-up row is laid out: [count] buttons, their weights, one font size. */
internal class HeadsUpLayout(val count: Int, val weights: List<Float>, val sp: Float)

/**
 * The largest size from 13 sp down to [HEADS_UP_MIN_SP] (half-sp steps) at which both labels fit
 * side by side in [widthPx]; else one button. [measure] gives a label's width in px at a size.
 */
internal fun headsUpLayout(
    labels: List<String>,
    widthPx: Int,
    density: Density,
    measure: (String, Float) -> Int,
): HeadsUpLayout {
    val pad = with(density) { HEADS_UP_PAD.toPx() } * 2
    val gap = with(density) { HEADS_UP_GAP.toPx() }
    if (labels.size >= 2) {
        var sp = HEADS_UP_MAX_SP
        while (sp >= HEADS_UP_MIN_SP) {
            val need = labels.take(2).map { measure(it, sp) + pad }
            val spare = widthPx - gap - need.sum()
            if (spare >= 0) {
                // Share the spare width out evenly; the weights are the resulting widths.
                return HeadsUpLayout(2, need.map { it + spare / 2 }, sp)
            }
            sp -= 0.5f
        }
    }
    // One button, full width: the largest size that fits, else the smallest (then ellipsized).
    val label = labels.firstOrNull() ?: return HeadsUpLayout(0, emptyList(), HEADS_UP_MAX_SP)
    var sp = HEADS_UP_MAX_SP
    while (sp > HEADS_UP_MIN_SP && measure(label, sp) + pad > widthPx) sp -= 0.5f
    return HeadsUpLayout(1, listOf(1f), sp)
}

private const val HEADS_UP_MAX_SP = 13f
private const val HEADS_UP_MIN_SP = 12f
private val HEADS_UP_PAD = 8.dp
private val HEADS_UP_GAP = 6.dp

/** Heads-up cards have room for two buttons (decision 2026-10-19). */
internal const val HEADS_UP_ACTIONS = 2
