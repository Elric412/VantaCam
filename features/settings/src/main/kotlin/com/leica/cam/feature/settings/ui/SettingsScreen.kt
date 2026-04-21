package com.leica.cam.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.leica.cam.ui_components.theme.LeicaBlack
import com.leica.cam.ui_components.theme.LeicaWhite

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val sections by viewModel.sections.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LeicaBlack),
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    text = "SETTINGS",
                    style = MaterialTheme.typography.displayLarge,
                    color = LeicaWhite,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                )
            }
            items(sections, key = { section -> section.id }) { section ->
                SettingsSectionView(section = section)
            }
        }
    }
}
