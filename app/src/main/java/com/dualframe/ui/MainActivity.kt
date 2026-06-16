package com.dualframe.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import java.util.Locale
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
import androidx.compose.ui.res.stringResource
import com.sunnlab.dualframe.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import com.dualframe.ui.theme.DualFrameTheme
import com.dualframe.viewmodel.MainViewModel

@UnstableApi
@androidx.camera.camera2.interop.ExperimentalCamera2Interop
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel

    private var hasCameraPermission by mutableStateOf(false)
    private var hasAudioPermission by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions[Manifest.permission.CAMERA] == true
        hasAudioPermission = permissions[Manifest.permission.RECORD_AUDIO] == true
    }

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("dualframe_settings", Context.MODE_PRIVATE)
        val lang = prefs.getString("app_language", "system") ?: "system"
        if (lang == "system") {
            super.attachBaseContext(newBase)
        } else {
            val locale = Locale(lang)
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Immersive full-screen: hide status bar + navigation bar.
        // Uses the modern WindowInsetsController approach (no deprecated flags).
        // BEHAVIOR_SHOW_TRANSIENT_BARS_BY_GESTURE keeps gesture nav working.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        hasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        hasAudioPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCameraPermission) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        } else if (!hasAudioPermission) {
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }

        setContent {
            DualFrameTheme {
                val uiState by viewModel.uiState.collectAsState()
                KeepScreenAwakeEffect(uiState.settings.keepScreenAwake)

                if (hasCameraPermission) {
                    MainScreen(
                        viewModel = viewModel,
                        hasAudioPermission = hasAudioPermission,
                    )
                } else {
                    val permanentlyDenied = !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
                        && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                    PermissionDeniedScreen(
                        permanentlyDenied = permanentlyDenied,
                        onRequestAgain = {
                            permissionLauncher.launch(
                                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                            )
                        },
                        onOpenSettings = {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            })
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        hasAudioPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Re-apply immersive mode when focus returns (e.g., after permission dialog)
        if (hasFocus) {
            val insetsController = WindowInsetsControllerCompat(window, window.decorView)
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    @Composable
    private fun KeepScreenAwakeEffect(enabled: Boolean) {
        LaunchedEffect(enabled) {
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

@Composable
private fun PermissionDeniedScreen(
    permanentlyDenied: Boolean,
    onRequestAgain: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (permanentlyDenied) stringResource(R.string.permission_denied_title)
                   else stringResource(R.string.permission_title),
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (permanentlyDenied) stringResource(R.string.permission_denied_desc)
                   else stringResource(R.string.permission_desc),
            color = Color(0xFFAAAAAA),
        )
        Spacer(modifier = Modifier.height(24.dp))
        if (permanentlyDenied) {
            Button(onClick = onOpenSettings) {
                Text(stringResource(R.string.permission_open_settings))
            }
        } else {
            Button(onClick = onRequestAgain) {
                Text(stringResource(R.string.permission_grant))
            }
        }
    }
}
