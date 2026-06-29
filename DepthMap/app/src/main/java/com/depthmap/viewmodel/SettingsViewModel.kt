package com.depthmap.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.depthmap.data.preferences.AppPreferences
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = AppPreferences(application)

    val isDarkMode: StateFlow<Boolean> = preferences.isDarkMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val useNNAPI: StateFlow<Boolean> = preferences.useNNAPI
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun toggleDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setDarkMode(enabled)
        }
    }

    fun toggleNNAPI(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setUseNNAPI(enabled)
        }
    }
}
