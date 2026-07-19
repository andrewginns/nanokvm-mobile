package org.nanokvm.mobile.runtime

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionBoundFeatureLifecycleTest {
    @Test
    fun `new generation replaces gateway and stale generation cannot resolve it`() {
        val lifecycle = SessionBoundFeatureLifecycle<Any>()
        val firstBinding = binding(generation = 4L)
        val secondBinding = binding(generation = 5L)
        val first = Any()
        val second = Any()

        lifecycle.install(firstBinding) { first }
        assertSame(first, lifecycle.resolve(firstBinding))

        lifecycle.install(secondBinding) { second }

        assertNull(lifecycle.resolve(firstBinding))
        assertSame(second, lifecycle.resolve(secondBinding))
    }

    @Test
    fun `clear destroys the active gateway binding`() {
        val lifecycle = SessionBoundFeatureLifecycle<Any>()
        val binding = binding(generation = 9L)
        lifecycle.install(binding) { Any() }

        lifecycle.clear()

        assertNull(lifecycle.binding())
        assertNull(lifecycle.resolve(binding))
    }

    @Test
    fun `approved destination requires exact profile authority and generation`() {
        val binding = binding(generation = 12L)
        val approved = ApprovedPhase3Destination(
            profileId = "office",
            authority = "192.0.2.4",
            sessionGeneration = 12L,
        )

        assertTrue(approved.matches(binding))
        assertFalse(approved.copy(sessionGeneration = 13L).matches(binding))
        assertFalse(approved.toString().contains("192.0.2.4"))
    }

    @Test
    fun `input recycle policy distinguishes dispatch from local no-op`() {
        assertTrue(
            NanoKvmPhase3MutationResult.Applied("mounted")
                .requiresInputRecycleAfterUsbMutation(),
        )
        assertTrue(
            NanoKvmPhase3MutationResult.Indeterminate<String>(
                state = null,
                dispatchError = NanoKvmPhase3Error(NanoKvmPhase3Error.Kind.CONNECTION),
                refreshError = null,
            ).requiresInputRecycleAfterUsbMutation(),
        )
        assertTrue(
            NanoKvmPhase3MutationResult.Accepted(
                state = "server-acknowledged",
                refreshError = null,
            ).requiresInputRecycleAfterUsbMutation(),
        )
        assertTrue(
            NanoKvmPhase3MutationResult.Reconciled(
                state = "read-back",
                observation = NanoKvmPhase3Observation.OTHER_STATE,
                dispatchError = NanoKvmPhase3Error(NanoKvmPhase3Error.Kind.CONNECTION),
            ).requiresInputRecycleAfterUsbMutation(),
        )
        assertFalse(
            NanoKvmPhase3MutationResult.AlreadySatisfied("unchanged")
                .requiresInputRecycleAfterUsbMutation(),
        )
        assertFalse(
            NanoKvmPhase3MutationResult.Rejected(
                NanoKvmPhase3Error(NanoKvmPhase3Error.Kind.FOREIGN_OR_STALE_STATE),
            ).requiresInputRecycleAfterUsbMutation(),
        )
    }

    private fun binding(generation: Long) = NanoKvmSessionBinding(
        profileId = "office",
        authority = "192.0.2.4",
        sessionGeneration = generation,
    )
}
