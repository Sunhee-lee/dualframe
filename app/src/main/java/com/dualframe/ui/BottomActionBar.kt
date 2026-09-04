package com.dualframe.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
    onGallery: () -> Unit,
    onRecord: () -> Unit,
    onSwitchCamera: () -> Unit,
    onPause: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isRecording = appStatus == AppStatus.RECORDING
    val isPaused = appStatus == AppStatus.PAUSED
    val isCountdown = appStatus == AppStatus.COUNTDOWN
    val isExporting = appStatus == AppStatus.EXPORTING_NATIVE || appStatus == AppStatus.EXPORTING_CROPPED
    val enabled = cameraReady && !isExporting

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isRecording || isPaused) {
            PauseResumeButton(paused = isPaused, onClick = onPause)
        } else {
            SwitchCameraButton(enabled && !isCountdown, onSwitchCamera)
        }
        RecordDot(isRecording, isPaused, isCountdown, isExporting, enabled, onRecord)
        GalleryButton(onGallery)
    }
}

@Composable
private fun SwitchCameraButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF1A1A1A))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Cameraswitch, "Switch",
            tint = if (enabled) Color.White else Color(0xFF555555),
            modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun PauseResumeButton(paused: Boolean, onClick: () -> Unit) {
    val bg = if (paused) Color(0xFFFF1744) else Color(0xFF1A1A1A)
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(bg)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            if (paused) "Resume" else "Pause",
            tint = Color.White,
            modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun GalleryButton(onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF1A1A1A))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.PhotoLibrary, "Gallery",
            tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun RecordDot(
    isRecording: Boolean,
    isPaused: Boolean,
    isCountdown: Boolean,
    isExporting: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val outerColor by animateColorAsState(
        targetValue = when {
            isRecording -> Color(0xFFFF1744)
            isPaused -> Color(0xFF888888)
            isCountdown -> Color(0xFFFFA726)
            isExporting -> Color(0xFF555555)
            else -> Color.White
        },
        label = "rec",
    )

    Box(
        modifier = Modifier.size(64.dp).clip(CircleShape).background(outerColor)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        when {
            isRecording || isPaused -> Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(Color.White))
            isCountdown -> Text("X", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            isExporting -> Box(Modifier.size(22.dp).clip(CircleShape).background(Color(0xFF888888)))
            else -> Box(Modifier.size(24.dp).clip(CircleShape).background(Color(0xFFFF1744)))
        }
    }
}

private val SAMSUNG_GALLERY_PACKAGES =
    listOf("com.sec.android.gallery3d", "com.samsung.android.gallery")

/**
 * The gallery app to hand media to, or null when none can be identified.
 *
 * resolveActivity returns the system chooser when several apps match and the
 * user has set no default, so a result of "android" is not an app we can name.
 */
private fun galleryPackage(pm: PackageManager): String? {
    for (pkg in SAMSUNG_GALLERY_PACKAGES) {
        if (pm.getLaunchIntentForPackage(pkg) != null) return pkg
    }
    val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY)
    return main.resolveActivity(pm)?.packageName?.takeUnless { it == "android" }
}

private fun viewIntent(uri: Uri): Intent =
    Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/mp4")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

/**
 * Opens [savedUri] in the gallery, falling back to the gallery app's own screen
 * — which is also what happens when nothing has been saved yet, since the
 * bottom-bar button is tappable on first launch and before the user taps Save.
 */
fun openInGallery(context: Context, savedUri: Uri?) {
    val pm = context.packageManager
    val pkg = galleryPackage(pm)

    // Name the gallery explicitly. Left implicit, ACTION_VIEW on video/mp4
    // matches every video player installed and the system puts up a chooser
    // instead of just showing the clip.
    if (savedUri != null && pkg != null) {
        val targeted = viewIntent(savedUri).setPackage(pkg)
        if (targeted.resolveActivity(pm) != null) {
            try {
                context.startActivity(targeted)
                return
            } catch (_: Exception) {
                // Gallery refused the item — fall through.
            }
        }
    }

    // The gallery could not take the item, so open the gallery app itself. This
    // is what the button did before it carried a URI at all; falling through to
    // an implicit ACTION_VIEW here would trade that for a chooser, which is
    // worse than what we started with.
    try {
        context.startActivity(buildGalleryIntent(context))
        return
    } catch (_: Exception) {
        // No gallery app at all — fall through.
    }

    // Last resort: let the system offer whatever can play it.
    if (savedUri != null) {
        try { context.startActivity(viewIntent(savedUri)) }
        catch (_: Exception) {}
    }
}

fun buildGalleryIntent(context: Context): Intent {
    val pm = context.packageManager
    for (pkg in SAMSUNG_GALLERY_PACKAGES) {
        val launchIntent = pm.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) return launchIntent
    }
    val galleryIntent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_APP_GALLERY)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (galleryIntent.resolveActivity(pm) != null) return galleryIntent
    return Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*")
    }
}
