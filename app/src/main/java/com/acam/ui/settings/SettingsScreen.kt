package com.acam.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acam.R
import com.acam.data.PhotoFormat
import com.acam.data.ThemeMode
import com.acam.ui.components.ConfirmDialog
import com.acam.ui.components.SectionHeader
import com.acam.ui.components.Segmented
import com.acam.ui.components.ToggleRow
import com.acam.ui.components.ValueSlider
import com.acam.vm.CameraViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: CameraViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var resetOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
        ) {
            SectionHeader("Appearance")
            Segmented(
                options = listOf("System", "Light", "Dark"),
                selected = state.ui.theme.ordinal,
                onSelect = { i -> vm.updateUi { it.copy(theme = ThemeMode.entries[i]) } },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            com.acam.ui.components.Hint("Manual toggle: your choice is remembered and applies everywhere, including after restart.")

            SectionHeader("Feedback")
            ToggleRow("Haptic feedback", state.ui.haptics) { on ->
                vm.updateUi { it.copy(haptics = on) }
            }
            ToggleRow("Shutter sound", state.ui.shutterSound) { on ->
                vm.updateUi { it.copy(shutterSound = on) }
            }
            ToggleRow("Volume button shutter", state.ui.volumeKeyShutter) { on ->
                vm.updateUi { it.copy(volumeKeyShutter = on) }
            }

            SectionHeader("Photos")
            Segmented(
                options = listOf("JPEG", "PNG"),
                selected = if (state.ui.photoFormat == PhotoFormat.JPEG) 0 else 1,
                onSelect = { i ->
                    vm.updateUi { it.copy(photoFormat = if (i == 0) PhotoFormat.JPEG else PhotoFormat.PNG) }
                },
                modifier = Modifier.fillMaxWidth()
            )
            if (state.ui.photoFormat == PhotoFormat.JPEG) {
                ValueSlider(
                    label = "JPEG quality",
                    value = state.ui.jpegQuality.toFloat(),
                    onValueChange = { v -> vm.updateUi { it.copy(jpegQuality = v.toInt()) } },
                    min = 40f,
                    max = 100f,
                    defaultValue = 92f,
                    format = { String.format(java.util.Locale.US, "%.0f", it) }
                )
            }

            SectionHeader("About")
            Text(
                text = "ACam 1.0",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "A vintage camera.\n\n" +
                    "Every effect renders in one shared OpenGL pipeline, so the viewfinder, " +
                    "saved photos and recorded videos always match.\n\n" +
                    "Camera pipeline patterns adapted from Google's android/camera-samples and " +
                    "grafika (Apache License 2.0). Filter math inspired by android-gpuimage " +
                    "(Apache License 2.0). Icons: Google Material Symbols (Apache License 2.0). " +
                    "Typeface: Inter by Rasmus Skov (SIL Open Font License 1.1).\n\n" +
                    "ACam has no internet access, no analytics and no trackers. See " +
                    "THIRD_PARTY.txt in the source repository for full notices.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            androidx.compose.material3.OutlinedButton(
                onClick = { resetOpen = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reset all")
            }
            com.acam.ui.components.Hint("Restores every setting, preset and customization to defaults. Custom presets are deleted.")
            Spacer(Modifier.height(32.dp))
        }
    }

    if (resetOpen) {
        ConfirmDialog(
            title = "Reset all",
            message = "Restore every setting, preset and customization to its default? Custom presets will be deleted.",
            onConfirm = {
                resetOpen = false
                vm.resetAll()
            },
            onDismiss = { resetOpen = false }
        )
    }
}
