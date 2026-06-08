package com.example.mine_detector

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    companion object {
        val RES_WIDTH = intPreferencesKey("res_width")
        val RES_HEIGHT = intPreferencesKey("res_height")
        val VIBRATE_ON_DETECTION = androidx.datastore.preferences.core.booleanPreferencesKey("vibrate_on_detection")
        val SOUND_ON_DETECTION = androidx.datastore.preferences.core.booleanPreferencesKey("sound_on_detection")
        val NOTIFICATION_INTERVAL = intPreferencesKey("notification_interval")
        val DETECTION_THRESHOLD = androidx.datastore.preferences.core.floatPreferencesKey("detection_threshold")
    }

    val resWidth: Flow<Int> = context.dataStore.data.map { it[RES_WIDTH] ?: 320 }
    val resHeight: Flow<Int> = context.dataStore.data.map { it[RES_HEIGHT] ?: 320 }
    val vibrateOnDetection: Flow<Boolean> = context.dataStore.data.map { it[VIBRATE_ON_DETECTION] ?: false }
    val soundOnDetection: Flow<Boolean> = context.dataStore.data.map { it[SOUND_ON_DETECTION] ?: false }
    val notificationInterval: Flow<Int> = context.dataStore.data.map { it[NOTIFICATION_INTERVAL] ?: 1000 }
    val detectionThreshold: Flow<Float> = context.dataStore.data.map { it[DETECTION_THRESHOLD] ?: 0.3f }

    suspend fun updateResolution(width: Int, height: Int) {
        context.dataStore.edit {
            it[RES_WIDTH] = width
            it[RES_HEIGHT] = height
        }
    }

    suspend fun updateVibration(enabled: Boolean) {
        context.dataStore.edit {
            it[VIBRATE_ON_DETECTION] = enabled
        }
    }

    suspend fun updateSound(enabled: Boolean) {
        context.dataStore.edit {
            it[SOUND_ON_DETECTION] = enabled
        }
    }

    suspend fun updateNotificationInterval(interval: Int) {
        context.dataStore.edit {
            it[NOTIFICATION_INTERVAL] = interval
        }
    }

    suspend fun updateThreshold(threshold: Float) {
        context.dataStore.edit {
            it[DETECTION_THRESHOLD] = threshold
        }
    }
}
