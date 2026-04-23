package com.dualframe.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dualframe.data.AppStatus

@Composable
fun BottomActionBar(
    appStatus: AppStatus,
    cameraReady: Boolean,
    onSwitchCamera: () -> Unit,
    onRecord: () -> Unit,
    onGallery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isRecording = appStatus == AppStatus.RECORDING
    val isCountdown = appStatus == AppStatus.COUNTDOWN
    val isExporting = appStatus == AppStatus.EXPORTING_NATIVE || appStatus == AppStatus.EXPORTING_CROPPED
    val enabled = cameraReady && !isExporting

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF1A1A1A))
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: Camera switch
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable(enabled = enabled && !isRecording) { onSwitchCamera() },
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF2A2A2A)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Cameraswitch, "전후면 전환",
                        tint = if (enabled && !isRecording) Color.White else Color(0xFF555555),
                        modifier = Modifier.size(22.dp))
                }
                Text("전후면 전환", color = Color(0xFF999999), fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }

            // Center: Record button (largest)
            RecordDot(
                isRecording = isRecording,
                isCountdown = isCountdown,
                isExporting = isExporting,
                enabled = enabled,
                onClick = onRecord,
            )

            // Right: Gallery
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onGallery() },
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF2A2A2A)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.PhotoLibrary, "갤러리",
                        tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Text("갤러리", color = Color(0xFF999999), fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun RecordDot(
    isRecording: Boolean,
    isCountdown: Boolean,
    isExporting: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val outerColor by animateColorAsState(
        targetValue = when {
            isRecording -> Color(0xFFFF1744)
            isCountdown -> Color(0xFFFFA726)
            isExporting -> Color(0xFF555555)
            else -> Color.White
        },
        label = "rec_outer",
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(outerColor)
                .clickable(enabled = enabled) { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            when {
                isRecording -> {
                    Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(Color.White))
                }
                isCountdown -> {
                    Text("X", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                isExporting -> {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(Color(0xFF888888)))
                }
                else -> {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(Color(0xFFFF1744)))
                }
            }
        }
        Text("촬영", color = Color(0xFF999999), fontSize = 10.sp,
            modifier = Modifier.padding(top = 4.dp))
    }
}
