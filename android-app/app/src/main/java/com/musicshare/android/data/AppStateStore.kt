package com.musicshare.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.musicshare.android.util.ClientInstallIdFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "music_share_state")

class AppStateStore(private val context: Context) {
    private val stateKey = stringPreferencesKey("state_json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    val state: Flow<PersistedAppState> =
        context.dataStore.data
            .catch { emit(emptyPreferences()) }
            .map { preferences -> decode(preferences[stateKey]) }

    suspend fun read(): PersistedAppState = state.first()

    suspend fun ensureClientInstallId() {
        update { current ->
            if (current.clientInstallId.isNotBlank()) {
                current
            } else {
                current.copy(clientInstallId = ClientInstallIdFactory.generate()).normalized()
            }
        }
    }

    suspend fun overwrite(newState: PersistedAppState) {
        context.dataStore.edit { preferences ->
            preferences[stateKey] = encode(newState.normalized())
        }
    }

    suspend fun update(transform: (PersistedAppState) -> PersistedAppState) {
        context.dataStore.edit { preferences ->
            val nextState = transform(decode(preferences[stateKey])).normalized()
            preferences[stateKey] = encode(nextState)
        }
    }

    private fun encode(state: PersistedAppState): String =
        json.encodeToString(PersistedAppState.serializer(), state)

    private fun decode(raw: String?): PersistedAppState {
        val decoded = raw
            ?.takeIf { it.isNotBlank() }
            ?.let { value ->
                runCatching { json.decodeFromString<PersistedAppState>(value) }.getOrNull()
            }
        return (decoded ?: PersistedAppState()).normalized()
    }
}
