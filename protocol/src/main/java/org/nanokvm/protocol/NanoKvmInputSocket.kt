package org.nanokvm.protocol

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

sealed interface InputConnectionState {
    data object Disconnected : InputConnectionState
    data object Connecting : InputConnectionState
    data object Connected : InputConnectionState
    data object Closing : InputConnectionState
    data class Failed(
        val cause: Throwable,
        val httpStatus: Int? = null,
    ) : InputConnectionState
}

data class CommittedTextResult(
    val sentKeystrokes: Int,
    val unsupported: List<UnsupportedCodePoint>,
    val connectionLost: Boolean,
)

/** Delay between completed HID press/release pairs in a paced committed-text operation. */
data class CommittedTextPacing(
    val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
) {
    init {
        require(intervalMillis in MIN_INTERVAL_MILLIS..MAX_INTERVAL_MILLIS) {
            "Committed-text interval must be between $MIN_INTERVAL_MILLIS and " +
                "$MAX_INTERVAL_MILLIS milliseconds"
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MILLIS = 30L
        const val MIN_INTERVAL_MILLIS = 10L
        const val MAX_INTERVAL_MILLIS = 250L
    }
}

data class PacedCommittedTextProgress(
    val sentKeystrokes: Int,
    val totalKeystrokes: Int,
) {
    init {
        require(totalKeystrokes >= 0) { "Total keystrokes must not be negative" }
        require(sentKeystrokes in 0..totalKeystrokes) {
            "Sent keystrokes must be within the operation total"
        }
    }
}

sealed interface PacedCommittedTextResult {
    val sentKeystrokes: Int

    data class Completed(
        override val sentKeystrokes: Int,
    ) : PacedCommittedTextResult

    /** Mapping failed during preflight, so no keyboard frames were sent. */
    data class Unsupported(
        val unsupported: List<UnsupportedCodePoint>,
    ) : PacedCommittedTextResult {
        override val sentKeystrokes: Int = 0
    }

    /** The connection stopped accepting frames. The operation is never retried automatically. */
    data class ConnectionLost(
        override val sentKeystrokes: Int,
    ) : PacedCommittedTextResult
}

/**
 * NanoKVM's binary HID WebSocket (`/api/ws`). This class is reusable across reconnects.
 *
 * Motion can be coalesced by callers, but key/button release reports must never be discarded.
 * [disconnect] always attempts keyboard and mouse releases before the close handshake.
 */
class NanoKvmInputSocket internal constructor(
    private val endpoint: NanoKvmEndpoint,
    private val httpClient: OkHttpClient,
    private val tokenStore: SessionTokenStore,
    private val heartbeatIntervalMillis: Long,
) : Closeable {
    private val lock = Any()
    private val mutableState = MutableStateFlow<InputConnectionState>(InputConnectionState.Disconnected)
    private val heartbeatExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "nanokvm-input-heartbeat").apply { isDaemon = true }
    }

    private var socket: WebSocket? = null
    private var heartbeat: ScheduledFuture<*>? = null
    private var lastAbsoluteX = 0
    private var lastAbsoluteY = 0
    private var absoluteMouseWasUsed = false
    private var disposed = false

    val state: StateFlow<InputConnectionState> = mutableState.asStateFlow()

    init {
        require(heartbeatIntervalMillis >= 1_000L) { "Heartbeat interval must be at least one second" }
    }

    /** Starts the asynchronous WebSocket handshake. */
    fun connect(): Boolean = synchronized(lock) {
        check(!disposed) { "This input socket has been disposed" }
        if (socket != null) return@synchronized false

        mutableState.value = InputConnectionState.Connecting
        val requestBuilder = Request.Builder()
            .url(endpoint.webSocketUrl("/api/ws"))
            .header("Accept", "application/json")
        tokenStore.read()?.let { token ->
            validateCookieValue(token)
            requestBuilder.header("Cookie", "nano-kvm-token=$token")
        }
        val request = requestBuilder.build()
        socket = httpClient.newWebSocket(request, Listener())
        true
    }

    fun sendKeyboard(report: HidKeyboardReport): Boolean =
        sendFrame(report.toWireFrame())

    /** Sends one key while preserving modifiers already held by the console accessory tray. */
    fun sendKeystroke(
        stroke: HidKeystroke,
        heldModifiers: Set<HidModifier> = emptySet(),
    ): Boolean = sendKeyboardChord(
        modifiers = stroke.modifiers,
        keys = listOf(stroke.usage),
        heldModifiers = heldModifiers,
    )

    /** Sends one complete multi-key chord and restores intentionally held modifiers atomically. */
    fun sendKeyboardChord(
        modifiers: Set<HidModifier>,
        keys: Collection<HidUsage>,
        heldModifiers: Set<HidModifier> = emptySet(),
    ): Boolean = synchronized(lock) {
        val effectiveModifiers = heldModifiers + modifiers
        if (mutableState.value !is InputConnectionState.Connected) return@synchronized false
        val current = socket ?: return@synchronized false
        if (!current.send(
                HidKeyboardReport.create(effectiveModifiers, keys)
                    .toWireFrame()
                    .toByteString(),
            )
        ) {
            return@synchronized false
        }
        current.send(HidKeyboardReport.create(heldModifiers).toWireFrame().toByteString())
    }

    /**
     * Runs one saved shortcut using the exact 2.4.3 WebUI algorithm: an incremental complete HID
     * report after each recorded key, followed by one all-keys release. Unknown future codes and
     * entries beyond the WebUI's six-key recorder limit are rejected before any frame is sent.
     * A failed dispatch is not replayed; only a best-effort safety release is attempted.
     */
    fun sendSavedHidShortcut(
        shortcut: NanoKvmSavedHidShortcut,
    ): NanoKvmHidShortcutRunResult {
        val plan = shortcut.toRunPlan()
        if (plan is HidShortcutRunPlan.Rejected) return plan.result
        plan as HidShortcutRunPlan.Reports

        return synchronized(lock) {
            if (mutableState.value !is InputConnectionState.Connected) {
                return@synchronized NanoKvmHidShortcutRunResult.ConnectionLost(reportsSent = 0)
            }
            val current = socket
                ?: return@synchronized NanoKvmHidShortcutRunResult.ConnectionLost(reportsSent = 0)
            var sent = 0
            for (report in plan.reports) {
                if (!current.send(report.toWireFrame().toByteString())) {
                    current.send(HidKeyboardReport.released().toWireFrame().toByteString())
                    return@synchronized NanoKvmHidShortcutRunResult.ConnectionLost(
                        reportsSent = sent,
                    )
                }
                sent++
            }
            NanoKvmHidShortcutRunResult.Completed(reportsSent = sent)
        }
    }

    /** Maps committed IME text to the configured target layout and sends press/release pairs. */
    fun sendCommittedText(
        text: String,
        layout: KeyboardLayout = KeyboardLayout.US,
        heldModifiers: Set<HidModifier> = emptySet(),
    ): CommittedTextResult {
        val mapping = HidCharacterMapper.mapText(text, layout)
        var sent = 0
        for (stroke in mapping.keystrokes) {
            if (!sendKeystroke(stroke, heldModifiers)) {
                // Release the character while preserving intentional modifier latches.
                sendKeyboard(HidKeyboardReport.create(heldModifiers))
                return CommittedTextResult(sent, mapping.unsupported, connectionLost = true)
            }
            sent++
        }
        return CommittedTextResult(sent, mapping.unsupported, connectionLost = false)
    }

    /**
     * Preflights and then types committed text as cancellable, paced HID press/release pairs.
     *
     * Unsupported code points reject the complete operation before any frame is sent. Cancellation
     * is checked only outside an atomic press/release pair, so each accepted character restores
     * [heldModifiers] before cancellation can stop the next one. Connection loss returns the count
     * of complete pairs and is never retried. [onProgress] is invoked in the caller's coroutine and
     * should return promptly.
     */
    suspend fun sendPacedCommittedText(
        text: String,
        layout: KeyboardLayout = KeyboardLayout.US,
        heldModifiers: Set<HidModifier> = emptySet(),
        pacing: CommittedTextPacing = CommittedTextPacing(),
        onProgress: (PacedCommittedTextProgress) -> Unit = {},
    ): PacedCommittedTextResult {
        val mapping = HidCharacterMapper.mapText(text, layout)
        if (mapping.unsupported.isNotEmpty()) {
            return PacedCommittedTextResult.Unsupported(mapping.unsupported.toList())
        }

        val preservedModifiers = heldModifiers.toSet()
        val total = mapping.keystrokes.size
        var sent = 0
        currentCoroutineContext().ensureActive()
        onProgress(PacedCommittedTextProgress(sentKeystrokes = 0, totalKeystrokes = total))

        mapping.keystrokes.forEachIndexed { index, stroke ->
            currentCoroutineContext().ensureActive()
            if (!sendKeystroke(stroke, preservedModifiers)) {
                // A failed release is treated as a failed pair; attempt to restore latches without
                // claiming success. The WebSocket queue is never recreated or retried here.
                sendKeyboard(HidKeyboardReport.create(preservedModifiers))
                return PacedCommittedTextResult.ConnectionLost(sentKeystrokes = sent)
            }
            sent++
            onProgress(PacedCommittedTextProgress(sentKeystrokes = sent, totalKeystrokes = total))
            currentCoroutineContext().ensureActive()

            if (index < mapping.keystrokes.lastIndex) {
                if (mutableState.value !is InputConnectionState.Connected) {
                    return PacedCommittedTextResult.ConnectionLost(sentKeystrokes = sent)
                }
                delay(pacing.intervalMillis)
            }
        }

        currentCoroutineContext().ensureActive()
        return PacedCommittedTextResult.Completed(sentKeystrokes = sent)
    }

    fun sendMouse(report: HidMouseReport): Boolean {
        val sent = sendFrame(report.toWireFrame())
        if (sent && report is AbsoluteMouseReport) synchronized(lock) {
            lastAbsoluteX = report.x
            lastAbsoluteY = report.y
            absoluteMouseWasUsed = true
        }
        return sent
    }

    /**
     * Sends horizontal-scroll compatibility as one serialized Shift, wheel, restore transaction.
     * The restore is attempted even when the wheel frame fails so synthetic Shift cannot leak into
     * later keyboard input.
     */
    fun sendShiftWheel(
        shiftedKeyboard: HidKeyboardReport,
        mouse: HidMouseReport,
        restoredKeyboard: HidKeyboardReport,
    ): Boolean = synchronized(lock) {
        if (mutableState.value !is InputConnectionState.Connected) return@synchronized false
        val current = socket ?: return@synchronized false
        if (!current.send(shiftedKeyboard.toWireFrame().toByteString())) return@synchronized false
        val mouseSent = current.send(mouse.toWireFrame().toByteString())
        val restoreSent = current.send(restoredKeyboard.toWireFrame().toByteString())
        if (mouseSent && mouse is AbsoluteMouseReport) {
            lastAbsoluteX = mouse.x
            lastAbsoluteY = mouse.y
            absoluteMouseWasUsed = true
        }
        mouseSent && restoreSent
    }

    /** Gracefully disconnects, attempting all key/button releases first. */
    fun disconnect(code: Int = 1000, reason: String = "client disconnect") {
        synchronized(lock) {
            val current = socket ?: run {
                mutableState.value = InputConnectionState.Disconnected
                return
            }
            stopHeartbeatLocked()
            mutableState.value = InputConnectionState.Closing
            sendReleaseFramesLocked(current)
            if (!current.close(code, reason.take(123))) current.cancel()
        }
    }

    /** Cancels an in-flight handshake after trying releases when possible. */
    fun cancel() {
        synchronized(lock) {
            val current = socket ?: return
            stopHeartbeatLocked()
            sendReleaseFramesLocked(current)
            current.cancel()
            socket = null
            mutableState.value = InputConnectionState.Disconnected
        }
    }

    override fun close() {
        synchronized(lock) {
            if (disposed) return
            disposed = true
            stopHeartbeatLocked()
            socket?.let { current ->
                mutableState.value = InputConnectionState.Closing
                sendReleaseFramesLocked(current)
                if (!current.close(1000, "client disposed")) current.cancel()
            } ?: run {
                mutableState.value = InputConnectionState.Disconnected
            }
            heartbeatExecutor.shutdownNow()
        }
    }

    private fun sendFrame(bytes: ByteArray): Boolean = synchronized(lock) {
        if (mutableState.value !is InputConnectionState.Connected) return@synchronized false
        socket?.send(bytes.toByteString()) == true
    }

    private fun sendReleaseFramesLocked(current: WebSocket) {
        current.send(HidKeyboardReport.released().toWireFrame().toByteString())
        current.send(RelativeMouseReport.create().toWireFrame().toByteString())
        if (absoluteMouseWasUsed) {
            current.send(
                AbsoluteMouseReport.create(x = lastAbsoluteX, y = lastAbsoluteY)
                    .toWireFrame()
                    .toByteString(),
            )
        }
    }

    private fun startHeartbeatLocked(current: WebSocket) {
        stopHeartbeatLocked()
        heartbeat = heartbeatExecutor.scheduleWithFixedDelay(
            {
                synchronized(lock) {
                    if (socket === current && mutableState.value is InputConnectionState.Connected) {
                        current.send(byteArrayOf(MESSAGE_HEARTBEAT).toByteString())
                    }
                }
            },
            heartbeatIntervalMillis,
            heartbeatIntervalMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun stopHeartbeatLocked() {
        heartbeat?.cancel(false)
        heartbeat = null
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (socket !== webSocket || disposed) {
                    webSocket.close(1000, "stale connection")
                    return
                }
                mutableState.value = InputConnectionState.Connected
                startHeartbeatLocked(webSocket)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(webSocket)) return
            if (!isInputTextMessageWithinLimit(text)) {
                closeForOversizedMessage(webSocket, "input text message too large")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!isCurrent(webSocket)) return
            if (!isInputBinaryMessageWithinLimit(bytes)) {
                closeForOversizedMessage(webSocket, "input binary message too large")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(lock) {
                if (socket !== webSocket) return
                stopHeartbeatLocked()
                mutableState.value = InputConnectionState.Closing
                sendReleaseFramesLocked(webSocket)
                webSocket.close(code, reason.take(123))
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(lock) {
                if (socket !== webSocket) return
                stopHeartbeatLocked()
                socket = null
                mutableState.value = InputConnectionState.Disconnected
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(lock) {
                if (socket !== webSocket) return
                stopHeartbeatLocked()
                socket = null
                mutableState.value = InputConnectionState.Failed(t, response?.code)
                response?.close()
            }
        }

        private fun isCurrent(webSocket: WebSocket): Boolean = synchronized(lock) {
            socket === webSocket
        }
    }

    /**
     * Stops command acceptance before beginning the close handshake. OkHttp delivers only a
     * complete WebSocket message, so this cannot undo its first transport allocation; it does
     * prevent further HID work and lets the client-level close timeout tear down an uncooperative
     * peer promptly.
     */
    private fun closeForOversizedMessage(webSocket: WebSocket, reason: String) {
        synchronized(lock) {
            if (socket !== webSocket) return
            stopHeartbeatLocked()
            mutableState.value = InputConnectionState.Closing
            sendReleaseFramesLocked(webSocket)
            if (!webSocket.close(MESSAGE_TOO_BIG_CLOSE_CODE, reason)) {
                webSocket.cancel()
                socket = null
                mutableState.value = InputConnectionState.Disconnected
            }
        }
    }
}

internal const val MAX_INPUT_SERVER_MESSAGE_BYTES = 64 * 1024
private const val MESSAGE_TOO_BIG_CLOSE_CODE = 1009

internal fun isInputBinaryMessageWithinLimit(bytes: ByteString): Boolean =
    bytes.size <= MAX_INPUT_SERVER_MESSAGE_BYTES

internal fun isInputTextMessageWithinLimit(text: String): Boolean =
    text.hasUtf8LengthAtMost(MAX_INPUT_SERVER_MESSAGE_BYTES)

/** Counts UTF-8 bytes without creating a second, potentially attacker-sized byte array. */
private fun String.hasUtf8LengthAtMost(limit: Int): Boolean {
    var bytes = 0
    var index = 0
    while (index < length) {
        val value = this[index]
        val byteCount = when {
            value.code < 0x80 -> 1
            value.code < 0x800 -> 2
            value.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> {
                index++
                4
            }
            else -> 3
        }
        bytes += byteCount
        if (bytes > limit) return false
        index++
    }
    return true
}
