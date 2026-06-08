package com.example.mine_detector

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.metadata.MetadataExtractor
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.*

class ObjectDetectionAnalyzer(
    private val onResults: (List<Detection>, Int, Int) -> Unit
) : ImageAnalysis.Analyzer {

    enum class ModelType { SSD, YOLO }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private val lock = ReentrantLock()
    private var labels = mutableListOf<String>()
    
    private var targetWidth: Int = 0
    private var targetHeight: Int = 0
    private var isModelQuantized: Boolean = false
    private var modelType: ModelType = ModelType.SSD
    private var isProcessing = false
    
    var threshold: Float = 0.3f
    
    private var inputBuffer: ByteBuffer? = null
    private var intValues: IntArray? = null

    // YOLO specific state
    private var yoloOutputShape: IntArray? = null
    private var lastErrorMessage: String? = null
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var configJob: Job? = null

    fun updateConfig(file: File?) {
        configJob?.cancel()
        if (file == null) {
            close()
            return
        }
        
        configJob = scope.launch {
            setupInterpreter(file)
        }
    }

    private fun setupInterpreter(file: File) {
        lock.withLock {
            close()
            try {
                val modelBuffer = loadModelFile(file)
                
                val metadataExtractor = MetadataExtractor(modelBuffer)
                try {
                    metadataExtractor.getAssociatedFile("labelmap.txt")?.let { inputStream ->
                        labels.clear()
                        InputStreamReader(inputStream).buffered().useLines { lines ->
                            labels.addAll(lines)
                        }
                        AppLogger.log("Loaded ${labels.size} labels from metadata")
                    }
                } catch (e: Exception) {
                    AppLogger.log("No labelmap.txt found in metadata")
                }

                val options = Interpreter.Options()
                try {
                    gpuDelegate = GpuDelegate()
                    options.addDelegate(gpuDelegate)
                    AppLogger.log("TFLite initialized with GPU")
                } catch (e: Exception) {
                    AppLogger.log("TFLite GPU failed: ${e.message}")
                }
                
                val newInterpreter = Interpreter(modelBuffer, options)
                
                // Inspect Input Tensor
                val inputTensor = newInterpreter.getInputTensor(0)
                val shape = inputTensor.shape() // [1, height, width, 3]
                this.targetHeight = shape[1]
                this.targetWidth = shape[2]
                this.isModelQuantized = inputTensor.dataType() != org.tensorflow.lite.DataType.FLOAT32
                
                val bytesPerChannel = if (isModelQuantized) 1 else 4
                inputBuffer = ByteBuffer.allocateDirect(targetWidth * targetHeight * 3 * bytesPerChannel)
                inputBuffer?.order(ByteOrder.nativeOrder())
                intValues = IntArray(targetWidth * targetHeight)
                
                // Determine Model Type
                modelType = if (newInterpreter.outputTensorCount == 1) {
                    val outShape = newInterpreter.getOutputTensor(0).shape()
                    if (outShape.size == 3) {
                        yoloOutputShape = outShape
                        ModelType.YOLO
                    } else {
                        ModelType.SSD
                    }
                } else {
                    ModelType.SSD
                }

                AppLogger.log("Model setup: ${targetWidth}x${targetHeight}, Type: $modelType, Quantized: $isModelQuantized")
                
                interpreter = newInterpreter
            } catch (e: Exception) {
                AppLogger.log("Failed to initialize TFLite: ${e.message}")
            }
        }
    }

    private fun loadModelFile(file: File): MappedByteBuffer {
        val inputStream = FileInputStream(file)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size())
    }

    @ExperimentalGetImage
    override fun analyze(image: ImageProxy) {
        if (isProcessing) {
            image.close()
            return
        }

        lock.withLock {
            val currentInterpreter = interpreter ?: run {
                image.close()
                return
            }

            try {
                isProcessing = true
                
                // 1. Pre-process using standard Bitmap operations
                val bitmap = image.toBitmap()
                
                val matrix = Matrix()
                matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
                val size = minOf(bitmap.width, bitmap.height)
                val x = (bitmap.width - size) / 2
                val y = (bitmap.height - size) / 2
                
                val scaledBitmap = Bitmap.createBitmap(
                    bitmap, x, y, size, size, matrix, true
                ).let {
                    Bitmap.createScaledBitmap(it, targetWidth, targetHeight, true)
                }

                val buffer = inputBuffer ?: return
                val pixels = intValues ?: return
                
                buffer.rewind()
                scaledBitmap.getPixels(pixels, 0, scaledBitmap.width, 0, 0, scaledBitmap.width, scaledBitmap.height)
                
                if (isModelQuantized) {
                    for (pixelValue in pixels) {
                        buffer.put((pixelValue shr 16 and 0xFF).toByte())
                        buffer.put((pixelValue shr 8 and 0xFF).toByte())
                        buffer.put((pixelValue and 0xFF).toByte())
                    }
                } else {
                    for (pixelValue in pixels) {
                        buffer.putFloat((pixelValue shr 16 and 0xFF) / 255.0f)
                        buffer.putFloat((pixelValue shr 8 and 0xFF) / 255.0f)
                        buffer.putFloat((pixelValue and 0xFF) / 255.0f)
                    }
                }

                val detections = if (modelType == ModelType.YOLO) {
                    runYoloInference(currentInterpreter, buffer)
                } else {
                    runSsdInference(currentInterpreter, buffer)
                }
                
                onResults(detections, targetWidth, targetHeight)
                lastErrorMessage = null

            } catch (e: Exception) {
                val msg = e.message ?: "Unknown error"
                if (msg != lastErrorMessage) {
                    AppLogger.log("Inference error: $msg")
                    lastErrorMessage = msg
                }
            } finally {
                isProcessing = false
                image.close()
            }
        }
    }

    private fun runSsdInference(interpreter: Interpreter, buffer: ByteBuffer): List<Detection> {
        val outputLocations = Array(1) { Array(10) { FloatArray(4) } }
        val outputClasses = Array(1) { FloatArray(10) }
        val outputScores = Array(1) { FloatArray(10) }
        val numDetections = FloatArray(1)

        val outputs = mutableMapOf<Int, Any>(
            0 to outputLocations,
            1 to outputClasses,
            2 to outputScores,
            3 to numDetections
        )

        interpreter.runForMultipleInputsOutputs(arrayOf(buffer), outputs)

        val detections = mutableListOf<Detection>()
        for (i in 0 until numDetections[0].toInt()) {
            if (outputScores[0][i] > threshold) {
                val box = outputLocations[0][i]
                val rect = RectF(
                    box[1] * targetWidth,
                    box[0] * targetHeight,
                    box[3] * targetWidth,
                    box[2] * targetHeight
                )
                
                val classId = outputClasses[0][i].toInt()
                val label = labels.getOrNull(classId) ?: ""
                
                detections.add(Detection(rect, listOf(Category(label, outputScores[0][i]))))
            }
        }
        return detections
    }

    private fun runYoloInference(interpreter: Interpreter, buffer: ByteBuffer): List<Detection> {
        val shape = yoloOutputShape ?: return emptyList()
        // Handle both [1, 5, 8400] and [1, 8400, 5]
        val isTransposed = shape[1] > shape[2]
        val numPredictions = if (isTransposed) shape[1] else shape[2]
        val numElements = if (isTransposed) shape[2] else shape[1]
        val numClasses = numElements - 4

        val output = Array(1) { Array(shape[1]) { FloatArray(shape[2]) } }
        val outputs = mutableMapOf<Int, Any>(0 to output)
        interpreter.runForMultipleInputsOutputs(arrayOf(buffer), outputs)

        val detections = mutableListOf<Detection>()

        for (i in 0 until numPredictions) {
            var maxScore = 0f
            var classId = -1
            
            for (c in 0 until numClasses) {
                val s = if (isTransposed) output[0][i][4 + c] else output[0][4 + c][i]
                if (s > maxScore) {
                    maxScore = s
                    classId = c
                }
            }

            if (maxScore > threshold) {
                var cx = if (isTransposed) output[0][i][0] else output[0][0][i]
                var cy = if (isTransposed) output[0][i][1] else output[0][1][i]
                var w = if (isTransposed) output[0][i][2] else output[0][2][i]
                var h = if (isTransposed) output[0][i][3] else output[0][3][i]

                // Automatically detect if coordinates are normalized (0-1) or pixel-based
                // If the largest dimension is < 2, it's highly likely normalized.
                if (cx <= 1.5f && w <= 1.5f) {
                    cx *= targetWidth
                    cy *= targetHeight
                    w *= targetWidth
                    h *= targetHeight
                }

                val rect = RectF(
                    (cx - w / 2),
                    (cy - h / 2),
                    (cx + w / 2),
                    (cy + h / 2)
                )
                
                val label = labels.getOrNull(classId) ?: ""
                detections.add(Detection(rect, listOf(Category(label, maxScore))))
            }
        }

        if (detections.isNotEmpty()) {
            // AppLogger.log("Detected ${detections.size} candidates before NMS")
        }

        return nms(detections)
    }

    private fun nms(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()
        
        val sorted = detections.sortedByDescending { it.categories.first().score }
        val selected = mutableListOf<Detection>()
        val active = BooleanArray(sorted.size) { true }

        for (i in sorted.indices) {
            if (active[i]) {
                selected.add(sorted[i])
                for (j in i + 1 until sorted.size) {
                    if (active[j]) {
                        if (calculateIoU(sorted[i].boundingBox, sorted[j].boundingBox) > 0.45f) {
                            active[j] = false
                        }
                    }
                }
            }
        }
        return selected.take(10) // Limit to top 10 for performance and UI
    }

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val intersection = RectF()
        if (!intersection.setIntersect(a, b)) return 0f
        val intersectionArea = intersection.width() * intersection.height()
        val unionArea = (a.width() * a.height()) + (b.width() * b.height()) - intersectionArea
        return intersectionArea / unionArea
    }

    fun close() {
        lock.withLock {
            interpreter?.close()
            interpreter = null
            gpuDelegate?.close()
            gpuDelegate = null
        }
    }
}
