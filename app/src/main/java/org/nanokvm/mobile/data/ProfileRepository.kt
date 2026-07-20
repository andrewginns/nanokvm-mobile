package org.nanokvm.mobile.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
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

private val profileStorageCorruptedKey = booleanPreferencesKey("profile_storage_corrupted")

internal val profilePreferencesCorruptionHandler: ReplaceFileCorruptionHandler<Preferences> =
    ReplaceFileCorruptionHandler {
        mutablePreferencesOf(profileStorageCorruptedKey to true)
    }

private val Context.profileDataStore by preferencesDataStore(
    name = "nanokvm_profiles",
    corruptionHandler = profilePreferencesCorruptionHandler,
)

sealed interface ProfileCatalogState {
    data class Ready(val profiles: List<HostProfile>) : ProfileCatalogState
    data object Corrupted : ProfileCatalogState
    data object Unavailable : ProfileCatalogState
}

interface ProfilesRepository {
    val profiles: Flow<ProfileCatalogState>
    suspend fun upsert(profile: HostProfile)
    suspend fun delete(profileId: String)
    suspend fun reset()
}

class ProfileRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProfilesRepository {
    constructor(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(context.profileDataStore, ioDispatcher)

    private val profilesKey = stringPreferencesKey("profiles_v1")

    override val profiles: Flow<ProfileCatalogState> = dataStore.data
        .map { preferences ->
            if (preferences[profileStorageCorruptedKey] == true) {
                return@map corruptedProfileCatalogState()
            }
            val encoded = preferences[profilesKey]
            if (encoded == null) {
                ProfileCatalogState.Ready(emptyList())
            } else {
                ProfileCodec.decode(encoded).fold(
                    onSuccess = { decoded ->
                        ProfileCatalogState.Ready(decoded)
                    },
                    onFailure = {
                        corruptedProfileCatalogState()
                    },
                )
            }
        }
        .catch { error ->
            when (error) {
                is CorruptionException -> emit(corruptedProfileCatalogState())
                is IOException -> emit(ProfileCatalogState.Unavailable)
                else -> throw error
            }
        }
        .flowOn(ioDispatcher)

    override suspend fun upsert(profile: HostProfile) = withContext(ioDispatcher) {
        ProfileInputPolicy.requireValid(profile)
        dataStore.edit { preferences ->
            val current = decodeForWrite(preferences).toMutableList()
            val index = current.indexOfFirst { it.id == profile.id }
            if (index >= 0) current[index] = profile else current += profile
            preferences[profilesKey] = ProfileCodec.encode(current)
        }
        Unit
    }

    override suspend fun delete(profileId: String) = withContext(ioDispatcher) {
        dataStore.edit { preferences ->
            val remaining = decodeForWrite(preferences).filterNot { it.id == profileId }
            preferences[profilesKey] = ProfileCodec.encode(remaining)
        }
        Unit
    }

    override suspend fun reset() = withContext(ioDispatcher) {
        dataStore.edit { preferences ->
            preferences.remove(profileStorageCorruptedKey)
            preferences[profilesKey] = ProfileCodec.encode(emptyList())
        }
        Unit
    }

    private fun decodeForWrite(preferences: Preferences): List<HostProfile> {
        if (preferences[profileStorageCorruptedKey] == true) {
            throw ProfileStorageCorruptedException()
        }
        val encoded = preferences[profilesKey]
        if (encoded == null) return emptyList()
        return ProfileCodec.decode(encoded).getOrElse { error ->
            throw ProfileStorageCorruptedException(error)
        }
    }
}

class ProfileStorageCorruptedException(
    cause: Throwable? = null,
) : Exception("Profile storage is corrupt and requires reset", cause)

internal object ProfileCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encode(profiles: List<HostProfile>): String = json.encodeToString(
        profiles.map { profile ->
            // Untouched HTTP records from an older app version remain readable until the editor
            // upgrades them. New and edited records are rejected by ProfileRepository.upsert.
            ProfileInputPolicy.requireValid(ProfileInputPolicy.prospectiveHttps(profile))
            ProfileRecord.fromDomain(profile)
        },
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
        val profile = HostProfile(
            id = id,
            name = name,
            host = host,
            port = port,
            useHttps = useHttps,
            username = username,
            trustedCertificateSha256 = trustedCertificateSha256,
        )
        ProfileInputPolicy.requireValid(ProfileInputPolicy.prospectiveHttps(profile))
        return profile
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

private fun corruptedProfileCatalogState() = ProfileCatalogState.Corrupted
