package com.example.mine_detector

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mediapipe.tasks.components.containers.Detection
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
    val logs: List<String> = emptyList()
)

class MainViewModel(
    context: Context,
    private val settingsRepository: SettingsRepository,
    private val onDetectionVibrate: () -> Unit = {}
) : ViewModel() {

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    val analyzer = ObjectDetectionAnalyzer(context) { result, width, height ->
        val detections = result.detections()
        if (detections.isNotEmpty() && _state.value.vibrateEnabled) {
            onDetectionVibrate()
        }
        _state.update { it.copy(detections = detections, frameWidth = width, frameHeight = height) }
    }

    init {
        viewModelScope.launch {
            combine(
                settingsRepository.resWidth,
                settingsRepository.resHeight,
                settingsRepository.vibrateOnDetection,
                AppLogger.logs
            ) { width, height, vibrate, logs ->
                DataPack(width, height, vibrate, logs)
            }.collect { pack ->
                _state.update { it.copy(
                    resWidth = pack.width, 
                    resHeight = pack.height, 
                    vibrateEnabled = pack.vibrate,
                    logs = pack.logs
                ) }
                analyzer.updateConfig(_state.value.modelFile, pack.width, pack.height)
            }
        }
    }

    private data class DataPack(val width: Int, val height: Int, val vibrate: Boolean, val logs: List<String>)

    fun onModelImported(context: Context, uri: Uri) {
        viewModelScope.launch {
            val file = copyUriToInternalStorage(context, uri)
            _state.update { it.copy(modelFile = file) }
            analyzer.updateConfig(file, _state.value.resWidth, _state.value.resHeight)
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
