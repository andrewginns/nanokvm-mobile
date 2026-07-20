package org.nanokvm.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.nanokvm.mobile.runtime.CertificatePresentationReason
import org.nanokvm.mobile.runtime.VideoStreamDescriptor
import org.nanokvm.mobile.runtime.VideoTransportDescriptor

class RuntimeMessageTextTest {
    @Test
    fun everyClosedNoticeKindHasItsOwnResource() {
        assertDistinctResources(SimpleNotice.entries, ::simpleNoticeResource)
        assertDistinctResources(PasswordNotice.entries, ::passwordNoticeResource)
        assertDistinctResources(ShareNotice.entries, ::shareNoticeResource)
        assertDistinctResources(CredentialNotice.entries, ::credentialNoticeResource)
    }

    @Test
    fun everyCertificateAndStreamDescriptorHasItsOwnResource() {
        assertDistinctResources(
            CertificatePresentationReason.entries,
            ::certificatePresentationReasonResource,
        )
        assertDistinctResources(
            listOf(
                VideoStreamDescriptor.WebRtc,
                VideoStreamDescriptor.DirectH264,
                VideoStreamDescriptor.Mjpeg,
                VideoStreamDescriptor.WebRtcFallback,
                VideoStreamDescriptor.DirectH264Fallback,
                VideoStreamDescriptor.MjpegFallback,
            ),
            ::videoStreamDescriptorResource,
        )
        assertDistinctResources(
            VideoTransportDescriptor.entries,
            ::videoTransportDescriptorResource,
        )
    }

    @Test
    fun profileStorageFailuresRemainSemanticAndDistinct() {
        val resources = listOf(
            profileStorageIssueMessageResource(ProfileStorageIssue.Corrupted),
            profileStorageIssueMessageResource(ProfileStorageIssue.Unavailable),
        )

        assertEquals(resources.size, resources.distinct().size)
    }

    private fun <T> assertDistinctResources(values: List<T>, resource: (T) -> Int) {
        assertEquals(values.size, values.map(resource).distinct().size)
    }
}
