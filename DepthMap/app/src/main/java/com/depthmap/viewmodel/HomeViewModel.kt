package com.depthmap.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.depthmap.data.models.DepthModel
import com.depthmap.data.models.OutputItem
import com.depthmap.data.preferences.AppPreferences
import com.depthmap.data.repository.ModelRepository
import com.depthmap.data.repository.OutputRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val modelRepository = ModelRepository(application)
    private val outputRepository = OutputRepository(application)
    private val preferences = AppPreferences(application)

    val selectedModelId: StateFlow<String> = preferences.lastSelectedModel
        .stateIn(viewModelScope, SharingStarted.Eagerly, "vitb")

    val selectedModel: StateFlow<DepthModel?> = combine(
        modelRepository.models,
        selectedModelId
    ) { models, modelId ->
        models.find { it.id == modelId }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _recentOutputs = MutableStateFlow<List<OutputItem>>(emptyList())
    val recentOutputs: StateFlow<List<OutputItem>> = _recentOutputs.asStateFlow()

    val storageUsed: StateFlow<Long> = modelRepository.models.map { models ->
        models.filter { it.isDownloaded }.sumOf { modelRepository.getModelSize(it) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    init {
        viewModelScope.launch {
            _recentOutputs.value = outputRepository.getOutputs()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _recentOutputs.value = outputRepository.getOutputs()
        }
    }
}
