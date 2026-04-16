package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class RecognitionResult(
    val predictions: List<Prediction>,
    val inferenceTime: Long
)

data class Prediction(
    val label: String,
    val score: Float
)

class ASLSignDetector(
    private val context: Context,
    private val modelPath: String = "hand_signs.tflite",
    private val onDebugInfo: (RecognitionResult) -> Unit,
    private val onResult: (String) -> Unit
) {
    private var interpreter: Interpreter? = null
    private var lastResultTime: Long = 0
    private val DETECTION_INTERVAL = 1500L
    private var labels: List<String> = emptyList()
    
    private var inputHeight = 0
    private var inputWidth = 0

    init {
        setupInterpreter()
    }

    private fun setupInterpreter() {
        try {
            val model = loadModelFile(context, modelPath)
            interpreter = Interpreter(model)
            
            val inputShape = interpreter?.getInputTensor(0)?.shape()
            inputHeight = inputShape?.get(1) ?: 224
            inputWidth = inputShape?.get(2) ?: 224
            
            labels = context.assets.open("labels.txt").bufferedReader().useLines { it.toList() }
            Log.d("ASLSignDetector", "Ready. Input target: ${inputWidth}x${inputHeight}")
        } catch (e: Exception) {
            Log.e("ASLSignDetector", "Init error: ${e.message}")
        }
    }

    private fun loadModelFile(context: Context, path: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(path)
        val fileChannel = FileInputStream(fileDescriptor.fileDescriptor).channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    fun detect(bitmap: Bitmap) {
        val interpreter = interpreter ?: return
        if (labels.isEmpty()) return

        try {
            val startTime = SystemClock.uptimeMillis()
            
            // 1. Center-Crop to Square and Scale to Target Size (e.g. 224x224)
            val croppedBitmap = centerCropAndScale(bitmap, inputWidth, inputHeight)
            val byteBuffer = convertBitmapToByteBuffer(croppedBitmap)

            val outputShape = interpreter.getOutputTensor(0).shape()
            val outputBuffer = ByteBuffer.allocateDirect(outputShape[1] * 4).apply {
                order(ByteOrder.nativeOrder())
            }

            interpreter.run(byteBuffer, outputBuffer.rewind())

            outputBuffer.rewind()
            val probabilities = FloatArray(outputShape[1])
            outputBuffer.asFloatBuffer().get(probabilities)

            val inferenceTime = SystemClock.uptimeMillis() - startTime
            
            val predictions = labels.mapIndexed { index, label ->
                Prediction(label, if (index < probabilities.size) probabilities[index] else 0f)
            }.sortedByDescending { it.score }.take(5)
            
            onDebugInfo(RecognitionResult(predictions, inferenceTime))

            val topPrediction = predictions.firstOrNull()
            if (topPrediction != null && topPrediction.score > 0.5f) {
                val currentTime = SystemClock.elapsedRealtime()
                if (currentTime - lastResultTime > DETECTION_INTERVAL) {
                    onResult(topPrediction.label)
                    lastResultTime = currentTime
                }
            }
        } catch (e: Exception) {
            Log.e("ASLSignDetector", "Inference error: ${e.message}")
        }
    }

    private fun centerCropAndScale(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val size = if (width < height) width else height
        
        val left = (width - size) / 2
        val top = (height - size) / 2
        
        val croppedBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(croppedBitmap)
        
        val srcRect = Rect(left, top, left + size, top + size)
        val dstRect = Rect(0, 0, targetWidth, targetHeight)
        
        canvas.drawBitmap(bitmap, srcRect, dstRect, null)
        return croppedBitmap
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
        }
        
        val intValues = IntArray(inputWidth * inputHeight)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        for (pixelValue in intValues) {
            byteBuffer.putFloat(((pixelValue shr 16 and 0xFF) / 255f))
            byteBuffer.putFloat(((pixelValue shr 8 and 0xFF) / 255f))
            byteBuffer.putFloat(((pixelValue and 0xFF) / 255f))
        }
        return byteBuffer
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
