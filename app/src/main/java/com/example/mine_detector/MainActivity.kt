package com.example.mine_detector

import android.Manifest
import android.os.Bundle
import android.os.Vibrator
import android.os.VibrationEffect
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import android.util.Size
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.mine_detector.ui.theme.MinedetectorTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mediapipe.tasks.components.containers.Detection
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settingsRepository = SettingsRepository(applicationContext)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MainViewModel(applicationContext, settingsRepository, ::vibrate) as T
            }
        }
        setContent {
            MinedetectorTheme {
                val navController = rememberNavController()
                val viewModel: MainViewModel = viewModel(factory = factory)
                
                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(navController, viewModel)
                    }
                    composable("settings") {
                        SettingsScreen(viewModel)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(navController: NavController, viewModel: MainViewModel) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            uri?.let { viewModel.onModelImported(context, it) }
        }
    )

    LaunchedEffect(Unit) {
        cameraPermissionState.launchPermissionRequest()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            BottomAppBar {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) }
                    ) {
                        Text(if (state.modelFile == null) "Import Model" else "Change Model")
                    }
                    
                    IconButton(onClick = { navController.navigate("settings") }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (cameraPermissionState.status.isGranted) {
                CameraPreview(viewModel.analyzer)
                DetectionOverlay(state.detections, state.frameWidth, state.frameHeight)
            } else {
                Text("Camera permission required", modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
fun CameraPreview(analyzer: ObjectDetectionAnalyzer) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                            .build()
                    )
                    .build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(640, 480),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER
                                )
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also {
                        it.setAnalyzer(executor, analyzer)
                    }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    AppLogger.log("Camera binding failed: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

fun MainActivity.vibrate() {
    val vibrator = getSystemService(Vibrator::class.java)
    vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
}

@Composable
fun DetectionOverlay(detections: List<Detection>, frameWidth: Int, frameHeight: Int) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }
        
        val scaleX = screenWidthPx / frameWidth
        val scaleY = screenHeightPx / frameHeight

        Canvas(modifier = Modifier.fillMaxSize()) {
            detections.forEach { detection ->
                val boundingBox = detection.boundingBox()
                val left = boundingBox.left * scaleX
                val top = boundingBox.top * scaleY
                val right = boundingBox.right * scaleX
                val bottom = boundingBox.bottom * scaleY

                drawRect(
                    color = Color.Red,
                    topLeft = androidx.compose.ui.geometry.Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                    style = Stroke(width = 4f)
                )
            }
        }
        
        detections.forEach { detection ->
            val boundingBox = detection.boundingBox()
            val left = boundingBox.left * scaleX
            val top = boundingBox.top * scaleY
            
            val label = detection.categories().firstOrNull()?.let {
                "${it.categoryName()} ${(it.score() * 100).toInt()}%"
            } ?: ""
            
            if (label.isNotEmpty()) {
                val leftDp = with(density) { left.toDp() }
                val topDp = with(density) { top.toDp() }
                
                Text(
                    text = label,
                    color = Color.White,
                    modifier = Modifier
                        .offset(x = leftDp, y = topDp)
                        .background(Color.Red.copy(alpha = 0.5f))
                        .padding(4.dp)
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    
    var widthText by remember { mutableStateOf(state.resWidth.toString()) }
    var heightText by remember { mutableStateOf(state.resHeight.toString()) }

    val logSaverLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
        onResult = { uri ->
            uri?.let { viewModel.saveLogsToUri(context, it) }
        }
    )

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Downsampling Resolution", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = widthText,
                    onValueChange = { widthText = it },
                    label = { Text("Width") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = heightText,
                    onValueChange = { heightText = it },
                    label = { Text("Height") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
            
            Button(
                onClick = {
                    val w = widthText.toIntOrNull() ?: 320
                    val h = heightText.toIntOrNull() ?: 320
                    viewModel.updateResolution(w, h)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Update Resolution")
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Vibrate on Detection", style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = state.vibrateEnabled,
                    onCheckedChange = { viewModel.toggleVibration(it) }
                )
            }

            HorizontalDivider()

            Text("Logs", style = MaterialTheme.typography.titleMedium)
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.1f))
                    .padding(8.dp)
            ) {
                items(state.logs) { log ->
                    Text(log, style = MaterialTheme.typography.bodySmall)
                }
            }

            Button(
                onClick = { 
                    val fileName = "detailed_logs_${System.currentTimeMillis()}.txt"
                    logSaverLauncher.launch(fileName) 
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Advanced Logs via File Manager")
            }
        }
    }
}

