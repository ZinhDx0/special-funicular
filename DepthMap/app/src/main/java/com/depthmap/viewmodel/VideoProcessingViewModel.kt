package com.depthmap.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.depthmap.data.models.DepthModel
import com.depthmap.data.models.ProcessingState
import com.depthmap.data.preferences.AppPreferences
import com.depthmap.data.repository.ModelRepository
import com.depthmap.data.repository.OutputRepository
import com.depthmap.domain.DepthEstimator
import com.depthmap.domain.TermuxBridge
import com.depthmap.domain.VideoProcessor
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class VideoProcessingViewModel(application: Application) : AndroidViewModel(application) {

    private val modelRepository = ModelRepository(application)
    private val outputRepository = OutputRepository(application)
    private val preferences = AppPreferences(application)
    private val estimator = DepthEstimator(application)
    private val videoProcessor = VideoProcessor(application)
    private val termuxBridge = TermuxBridge(application)

    val selectedModelId: StateFlow<String> = preferences.lastSelectedModel
        .stateIn(viewModelScope, SharingStarted.Eagerly, "vitb")

    val selectedModel: StateFlow<DepthModel?> = combine(
        modelRepository.models,
        selectedModelId
    ) { models, modelId ->
        models.find { it.id == modelId }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _selectedVideoUri = MutableStateFlow<Uri?>(null)
    val selectedVideoUri: StateFlow<Uri?> = _selectedVideoUri.asStateFlow()

    private val _selectedFileName = MutableStateFlow<String?>(null)
    val selectedFileName: StateFlow<String?> = _selectedFileName.asStateFlow()

    private val _processingState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val processingState: StateFlow<ProcessingState> = _processingState.asStateFlow()

    fun setSelectedVideo(uri: Uri, fileName: String?) {
        _selectedVideoUri.value = uri
        _selectedFileName.value = fileName
        _processingState.value = ProcessingState.FileSelected
    }

    fun processVideo() {
        viewModelScope.launch {
            val model = selectedModel.value ?: return@launch
            val videoUri = _selectedVideoUri.value ?: return@launch

            if (!model.isDownloaded) {
                _processingState.value = ProcessingState.Error("Model not downloaded")
                return@launch
            }

            _processingState.value = ProcessingState.Processing(progress = 0f, message = "Processing video...")

            val modelFile = modelRepository.getModelFile(model)
            val loadResult = estimator.loadModel(modelFile)
            if (loadResult.isFailure) {
                _processingState.value = ProcessingState.Error("Failed to load model: ${loadResult.exceptionOrNull()?.message}")
                return@launch
            }

            val outputDir = outputRepository.getOutputDirectory()
            val result = videoProcessor.processVideo(videoUri, outputDir, estimator) { progress ->
                _processingState.value = ProcessingState.Processing(progress = progress, message = "Processing video...")
            }

            result.fold(
                onSuccess = { file ->
                    _processingState.value = ProcessingState.Completed(outputPath = file.absolutePath)
                },
                onFailure = { error ->
                    _processingState.value = ProcessingState.Error(error.message ?: "Unknown error")
                }
            )
        }
    }

    fun cancelProcessing() {
        videoProcessor.cancel()
        termuxBridge.cancel()
        _processingState.value = ProcessingState.Cancelled
    }

    fun resetState() {
        _processingState.value = ProcessingState.Idle
        _selectedVideoUri.value = null
        _selectedFileName.value = null
    }

    override fun onCleared() {
        super.onCleared()
        estimator.close()
    }
}
