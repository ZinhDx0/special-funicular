package com.depthmap.domain

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer

class DepthEstimator(private val context: Context) {

    private val ortEnvironment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null

    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val std = floatArrayOf(0.229f, 0.224f, 0.225f)

    suspend fun loadModel(modelFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            ortSession?.close()
            ortSession = null
            val sessionOptions = OrtSession.SessionOptions()
            sessionOptions.setIntraOpNumThreads(4)
            ortSession = ortEnvironment.createSession(modelFile.absolutePath, sessionOptions)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun isModelLoaded(): Boolean = ortSession != null

    suspend fun estimateDepth(inputBitmap: Bitmap): Result<Bitmap> = withContext(Dispatchers.IO) {
        try {
            val session = ortSession ?: return@withContext Result.failure(Exception("Model not loaded"))

            val inputInfo = session.inputInfo.values.first().info as TensorInfo
            val inputName = session.inputInfo.keys.first()
            val outputName = session.outputInfo.keys.first()

            val inputShape = inputInfo.shape
            val targetW = inputShape[2].toInt()
            val targetH = inputShape[3].toInt()

            val resized = Bitmap.createScaledBitmap(inputBitmap, targetW, targetH, true)

            val inputChannels = 3
            val floatArray = FloatArray(targetW * targetH * inputChannels)
            val pixels = IntArray(targetW * targetH)
            resized.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)

            for (i in pixels.indices) {
                val pixel = pixels[i]
                val r = ((pixel shr 16) and 0xFF) / 255.0f
                val g = ((pixel shr 8) and 0xFF) / 255.0f
                val b = (pixel and 0xFF) / 255.0f

                floatArray[i] = (r - mean[0]) / std[0]
                floatArray[i + targetW * targetH] = (g - mean[1]) / std[1]
                floatArray[i + targetW * targetH * 2] = (b - mean[2]) / std[2]
            }

            val tensor = OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(floatArray), longArrayOf(1L, 3L, targetH.toLong(), targetW.toLong()))

            val results = session.run(mapOf(inputName to tensor))
            val outputValue = results.get(outputName)
                .orElseThrow { Exception("No output tensor for: $outputName") }
            val outputTensor = outputValue as OnnxTensor
            val outputInfo = outputTensor.info as TensorInfo
            val outputBuffer = outputTensor.floatBuffer

            val outH = outputInfo.shape[2].toInt()
            val outW = outputInfo.shape[3].toInt()

            val depthArray = FloatArray(outW * outH)
            outputBuffer.rewind()
            outputBuffer.get(depthArray)

            var minVal = Float.MAX_VALUE
            var maxVal = Float.MIN_VALUE
            for (v in depthArray) {
                if (v < minVal) minVal = v
                if (v > maxVal) maxVal = v
            }
            val range = maxVal - minVal

            val depthBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val depthPixels = IntArray(outW * outH)
            for (i in depthArray.indices) {
                val normalized = ((depthArray[i] - minVal) / range * 255.0).toInt().coerceIn(0, 255)
                depthPixels[i] = (0xFF shl 24) or (normalized shl 16) or (normalized shl 8) or normalized
            }
            depthBitmap.setPixels(depthPixels, 0, outW, 0, 0, outW, outH)

            val scaledDepth = Bitmap.createScaledBitmap(depthBitmap, inputBitmap.width, inputBitmap.height, true)

            results.close()
            tensor.close()

            Result.success(scaledDepth)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun close() {
        ortSession?.close()
        ortSession = null
    }
}
