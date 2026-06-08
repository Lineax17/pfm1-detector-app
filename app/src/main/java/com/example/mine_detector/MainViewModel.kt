package com.example.mine_detector

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

data class AppState(
    val detections: List<Detection> = emptyList(),
    val frameWidth: Int = 1,
    val frameHeight: Int = 1,
    val modelFile: File? = null,
    val resWidth: Int = 320,
    val resHeight: Int = 320,
    val vibrateEnabled: Boolean = true,
    val detectionThreshold: Float = 0.3f,
    val logs: List<String> = emptyList()
)

class MainViewModel(
    context: Context,
    private val settingsRepository: SettingsRepository,
    private val onDetectionVibrate: () -> Unit = {}
) : ViewModel() {

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    val analyzer = ObjectDetectionAnalyzer { detections, width, height ->
        if (detections.isNotEmpty() && _state.value.vibrateEnabled) {
            onDetectionVibrate()
        }
        _state.update { it.copy(detections = detections, frameWidth = width, frameHeight = height) }
    }

    @OptIn(FlowPreview::class)
    private fun startMonitoring() {
        // Monitor settings - Debounced to prevent rapid reconfigurations
        viewModelScope.launch {
            combine(
                settingsRepository.resWidth,
                settingsRepository.resHeight,
            ) { width, height ->
                Pair(width, height)
            }
            .debounce(500) // Wait for user to stop spamming
            .collect { (width, height) ->
                _state.update { it.copy(resWidth = width, resHeight = height) }
                analyzer.updateConfig(_state.value.modelFile)
            }
        }

        // Monitor logs separately to avoid configuration loops
        viewModelScope.launch {
            AppLogger.logs
                .sample(500) // Only update logs UI twice a second
                .collect { logs ->
                    _state.update { it.copy(logs = logs) }
                }
        }

        // Monitor vibration setting
        viewModelScope.launch {
            settingsRepository.vibrateOnDetection.collect { enabled ->
                _state.update { it.copy(vibrateEnabled = enabled) }
            }
        }

        // Monitor threshold setting
        viewModelScope.launch {
            settingsRepository.detectionThreshold.collect { threshold ->
                _state.update { it.copy(detectionThreshold = threshold) }
                analyzer.threshold = threshold
            }
        }
    }

    init {
        startMonitoring()
    }

    fun onModelImported(context: Context, uri: Uri) {
        viewModelScope.launch {
            val file = copyUriToInternalStorage(context, uri)
            _state.update { it.copy(modelFile = file) }
            analyzer.updateConfig(file)
            AppLogger.log("Model imported: ${file.name}")
        }
    }

    fun updateResolution(width: Int, height: Int) {
        viewModelScope.launch {
            settingsRepository.updateResolution(width, height)
        }
    }

    fun toggleVibration(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateVibration(enabled)
        }
    }

    fun updateThreshold(threshold: Float) {
        viewModelScope.launch {
            settingsRepository.updateThreshold(threshold)
        }
    }

    fun saveLogsToUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                context.contentResolver.openOutputStream(uri)?.let { output ->
                    output.use {
                        val logs = AppLogger.getDetailedLogs()
                        it.write(logs.toByteArray())
                    }
                }
                AppLogger.log("Logs saved successfully")
            } catch (e: Exception) {
                AppLogger.log("Failed to save logs: ${e.message}")
            }
        }
    }

    private fun copyUriToInternalStorage(context: Context, uri: Uri): File {
        // Use a unique name to ensure the analyzer detects a change
        val fileName = "model_${System.currentTimeMillis()}.tflite"
        val file = File(context.filesDir, fileName)
        
        // Cleanup old models
        context.filesDir.listFiles()?.forEach { 
            if (it.name.startsWith("model_") && it.name.endsWith(".tflite")) {
                it.delete()
            }
        }

        val inputStream = context.contentResolver.openInputStream(uri)
        val outputStream = FileOutputStream(file)
        inputStream?.use { input ->
            outputStream.use { output ->
                input.copyTo(output)
            }
        }
        return file
    }
}
