package com.dualframe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dualframe.data.AppSettings
import com.dualframe.data.FrameRate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1E1E1E),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── Audio ──
            SwitchSetting(
                label = "Audio Recording",
                checked = settings.audioEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(audioEnabled = it)) },
            )

            SettingDivider()

            // ── Countdown ──
            SettingSectionTitle("Countdown")
            RadioGroup(
                options = listOf("Off", "3 seconds", "5 seconds", "10 seconds"),
                selectedIndex = when (settings.countdownSeconds) {
                    3 -> 1
                    5 -> 2
                    10 -> 3
                    else -> 0
                },
                onSelected = { index ->
                    val seconds = when (index) { 1 -> 3; 2 -> 5; 3 -> 10; else -> 0 }
                    onSettingsChange(settings.copy(countdownSeconds = seconds))
                },
            )

            SettingDivider()

            // ── Frame Rate ──
            SettingSectionTitle("Frame Rate")
            RadioGroup(
                options = FrameRate.entries.map { it.label },
                selectedIndex = FrameRate.entries.indexOf(settings.frameRate),
                onSelected = { index ->
                    onSettingsChange(settings.copy(frameRate = FrameRate.entries[index]))
                },
            )

            SettingDivider()

            // ── Keep Screen Awake ──
            SwitchSetting(
                label = "Keep Screen Awake",
                checked = settings.keepScreenAwake,
                onCheckedChange = { onSettingsChange(settings.copy(keepScreenAwake = it)) },
            )

            SettingDivider()

            // ── Show Guides ──
            SwitchSetting(
                label = "Show Crop Guides",
                checked = settings.showGuides,
                onCheckedChange = { onSettingsChange(settings.copy(showGuides = it)) },
            )
        }
    }
}

@Composable
private fun SettingSectionTitle(title: String) {
    Text(
        text = title,
        color = Color(0xFFBBBBBB),
        fontSize = 13.sp,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun SettingDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 12.dp),
        color = Color(0xFF333333),
    )
}

@Composable
private fun RadioGroup(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    Column(modifier = Modifier.selectableGroup()) {
        options.forEachIndexed { index, label ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = index == selectedIndex,
                        onClick = { onSelected(index) },
                        role = Role.RadioButton,
                    )
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = index == selectedIndex,
                    onClick = null,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(label, color = Color.White, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun SwitchSetting(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
