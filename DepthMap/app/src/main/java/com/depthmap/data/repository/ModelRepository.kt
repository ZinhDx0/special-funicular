package com.depthmap.data.repository

import android.content.Context
import com.depthmap.data.models.DepthModel
import com.depthmap.data.models.DownloadStatus
import com.depthmap.data.models.availableModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ModelRepository(private val context: Context) {

    private val modelsDir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }

    private val _models = MutableStateFlow(availableModels.map { model ->
        model.copy(
            isDownloaded = isModelDownloaded(model),
            downloadStatus = if (isModelDownloaded(model)) DownloadStatus.DOWNLOADED else DownloadStatus.NOT_DOWNLOADED
        )
    })
    val models: StateFlow<List<DepthModel>> = _models.asStateFlow()

    fun getModelFile(model: DepthModel): File {
        return File(modelsDir, "${model.name}.onnx")
    }

    fun isModelDownloaded(model: DepthModel): Boolean {
        return getModelFile(model).exists()
    }

    fun getModelSize(model: DepthModel): Long {
        return getModelFile(model).length()
    }

    fun getTotalStorageUsed(): Long {
        return modelsDir.listFiles()?.sumOf { it.length() } ?: 0
    }

    fun refreshModels() {
        _models.value = availableModels.map { model ->
            model.copy(
                isDownloaded = isModelDownloaded(model),
                downloadStatus = if (isModelDownloaded(model)) DownloadStatus.DOWNLOADED else DownloadStatus.NOT_DOWNLOADED,
                downloadProgress = if (isModelDownloaded(model)) 1f else 0f
            )
        }
    }

    suspend fun downloadModel(model: DepthModel, onProgress: (Float) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val index = _models.value.indexOfFirst { it.id == model.id }
            if (index >= 0) {
                val updated = _models.value.toMutableList()
                updated[index] = updated[index].copy(downloadStatus = DownloadStatus.DOWNLOADING, downloadProgress = 0f)
                _models.value = updated
            }

            val url = URL(model.downloadUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.connect()

            val fileLength = connection.contentLengthLong
            val inputStream = connection.inputStream
            val outputFile = modelsDir.resolve("${model.name}.onnx.tmp")
            val outputStream = FileOutputStream(outputFile)

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytesRead = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                if (fileLength > 0) {
                    val progress = totalBytesRead.toFloat() / fileLength.toFloat()
                    val index2 = _models.value.indexOfFirst { it.id == model.id }
                    if (index2 >= 0) {
                        val updated = _models.value.toMutableList()
                        updated[index2] = updated[index2].copy(downloadProgress = progress)
                        _models.value = updated
                    }
                    onProgress(progress)
                }
            }

            outputStream.close()
            inputStream.close()
            connection.disconnect()

            // Remove old file if exists, then rename
            val targetFile = modelsDir.resolve("${model.name}.onnx")
            if (targetFile.exists()) targetFile.delete()
            if (!outputFile.renameTo(targetFile)) {
                throw IOException("Failed to rename downloaded model file")
            }

            val idx = _models.value.indexOfFirst { it.id == model.id }
            if (idx >= 0) {
                val updated = _models.value.toMutableList()
                updated[idx] = updated[idx].copy(
                    isDownloaded = true,
                    downloadStatus = DownloadStatus.DOWNLOADED,
                    downloadProgress = 1f
                )
                _models.value = updated
            }

            Result.success(Unit)
        } catch (e: Exception) {
            // Clean up temp file
            try { modelsDir.resolve("${model.name}.onnx.tmp").delete() } catch (_: Exception) {}
            val idx = _models.value.indexOfFirst { it.id == model.id }
            if (idx >= 0) {
                val updated = _models.value.toMutableList()
                updated[idx] = updated[idx].copy(
                    isDownloaded = false,
                    downloadStatus = DownloadStatus.ERROR,
                    downloadProgress = 0f
                )
                _models.value = updated
            }
            Result.failure(e)
        }
    }

    suspend fun deleteModel(model: DepthModel): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = getModelFile(model)
            if (file.exists()) file.delete()

            val idx = _models.value.indexOfFirst { it.id == model.id }
            if (idx >= 0) {
                val updated = _models.value.toMutableList()
                updated[idx] = updated[idx].copy(
                    isDownloaded = false,
                    downloadStatus = DownloadStatus.NOT_DOWNLOADED,
                    downloadProgress = 0f
                )
                _models.value = updated
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun verifyModel(model: DepthModel): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val file = getModelFile(model)
            if (!file.exists()) return@withContext Result.success(false)
            val valid = file.length() > 1000
            Result.success(valid)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getDownloadedModels(): List<DepthModel> {
        return _models.value.filter { it.isDownloaded }
    }
}
