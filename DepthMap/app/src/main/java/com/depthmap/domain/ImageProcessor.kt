package com.depthmap.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ImageProcessor(private val context: Context) {

    suspend fun processImage(
        imageUri: Uri,
        outputDir: File,
        estimator: DepthEstimator
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(imageUri)
                ?: return@withContext Result.failure(Exception("Cannot open image"))
            val inputBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outputFile = File(outputDir, "depthmap_${timestamp}.png")

            val depthResult = estimator.estimateDepth(inputBitmap)
            val depthBitmap = depthResult.getOrElse {
                return@withContext Result.failure(it)
            }

            FileOutputStream(outputFile).use { out ->
                depthBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
