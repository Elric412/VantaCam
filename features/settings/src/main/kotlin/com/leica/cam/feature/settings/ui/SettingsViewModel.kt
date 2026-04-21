package com.leica.cam.feature.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leica.cam.feature.settings.preferences.CameraPreferences
import com.leica.cam.feature.settings.preferences.CameraPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class SettingsViewModel @Inject constructor(
    repository: CameraPreferencesRepository,
) : ViewModel() {
    private val mutate: ((CameraPreferences) -> CameraPreferences) -> Unit = repository::update

    val sections: StateFlow<List<SettingsSection>> = repository.state
        .map { preferences -> SettingsCatalog.build(preferences, mutate) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = SettingsCatalog.build(repository.current(), mutate),
        )
}
