package com.dualframe.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dualframe.data.AppStatus
import com.dualframe.util.formatDuration

/**
 * Record button + timer + countdown display.
 *
 * Visual states:
 * - IDLE / EXPORT_COMPLETE / ERROR:  white circle with red inner dot → "tap to record"
 * - COUNTDOWN:  pulsing orange ring with countdown number inside
 * - RECORDING:  red circle with white stop square → "tap to stop"
 * - EXPORTING:  grey disabled circle with spinner-like appearance
 */
@Composable
fun RecordControls(
    appStatus: AppStatus,
    cameraReady: Boolean,
    countdownRemaining: Int,
    recordingSeconds: Int,
    onRecordTap: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Timer (only during recording)
        if (appStatus == AppStatus.RECORDING) {
            RecordingTimer(recordingSeconds)
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Countdown number (only during countdown)
        if (appStatus == AppStatus.COUNTDOWN) {
            CountdownDisplay(countdownRemaining)
            Spacer(modifier = Modifier.height(8.dp))
        }

        // The button itself
        RecordButton(
            appStatus = appStatus,
            cameraReady = cameraReady,
            onClick = onRecordTap,
        )
    }
}

// ── Record Button ─────────────────────────────────────────────────────

@Composable
private fun RecordButton(
    appStatus: AppStatus,
    cameraReady: Boolean,
    onClick: () -> Unit,
) {
    val isRecording = appStatus == AppStatus.RECORDING
    val isCountdown = appStatus == AppStatus.COUNTDOWN
    val isExporting = appStatus == AppStatus.EXPORTING_16x9 || appStatus == AppStatus.EXPORTING_9x16

    // Button is enabled when camera is ready and we're not mid-export
    val enabled = cameraReady && !isExporting

    val outerColor by animateColorAsState(
        targetValue = when {
            isRecording -> Color(0xFFFF1744)
            isCountdown -> Color(0xFFFFA726)
            isExporting -> Color(0xFF555555)
            else -> Color.White
        },
        label = "rec_outer",
    )

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(72.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = outerColor,
            disabledContainerColor = Color(0xFF444444),
        ),
    ) {
        when {
            isRecording -> {
                // White stop square
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(Color.White, RoundedCornerShape(4.dp)),
                )
            }
            isCountdown -> {
                // Orange pulsing — show an X to cancel
                Text("X", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            isExporting -> {
                // Grey dot — disabled look
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(Color(0xFF888888), CircleShape),
                )
            }
            else -> {
                // Red record dot
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(Color(0xFFFF1744), CircleShape),
                )
            }
        }
    }
}

// ── Recording Timer ───────────────────────────────────────────────────

@Composable
private fun RecordingTimer(seconds: Int) {
    val transition = rememberInfiniteTransition(label = "blink")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "blink_alpha",
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(Color(0xFFFF1744).copy(alpha = alpha)),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = formatDuration(seconds),
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

// ── Countdown Display ─────────────────────────────────────────────────

@Composable
private fun CountdownDisplay(remaining: Int) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "pulse_scale",
    )

    Box(
        modifier = Modifier
            .size((56 * scale).dp)
            .border(3.dp, Color(0xFFFFA726), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = remaining.toString(),
            color = Color(0xFFFFA726),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
