package com.depthmap.domain

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TermuxBridge(private val context: Context) {

    companion object {
        private const val TAG = "TermuxBridge"
        private const val TERMUX_PACKAGE = "com.termux"
        private const val TASK_DIR_NAME = "DepthMap"
        private const val POLL_INTERVAL_MS = 200L
        private const val MAX_POLL_TIME_MS = 300_000L

        fun isTermuxInstalled(context: Context): Boolean {
            return try {
                context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    data class TermuxResult(
        val success: Boolean,
        val outputPath: String? = null,
        val errorMessage: String? = null
    )

    private val sharedDir: File
        get() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val dir = File(
                    Environment.getExternalStorageDirectory(),
                    "$TASK_DIR_NAME/termux_tasks"
                )
                dir.mkdirs()
                return dir
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStorageDirectory(),
                    "$TASK_DIR_NAME/termux_tasks"
                )
                dir.mkdirs()
                return dir
            }
        }

    private val modelsDir: File
        get() {
            val dir = File(sharedDir.parentFile ?: sharedDir, "models")
            dir.mkdirs()
            return dir
        }

    private var isRunning = false

    suspend fun runInference(
        inputImagePath: String,
        modelFilePath: String,
        modelFileName: String,
        onProgress: (String) -> Unit
    ): TermuxResult = withContext(Dispatchers.IO) {
        isRunning = true
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val taskDir = File(sharedDir, "task_$timestamp")
            taskDir.mkdirs()

            onProgress("Preparing files for Termux...")

            // Copy input image to task dir
            val inputFile = File(taskDir, "input.png")
            copyInputImage(inputImagePath, inputFile)

            // Ensure model is accessible
            val modelInShared = File(modelsDir, modelFileName)
            if (!modelInShared.exists()) {
                onProgress("Copying model to shared storage (one-time)...")
                val sourceModel = File(modelFilePath)
                sourceModel.copyTo(modelInShared, overwrite = true)
            }

            onProgress("Launching Termux...")

            // Write task JSON
            val taskJson = JSONObject().apply {
                put("input_image", inputFile.absolutePath)
                put("model_path", modelInShared.absolutePath)
                put("output_image", File(taskDir, "depth_output.png").absolutePath)
            }
            val taskFile = File(taskDir, "task.json")
            taskFile.writeText(taskJson.toString())

            // Extract and copy Python script to Termux-accessible location
            val scriptFile = copyPythonScript(taskDir)

            // Launch Termux via RUN_COMMAND intent
            launchTermux(scriptFile.absolutePath, taskFile.absolutePath)

            onProgress("Waiting for Termux to complete...")

            // Poll for result
            val resultFile = File(taskDir, "result.json")
            val startTime = System.currentTimeMillis()
            var result: TermuxResult? = null

            while (result == null && isRunning) {
                if (System.currentTimeMillis() - startTime > MAX_POLL_TIME_MS) {
                    result = TermuxResult(
                        success = false,
                        errorMessage = "Termux did not respond within ${MAX_POLL_TIME_MS / 1000}s"
                    )
                    break
                }

                if (resultFile.exists()) {
                    val content = resultFile.readText()
                    val json = JSONObject(content)
                    val status = json.optString("status")
                    result = when (status) {
                        "ok" -> {
                            val output = json.optString("output")
                            TermuxResult(success = true, outputPath = output)
                        }
                        "error" -> TermuxResult(
                            success = false,
                            errorMessage = json.optString("error", "Unknown error")
                        )
                        else -> null
                    }
                }

                if (result == null) {
                    delay(POLL_INTERVAL_MS)
                }
            }

            result ?: TermuxResult(success = false, errorMessage = "Cancelled")

        } catch (e: Exception) {
            Log.e(TAG, "Termux inference failed", e)
            TermuxResult(success = false, errorMessage = e.message ?: "Unknown error")
        } finally {
            isRunning = false
        }
    }

    fun cancel() {
        isRunning = false
    }

    private fun copyInputImage(sourcePath: String, destFile: File) {
        val sourceFile = File(sourcePath)
        if (sourceFile.exists()) {
            sourceFile.copyTo(destFile, overwrite = true)
        } else {
            // Try as content URI
            try {
                val uri = Uri.parse(sourcePath)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to copy input image from URI", e)
            }
        }
    }

    private fun copyPythonScript(taskDir: File): File {
        // First, extract the Python script from assets to a shared location
        val scriptFile = File(taskDir, "depth_termux.py")
        try {
            context.assets.open("depth_termux.py").use { input ->
                scriptFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            scriptFile.setExecutable(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract Python script", e)
        }
        return scriptFile
    }

    private fun launchTermux(scriptPath: String, taskPath: String) {
        try {
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                component = ComponentName(
                    "com.termux",
                    "com.termux.app.RunCommandService"
                )
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/python3")
                putExtra(
                    "com.termux.RUN_COMMAND_ARGUMENTS",
                    arrayOf(scriptPath, taskPath)
                )
                putExtra("com.termux.RUN_COMMAND_WORKDIR", File(scriptPath).parent)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Termux via service, trying activity...")
            try {
                val intent = Intent("com.termux.RUN_COMMAND").apply {
                    putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/python3")
                    putExtra(
                        "com.termux.RUN_COMMAND_ARGUMENTS",
                        arrayOf(scriptPath, taskPath)
                    )
                    putExtra("com.termux.RUN_COMMAND_WORKDIR", File(scriptPath).parent)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to launch Termux", e2)
                throw e2
            }
        }
    }

    fun registerOutputWithGallery(outputPath: String) {
        try {
            val file = File(outputPath)
            if (!file.exists()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
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
                        file.inputStream().use { it.copyTo(out) }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val picturesDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES
                )
                val publicDir = File(picturesDir, "DepthMap")
                publicDir.mkdirs()
                val publicFile = File(publicDir, file.name)
                file.copyTo(publicFile, overwrite = true)
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(publicFile.absolutePath),
                    arrayOf("image/png"),
                    null
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register output with gallery", e)
        }
    }

    suspend fun verifySetup(): TermuxSetupStatus = withContext(Dispatchers.IO) {
        val termuxInstalled = isTermuxInstalled(context)

        if (!termuxInstalled) {
            return@withContext TermuxSetupStatus(
                termuxInstalled = false,
                ready = false,
                message = "Termux is not installed. Install from F-Droid: https://f-droid.org/packages/com.termux/"
            )
        }

        // Check if shared storage is accessible
        val testDir = sharedDir
        val testFile = File(testDir, ".termux_bridge_test")
        try {
            testFile.writeText("test")
            testFile.delete()
        } catch (e: Exception) {
            return@withContext TermuxSetupStatus(
                termuxInstalled = true,
                ready = false,
                message = "Cannot access shared storage. Grant storage permission to DepthMap."
            )
        }

        TermuxSetupStatus(
            termuxInstalled = true,
            ready = true,
            message = "Termux is ready. Run 'pip install onnxruntime pillow numpy' in Termux."
        )
    }

    data class TermuxSetupStatus(
        val termuxInstalled: Boolean,
        val ready: Boolean,
        val message: String
    )
}
