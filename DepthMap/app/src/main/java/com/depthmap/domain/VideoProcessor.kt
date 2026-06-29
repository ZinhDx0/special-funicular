package com.depthmap.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

class VideoProcessor(private val context: Context) {

    private var isCancelled = false

    fun cancel() {
        isCancelled = true
    }

    suspend fun processVideo(
        videoUri: Uri,
        outputDir: File,
        estimator: DepthEstimator,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        isCancelled = false
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val depthFramesDir = File(context.cacheDir, "depth_frames_$timestamp")
            depthFramesDir.mkdirs()

            val contentResolver = context.contentResolver
            val videoFile = File(context.cacheDir, "input_video_$timestamp.mp4")
            contentResolver.openInputStream(videoUri)?.use { input ->
                videoFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure(Exception("Cannot open video"))

            val frameCount = extractAndProcessFrames(videoUri, depthFramesDir, estimator, onProgress)

            if (isCancelled || frameCount == 0) {
                videoFile.delete()
                depthFramesDir.deleteRecursively()
                if (isCancelled) return@withContext Result.failure(Exception("Cancelled"))
                return@withContext Result.failure(Exception("No frames could be processed"))
            }

            val outputFile = File(outputDir, "depthmap_${timestamp}.mp4")
            val success = reencodeVideo(frameCount, depthFramesDir, videoFile, outputFile)

            videoFile.delete()
            depthFramesDir.deleteRecursively()

            if (success && outputFile.exists() && outputFile.length() > 0) {
                Result.success(outputFile)
            } else {
                if (outputFile.exists()) outputFile.delete()
                Result.failure(Exception("Failed to re-encode video"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun extractAndProcessFrames(
        videoUri: Uri,
        depthFramesDir: File,
        estimator: DepthEstimator,
        onProgress: (Float) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, videoUri)

        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: run { retriever.release(); return@withContext 0 }

        val targetFps = 30
        val totalFrames = (durationMs * targetFps / 1000).toInt()
        if (totalFrames <= 0) { retriever.release(); return@withContext 0 }

        var processedCount = 0
        for (i in 0 until totalFrames) {
            if (isCancelled || !coroutineContext.isActive) {
                retriever.release()
                return@withContext processedCount
            }

            val timeUs = i * 1000000L / targetFps
            val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            if (frame != null) {
                val depthResult = estimator.estimateDepth(frame)
                if (depthResult.isSuccess) {
                    val depthBitmap = depthResult.getOrThrow()
                    val depthFile = File(depthFramesDir, String.format("%08d.png", processedCount))
                    depthBitmap.compress(Bitmap.CompressFormat.PNG, 100, depthFile.outputStream())
                    depthBitmap.recycle()
                    processedCount++
                }
                frame.recycle()
            }
            onProgress((i + 1).toFloat() / totalFrames)
        }
        retriever.release()
        processedCount
    }

    private data class AudioSample(
        val bufferInfo: MediaCodec.BufferInfo,
        val data: ByteBuffer
    )

    private data class AudioTrackInfo(
        val format: MediaFormat,
        val samples: List<AudioSample>
    )

    private fun reencodeVideo(
        frameCount: Int,
        depthFramesDir: File,
        videoFile: File,
        outputFile: File
    ): Boolean {
        try {
            val firstFrameFile = File(depthFramesDir, "00000000.png")
            val firstFrame = BitmapFactory.decodeFile(firstFrameFile.absolutePath)
                ?: return false
            val w = firstFrame.width
            val h = firstFrame.height
            firstFrame.recycle()

            val targetFps = 30
            val bitRate = calculateBitRate(w, h, targetFps)

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            val encodedDatas = mutableListOf<ByteArray>()
            val bufferInfo = MediaCodec.BufferInfo()
            var encoderOutputFormat: MediaFormat? = null

            for (i in 0 until frameCount) {
                if (isCancelled) {
                    cleanupEncoder(encoder, inputSurface, null, null)
                    return false
                }

                val frameFile = File(depthFramesDir, String.format("%08d.png", i))
                if (!frameFile.exists()) continue

                val bitmap = BitmapFactory.decodeFile(frameFile.absolutePath) ?: continue

                try {
                    val canvas = inputSurface.lockCanvas(null)
                    canvas.drawBitmap(bitmap, null, Rect(0, 0, w, h), null)
                    inputSurface.unlockCanvasAndPost(canvas)
                } finally {
                    bitmap.recycle()
                }

                drainEncoderOutput(encoder, bufferInfo, encodedDatas) { fmt ->
                    encoderOutputFormat = fmt
                }
            }

            encoder.signalEndOfInputStream()
            drainEncoderOutput(encoder, bufferInfo, encodedDatas) { fmt ->
                encoderOutputFormat = fmt
            }

            if (encodedDatas.isEmpty()) {
                cleanupEncoder(encoder, inputSurface, null, null)
                return false
            }

            val actualFormat = encoderOutputFormat
            val audioInfo = extractAudioTrackInfo(videoFile)

            val muxer: MediaMuxer
            try {
                muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } catch (e: Exception) {
                cleanupEncoder(encoder, inputSurface, null, null)
                return false
            }

            try {
                val videoTrack: Int
                if (actualFormat != null) {
                    videoTrack = muxer.addTrack(actualFormat)
                } else {
                    videoTrack = muxer.addTrack(format)
                }

                var audioTrack = -1
                if (audioInfo != null) {
                    audioTrack = muxer.addTrack(audioInfo.format)
                }

                muxer.start()

                var vi = 0
                var ai = 0
                val audioSamples = audioInfo?.samples ?: emptyList()

                while (vi < encodedDatas.size || ai < audioSamples.size) {
                    val takeVideo = when {
                        vi >= encodedDatas.size -> false
                        ai >= audioSamples.size -> true
                        else -> {
                            val vPts = vi * 1000000L / targetFps
                            val aPts = audioSamples[ai].bufferInfo.presentationTimeUs
                            vPts <= aPts
                        }
                    }

                    if (takeVideo) {
                        val ptsUs = vi * 1000000L / targetFps
                        val info = MediaCodec.BufferInfo()
                        info.set(0, encodedDatas[vi].size, ptsUs, 0)
                        muxer.writeSampleData(videoTrack, ByteBuffer.wrap(encodedDatas[vi]), info)
                        vi++
                    } else {
                        val sample = audioSamples[ai]
                        val info = sample.bufferInfo
                        if (info.size > 0) {
                            val buf = sample.data.duplicate()
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(audioTrack, buf, info)
                        }
                        ai++
                    }
                }
            } finally {
                try { muxer.stop() } catch (_: Exception) {}
                try { muxer.release() } catch (_: Exception) {}
            }

            cleanupEncoder(encoder, inputSurface, null, null)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun cleanupEncoder(
        encoder: MediaCodec?,
        surface: android.view.Surface?,
        muxer: MediaMuxer?,
        outputFile: File?
    ) {
        try { encoder?.signalEndOfInputStream() } catch (_: Exception) {}
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        try { surface?.release() } catch (_: Exception) {}
        try { muxer?.stop() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        if (outputFile?.exists() == true) outputFile.delete()
    }

    private fun drainEncoderOutput(
        encoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        output: MutableList<ByteArray>,
        onFormatChanged: (MediaFormat) -> Unit
    ) {
        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 5000L)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    onFormatChanged(encoder.outputFormat)
                }
                outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {}
                outputIndex >= 0 -> {
                    val buffer = encoder.getOutputBuffer(outputIndex)
                    if (buffer != null && bufferInfo.size > 0) {
                        val data = ByteArray(bufferInfo.size)
                        buffer.position(bufferInfo.offset)
                        buffer.get(data)
                        output.add(data)
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)
                }
                else -> break
            }
        }
    }

    private fun extractAudioTrackInfo(videoFile: File): AudioTrackInfo? {
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(videoFile.absolutePath)

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    audioFormat = fmt
                    break
                }
            }
            if (audioTrackIndex < 0 || audioFormat == null) {
                extractor.release()
                return null
            }

            extractor.selectTrack(audioTrackIndex)
            val samples = mutableListOf<AudioSample>()
            val maxBufferSize = 1024 * 1024
            val buffer = ByteBuffer.allocate(maxBufferSize)

            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val info = MediaCodec.BufferInfo()
                info.set(0, sampleSize, extractor.sampleTime, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                val data = ByteBuffer.allocate(sampleSize)
                buffer.position(0)
                buffer.limit(sampleSize)
                data.put(buffer)
                data.flip()
                samples.add(AudioSample(info, data))
                extractor.advance()
            }
            extractor.release()
            return AudioTrackInfo(audioFormat!!, samples)
        } catch (_: Exception) {
            return null
        }
    }

    private fun calculateBitRate(width: Int, height: Int, fps: Int): Int {
        return (width * height * fps * 0.07).toInt().coerceIn(500_000, 20_000_000)
    }
}
