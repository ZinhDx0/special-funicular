package com.depthmap.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.depthmap.data.preferences.AppPreferences
import com.depthmap.domain.TermuxBridge
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = AppPreferences(application)
    private val termuxBridge = TermuxBridge(application)

    val isDarkMode: StateFlow<Boolean> = preferences.isDarkMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val useNNAPI: StateFlow<Boolean> = preferences.useNNAPI
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val useTermux: StateFlow<Boolean> = preferences.useTermux
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _termuxStatus = MutableStateFlow<TermuxBridge.TermuxSetupStatus?>(null)
    val termuxStatus: StateFlow<TermuxBridge.TermuxSetupStatus?> = _termuxStatus.asStateFlow()

    val isTermuxInstalled: Boolean
        get() = TermuxBridge.isTermuxInstalled(getApplication())

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

    fun toggleTermux(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setUseTermux(enabled)
            if (enabled) {
                val status = termuxBridge.verifySetup()
                _termuxStatus.value = status
            } else {
                _termuxStatus.value = null
            }
        }
    }
}
