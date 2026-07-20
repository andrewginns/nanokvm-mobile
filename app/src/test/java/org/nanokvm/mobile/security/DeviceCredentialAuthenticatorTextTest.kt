package org.nanokvm.mobile.security

import org.junit.Assert.assertEquals
import org.junit.Test
import org.nanokvm.mobile.R

class DeviceCredentialAuthenticatorTextTest {
    @Test
    fun unlockPromptUsesDedicatedResources() {
        assertEquals(
            R.string.credential_prompt_unlock_title,
            credentialPromptTitleResource(CredentialPromptKind.Unlock),
        )
        assertEquals(
            R.string.credential_prompt_unlock_subtitle,
            credentialPromptSubtitleResource(CredentialPromptKind.Unlock),
        )
        assertEquals(
            R.string.credential_prompt_use_password,
            credentialPromptNegativeButtonResource(CredentialPromptKind.Unlock),
        )
    }

    @Test
    fun savePromptUsesDedicatedResources() {
        assertEquals(
            R.string.credential_prompt_save_title,
            credentialPromptTitleResource(CredentialPromptKind.Save),
        )
        assertEquals(
            R.string.credential_prompt_save_subtitle,
            credentialPromptSubtitleResource(CredentialPromptKind.Save),
        )
        assertEquals(
            R.string.credential_prompt_not_now,
            credentialPromptNegativeButtonResource(CredentialPromptKind.Save),
        )
    }
}
