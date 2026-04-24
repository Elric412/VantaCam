package com.leica.cam.feature.settings.preferences

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for camera preferences.
 *
 * Updates are small synchronous SharedPreferences writes triggered only by user
 * interaction, never on the capture hot path.
 */
@Singleton
class CameraPreferencesRepository @Inject constructor(
    private val store: SharedPreferencesCameraStore,
) {
    private val _state = MutableStateFlow(store.load())

    val state: StateFlow<CameraPreferences> = _state.asStateFlow()

    fun current(): CameraPreferences = _state.value

    fun update(transform: (CameraPreferences) -> CameraPreferences) {
        val next = transform(_state.value)
        if (next == _state.value) return
        _state.value = next
        store.save(next)
    }
}
