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
import com.depthmap.domain.ImageProcessor
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ImageProcessingViewModel(application: Application) : AndroidViewModel(application) {

    private val modelRepository = ModelRepository(application)
    private val outputRepository = OutputRepository(application)
    private val preferences = AppPreferences(application)
    private val estimator = DepthEstimator(application)
    private val imageProcessor = ImageProcessor(application)

    val selectedModelId: StateFlow<String> = preferences.lastSelectedModel
        .stateIn(viewModelScope, SharingStarted.Eagerly, "vitb")

    val selectedModel: StateFlow<DepthModel?> = combine(
        modelRepository.models,
        selectedModelId
    ) { models, modelId ->
        models.find { it.id == modelId }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _selectedImageUri = MutableStateFlow<Uri?>(null)
    val selectedImageUri: StateFlow<Uri?> = _selectedImageUri.asStateFlow()

    private val _selectedFileName = MutableStateFlow<String?>(null)
    val selectedFileName: StateFlow<String?> = _selectedFileName.asStateFlow()

    private val _processingState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val processingState: StateFlow<ProcessingState> = _processingState.asStateFlow()

    fun setSelectedImage(uri: Uri, fileName: String?) {
        _selectedImageUri.value = uri
        _selectedFileName.value = fileName
        _processingState.value = ProcessingState.FileSelected
    }

    fun processImage() {
        viewModelScope.launch {
            val model = selectedModel.value ?: return@launch
            val imageUri = _selectedImageUri.value ?: return@launch

            if (!model.isDownloaded) {
                _processingState.value = ProcessingState.Error("Model not downloaded")
                return@launch
            }

            val modelFile = modelRepository.getModelFile(model)
            val loadResult = estimator.loadModel(modelFile)
            if (loadResult.isFailure) {
                _processingState.value = ProcessingState.Error("Failed to load model: ${loadResult.exceptionOrNull()?.message}")
                return@launch
            }

            _processingState.value = ProcessingState.Processing(progress = 0f, message = "Processing image...")

            val outputDir = outputRepository.getOutputDirectory()
            val result = imageProcessor.processImage(imageUri, outputDir, estimator)

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
        _processingState.value = ProcessingState.Cancelled
    }

    fun resetState() {
        _processingState.value = ProcessingState.Idle
        _selectedImageUri.value = null
        _selectedFileName.value = null
    }

    override fun onCleared() {
        super.onCleared()
        estimator.close()
    }
}
