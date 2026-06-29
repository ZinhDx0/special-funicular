package com.depthmap.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.depthmap.domain.TermuxBridge
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

class ImageProcessingViewModel(application: Application) : AndroidViewModel(application) {

    private val modelRepository = ModelRepository(application)
    private val outputRepository = OutputRepository(application)
    private val preferences = AppPreferences(application)
    private val estimator = DepthEstimator(application)
    private val imageProcessor = ImageProcessor(application)
    private val termuxBridge = TermuxBridge(application)

    val selectedModelId: StateFlow<String> = preferences.lastSelectedModel
        .stateIn(viewModelScope, SharingStarted.Eagerly, "vitb")

    val selectedModel: StateFlow<DepthModel?> = combine(
        modelRepository.models,
        selectedModelId
    ) { models, modelId ->
        models.find { it.id == modelId }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val useTermux: StateFlow<Boolean> = preferences.useTermux
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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
            val outputDir = outputRepository.getOutputDirectory()

            if (useTermux.value && TermuxBridge.isTermuxInstalled(getApplication())) {
                processViaTermux(imageUri, model, modelFile, outputDir)
            } else {
                processViaNative(imageUri, modelFile, outputDir)
            }
        }
    }

    private suspend fun processViaTermux(
        imageUri: Uri,
        model: DepthModel,
        modelFile: File,
        outputDir: File
    ) {
        _processingState.value = ProcessingState.Processing(progress = 0f, message = "Starting Termux inference...")

        // Copy input to a file path
        val inputFile = File(outputDir, "termux_input_${System.currentTimeMillis()}.png")
        try {
            val inputStream = getApplication<Application>().contentResolver.openInputStream(imageUri)
                ?: run {
                    _processingState.value = ProcessingState.Error("Cannot open image")
                    return
                }
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (bitmap == null) {
                _processingState.value = ProcessingState.Error("Failed to decode image")
                return
            }
            File(inputFile.absolutePath).outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: Exception) {
            _processingState.value = ProcessingState.Error("Failed to prepare image: ${e.message}")
            return
        }

        val result = termuxBridge.runInference(
            inputImagePath = inputFile.absolutePath,
            modelFilePath = modelFile.absolutePath,
            modelFileName = "${model.name}.onnx",
            onProgress = { msg ->
                _processingState.value = ProcessingState.Processing(
                    progress = 0.5f,
                    message = msg
                )
            }
        )

        inputFile.delete()

        if (result.success && result.outputPath != null) {
            // Copy output to app's output directory and register with gallery
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val outputFile = File(outputDir, "depthmap_${timestamp}.png")
            try {
                File(result.outputPath).copyTo(outputFile, overwrite = true)
            } catch (e: Exception) {
                _processingState.value = ProcessingState.Error("Failed to copy output: ${e.message}")
                return
            }
            termuxBridge.registerOutputWithGallery(outputFile.absolutePath)
            _processingState.value = ProcessingState.Completed(outputPath = outputFile.absolutePath)
        } else {
            _processingState.value = ProcessingState.Error(
                result.errorMessage ?: "Termux processing failed"
            )
        }
    }

    private suspend fun processViaNative(
        imageUri: Uri,
        modelFile: File,
        outputDir: File
    ) {
        val loadResult = estimator.loadModel(modelFile)
        if (loadResult.isFailure) {
            _processingState.value = ProcessingState.Error(
                "Failed to load model: ${loadResult.exceptionOrNull()?.message}"
            )
            return
        }

        _processingState.value = ProcessingState.Processing(progress = 0f, message = "Processing image...")

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

    fun cancelProcessing() {
        termuxBridge.cancel()
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
