package com.leica.cam.feature.settings.ui

/**
 * Typed representation of every row that can appear on the settings screen.
 * Exhaustive rendering is enforced in [SettingsSectionView].
 */
sealed interface SettingsRow {
    val id: String
    val title: String

    data class Toggle(
        override val id: String,
        override val title: String,
        val checked: Boolean,
        val enabled: Boolean = true,
        val onToggle: (Boolean) -> Unit,
    ) : SettingsRow

    data class Choice<T>(
        override val id: String,
        override val title: String,
        val options: List<T>,
        val selected: T,
        val label: (T) -> String,
        val onSelect: (T) -> Unit,
    ) : SettingsRow

    data class Info(
        override val id: String,
        override val title: String,
        val value: String,
    ) : SettingsRow
}

data class SettingsSection(
    val id: String,
    val title: String,
    val rows: List<SettingsRow>,
)
