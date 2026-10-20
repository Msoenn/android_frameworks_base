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

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The reply screen of a notification's reply action (a `RemoteInput`), on the round screen:
 * "Type" (a text field, so the Circa keyboard comes up; its enter key reads Send) and the
 * RemoteInput's choices as pills (WatchLink puts Gadgetbridge's canned replies there). A choice
 * sends at once (or fills the field when the app asks to edit choices first). After sending, a
 * short "Sent" check, then it closes.
 *
 * It never shows over the keyguard (no showWhenLocked): [CircaNotifications.openReply] starts it
 * through the keyguard dismissal. Only the notification key and the action's index travel in the
 * intent; the action is looked up again when sending ([CircaNotifications.sendReply]), so a reply
 * to a notification that went away in the meantime says so instead of firing a stale intent.
 * Not exported. Swipe right, BACK or the crown press closes it.
 */
class CircaReplyActivity
@Inject
constructor(private val notifications: CircaNotifications, private val tray: CircaTray) :
    ComponentActivity() {

    private enum class Phase { PICK, SENT, GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra(EXTRA_KEY)
        val index = intent.getIntExtra(EXTRA_INDEX, -1)
        val item = key?.let(notifications::find)
        val action = item?.actions?.firstOrNull { it.index == index && it.isReply }
        if (item == null || action == null) {
            Log.w(TAG, "reply: notification or reply action gone")
            finish()
            return
        }
        val inputs = action.replyInputs
        val choices = inputs.flatMap { it.choices.orEmpty().toList() }.filter { it.isNotBlank() }
        val freeForm = inputs.any { it.allowFreeFormInput }
        val editChoices =
            inputs.any { it.editChoicesBeforeSending == android.app.RemoteInput.EDIT_CHOICES_BEFORE_SENDING_ENABLED }
        val accent = tray.accent()
        setContent {
            CircaTrayDensity {
                ReplyScreen(
                    colors = remember { CircaColors(accent) },
                    heading = item.title ?: item.appName,
                    choices = choices,
                    freeForm = freeForm,
                    editChoices = editChoices,
                    send = { text, fromChoice ->
                        notifications.sendReply(item.key, action.index, action.title, text, fromChoice)
                    },
                    close = ::finish,
                )
            }
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_STEM_PRIMARY) {
            finish()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    @Composable
    private fun ReplyScreen(
        colors: CircaColors,
        heading: CharSequence,
        choices: List<CharSequence>,
        freeForm: Boolean,
        editChoices: Boolean,
        send: (CharSequence, Boolean) -> Boolean,
        close: () -> Unit,
    ) {
        var phase by remember { mutableStateOf(Phase.PICK) }
        var typing by remember { mutableStateOf(false) }
        var text by remember { mutableStateOf("") }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val listFocus = remember { FocusRequester() }
        val fieldFocus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        val dragX = remember { Animatable(0f) }

        fun submit(value: CharSequence, fromChoice: Boolean) {
            if (value.isBlank() || phase != Phase.PICK) return
            keyboard?.hide()
            phase = if (send(value, fromChoice)) Phase.SENT else Phase.GONE
        }

        LaunchedEffect(phase) {
            when (phase) {
                Phase.PICK -> listFocus.requestFocus()
                Phase.SENT -> { delay(SENT_MILLIS); close() }
                Phase.GONE -> { delay(GONE_MILLIS); close() }
            }
        }
        LaunchedEffect(typing) {
            if (typing) {
                fieldFocus.requestFocus()
                keyboard?.show()
            }
        }

        Box(
            Modifier.fillMaxSize()
                .background(Color.Black)
                .offset { IntOffset(dragX.value.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state =
                        rememberDraggableState { d ->
                            scope.launch { dragX.snapTo((dragX.value + d).coerceAtLeast(0f)) }
                        },
                    onDragStopped = {
                        if (dragX.value > SWIPE_CLOSE_PX) close()
                        else dragX.animateTo(0f, tween(180))
                    },
                )
        ) {
            when (phase) {
                Phase.SENT -> Confirmation(colors, "Sent", ok = true)
                Phase.GONE -> Confirmation(colors, "Not available", ok = false)
                Phase.PICK ->
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 30.dp, bottom = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier =
                            Modifier.fillMaxSize()
                                .testTag("circa_reply")
                                .onRotaryScrollEvent { e ->
                                    scope.launch { listState.scrollBy(e.verticalScrollPixels) }
                                    true
                                }
                                .focusRequester(listFocus)
                                .focusable(),
                    ) {
                        item {
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("Reply", color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    heading.toString(),
                                    color = colors.onSurfaceVariant,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                        if (freeForm) {
                            item {
                                if (!typing) {
                                    ActionPill("Type", primary = true, colors, Modifier.fillMaxWidth().height(44.dp)) {
                                        typing = true
                                    }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        BasicTextField(
                                            value = text,
                                            onValueChange = { text = it },
                                            textStyle = TextStyle(color = colors.onSurface, fontSize = 15.sp),
                                            cursorBrush = SolidColor(colors.accent),
                                            keyboardOptions =
                                                KeyboardOptions(
                                                    capitalization = KeyboardCapitalization.Sentences,
                                                    imeAction = ImeAction.Send,
                                                ),
                                            keyboardActions = KeyboardActions(onSend = { submit(text, false) }),
                                            maxLines = 4,
                                            modifier =
                                                Modifier.fillMaxWidth()
                                                    .clip(RoundedCornerShape(20.dp))
                                                    .background(colors.surface)
                                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                                                    .focusRequester(fieldFocus)
                                                    .testTag("circa_reply_field"),
                                        )
                                        if (text.isNotBlank()) {
                                            ActionPill("Send", primary = true, colors, Modifier.fillMaxWidth()) {
                                                submit(text, false)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        items(choices) { c ->
                            ActionPill(c, primary = false, colors, Modifier.fillMaxWidth()) {
                                if (editChoices && freeForm) {
                                    text = c.toString()
                                    typing = true
                                } else {
                                    submit(c, true)
                                }
                            }
                        }
                    }
            }
        }
    }

    /** Wear's confirmation: a check (or a cross) in a disc and one word, centred. */
    @Composable
    private fun Confirmation(colors: CircaColors, label: String, ok: Boolean) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(28.dp)).background(if (ok) colors.accent else colors.surfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(24.dp)) {
                    val w = 3.dp.toPx()
                    val c = if (ok) colors.onAccent else colors.onSurface
                    if (ok) {
                        drawLine(c, Offset(size.width * 0.08f, size.height * 0.55f), Offset(size.width * 0.38f, size.height * 0.85f), w, StrokeCap.Round)
                        drawLine(c, Offset(size.width * 0.38f, size.height * 0.85f), Offset(size.width * 0.95f, size.height * 0.2f), w, StrokeCap.Round)
                    } else {
                        drawLine(c, Offset(size.width * 0.15f, size.height * 0.15f), Offset(size.width * 0.85f, size.height * 0.85f), w, StrokeCap.Round)
                        drawLine(c, Offset(size.width * 0.85f, size.height * 0.15f), Offset(size.width * 0.15f, size.height * 0.85f), w, StrokeCap.Round)
                    }
                }
            }
            Text(
                label,
                color = colors.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }

    companion object {
        const val EXTRA_KEY = "circa_notification_key"
        const val EXTRA_INDEX = "circa_action_index"
        private const val TAG = "CircaReply"
        private const val SENT_MILLIS = 900L
        private const val GONE_MILLIS = 1300L
        private const val SWIPE_CLOSE_PX = 90f
    }
}
