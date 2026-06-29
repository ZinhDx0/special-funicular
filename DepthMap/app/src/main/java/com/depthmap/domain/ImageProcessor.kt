package com.depthmap.domain

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
            if (inputBitmap == null) {
                return@withContext Result.failure(Exception("Failed to decode image"))
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "depthmap_${timestamp}.png"

            val depthResult = estimator.estimateDepth(inputBitmap)
            val depthBitmap = depthResult.getOrElse {
                return@withContext Result.failure(it)
            }

            // Save to app-internal output dir
            val outputFile = File(outputDir, fileName)
            FileOutputStream(outputFile).use { out ->
                depthBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            // Register with MediaStore so it appears in gallery
            registerWithMediaStore(fileName, depthBitmap)

            // Scan the file for added visibility
            MediaScannerConnection.scanFile(
                context,
                arrayOf(outputFile.absolutePath),
                arrayOf("image/png"),
                null
            )

            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun registerWithMediaStore(fileName: String, bitmap: Bitmap) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DepthMap")
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                )
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES + "/DepthMap"
                )
                dir.mkdirs()
                val publicFile = File(dir, fileName)
                FileOutputStream(publicFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(publicFile.absolutePath),
                    arrayOf("image/png"),
                    null
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("ImageProcessor", "Failed to register with MediaStore", e)
        }
    }
}
