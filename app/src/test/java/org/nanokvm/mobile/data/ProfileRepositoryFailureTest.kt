package org.nanokvm.mobile.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileRepositoryFailureTest {
    @Test
    fun `a transient read failure is unavailable without replacing data and a retry recovers`() =
        runTest {
            var collections = 0
            val store = FakePreferencesDataStore(
                dataFactory = {
                    flow {
                        collections++
                        if (collections == 1) throw IOException("temporary read failure")
                        emit(emptyPreferences())
                    }
                },
            )
            val repository = ProfileRepository(store)

            val unavailable = repository.profiles.first()
            val recovered = repository.profiles.first()

            assertEquals(ProfileCatalogState.Unavailable, unavailable)
            assertEquals(ProfileCatalogState.Ready(emptyList()), recovered)
            assertEquals(2, collections)
        }

    @Test
    fun `a failed atomic write is reported and a later retry starts from authoritative data`() =
        runTest {
            val failure = IOException("temporary write failure")
            val store = FakePreferencesDataStore(writeFailures = ArrayDeque(listOf(failure)))
            val repository = ProfileRepository(store)
            val profile = HostProfile(
                id = "desk",
                name = "Desk",
                host = "192.0.2.20",
                port = 443,
                useHttps = true,
                username = "admin",
            )

            val observed = runCatching { repository.upsert(profile) }.exceptionOrNull()

            assertEquals(IOException::class.java, observed?.javaClass)
            assertEquals(failure.message, observed?.message)
            assertEquals(ProfileCatalogState.Ready(emptyList()), repository.profiles.first())

            repository.upsert(profile)

            assertEquals(ProfileCatalogState.Ready(listOf(profile)), repository.profiles.first())
        }
}

private class FakePreferencesDataStore(
    private val dataFactory: (() -> Flow<Preferences>)? = null,
    private val writeFailures: ArrayDeque<Throwable> = ArrayDeque(),
) : DataStore<Preferences> {
    private var current: Preferences = emptyPreferences()

    override val data: Flow<Preferences>
        get() = dataFactory?.invoke() ?: flow { emit(current) }

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences {
        writeFailures.removeFirstOrNull()?.let { throw it }
        return transform(current).also { current = it }
    }
}
