package com.dualframe.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import com.dualframe.ui.theme.DualFrameTheme
import com.dualframe.viewmodel.MainViewModel

/**
 * Single Activity for the DualFrame app.
 *
 * Handles:
 * - Runtime permission requests (CAMERA required, RECORD_AUDIO optional)
 * - Launches the main screen once camera permission is granted
 * - Shows a permission-denied UI if the user declines
 */
@UnstableApi
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel

    private var hasCameraPermission by mutableStateOf(false)
    private var hasAudioPermission by mutableStateOf(false)
    private var permissionRequested by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions[Manifest.permission.CAMERA] == true
        hasAudioPermission = permissions[Manifest.permission.RECORD_AUDIO] == true
        permissionRequested = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        // Check existing permissions
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        hasAudioPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        // Request permissions if camera not yet granted
        if (!hasCameraPermission) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        } else if (!hasAudioPermission) {
            // Camera granted but audio not — request audio separately
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        } else {
            permissionRequested = true
        }

        setContent {
            DualFrameTheme {
                if (hasCameraPermission) {
                    MainScreen(
                        viewModel = viewModel,
                        hasAudioPermission = hasAudioPermission,
                    )
                } else {
                    // Permission denied screen
                    PermissionDeniedScreen(
                        onRequestAgain = {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.CAMERA,
                                    Manifest.permission.RECORD_AUDIO,
                                )
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionDeniedScreen(onRequestAgain: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Camera Permission Required",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "DualFrame needs camera access to show previews and record video. " +
                "Audio permission is optional but recommended for recording with sound.",
            color = Color(0xFFAAAAAA),
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRequestAgain) {
            Text("Grant Permissions")
        }
    }
}
