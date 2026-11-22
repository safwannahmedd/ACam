package com.acam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.view.KeyEvent
import android.view.Surface
import android.view.WindowManager
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acam.camera.CameraController
import com.acam.data.ThemeMode
import com.acam.ui.camera.CameraScreen
import com.acam.ui.settings.SettingsScreen
import com.acam.ui.theme.ACamTheme
import com.acam.vm.CameraViewModel

class MainActivity : ComponentActivity() {

    private val vm: CameraViewModel by viewModels()
    private lateinit var controller: CameraController

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            cameraGranted = granted
            if (granted) startCamera()
        }

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var cameraGranted: Boolean = false
    private var screen by mutableStateOf(Screen.Camera)

    private enum class Screen { Camera, Settings }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        controller = CameraController(this, this, vm.pipe)
        vm.controller = controller
        controller.prefsProvider = { vm.state.value.cameraPrefs }
        // Single-stream ("compatible") capture mode: bound by default, can
        // be turned off for devices whose HAL handles simultaneous streams.
        controller.compatProvider = { vm.state.value.ui.compatCapture }
        // Stabilization lives with the video settings and is re-applied
        // from them on every bind, so it survives restarts.
        controller.stabilizationProvider = { vm.state.value.video.stabilization }
        // Display rotation for the Preview use case's target rotation.
        controller.displayRotationProvider = { vm.state.value.displayRotation }
        // Viewfinder reliability: start the resolution ladder at the last
        // rung the probe declared healthy, and engage the persisted CPU-frame
        // compatibility viewfinder without re-running the whole ladder.
        controller.savedPreviewProvider = {
            val ui = vm.state.value.ui
            if (ui.previewW > 0 && ui.previewH > 0) {
                Size(ui.previewW, ui.previewH)
            } else null
        }
        controller.cpuPrefProvider = { vm.state.value.ui.vfCpuMode }
        controller.systemVfPrefProvider = { vm.state.value.ui.vfSystemMode }
        controller.onVfSystem = { on -> vm.onVfSystem(on) }
        controller.onPreviewRungHealthy = { w, h -> vm.savePreviewRung(w, h) }
        controller.onVfCompat = { on -> vm.onVfCompat(on) }
        controller.onCapsChanged = { caps -> vm.onCaps(caps) }
        controller.onEntriesChanged = { entries, active -> vm.onLensEntries(entries, active) }
        controller.onStreamSuspect = { suspect -> vm.onStreamSuspect(suspect) }
        controller.onAnalysisFallback = { size -> vm.onAnalysisFallback(size) }
        controller.onError = { msg -> android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show() }

        // Keep the pipeline informed about display rotation.
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                val rot = windowManager.defaultDisplay.rotation
                vm.setDisplayRotation(
                    when (rot) {
                        Surface.ROTATION_90 -> 90
                        Surface.ROTATION_180 -> 180
                        Surface.ROTATION_270 -> 270
                        else -> 0
                    }
                )
            }
        }
        dm.registerDisplayListener(displayListener, null)
        // Seed the rotation with the CURRENT display state instead of
        // assuming portrait: the listener only fires on changes, so a
        // landscape-held start would otherwise carry a wrong rotation
        // (and a wrong viewfinder orientation) until the first change.
        vm.setDisplayRotation(
            when (windowManager.defaultDisplay.rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
        )

        cameraGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            ACamTheme(mode = state.ui.theme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when {
                        !cameraGranted -> PermissionScreen { requestCamera.launch(Manifest.permission.CAMERA) }
                        else -> {
                            // The camera screen stays composed at all times;
                            // Settings is drawn ON TOP of it. The viewfinder
                            // therefore keeps running (and the GL view is
                            // never torn down) while settings are open.
                            Box(modifier = Modifier.fillMaxSize()) {
                                CameraScreen(
                                    vm = vm,
                                    onOpenSettings = {
                                        if (state.video.audio) {
                                            maybeRequestMic()
                                        }
                                        screen = Screen.Settings
                                    }
                                )
                                if (screen == Screen.Settings) {
                                    Surface(
                                        modifier = Modifier.fillMaxSize(),
                                        color = MaterialTheme.colorScheme.background
                                    ) {
                                        SettingsScreen(vm) {
                                            screen = Screen.Camera
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (cameraGranted) startCamera()
    }

    private fun maybeRequestMic() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startCamera() {
        controller.start()
    }

    /** Volume button shutter (toggleable in Settings). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val useVolume = vm.state.value.ui.volumeKeyShutter
        if (useVolume && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    vm.onShutter()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Camera access",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "ACam is completely offline. The camera is used only to frame and " +
                    "capture your photos and videos. Nothing ever leaves your device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRequest) { Text("Grant permission") }
        }
    }
}
