package org.nanokvm.mobile.ui.components

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.nanokvm.mobile.R
import org.nanokvm.mobile.runtime.RemoteInputSink
import org.nanokvm.mobile.runtime.KeyboardLayout
import org.nanokvm.mobile.runtime.RemoteKey
import org.nanokvm.mobile.ui.input.ModifierLatch
import org.nanokvm.mobile.ui.input.ModifierMode
import org.nanokvm.mobile.ui.theme.LocalConsoleColorScheme

@Composable
fun ConsoleKeyboard(
    input: RemoteInputSink,
    visible: Boolean,
    releaseGeneration: Long,
    layout: KeyboardLayout,
    onLayoutChange: (KeyboardLayout) -> Unit,
    onClose: () -> Unit,
    onCtrlAltDelete: () -> Unit,
    onViewportAction: (ViewportAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val consoleColors = LocalConsoleColorScheme.current
    val hideKeyboardDescription = stringResource(R.string.console_hide_native_keyboard)
    var latch by remember { mutableStateOf(ModifierLatch()) }
    var showFunctionKeys by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(visible, releaseGeneration) {
        if (!visible || latch.activeKeys().isNotEmpty()) latch = ModifierLatch()
    }

    fun releaseOneShot() {
        latch.modes.forEach { (key, mode) ->
            if (mode == ModifierMode.OneShot) input.key(key, false)
        }
        latch = latch.consumeOneShot()
    }

    fun sendKey(key: RemoteKey) {
        input.key(key, true)
        input.key(key, false)
        releaseOneShot()
    }

    fun cycleModifier(key: RemoteKey) {
        val previous = latch.mode(key)
        val next = latch.cycle(key)
        when {
            previous == ModifierMode.Off -> input.key(key, true)
            previous == ModifierMode.Locked -> input.key(key, false)
            else -> Unit
        }
        latch = next
    }

    NativeImeHost(
        active = visible,
        onCommittedText = { text ->
            if (text.isNotEmpty()) {
                input.typeCommittedText(text, layout)
                releaseOneShot()
            }
        },
        onKey = { key, pressed -> input.key(key, pressed) },
    )

    if (!visible) return

    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            color = consoleColors.controlSurface,
            contentColor = consoleColors.onSurface,
            tonalElevation = 0.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KeyChip(stringResource(R.string.console_key_escape)) { sendKey(RemoteKey.Escape) }
                    KeyChip(stringResource(R.string.console_key_tab)) { sendKey(RemoteKey.Tab) }
                    KeyChip(stringResource(R.string.console_key_enter)) { sendKey(RemoteKey.Enter) }
                    FilterChip(
                        selected = layout == KeyboardLayout.Uk,
                        onClick = {
                            onLayoutChange(
                                if (layout == KeyboardLayout.Us) KeyboardLayout.Uk else KeyboardLayout.Us,
                            )
                        },
                        label = {
                            Text(
                                stringResource(
                                    if (layout == KeyboardLayout.Us) {
                                        R.string.console_keyboard_layout_us
                                    } else {
                                        R.string.console_keyboard_layout_uk
                                    },
                                ),
                            )
                        },
                        modifier = Modifier.height(48.dp),
                        colors = consoleFilterChipColors(),
                    )
                    KeyChip(stringResource(R.string.console_view_top)) {
                        onViewportAction(ViewportAction.FocusTop)
                    }
                    KeyChip(stringResource(R.string.console_view_centre)) {
                        onViewportAction(ViewportAction.Center)
                    }
                    KeyChip(stringResource(R.string.console_view_bottom)) {
                        onViewportAction(ViewportAction.FocusBottom)
                    }
                    KeyChip(stringResource(R.string.console_zoom_out_short)) {
                        onViewportAction(ViewportAction.ZoomOut)
                    }
                    KeyChip(stringResource(R.string.console_zoom_in_short)) {
                        onViewportAction(ViewportAction.ZoomIn)
                    }
                    KeyChip(stringResource(R.string.console_key_backspace)) {
                        sendKey(RemoteKey.Backspace)
                    }
                    KeyChip(stringResource(R.string.console_key_delete)) {
                        sendKey(RemoteKey.Delete)
                    }
                    ModifierChip(
                        stringResource(R.string.console_key_control),
                        latch.mode(RemoteKey.Control),
                    ) {
                        cycleModifier(RemoteKey.Control)
                    }
                    ModifierChip(
                        stringResource(R.string.console_key_alt),
                        latch.mode(RemoteKey.Alt),
                    ) {
                        cycleModifier(RemoteKey.Alt)
                    }
                    ModifierChip(
                        stringResource(R.string.console_key_shift),
                        latch.mode(RemoteKey.Shift),
                    ) {
                        cycleModifier(RemoteKey.Shift)
                    }
                    ModifierChip(
                        stringResource(R.string.console_key_super),
                        latch.mode(RemoteKey.Super),
                    ) {
                        cycleModifier(RemoteKey.Super)
                    }
                    KeyChip(stringResource(R.string.console_key_arrow_left)) {
                        sendKey(RemoteKey.ArrowLeft)
                    }
                    KeyChip(stringResource(R.string.console_key_arrow_up)) {
                        sendKey(RemoteKey.ArrowUp)
                    }
                    KeyChip(stringResource(R.string.console_key_arrow_down)) {
                        sendKey(RemoteKey.ArrowDown)
                    }
                    KeyChip(stringResource(R.string.console_key_arrow_right)) {
                        sendKey(RemoteKey.ArrowRight)
                    }
                    KeyChip(stringResource(R.string.console_ctrl_alt_delete), onCtrlAltDelete)
                    FilterChip(
                        selected = showFunctionKeys,
                        onClick = { showFunctionKeys = !showFunctionKeys },
                        label = { Text(stringResource(R.string.console_function_keys)) },
                        modifier = Modifier.height(48.dp),
                        colors = consoleFilterChipColors(),
                    )
                    IconButton(
                        onClick = {
                            input.releaseAllInput()
                            onClose()
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = hideKeyboardDescription },
                    ) {
                        Icon(Icons.Default.KeyboardHide, contentDescription = null)
                    }
                }
                if (showFunctionKeys) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FunctionKeys.forEachIndexed { index, key ->
                            KeyChip(stringResource(R.string.console_function_key, index + 1)) {
                                sendKey(key)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyChip(label: String, onClick: () -> Unit) {
    val consoleColors = LocalConsoleColorScheme.current
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.height(48.dp),
        colors = AssistChipDefaults.assistChipColors(
            containerColor = consoleColors.controlSurfaceElevated,
            labelColor = consoleColors.onSurface,
        ),
    )
}

@Composable
private fun ModifierChip(label: String, mode: ModifierMode, onClick: () -> Unit) {
    FilterChip(
        selected = mode != ModifierMode.Off,
        onClick = onClick,
        label = {
            Text(
                when (mode) {
                    ModifierMode.Off -> label
                    ModifierMode.OneShot -> stringResource(
                        R.string.console_modifier_once,
                        label,
                    )
                    ModifierMode.Locked -> stringResource(
                        R.string.console_modifier_locked,
                        label,
                    )
                },
            )
        },
        modifier = Modifier.height(48.dp),
        colors = consoleFilterChipColors(),
    )
}

@Composable
private fun consoleFilterChipColors() = LocalConsoleColorScheme.current.let { consoleColors ->
    FilterChipDefaults.filterChipColors(
        containerColor = consoleColors.controlSurfaceElevated,
        labelColor = consoleColors.onSurface,
        selectedContainerColor = consoleColors.active,
        selectedLabelColor = consoleColors.onActive,
    )
}

@Composable
private fun NativeImeHost(
    active: Boolean,
    onCommittedText: (String) -> Unit,
    onKey: (RemoteKey, Boolean) -> Unit,
) {
    var sinkView by remember { mutableStateOf<ImeSinkView?>(null) }

    Box(Modifier.size(1.dp)) {
        AndroidView(
            factory = { context ->
                ImeSinkView(context).also {
                    it.onCommittedText = onCommittedText
                    it.onRemoteKey = onKey
                    sinkView = it
                }
            },
            update = {
                it.onCommittedText = onCommittedText
                it.onRemoteKey = onKey
            },
            modifier = Modifier.size(1.dp),
        )
    }

    LaunchedEffect(active, sinkView) {
        val view = sinkView ?: return@LaunchedEffect
        val inputMethod = view.context.getSystemService(InputMethodManager::class.java)
        if (active) {
            view.requestFocus()
            view.post { inputMethod.showSoftInput(view, 0) }
        } else {
            inputMethod.hideSoftInputFromWindow(view.windowToken, 0)
            view.clearFocus()
        }
    }

    DisposableEffect(sinkView) {
        onDispose {
            sinkView?.let { view ->
                view.context.getSystemService(InputMethodManager::class.java)
                    .hideSoftInputFromWindow(view.windowToken, 0)
            }
        }
    }
}

private class ImeSinkView(context: Context) : View(context) {
    var onCommittedText: (String) -> Unit = {}
    var onRemoteKey: (RemoteKey, Boolean) -> Unit = { _, _ -> }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        return KvmInputConnection()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val key = remoteKeyForAndroidKeyCode(keyCode) ?: return super.onKeyDown(keyCode, event)
        // The remote host owns key-repeat timing while the HID usage remains pressed. Android's
        // repeated ACTION_DOWN events would only duplicate identical network reports.
        if (event.repeatCount == 0) onRemoteKey(key, true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val key = remoteKeyForAndroidKeyCode(keyCode) ?: return super.onKeyUp(keyCode, event)
        onRemoteKey(key, false)
        return true
    }

    private inner class KvmInputConnection : BaseInputConnection(this@ImeSinkView, true) {
        private val localEditable = SpannableStringBuilder()

        override fun getEditable(): Editable = localEditable

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            text?.toString()?.takeIf(String::isNotEmpty)?.let(onCommittedText)
            localEditable.clear()
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val composing = getComposingSpanStart(localEditable) >= 0
            return if (composing || localEditable.isNotEmpty()) {
                super.deleteSurroundingText(beforeLength, afterLength)
            } else {
                repeat(beforeLength.coerceAtLeast(1)) {
                    onRemoteKey(RemoteKey.Backspace, true)
                    onRemoteKey(RemoteKey.Backspace, false)
                }
                true
            }
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            val key = remoteKeyForAndroidKeyCode(event.keyCode)
                ?: return super.sendKeyEvent(event)
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) onRemoteKey(key, true)
                KeyEvent.ACTION_UP -> onRemoteKey(key, false)
                else -> return super.sendKeyEvent(event)
            }
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            onRemoteKey(RemoteKey.Enter, true)
            onRemoteKey(RemoteKey.Enter, false)
            return true
        }
    }
}

/** Exact Android key-code to USB keyboard/keypad-page mapping for physical input devices. */
internal fun remoteKeyForAndroidKeyCode(keyCode: Int): RemoteKey? = when (keyCode) {
    KeyEvent.KEYCODE_A -> RemoteKey.A
    KeyEvent.KEYCODE_B -> RemoteKey.B
    KeyEvent.KEYCODE_C -> RemoteKey.C
    KeyEvent.KEYCODE_D -> RemoteKey.D
    KeyEvent.KEYCODE_E -> RemoteKey.E
    KeyEvent.KEYCODE_F -> RemoteKey.F
    KeyEvent.KEYCODE_G -> RemoteKey.G
    KeyEvent.KEYCODE_H -> RemoteKey.H
    KeyEvent.KEYCODE_I -> RemoteKey.I
    KeyEvent.KEYCODE_J -> RemoteKey.J
    KeyEvent.KEYCODE_K -> RemoteKey.K
    KeyEvent.KEYCODE_L -> RemoteKey.L
    KeyEvent.KEYCODE_M -> RemoteKey.M
    KeyEvent.KEYCODE_N -> RemoteKey.N
    KeyEvent.KEYCODE_O -> RemoteKey.O
    KeyEvent.KEYCODE_P -> RemoteKey.P
    KeyEvent.KEYCODE_Q -> RemoteKey.Q
    KeyEvent.KEYCODE_R -> RemoteKey.R
    KeyEvent.KEYCODE_S -> RemoteKey.S
    KeyEvent.KEYCODE_T -> RemoteKey.T
    KeyEvent.KEYCODE_U -> RemoteKey.U
    KeyEvent.KEYCODE_V -> RemoteKey.V
    KeyEvent.KEYCODE_W -> RemoteKey.W
    KeyEvent.KEYCODE_X -> RemoteKey.X
    KeyEvent.KEYCODE_Y -> RemoteKey.Y
    KeyEvent.KEYCODE_Z -> RemoteKey.Z
    KeyEvent.KEYCODE_1 -> RemoteKey.Digit1
    KeyEvent.KEYCODE_2 -> RemoteKey.Digit2
    KeyEvent.KEYCODE_3 -> RemoteKey.Digit3
    KeyEvent.KEYCODE_4 -> RemoteKey.Digit4
    KeyEvent.KEYCODE_5 -> RemoteKey.Digit5
    KeyEvent.KEYCODE_6 -> RemoteKey.Digit6
    KeyEvent.KEYCODE_7 -> RemoteKey.Digit7
    KeyEvent.KEYCODE_8 -> RemoteKey.Digit8
    KeyEvent.KEYCODE_9 -> RemoteKey.Digit9
    KeyEvent.KEYCODE_0 -> RemoteKey.Digit0
    KeyEvent.KEYCODE_ESCAPE -> RemoteKey.Escape
    KeyEvent.KEYCODE_TAB -> RemoteKey.Tab
    KeyEvent.KEYCODE_ENTER -> RemoteKey.Enter
    KeyEvent.KEYCODE_SPACE -> RemoteKey.Space
    KeyEvent.KEYCODE_DEL -> RemoteKey.Backspace
    KeyEvent.KEYCODE_FORWARD_DEL -> RemoteKey.Delete
    KeyEvent.KEYCODE_MINUS -> RemoteKey.Minus
    KeyEvent.KEYCODE_EQUALS -> RemoteKey.Equal
    KeyEvent.KEYCODE_LEFT_BRACKET -> RemoteKey.LeftBracket
    KeyEvent.KEYCODE_RIGHT_BRACKET -> RemoteKey.RightBracket
    KeyEvent.KEYCODE_BACKSLASH -> RemoteKey.Backslash
    KeyEvent.KEYCODE_POUND -> RemoteKey.NonUsHash
    KeyEvent.KEYCODE_SEMICOLON -> RemoteKey.Semicolon
    KeyEvent.KEYCODE_APOSTROPHE -> RemoteKey.Apostrophe
    KeyEvent.KEYCODE_GRAVE -> RemoteKey.Grave
    KeyEvent.KEYCODE_COMMA -> RemoteKey.Comma
    KeyEvent.KEYCODE_PERIOD -> RemoteKey.Period
    KeyEvent.KEYCODE_SLASH -> RemoteKey.Slash
    KeyEvent.KEYCODE_CAPS_LOCK -> RemoteKey.CapsLock
    KeyEvent.KEYCODE_SYSRQ -> RemoteKey.PrintScreen
    KeyEvent.KEYCODE_SCROLL_LOCK -> RemoteKey.ScrollLock
    KeyEvent.KEYCODE_BREAK -> RemoteKey.Pause
    KeyEvent.KEYCODE_INSERT -> RemoteKey.Insert
    KeyEvent.KEYCODE_MOVE_HOME -> RemoteKey.Home
    KeyEvent.KEYCODE_PAGE_UP -> RemoteKey.PageUp
    KeyEvent.KEYCODE_MOVE_END -> RemoteKey.End
    KeyEvent.KEYCODE_PAGE_DOWN -> RemoteKey.PageDown
    KeyEvent.KEYCODE_DPAD_UP -> RemoteKey.ArrowUp
    KeyEvent.KEYCODE_DPAD_DOWN -> RemoteKey.ArrowDown
    KeyEvent.KEYCODE_DPAD_LEFT -> RemoteKey.ArrowLeft
    KeyEvent.KEYCODE_DPAD_RIGHT -> RemoteKey.ArrowRight
    KeyEvent.KEYCODE_NUM_LOCK -> RemoteKey.NumLock
    KeyEvent.KEYCODE_NUMPAD_DIVIDE -> RemoteKey.NumpadDivide
    KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> RemoteKey.NumpadMultiply
    KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> RemoteKey.NumpadSubtract
    KeyEvent.KEYCODE_NUMPAD_ADD -> RemoteKey.NumpadAdd
    KeyEvent.KEYCODE_NUMPAD_ENTER -> RemoteKey.NumpadEnter
    KeyEvent.KEYCODE_NUMPAD_1 -> RemoteKey.Numpad1
    KeyEvent.KEYCODE_NUMPAD_2 -> RemoteKey.Numpad2
    KeyEvent.KEYCODE_NUMPAD_3 -> RemoteKey.Numpad3
    KeyEvent.KEYCODE_NUMPAD_4 -> RemoteKey.Numpad4
    KeyEvent.KEYCODE_NUMPAD_5 -> RemoteKey.Numpad5
    KeyEvent.KEYCODE_NUMPAD_6 -> RemoteKey.Numpad6
    KeyEvent.KEYCODE_NUMPAD_7 -> RemoteKey.Numpad7
    KeyEvent.KEYCODE_NUMPAD_8 -> RemoteKey.Numpad8
    KeyEvent.KEYCODE_NUMPAD_9 -> RemoteKey.Numpad9
    KeyEvent.KEYCODE_NUMPAD_0 -> RemoteKey.Numpad0
    KeyEvent.KEYCODE_NUMPAD_DOT -> RemoteKey.NumpadDecimal
    KeyEvent.KEYCODE_NUMPAD_COMMA -> RemoteKey.NumpadComma
    KeyEvent.KEYCODE_NUMPAD_EQUALS -> RemoteKey.NumpadEqual
    KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN -> RemoteKey.NumpadLeftParen
    KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN -> RemoteKey.NumpadRightParen
    KeyEvent.KEYCODE_MENU -> RemoteKey.ContextMenu
    KeyEvent.KEYCODE_HELP -> RemoteKey.Help
    KeyEvent.KEYCODE_CUT -> RemoteKey.Cut
    KeyEvent.KEYCODE_COPY -> RemoteKey.Copy
    KeyEvent.KEYCODE_PASTE -> RemoteKey.Paste
    KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE -> RemoteKey.VolumeMute
    KeyEvent.KEYCODE_VOLUME_UP -> RemoteKey.VolumeUp
    KeyEvent.KEYCODE_VOLUME_DOWN -> RemoteKey.VolumeDown
    KeyEvent.KEYCODE_CTRL_LEFT -> RemoteKey.Control
    KeyEvent.KEYCODE_SHIFT_LEFT -> RemoteKey.Shift
    KeyEvent.KEYCODE_ALT_LEFT -> RemoteKey.Alt
    KeyEvent.KEYCODE_META_LEFT -> RemoteKey.Super
    KeyEvent.KEYCODE_CTRL_RIGHT -> RemoteKey.RightControl
    KeyEvent.KEYCODE_SHIFT_RIGHT -> RemoteKey.RightShift
    KeyEvent.KEYCODE_ALT_RIGHT -> RemoteKey.RightAlt
    KeyEvent.KEYCODE_META_RIGHT -> RemoteKey.RightSuper
    KeyEvent.KEYCODE_F1 -> RemoteKey.F1
    KeyEvent.KEYCODE_F2 -> RemoteKey.F2
    KeyEvent.KEYCODE_F3 -> RemoteKey.F3
    KeyEvent.KEYCODE_F4 -> RemoteKey.F4
    KeyEvent.KEYCODE_F5 -> RemoteKey.F5
    KeyEvent.KEYCODE_F6 -> RemoteKey.F6
    KeyEvent.KEYCODE_F7 -> RemoteKey.F7
    KeyEvent.KEYCODE_F8 -> RemoteKey.F8
    KeyEvent.KEYCODE_F9 -> RemoteKey.F9
    KeyEvent.KEYCODE_F10 -> RemoteKey.F10
    KeyEvent.KEYCODE_F11 -> RemoteKey.F11
    KeyEvent.KEYCODE_F12 -> RemoteKey.F12
    else -> null
}

private val FunctionKeys = listOf(
    RemoteKey.F1,
    RemoteKey.F2,
    RemoteKey.F3,
    RemoteKey.F4,
    RemoteKey.F5,
    RemoteKey.F6,
    RemoteKey.F7,
    RemoteKey.F8,
    RemoteKey.F9,
    RemoteKey.F10,
    RemoteKey.F11,
    RemoteKey.F12,
)
