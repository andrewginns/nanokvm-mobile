package org.nanokvm.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class NanoKvmInputControl { UNMANAGED, WAITING, VIEW_ONLY, CONTROLLING }

/** Per-client, per-socket secret; never included in public state, diagnostics or data-class output. */
internal class NanoKvmInputLease {
    private var owner: Any? = null
    private var lease: String? = null
    private val mutableControl = MutableStateFlow(NanoKvmInputControl.UNMANAGED)
    val control = mutableControl.asStateFlow()

    @Synchronized fun begin(socketOwner: Any) {
        owner = socketOwner
        lease = null
        mutableControl.value = NanoKvmInputControl.WAITING
    }

    @Synchronized fun update(socketOwner: Any, enabled: Boolean, value: String?) {
        if (owner !== socketOwner) return
        lease = value?.takeIf { enabled && Regex("[0-9a-f]{32}").matches(it) }
        mutableControl.value = if (lease != null) NanoKvmInputControl.CONTROLLING
            else NanoKvmInputControl.VIEW_ONLY
    }

    @Synchronized fun clear(socketOwner: Any) {
        if (owner !== socketOwner) return
        owner = null
        lease = null
        mutableControl.value = NanoKvmInputControl.WAITING
    }

    @Synchronized fun reset() {
        owner = null
        lease = null
        mutableControl.value = NanoKvmInputControl.UNMANAGED
    }

    @Synchronized fun allows(socketOwner: Any): Boolean = owner === socketOwner && lease != null
    @Synchronized fun read(): String? = lease
}

@Serializable
private data class ControlEnvelope(val type: String, val data: String = "")
@Serializable
private data class ControlPayload(val enabled: Boolean, val lease: String? = null)

private val controlJson = Json { ignoreUnknownKeys = true }

/** The OS encodes data as a JSON string, not a nested object. Ignore unrelated notifications. */
internal fun NanoKvmInputLease.receive(owner: Any, text: String) {
    val envelope = runCatching { controlJson.decodeFromString<ControlEnvelope>(text) }.getOrNull()
        ?: return
    if (envelope.type != "control") return
    val payload = runCatching { controlJson.decodeFromString<ControlPayload>(envelope.data) }
        .getOrNull()
    update(owner, payload?.enabled == true, payload?.lease)
}
