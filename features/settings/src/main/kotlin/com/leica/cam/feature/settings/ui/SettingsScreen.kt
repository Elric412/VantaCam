package com.leica.cam.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.leica.cam.ui_components.theme.LeicaBlack
import com.leica.cam.ui_components.theme.LeicaWhite

@Composable
fun SettingsScreen() {
    val settings = listOf(
        "Capture Format" to "RAW + JPEG",
        "Storage Location" to "SD Card",
        "Grid Lines" to "3x3",
        "Leveling Guide" to "Enabled",
        "Face Detection" to "Auto",
        "Scene Detection" to "Enabled",
        "Shutter Sound" to "Leica M Type 240",
        "Firmware Version" to "1.2.0"
    )

    Box(modifier = Modifier.fillMaxSize().background(LeicaBlack)) {
        Column {
            Text(
                text = "SETTINGS",
                style = MaterialTheme.typography.displayLarge,
                color = LeicaWhite,
                modifier = Modifier.padding(16.dp)
            )

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(settings) { (label, value) ->
                    SettingsItem(label, value)
                    HorizontalDivider(color = Color.DarkGray, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Composable
fun SettingsItem(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { /* Action */ }
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = LeicaWhite)
        Text(value.uppercase(), style = MaterialTheme.typography.labelMedium, color = Color.Gray)
    }
}
