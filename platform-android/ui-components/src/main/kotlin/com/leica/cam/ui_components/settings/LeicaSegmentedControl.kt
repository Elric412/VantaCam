package com.leica.cam.ui_components.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.leica.cam.ui_components.theme.LeicaDarkGray
import com.leica.cam.ui_components.theme.LeicaGray
import com.leica.cam.ui_components.theme.LeicaRed
import com.leica.cam.ui_components.theme.LeicaWhite

/**
 * Flat Leica-style segmented control with a monochrome body and red selection
 * underline.
 */
@Composable
fun <T> LeicaSegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .border(width = 1.dp, color = LeicaDarkGray),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(LeicaDarkGray)
                    .clickable { onSelect(option) }
                    .semantics { role = Role.RadioButton },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option).uppercase(),
                    color = if (isSelected) LeicaWhite else LeicaGray,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                )
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(LeicaRed),
                    )
                }
            }
        }
    }
}
