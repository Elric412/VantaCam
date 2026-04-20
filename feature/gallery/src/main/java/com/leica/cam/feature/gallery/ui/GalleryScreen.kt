package com.leica.cam.feature.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.leica.cam.ui_components.theme.LeicaBlack
import com.leica.cam.ui_components.theme.LeicaRed
import com.leica.cam.ui_components.theme.LeicaWhite
import java.time.Instant

@Composable
fun GalleryScreen(
    engine: GalleryMetadataEngine
) {
    // Simulated items
    val items = remember {
        listOf(
            GalleryItem("1", Instant.now(), 4000, 3000, "image/jpeg", "35mm", "PRO", 100, 1000, 5500, 0f),
            GalleryItem("2", Instant.now().minusSeconds(3600), 4000, 3000, "image/jpeg", "50mm", "PHOTO", 400, 500, 5000, 0.3f),
            GalleryItem("3", Instant.now().minusSeconds(7200), 4000, 3000, "image/jpeg", "35mm", "NIGHT", 1600, 100, 3200, -0.5f)
        )
    }

    var selectedItem by remember { mutableStateOf<GalleryItem?>(null) }
    val viewState = remember(items) { engine.buildViewState(items) }

    Box(modifier = Modifier.fillMaxSize().background(LeicaBlack)) {
        Column {
            // Top Bar
            Text(
                text = "GALLERY",
                style = MaterialTheme.typography.displayLarge,
                color = LeicaWhite,
                modifier = Modifier.padding(16.dp)
            )

            // Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(1.dp),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                items(viewState.items) { item ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .background(Color.DarkGray)
                            .clickable { selectedItem = item },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(item.modeLabel, color = LeicaWhite.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // Metadata Panel (Overlay when item selected)
        selectedItem?.let { item ->
            val panelResult = engine.buildMetadataPanel(item)
            if (panelResult is com.leica.cam.common.result.LeicaResult.Success) {
                val panel = panelResult.value
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.4f),
                    color = LeicaBlack.copy(alpha = 0.9f),
                    contentColor = LeicaWhite
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(panel.headline, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("CLOSE", modifier = Modifier.clickable { selectedItem = null }, color = LeicaRed, style = MaterialTheme.typography.labelMedium)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                panel.captureDetails.forEach { (label, value) ->
                                    MetadataItem(label, value)
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                panel.technicalDetails.forEach { (label, value) ->
                                    MetadataItem(label, value)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MetadataItem(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = LeicaWhite)
    }
}
