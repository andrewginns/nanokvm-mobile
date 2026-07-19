package org.nanokvm.mobile.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.profileDataStore by preferencesDataStore(name = "nanokvm_profiles")

sealed interface ProfileCatalogState {
    data class Ready(val profiles: List<HostProfile>) : ProfileCatalogState
    data class Corrupted(val userMessage: String) : ProfileCatalogState
    data class Unavailable(val userMessage: String) : ProfileCatalogState
}

interface ProfilesRepository {
    val profiles: Flow<ProfileCatalogState>
    suspend fun upsert(profile: HostProfile)
    suspend fun delete(profileId: String)
    suspend fun reset()
}

class ProfileRepository(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProfilesRepository {
    private val profilesKey = stringPreferencesKey("profiles_v1")

    override val profiles: Flow<ProfileCatalogState> = context.profileDataStore.data
        .map { preferences ->
            val encoded = preferences[profilesKey]
            if (encoded == null) {
                ProfileCatalogState.Ready(emptyList())
            } else {
                ProfileCodec.decode(encoded).fold(
                    onSuccess = { decoded ->
                        ProfileCatalogState.Ready(decoded)
                    },
                    onFailure = {
                        ProfileCatalogState.Corrupted(
                            "Saved connections are damaged. Reset them before making changes.",
                        )
                    },
                )
            }
        }
        .catch { error ->
            when (error) {
                is CorruptionException -> emit(
                    ProfileCatalogState.Corrupted(
                        "Saved connections are damaged. Reset them before making changes.",
                    ),
                )
                is IOException -> emit(
                    ProfileCatalogState.Unavailable(
                        "Saved connections cannot be read right now. No data has been changed.",
                    ),
                )
                else -> throw error
            }
        }
        .flowOn(ioDispatcher)

    override suspend fun upsert(profile: HostProfile) = withContext(ioDispatcher) {
        context.profileDataStore.edit { preferences ->
            val current = decodeForWrite(preferences[profilesKey]).toMutableList()
            val index = current.indexOfFirst { it.id == profile.id }
            if (index >= 0) current[index] = profile else current += profile
            preferences[profilesKey] = ProfileCodec.encode(current)
        }
        Unit
    }

    override suspend fun delete(profileId: String) = withContext(ioDispatcher) {
        context.profileDataStore.edit { preferences ->
            val remaining = decodeForWrite(preferences[profilesKey]).filterNot { it.id == profileId }
            preferences[profilesKey] = ProfileCodec.encode(remaining)
        }
        Unit
    }

    override suspend fun reset() = withContext(ioDispatcher) {
        context.profileDataStore.edit { preferences ->
            preferences[profilesKey] = ProfileCodec.encode(emptyList())
        }
        Unit
    }

    private fun decodeForWrite(encoded: String?): List<HostProfile> {
        if (encoded == null) return emptyList()
        return ProfileCodec.decode(encoded).getOrElse { error ->
            throw ProfileStorageCorruptedException(
                "Profile storage must be reset before it can be changed.",
                error,
            )
        }
    }
}

class ProfileStorageCorruptedException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal object ProfileCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encode(profiles: List<HostProfile>): String = json.encodeToString(
        profiles.map(ProfileRecord::fromDomain),
    )

    fun decode(encoded: String): Result<List<HostProfile>> = runCatching {
        json.decodeFromString<List<ProfileRecord>>(encoded).map(ProfileRecord::toDomain)
    }
}

@Serializable
private data class ProfileRecord(
    val id: String,
    val name: String = "NanoKVM",
    val host: String = "192.0.2.250",
    val port: Int = 443,
    @SerialName("https") val useHttps: Boolean = true,
    val username: String = "admin",
    @SerialName("certificate") val trustedCertificateSha256: String? = null,
) {
    fun toDomain(): HostProfile {
        require(id.isNotBlank()) { "Profile ID must not be blank" }
        require(host.isNotBlank()) { "Profile host must not be blank" }
        require(port in 1..65_535) { "Profile port is invalid" }
        return HostProfile(
            id = id,
            name = name,
            host = host,
            port = port,
            useHttps = useHttps,
            username = username,
            trustedCertificateSha256 = trustedCertificateSha256,
        )
    }

    companion object {
        fun fromDomain(profile: HostProfile): ProfileRecord = ProfileRecord(
            id = profile.id,
            name = profile.name,
            host = profile.host,
            port = profile.port,
            useHttps = profile.useHttps,
            username = profile.username,
            trustedCertificateSha256 = profile.trustedCertificateSha256,
        )
    }
}
