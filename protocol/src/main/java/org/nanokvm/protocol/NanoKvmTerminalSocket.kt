package org.nanokvm.protocol

import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.Closeable

sealed interface NanoKvmTerminalConnectionState {
    val generation: Long?

    data object Disconnected : NanoKvmTerminalConnectionState {
        override val generation: Long? = null
    }

    data class Connecting(override val generation: Long) : NanoKvmTerminalConnectionState

    data class Connected(override val generation: Long) : NanoKvmTerminalConnectionState

    data class Closing(override val generation: Long) : NanoKvmTerminalConnectionState

    data class Failed(
        override val generation: Long,
        val cause: Throwable,
        val httpStatus: Int? = null,
    ) : NanoKvmTerminalConnectionState
}

sealed interface NanoKvmTerminalEvent {
    val generation: Long

    /** A defensively retained raw PTY output chunk. */
    class Output internal constructor(
        override val generation: Long,
        bytes: ByteArray,
    ) : NanoKvmTerminalEvent {
        private val retainedBytes = bytes.copyOf()

        val size: Int
            get() = retainedBytes.size

        fun copyBytes(): ByteArray = retainedBytes.copyOf()
    }

    data class ProtocolViolation(
        override val generation: Long,
        val reason: String,
    ) : NanoKvmTerminalEvent

    data class PeerClosing(
        override val generation: Long,
        val code: Int,
        val reason: String,
    ) : NanoKvmTerminalEvent

    data class Closed(
        override val generation: Long,
        val code: Int,
        val reason: String,
    ) : NanoKvmTerminalEvent

    data class Failure(
        override val generation: Long,
        val cause: Throwable,
        val httpStatus: Int?,
    ) : NanoKvmTerminalEvent
}

/**
 * One explicit connection to NanoKVM 2.4.3's root PTY WebSocket (`/api/vm/terminal`).
 *
 * There is no automatic reconnect, replay, input queue, heartbeat, or command retry. A listener is
 * bound to a monotonically increasing generation so callbacks from an old socket cannot affect a
 * replacement connection. Server output is binary-only and is retained in at most eight bounded
 * chunks for slow collectors.
 */
class NanoKvmTerminalSocket internal constructor(
    private val endpoint: NanoKvmEndpoint,
    private val httpClient: OkHttpClient,
    private val tokenStore: SessionTokenStore,
) : Closeable {
    private val lock = Any()
    private val mutableState = MutableStateFlow<NanoKvmTerminalConnectionState>(
        NanoKvmTerminalConnectionState.Disconnected,
    )
    private val mutableEvents = MutableSharedFlow<NanoKvmTerminalEvent>(
        replay = 0,
        extraBufferCapacity = MAX_RETAINED_TERMINAL_CHUNKS,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var socket: WebSocket? = null
    private var activeGeneration: Long? = null
    private var nextGeneration = 1L
    private var disposed = false
    private var serialExitGeneration: Long? = null

    val state: StateFlow<NanoKvmTerminalConnectionState> = mutableState.asStateFlow()
    val events: SharedFlow<NanoKvmTerminalEvent> = mutableEvents.asSharedFlow()

    /** Starts exactly one asynchronous handshake. A later reconnect must be requested explicitly. */
    fun connect(): Boolean = synchronized(lock) {
        check(!disposed) { "This terminal socket has been disposed" }
        if (socket != null) return@synchronized false

        val token = tokenStore.read() ?: throw AuthenticationExpiredException()
        validateCookieValue(token)
        val generation = nextGeneration++
        val requestBuilder = Request.Builder()
            .url(endpoint.webSocketUrl(TERMINAL_PATH))
            .header("Cookie", "nano-kvm-token=$token")

        activeGeneration = generation
        serialExitGeneration = null
        mutableState.value = NanoKvmTerminalConnectionState.Connecting(generation)
        socket = httpClient.newWebSocket(requestBuilder.build(), Listener(generation))
        true
    }

    /** Sends raw PTY input as one WebSocket text frame; it is never queued or replayed. */
    fun sendInput(text: String): Boolean {
        require(text.hasBoundedUtf8Length(MAX_TERMINAL_CLIENT_TEXT_UTF8_BYTES)) {
            "Terminal input exceeds $MAX_TERMINAL_CLIENT_TEXT_UTF8_BYTES UTF-8 bytes"
        }
        return synchronized(lock) {
            val current = socket ?: return@synchronized false
            if (mutableState.value !is NanoKvmTerminalConnectionState.Connected) {
                return@synchronized false
            }
            current.send(text)
        }
    }

    /** Sends NanoKVM's binary JSON resize message with uint16 rows and columns. */
    fun resize(size: NanoKvmTerminalSize): Boolean = synchronized(lock) {
        val current = socket ?: return@synchronized false
        if (mutableState.value !is NanoKvmTerminalConnectionState.Connected) {
            return@synchronized false
        }
        val frame = JSON.encodeToString(
            TerminalResizeRequest(rows = size.rows, cols = size.columns),
        ).encodeToByteArray()
        current.send(frame.toByteString())
    }

    /** Starts picocom with a command composed only from typed, allowlisted fields. */
    fun startSerial(configuration: NanoKvmSerialConfiguration = NanoKvmSerialConfiguration()): Boolean =
        sendInput(configuration.toPicocomCommand())

    /**
     * Sends picocom's Ctrl-A Ctrl-X exit sequence once, waits 100 ms, then closes this generation.
     * A replacement connection created during the delay is never closed by the old operation.
     */
    suspend fun exitSerialAndDisconnect(): Boolean {
        val generation = synchronized(lock) {
            val currentGeneration = activeGeneration ?: return false
            val current = socket ?: return false
            if (mutableState.value !is NanoKvmTerminalConnectionState.Connected) return false
            if (serialExitGeneration == currentGeneration) return false
            if (!current.send(SERIAL_EXIT_SEQUENCE)) return false
            serialExitGeneration = currentGeneration
            currentGeneration
        }

        delay(SERIAL_EXIT_CLOSE_DELAY_MILLIS)
        disconnectGeneration(generation, reason = "serial terminal exit")
        return true
    }

    /** Gracefully closes the current generation; server-side close kills the spawned shell. */
    fun disconnect(code: Int = 1000, reason: String = "terminal disconnect") {
        val generation = synchronized(lock) { activeGeneration } ?: return
        disconnectGeneration(generation, code, reason)
    }

    /** Cancels an in-flight or open connection without creating a replacement. */
    fun cancel() {
        synchronized(lock) {
            val current = socket ?: return
            current.cancel()
            clearCurrentLocked(current)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (disposed) return
            disposed = true
            val current = socket
            val generation = activeGeneration
            if (current != null && generation != null) {
                mutableState.value = NanoKvmTerminalConnectionState.Closing(generation)
                if (!current.close(1000, "terminal disposed")) {
                    current.cancel()
                    clearCurrentLocked(current)
                }
            } else {
                mutableState.value = NanoKvmTerminalConnectionState.Disconnected
            }
        }
    }

    private fun disconnectGeneration(
        generation: Long,
        code: Int = 1000,
        reason: String,
    ) {
        synchronized(lock) {
            if (activeGeneration != generation) return
            val current = socket ?: return
            mutableState.value = NanoKvmTerminalConnectionState.Closing(generation)
            if (!current.close(code, reason.take(MAX_SAFE_CLOSE_REASON_CHARS))) {
                current.cancel()
                clearCurrentLocked(current)
            }
        }
    }

    private fun clearCurrentLocked(current: WebSocket) {
        if (socket !== current) return
        socket = null
        activeGeneration = null
        serialExitGeneration = null
        mutableState.value = NanoKvmTerminalConnectionState.Disconnected
    }

    private inner class Listener(
        private val generation: Long,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (!isCurrentLocked(webSocket, generation) || disposed) {
                    webSocket.close(1000, "stale terminal connection")
                    return
                }
                mutableState.value = NanoKvmTerminalConnectionState.Connected(generation)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!isCurrent(webSocket, generation)) return
            if (bytes.size > MAX_TERMINAL_SERVER_CHUNK_BYTES) {
                closeForProtocolViolation(
                    webSocket,
                    generation,
                    MESSAGE_TOO_BIG_CLOSE_CODE,
                    "terminal output chunk too large",
                )
                return
            }
            mutableEvents.tryEmit(
                NanoKvmTerminalEvent.Output(generation, bytes.toByteArray()),
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(webSocket, generation)) return
            closeForProtocolViolation(
                webSocket,
                generation,
                UNSUPPORTED_DATA_CLOSE_CODE,
                "terminal server sent a text frame",
            )
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(lock) {
                if (!isCurrentLocked(webSocket, generation)) return
                mutableState.value = NanoKvmTerminalConnectionState.Closing(generation)
                mutableEvents.tryEmit(NanoKvmTerminalEvent.PeerClosing(generation, code, reason))
                webSocket.close(code, reason.take(MAX_SAFE_CLOSE_REASON_CHARS))
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(lock) {
                if (!isCurrentLocked(webSocket, generation)) return
                mutableEvents.tryEmit(NanoKvmTerminalEvent.Closed(generation, code, reason))
                clearCurrentLocked(webSocket)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(lock) {
                if (!isCurrentLocked(webSocket, generation)) {
                    response?.close()
                    return
                }
                val cause = if (response?.code == 401) {
                    tokenStore.write(null)
                    AuthenticationExpiredException()
                } else {
                    t
                }
                socket = null
                activeGeneration = null
                serialExitGeneration = null
                mutableState.value = NanoKvmTerminalConnectionState.Failed(
                    generation,
                    cause,
                    response?.code,
                )
                mutableEvents.tryEmit(
                    NanoKvmTerminalEvent.Failure(generation, cause, response?.code),
                )
                response?.close()
            }
        }
    }

    private fun closeForProtocolViolation(
        webSocket: WebSocket,
        generation: Long,
        code: Int,
        reason: String,
    ) {
        synchronized(lock) {
            if (!isCurrentLocked(webSocket, generation)) return
            mutableEvents.tryEmit(NanoKvmTerminalEvent.ProtocolViolation(generation, reason))
            mutableState.value = NanoKvmTerminalConnectionState.Closing(generation)
            if (!webSocket.close(code, reason)) {
                webSocket.cancel()
                clearCurrentLocked(webSocket)
            }
        }
    }

    private fun isCurrent(webSocket: WebSocket, generation: Long): Boolean = synchronized(lock) {
        isCurrentLocked(webSocket, generation)
    }

    private fun isCurrentLocked(webSocket: WebSocket, generation: Long): Boolean =
        socket === webSocket && activeGeneration == generation

    companion object {
        private val JSON = Json { explicitNulls = false }
        private const val TERMINAL_PATH = "/api/vm/terminal"
        private const val MAX_RETAINED_TERMINAL_CHUNKS = 8
        // Any 30 UTF-16 code units encode to at most 120 UTF-8 bytes, below RFC 6455's 123-byte
        // close-reason limit without allocating an attacker-sized encoded copy.
        private const val MAX_SAFE_CLOSE_REASON_CHARS = 30
        private const val UNSUPPORTED_DATA_CLOSE_CODE = 1003
        private const val MESSAGE_TOO_BIG_CLOSE_CODE = 1009
        private const val SERIAL_EXIT_SEQUENCE = "\u0001\u0018"
        private const val SERIAL_EXIT_CLOSE_DELAY_MILLIS = 100L
    }
}

/** Counts UTF-8 bytes without allocating a second attacker-sized byte array. */
internal fun String.hasBoundedUtf8Length(limit: Int): Boolean {
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
