package com.depthmap.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.depthmap.data.models.OutputItem
import com.depthmap.data.models.OutputType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class OutputRepository(private val context: Context) {

    private val outputDir: File
        get() {
            val dir = File(context.getExternalFilesDir(null), "DepthMap")
            dir.mkdirs()
            return dir
        }

    fun getOutputUri(output: OutputItem): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            File(output.path)
        )
    }

    suspend fun getOutputs(): List<OutputItem> = withContext(Dispatchers.IO) {
        val dir = outputDir
        if (!dir.exists()) return@withContext emptyList()

        dir.listFiles()?.mapNotNull { file ->
            when {
                file.extension.lowercase() == "png" -> OutputItem(
                    name = file.nameWithoutExtension,
                    path = file.absolutePath,
                    type = OutputType.IMAGE_DEPTH,
                    timestamp = file.lastModified(),
                    sizeBytes = file.length()
                )
                file.extension.lowercase() == "mp4" -> OutputItem(
                    name = file.nameWithoutExtension,
                    path = file.absolutePath,
                    type = OutputType.VIDEO_DEPTH,
                    timestamp = file.lastModified(),
                    sizeBytes = file.length()
                )
                else -> null
            }
        }?.sortedByDescending { it.timestamp } ?: emptyList()
    }

    suspend fun deleteOutput(output: OutputItem): Boolean = withContext(Dispatchers.IO) {
        val file = File(output.path)
        file.delete()
    }

    suspend fun getOutputDirectory(): File = withContext(Dispatchers.IO) {
        outputDir
    }
}
