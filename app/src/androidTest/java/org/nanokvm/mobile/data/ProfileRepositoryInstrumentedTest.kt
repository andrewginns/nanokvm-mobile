package org.nanokvm.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileRepositoryInstrumentedTest {
    @Test
    fun binaryCorruptionBlocksMutationUntilExplicitReset() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(
            context.cacheDir,
            "profile-repository-${UUID.randomUUID()}.preferences_pb",
        )
        file.writeBytes(byteArrayOf(0x0A, 0x05, 0x01))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = profilePreferencesCorruptionHandler,
            scope = scope,
            produceFile = { file },
        )
        val repository = ProfileRepository(dataStore)
        val profile = HostProfile(
            id = "desk",
            name = "Desk",
            host = "192.0.2.4",
            port = 443,
            useHttps = true,
            username = "admin",
        )

        try {
            assertEquals(ProfileCatalogState.Corrupted, repository.profiles.first())
            assertTrue(
                runCatching { repository.upsert(profile) }.exceptionOrNull() is
                    ProfileStorageCorruptedException,
            )
            assertTrue(
                runCatching { repository.delete(profile.id) }.exceptionOrNull() is
                    ProfileStorageCorruptedException,
            )

            repository.reset()
            assertEquals(ProfileCatalogState.Ready(emptyList()), repository.profiles.first())

            repository.upsert(profile)
            val recovered = withTimeout(5_000L) {
                repository.profiles.first { state ->
                    state is ProfileCatalogState.Ready && state.profiles == listOf(profile)
                }
            }
            assertEquals(ProfileCatalogState.Ready(listOf(profile)), recovered)
        } finally {
            scope.cancel()
            file.delete()
        }
    }
}
