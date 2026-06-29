package com.depthmap.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.depthmap.data.models.DepthModel
import com.depthmap.data.preferences.AppPreferences
import com.depthmap.data.repository.ModelRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ModelManagerViewModel(application: Application) : AndroidViewModel(application) {

    private val modelRepository = ModelRepository(application)
    private val preferences = AppPreferences(application)

    val models: StateFlow<List<DepthModel>> = modelRepository.models

    val selectedModelId: StateFlow<String> = preferences.lastSelectedModel
        .stateIn(viewModelScope, SharingStarted.Eagerly, "vitb")

    val storageUsed: StateFlow<Long> = models.map { list ->
        list.filter { it.isDownloaded }.sumOf { modelRepository.getModelSize(it) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    fun selectModel(model: DepthModel) {
        viewModelScope.launch {
            preferences.setLastSelectedModel(model.id)
        }
    }

    fun downloadModel(model: DepthModel) {
        viewModelScope.launch {
            modelRepository.downloadModel(model) { progress ->
                models.value
            }
        }
    }

    fun deleteModel(model: DepthModel) {
        viewModelScope.launch {
            modelRepository.deleteModel(model)
        }
    }
}
