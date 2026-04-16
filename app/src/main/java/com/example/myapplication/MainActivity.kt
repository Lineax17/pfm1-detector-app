package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.myapplication.ui.theme.MyApplicationTheme
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private var hasCameraPermission by mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasCameraPermission = isGranted
        if (!isGranted) {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkCameraPermission()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (hasCameraPermission) {
                        ASLCameraScreen()
                    }
                }
            }
        }
    }

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                hasCameraPermission = true
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }
}

@Composable
fun ASLCameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    
    var detectedText by remember { mutableStateOf("") }
    var debugInfo by remember { mutableStateOf<RecognitionResult?>(null) }
    
    val detector = remember {
        ASLSignDetector(
            context = context,
            onDebugInfo = { info -> debugInfo = info },
            onResult = { result ->
                (context as? ComponentActivity)?.runOnUiThread {
                    when (result) {
                        "space" -> detectedText += " "
                        "delete" -> if (detectedText.isNotEmpty()) detectedText = detectedText.dropLast(1)
                        else -> detectedText += result
                    }
                }
            }
        )
    }

    val previewView = remember { PreviewView(context) }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    LaunchedEffect(Unit) {
        val cameraProvider = cameraProviderFuture.get()
        val preview = androidx.camera.core.Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also {
                it.setAnalyzer(executor) { imageProxy ->
                    val bitmap = imageProxy.toBitmap()
                    detector.detect(bitmap)
                    imageProxy.close()
                }
            }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        
        // Debug Box / Console
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .fillMaxWidth(0.6f)
                .fillMaxHeight(0.4f),
            color = Color.Black.copy(alpha = 0.8f),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("DEBUG CONSOLE", color = Color.Green, fontSize = 12.sp, style = MaterialTheme.typography.labelLarge)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = Color.Green.copy(alpha = 0.5f))
                
                debugInfo?.let { info ->
                    Text("Inference: ${info.inferenceTime}ms", color = Color.White, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Top Predictions:", color = Color.Yellow, fontSize = 11.sp)
                    info.predictions.forEach { pred ->
                        Text(
                            text = "${pred.label}: ${(pred.score * 100).toInt()}%",
                            color = if (pred.score > 0.5f) Color.Cyan else Color.LightGray,
                            fontSize = 11.sp
                        )
                    }
                } ?: Text("Waiting for frames...", color = Color.Gray, fontSize = 11.sp)
            }
        }

        // Sentence output (Bottom)
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 50.dp)
                .fillMaxWidth(0.9f),
            color = Color.Black.copy(alpha = 0.7f),
            shape = MaterialTheme.shapes.large
        ) {
            Text(
                text = if (detectedText.isEmpty()) "Start signing..." else detectedText,
                color = Color.White,
                modifier = Modifier.padding(20.dp),
                style = MaterialTheme.typography.headlineSmall
            )
        }

        // Scan area indicator (Square)
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(300.dp)
                .border(2.dp, Color.White.copy(alpha = 0.3f), MaterialTheme.shapes.medium)
        )
    }
}