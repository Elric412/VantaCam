package com.leica.cam.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.leica.cam.ui_components.settings.LeicaSegmentedControl
import com.leica.cam.ui_components.settings.LeicaToggleRow
import com.leica.cam.ui_components.theme.LeicaDarkGray
import com.leica.cam.ui_components.theme.LeicaGray
import com.leica.cam.ui_components.theme.LeicaWhite

@Composable
fun SettingsSectionView(
    section: SettingsSection,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = section.title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = LeicaGray,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        section.rows.forEachIndexed { index, row ->
            SettingsRowView(row = row)
            if (index != section.rows.lastIndex) {
                HorizontalDivider(
                    color = LeicaDarkGray,
                    thickness = 1.dp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun SettingsRowView(row: SettingsRow) {
    when (row) {
        is SettingsRow.Toggle -> LeicaToggleRow(
            label = row.title,
            checked = row.checked,
            enabled = row.enabled,
            onCheckedChange = row.onToggle,
        )
        is SettingsRow.Choice<*> -> ChoiceRow(row)
        is SettingsRow.Info -> InfoRow(label = row.title, value = row.value)
    }
}

@Suppress("UNCHECKED_CAST")
@Composable
private fun ChoiceRow(row: SettingsRow.Choice<*>) {
    val typedRow = row as SettingsRow.Choice<Any>
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = typedRow.title,
            color = LeicaWhite,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))
        LeicaSegmentedControl(
            options = typedRow.options,
            selected = typedRow.selected,
            label = typedRow.label,
            onSelect = typedRow.onSelect,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = LeicaWhite,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = value.uppercase(),
            color = LeicaGray,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
