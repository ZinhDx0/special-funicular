package com.depthmap.domain

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer

class DepthEstimator(private val context: Context) {

    companion object {
        private const val TAG = "DepthEstimator"
    }

    private val ortEnvironment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null

    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val std = floatArrayOf(0.229f, 0.224f, 0.225f)

    private var cachedInputSize: Pair<Int, Int>? = null

    suspend fun loadModel(modelFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "loadModel: ${modelFile.absolutePath}")

            if (!modelFile.exists()) {
                val msg = "Model file not found: ${modelFile.absolutePath}"
                Log.e(TAG, msg)
                return@withContext Result.failure(Exception(msg))
            }

            val fileSize = modelFile.length()
            Log.d(TAG, "Model file size: $fileSize bytes (${fileSize / (1024*1024)} MB)")

            if (fileSize < 1000) {
                val msg = "Model file too small (corrupt?): $fileSize bytes"
                Log.e(TAG, msg)
                return@withContext Result.failure(Exception(msg))
            }

            ortSession?.close()
            ortSession = null
            cachedInputSize = null

            val sessionOptions = OrtSession.SessionOptions()
            sessionOptions.setIntraOpNumThreads(4)
            sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)

            Log.d(TAG, "Creating ONNX session...")
            ortSession = ortEnvironment.createSession(modelFile.absolutePath, sessionOptions)
            Log.d(TAG, "ONNX session created successfully")

            val inputNames = ortSession!!.inputInfo.keys
            val outputNames = ortSession!!.outputInfo.keys
            Log.d(TAG, "Inputs: $inputNames, Outputs: $outputNames")

            for ((name, info) in ortSession!!.inputInfo) {
                val tensorInfo = info.info as? TensorInfo
                Log.d(TAG, "Input '$name': shape=${tensorInfo?.shape?.contentToString()}, type=${tensorInfo?.type}")
            }
            for ((name, info) in ortSession!!.outputInfo) {
                val tensorInfo = info.info as? TensorInfo
                Log.d(TAG, "Output '$name': shape=${tensorInfo?.shape?.contentToString()}, type=${tensorInfo?.type}")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun isModelLoaded(): Boolean = ortSession != null

    suspend fun estimateDepth(inputBitmap: Bitmap): Result<Bitmap> = withContext(Dispatchers.IO) {
        try {
            val session = ortSession
            if (session == null) {
                Log.e(TAG, "estimateDepth: Model not loaded")
                return@withContext Result.failure(Exception("Model not loaded"))
            }

            Log.d(TAG, "estimateDepth: input bitmap ${inputBitmap.width}x${inputBitmap.height}")

            val inputInfo = session.inputInfo.values.first().info as TensorInfo
            val inputName = session.inputInfo.keys.first()
            val outputName = session.outputInfo.keys.first()
            val inputShape = inputInfo.shape
            val inputType = inputInfo.type

            Log.d(TAG, "Model input shape: ${inputShape.contentToString()}, type: $inputType")
            Log.d(TAG, "Input name: '$inputName', Output name: '$outputName'")

            // Detect NCHW ([1,3,H,W]) vs NHWC ([1,H,W,3]) layout
            val isNHWC = inputShape.size == 4 && inputShape[3] in 1L..4L && inputShape[1] > 4L
            val isNCHW = inputShape.size == 4 && inputShape[1] in 1L..4L && inputShape[3] > 4L

            val targetW: Int
            val targetH: Int
            if (isNCHW) {
                targetW = inputShape[3].toInt()
                targetH = inputShape[2].toInt()
            } else if (isNHWC) {
                targetW = inputShape[2].toInt()
                targetH = inputShape[1].toInt()
            } else {
                targetW = inputShape[3].toInt()
                targetH = inputShape[2].toInt()
            }

            Log.d(TAG, "Detected layout: ${if (isNHWC) "NHWC" else if (isNCHW) "NCHW" else "unknown"}, target: ${targetW}x${targetH}")

            if (targetW <= 0 || targetH <= 0) {
                val msg = "Invalid model input dimensions: ${targetW}x${targetH}"
                Log.e(TAG, msg)
                return@withContext Result.failure(Exception(msg))
            }

            cachedInputSize = Pair(targetW, targetH)

            Log.d(TAG, "Resizing input to ${targetW}x${targetH}")
            val resized = Bitmap.createScaledBitmap(inputBitmap, targetW, targetH, true)

            val inputChannels = 3
            val pixelCount = targetW * targetH
            val pixels = IntArray(pixelCount)
            resized.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)

            val tensor: OnnxTensor
            if (isNHWC) {
                // NHWC format: [1, H, W, 3], typically UINT8
                val byteBuffer = ByteBuffer.allocate(pixelCount * 3)
                for (i in pixels.indices) {
                    val pixel = pixels[i]
                    byteBuffer.put(((pixel shr 16) and 0xFF).toByte())
                    byteBuffer.put(((pixel shr 8) and 0xFF).toByte())
                    byteBuffer.put((pixel and 0xFF).toByte())
                }
                byteBuffer.rewind()
                tensor = OnnxTensor.createTensor(
                    ortEnvironment, byteBuffer,
                    longArrayOf(1L, targetH.toLong(), targetW.toLong(), 3L), OnnxJavaType.UINT8
                )
            } else {
                // NCHW format: [1, 3, H, W], FLOAT32 with normalization
                val floatArray = FloatArray(pixelCount * inputChannels)
                for (i in pixels.indices) {
                    val pixel = pixels[i]
                    val r = ((pixel shr 16) and 0xFF) / 255.0f
                    val g = ((pixel shr 8) and 0xFF) / 255.0f
                    val b = (pixel and 0xFF) / 255.0f
                    floatArray[i] = (r - mean[0]) / std[0]
                    floatArray[i + pixelCount] = (g - mean[1]) / std[1]
                    floatArray[i + pixelCount * 2] = (b - mean[2]) / std[2]
                }
                tensor = OnnxTensor.createTensor(
                    ortEnvironment, FloatBuffer.wrap(floatArray),
                    longArrayOf(1L, 3L, targetH.toLong(), targetW.toLong())
                )
            }

            Log.d(TAG, "Running inference...")
            val results = session.run(mapOf(inputName to tensor))

            val outputOptional = results.get(outputName)
            if (!outputOptional.isPresent) {
                results.close()
                tensor.close()
                val msg = "No output tensor for: $outputName"
                Log.e(TAG, msg)
                return@withContext Result.failure(Exception(msg))
            }

            val outputValue = outputOptional.get()
            if (outputValue !is OnnxTensor) {
                results.close()
                tensor.close()
                val msg = "Output is not OnnxTensor: ${outputValue.javaClass.name}"
                Log.e(TAG, msg)
                return@withContext Result.failure(Exception(msg))
            }

            val outputTensor = outputValue as OnnxTensor
            val outputInfo = outputTensor.info as TensorInfo
            val outShape = outputInfo.shape
            Log.d(TAG, "Output shape: ${outShape.contentToString()}")

            val outH: Int
            val outW: Int
            when (outShape.size) {
                4 -> {
                    if (outShape[1] in 1L..4L && outShape[3] > 4L) {
                        // NCHW output: [1, 1, H, W]
                        outH = outShape[2].toInt()
                        outW = outShape[3].toInt()
                    } else {
                        // NHWC output: [1, H, W, 1]
                        outH = outShape[1].toInt()
                        outW = outShape[2].toInt()
                    }
                }
                3 -> {
                    outH = outShape[1].toInt()
                    outW = outShape[2].toInt()
                }
                2 -> {
                    outH = outShape[0].toInt()
                    outW = outShape[1].toInt()
                }
                1 -> {
                    val side = Math.sqrt(outShape[0].toDouble()).toInt()
                    outH = side
                    outW = side
                }
                else -> {
                    results.close()
                    tensor.close()
                    val msg = "Unexpected output shape rank: ${outShape.size}"
                    Log.e(TAG, msg)
                    return@withContext Result.failure(Exception(msg))
                }
            }

            Log.d(TAG, "Output dimensions: ${outW}x${outH}")

            val depthArray = FloatArray(outW * outH)
            outputTensor.floatBuffer.rewind()
            outputTensor.floatBuffer.get(depthArray)

            val depthBitmap = applyInfernoColormap(depthArray, outW, outH)

            val scaledDepth = Bitmap.createScaledBitmap(depthBitmap, inputBitmap.width, inputBitmap.height, true)
            depthBitmap.recycle()

            results.close()
            tensor.close()

            Log.d(TAG, "Depth estimation completed successfully")
            Result.success(scaledDepth)
        } catch (e: ai.onnxruntime.OrtException) {
            Log.e(TAG, "ONNX Runtime error: ${e.message}", e)
            Result.failure(Exception("ONNX error: ${e.message}"))
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory during inference", e)
            Result.failure(Exception("Out of memory. Try a smaller model."))
        } catch (e: Exception) {
            Log.e(TAG, "Depth estimation failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun verifyModel(modelFile: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!modelFile.exists()) {
                return@withContext Result.failure(Exception("File not found"))
            }

            val tempSession = ortEnvironment.createSession(modelFile.absolutePath)
            val inputInfo = tempSession.inputInfo
            val outputInfo = tempSession.outputInfo
            val sb = StringBuilder()
            sb.appendLine("Inputs:")
            for ((name, value) in inputInfo) {
                val ti = value.info as? TensorInfo
                sb.appendLine("  $name: shape=${ti?.shape?.contentToString()}, type=${ti?.type}")
            }
            sb.appendLine("Outputs:")
            for ((name, value) in outputInfo) {
                val ti = value.info as? TensorInfo
                sb.appendLine("  $name: shape=${ti?.shape?.contentToString()}, type=${ti?.type}")
            }
            tempSession.close()
            Result.success(sb.toString())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getCachedInputSize(): Pair<Int, Int>? = cachedInputSize

    fun close() {
        Log.d(TAG, "Closing session")
        ortSession?.close()
        ortSession = null
    }
}
