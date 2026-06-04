package com.example.mine_detector

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MediaImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import com.google.mediapipe.tasks.components.containers.Detection
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import java.io.File

class ObjectDetectionAnalyzer(
    private val context: Context,
    private val onResults: (ObjectDetectorResult, Int, Int) -> Unit
) : ImageAnalysis.Analyzer {

    private var objectDetector: ObjectDetector? = null
    private var modelFile: File? = null
    
    private var targetWidth: Int = 320
    private var targetHeight: Int = 320
    
    private var isProcessing = false
    private var lastLogTime = 0L

    fun updateConfig(file: File?, width: Int, height: Int) {
        val safeWidth = if (width > 0) width else 320
        val safeHeight = if (height > 0) height else 320

        if ((this.modelFile == file) && (this.targetWidth == safeWidth) && (this.targetHeight == safeHeight)) return
        
        AppLogger.log("Updating analyzer config: res=${safeWidth}x${safeHeight}, model=${file?.name ?: "none"}")

        this.modelFile = file
        this.targetWidth = safeWidth
        this.targetHeight = safeHeight
        
        if (file == null) {
            objectDetector?.close()
            objectDetector = null
            return
        }

        setupObjectDetector(file)
    }

    private fun setupObjectDetector(file: File) {
        objectDetector?.close()
        
        var pfd: ParcelFileDescriptor? = null
        try {
            val baseOptionsBuilder = BaseOptions.builder()
            
            if (file.absolutePath.contains("android_asset")) {
                val assetPath = file.absolutePath.split("android_asset/").last()
                baseOptionsBuilder.setModelAssetPath(assetPath)
            } else {
                pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                baseOptionsBuilder.setModelAssetFileDescriptor(pfd.fd)
            }
            
            fun createOptions(delegate: Delegate): ObjectDetector.ObjectDetectorOptions {
                return ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(baseOptionsBuilder.setDelegate(delegate).build())
                    .setScoreThreshold(0.3f)
                    .setMaxResults(5)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setResultListener { result, inputImage ->
                        isProcessing = false
                        if (result.detections().isNotEmpty()) {
                            val labels = result.detections().map { it.categories().firstOrNull()?.categoryName() ?: "unknown" }
                            AppLogger.log("Detected ${result.detections().size} objects: ${labels.joinToString(", ")}")
                        }
                        onResults(result, inputImage.width, inputImage.height)
                    }
                    .build()
            }
            
            try {
                // Force CPU first for debugging if GPU isn't producing results
                // objectDetector = ObjectDetector.createFromOptions(context, createOptions(Delegate.CPU))
                // AppLogger.log("MediaPipe ObjectDetector forced to CPU for debugging")

                objectDetector = ObjectDetector.createFromOptions(context, createOptions(Delegate.GPU))
                AppLogger.log("MediaPipe ObjectDetector initialized with GPU")
            } catch (e: Exception) {
                AppLogger.log("MediaPipe GPU failed, falling back to CPU: ${e.message}")
                objectDetector = ObjectDetector.createFromOptions(context, createOptions(Delegate.CPU))
                AppLogger.log("MediaPipe ObjectDetector initialized with CPU")
            }
        } catch (e: Exception) {
            AppLogger.log("Failed to initialize MediaPipe ObjectDetector: ${e.message}")
        } finally {
            pfd?.close()
        }
    }

    @ExperimentalGetImage
    override fun analyze(image: ImageProxy) {
        val detector = objectDetector ?: run {
            image.close()
            return
        }

        if (isProcessing) {
            image.close()
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastLogTime > 5000) {
            AppLogger.log("Analyzer pulse: Camera Input ${image.width}x${image.height}, Target Res ${targetWidth}x${targetHeight}, rotation ${image.imageInfo.rotationDegrees}")
            lastLogTime = currentTime
        }

        val frameTime = SystemClock.uptimeMillis()

        try {
            val mediaImage = image.image
            if (mediaImage != null) {
                isProcessing = true
                val mpImage = MediaImageBuilder(mediaImage).build()
                
                val processingOptions = ImageProcessingOptions.builder()
                    .setRotationDegrees(image.imageInfo.rotationDegrees)
                    .build()

                detector.detectAsync(mpImage, processingOptions, frameTime)
            }
        } catch (e: Exception) {
            AppLogger.log("Analyzer error: ${e.message}")
            isProcessing = false
        } finally {
            // In LIVE_STREAM mode with detectAsync, MediaPipe takes care of 
            // the underlying image if we use MediaImageBuilder, but we must 
            // close the ImageProxy to return it to the CameraX pool.
            // Since detectAsync is non-blocking, we close it immediately here.
            // CameraX will provide the next frame when this one is closed.
            image.close()
        }
    }
}
