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
import com.dualframe.data.VideoQuality

/**
 * Settings bottom sheet with capability-aware options.
 * Unsupported quality/fps options are shown but disabled (greyed out).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: AppSettings,
    supportedQualities: List<VideoQuality>,
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
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )

            Spacer(modifier = Modifier.height(10.dp))

            // ── Audio ──
            SwitchSetting(
                label = "Audio Recording",
                checked = settings.audioEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(audioEnabled = it)) },
            )

            SettingDivider()

            // ── Video Quality (capability-aware) ──
            SettingSectionTitle("Video Quality")
            RadioGroupWithEnabled(
                options = VideoQuality.entries.map { it.label },
                enabledMask = VideoQuality.entries.map { it in supportedQualities },
                selectedIndex = VideoQuality.entries.indexOf(settings.videoQuality),
                onSelected = { index ->
                    val selected = VideoQuality.entries[index]
                    if (selected in supportedQualities) {
                        onSettingsChange(settings.copy(videoQuality = selected))
                    }
                },
            )

            SettingDivider()

            // ── Countdown ──
            SettingSectionTitle("Countdown")
            RadioGroupWithEnabled(
                options = listOf("Off", "3 seconds", "5 seconds", "10 seconds"),
                enabledMask = listOf(true, true, true, true),
                selectedIndex = when (settings.countdownSeconds) {
                    3 -> 1; 5 -> 2; 10 -> 3; else -> 0
                },
                onSelected = { index ->
                    val seconds = when (index) { 1 -> 3; 2 -> 5; 3 -> 10; else -> 0 }
                    onSettingsChange(settings.copy(countdownSeconds = seconds))
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

            SettingDivider()

            // ── Mirror Front Camera ──
            SwitchSetting(
                label = "Mirror Front Camera",
                checked = settings.mirrorFrontCamera,
                onCheckedChange = { onSettingsChange(settings.copy(mirrorFrontCamera = it)) },
            )

            SettingDivider()

            // ── Front Camera Effect ──
            SwitchSetting(
                label = "Front Camera Effect",
                checked = settings.frontCameraEffect,
                onCheckedChange = { onSettingsChange(settings.copy(frontCameraEffect = it)) },
            )
        }
    }
}

@Composable
private fun SettingSectionTitle(title: String) {
    Text(
        text = title,
        color = Color(0xFFBBBBBB),
        fontSize = 15.sp,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun SettingDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = Color(0xFF333333),
    )
}

/**
 * Radio group where individual options can be disabled.
 * Disabled options appear greyed out and are not selectable.
 */
@Composable
private fun RadioGroupWithEnabled(
    options: List<String>,
    enabledMask: List<Boolean>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    Column(modifier = Modifier.selectableGroup()) {
        options.forEachIndexed { index, label ->
            val enabled = enabledMask.getOrElse(index) { true }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = index == selectedIndex,
                        enabled = enabled,
                        onClick = { onSelected(index) },
                        role = Role.RadioButton,
                    )
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = index == selectedIndex,
                    onClick = null,
                    enabled = enabled,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    color = if (enabled) Color.White else Color(0xFF555555),
                    fontSize = 16.sp,
                )
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
        Text(label, color = Color.White, fontSize = 16.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
