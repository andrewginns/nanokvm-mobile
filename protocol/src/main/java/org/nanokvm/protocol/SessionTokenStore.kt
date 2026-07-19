package org.nanokvm.protocol

import java.util.concurrent.atomic.AtomicReference

/** Synchronous token access is required because OkHttp interceptors run outside coroutines. */
interface SessionTokenStore {
    fun read(): String?
    fun write(token: String?)
}

/** Thread-safe in-memory session storage. Persisted stores should encrypt tokens at rest. */
class InMemorySessionTokenStore(initialToken: String? = null) : SessionTokenStore {
    private val token = AtomicReference(initialToken)

    override fun read(): String? = token.get()

    override fun write(token: String?) {
        this.token.set(token)
    }
}
