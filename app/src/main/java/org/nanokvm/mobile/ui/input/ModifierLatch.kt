package org.nanokvm.mobile.ui.input

import org.nanokvm.mobile.runtime.RemoteKey

enum class ModifierMode { Off, OneShot, Locked }

data class ModifierLatch(
    val modes: Map<RemoteKey, ModifierMode> = emptyMap(),
) {
    fun cycle(key: RemoteKey): ModifierLatch {
        require(key in ModifierKeys) { "$key is not a modifier" }
        val next = when (modes[key] ?: ModifierMode.Off) {
            ModifierMode.Off -> ModifierMode.OneShot
            ModifierMode.OneShot -> ModifierMode.Locked
            ModifierMode.Locked -> ModifierMode.Off
        }
        return copy(modes = modes + (key to next))
    }

    fun mode(key: RemoteKey): ModifierMode = modes[key] ?: ModifierMode.Off

    fun activeKeys(): Set<RemoteKey> = modes.filterValues { it != ModifierMode.Off }.keys

    fun consumeOneShot(): ModifierLatch = copy(
        modes = modes.mapValues { (_, mode) ->
            if (mode == ModifierMode.OneShot) ModifierMode.Off else mode
        },
    )

    companion object {
        val ModifierKeys = setOf(
            RemoteKey.Control,
            RemoteKey.Alt,
            RemoteKey.Shift,
            RemoteKey.Super,
        )
    }
}
