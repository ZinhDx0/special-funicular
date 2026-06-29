package com.depthmap.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "depthmap_settings")

class AppPreferences(private val context: Context) {

    companion object {
        private val LAST_SELECTED_MODEL = stringPreferencesKey("last_selected_model")
        private val DARK_MODE = booleanPreferencesKey("dark_mode")
        private val OUTPUT_DIRECTORY = stringPreferencesKey("output_directory")
        private val USE_NNAPI = booleanPreferencesKey("use_nnapi")
        private val USE_TERMUX = booleanPreferencesKey("use_termux")
    }

    val lastSelectedModel: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[LAST_SELECTED_MODEL] ?: "vitb"
    }

    val isDarkMode: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[DARK_MODE] ?: false
    }

    val outputDirectory: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[OUTPUT_DIRECTORY] ?: ""
    }

    val useNNAPI: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[USE_NNAPI] ?: true
    }

    val useTermux: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[USE_TERMUX] ?: false
    }

    suspend fun setLastSelectedModel(model: String) {
        context.dataStore.edit { prefs ->
            prefs[LAST_SELECTED_MODEL] = model
        }
    }

    suspend fun setDarkMode(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[DARK_MODE] = enabled
        }
    }

    suspend fun setOutputDirectory(path: String) {
        context.dataStore.edit { prefs ->
            prefs[OUTPUT_DIRECTORY] = path
        }
    }

    suspend fun setUseNNAPI(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[USE_NNAPI] = enabled
        }
    }

    suspend fun setUseTermux(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[USE_TERMUX] = enabled
        }
    }
}
