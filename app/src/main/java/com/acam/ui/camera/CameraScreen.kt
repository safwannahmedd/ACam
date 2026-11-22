package com.acam.ui.camera

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acam.R
import com.acam.data.FlashMode
import com.acam.data.GridMode
import com.acam.gl.GlCameraView
import com.acam.ui.adjust.AdjustSheet
import com.acam.ui.components.Segmented
import com.acam.util.RatioMath
import com.acam.vm.CameraViewModel
import kotlinx.coroutines.delay

private val ChromeOn = Color(0xFFF2F2F2)
private val ChromeDim = Color(0xB3F2F2F2)
private val AccentOrange = Color(0xFFE8590C)
private val RecRed = Color(0xFFFF5C5C)

/**
 * Hoisted Adjust-sheet UI state (selected tabs + one scroll position per
 * tab). Living at the CameraScreen level - OUTSIDE the
 * "if (adjustOpen)" conditional - is what makes the sheet remember where
 * you were after closing and reopening it (the old sheet reset to the top
 * every time).
 */
class SheetUiState {
    val mainTab = androidx.compose.runtime.mutableIntStateOf(0)
    val lookTab = androidx.compose.runtime.mutableIntStateOf(0)
    val scrolls = List(9) { ScrollState(0) }
}

@Composable
fun CameraScreen(vm: CameraViewModel, onOpenSettings: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val clipboard = LocalClipboardManager.current
    val sheet = remember { SheetUiState() }

    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var presetMenuOpen by remember { mutableStateOf(false) }
    var exportOpen by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }
    var saveAsOpen by remember { mutableStateOf(false) }

    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(1200)
            focusPoint = null
        }
    }

    LaunchedEffect(state.toast) {
        state.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeToast()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // ---------------- viewfinder + gestures ----------------
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { off ->
                            vm.focusAt(off.x, off.y, size.width.toFloat(), size.height.toFloat())
                            focusPoint = off
                        },
                        onDoubleTap = {
                            // Double-tap always resets zoom - a run-away
                            // pinch can never trap the viewfinder.
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                            vm.resetZoom()
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (zoom != 1f) {
                            vm.setZoom(vm.state.value.cameraPrefs.zoom * zoom)
                        }
                    }
                }
        ) {
            AndroidView(
                factory = { GlCameraView(it, vm.pipe) },
                modifier = Modifier.fillMaxSize()
            )
            // ---------------- system viewfinder (last-resort rung) ----------------
            // Rendered by the platform's own PreviewView: the compositor
            // path the stock camera app uses, engaged only after every GL
            // viewfinder shape was declared degenerate. Photos (and their
            // effects) still go through the GL pipeline underneath.
            if (state.vfSystem) {
                AndroidView(
                    factory = { ctx ->
                        androidx.camera.view.PreviewView(ctx).apply {
                            scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
                            implementationMode =
                                androidx.camera.view.PreviewView.ImplementationMode.PERFORMANCE
                            vm.controller?.attachSystemPreviewView(this)
                        }
                    },
                    onRelease = { pv -> vm.controller?.detachSystemPreviewView(pv) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        GridOverlay(state.cameraPrefs.grid)
        FocusRing(focusPoint)
        CaptureFlash(state.flashOverlay)

        // ---------------- top bar ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TopIcon(flashIcon(state.cameraPrefs.flashMode), "Flash") {
                val order = if (state.caps?.hasFlash == true)
                    listOf(FlashMode.OFF, FlashMode.AUTO, FlashMode.ON, FlashMode.TORCH)
                else
                    listOf(FlashMode.OFF, FlashMode.AUTO, FlashMode.ON)
                val next = order[(order.indexOf(state.cameraPrefs.flashMode) + 1) % order.size]
                vm.updateCameraPrefs { it.copy(flashMode = next) }
            }
            TopIcon(R.drawable.ic_timer, "Self-timer") {
                val order = listOf(0, 3, 5, 10)
                val next = order[(order.indexOf(state.cameraPrefs.timer) + 1) % order.size]
                vm.updateCameraPrefs { it.copy(timer = next) }
            }
            TopIcon(gridIcon(state.cameraPrefs.grid), "Grid") {
                val order = listOf(GridMode.OFF, GridMode.THIRDS, GridMode.SQUARE)
                val next = order[(order.indexOf(state.cameraPrefs.grid) + 1) % order.size]
                vm.updateCameraPrefs { it.copy(grid = next) }
            }
            if (state.cameraPrefs.timer > 0) {
                Text(
                    "${state.cameraPrefs.timer}s",
                    color = AccentOrange,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 2.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            if (state.cameraPrefs.showLevel && state.caps?.isFront != true) {
                LevelIndicator(enabled = true)
                Spacer(Modifier.width(10.dp))
            }
            TopIcon(R.drawable.ic_settings, "Settings", onClick = onOpenSettings)
        }

        // ---------------- resolution badge ----------------
        if (state.ui.showResBadge) {
            val size = remember(state.resolution, state.caps, state.displayRotation) {
                vm.resolvedSize()
            }
            Column(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(top = 52.dp, start = 14.dp)
            ) {
                Text(
                    text = "${size.w} x ${size.h}",
                    color = ChromeDim,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = RatioMath.megapixelsLabel(size.w, size.h),
                    color = ChromeDim.copy(alpha = 0.6f),
                    fontSize = 10.sp
                )
            }
        }

        // ---------------- zoom HUD ----------------
        // Always visible while zoomed; tap to jump back to 1x.
        if (state.cameraPrefs.zoom > 1.01f) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp)
                    .clickable {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                        vm.resetZoom()
                    }
            ) {
                Text(
                    text = String.format(java.util.Locale.US, "%.1fx", state.cameraPrefs.zoom),
                    color = ChromeOn,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                )
            }
        }

        // ---------------- recording badge ----------------
        if (state.recording) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 52.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Canvas(modifier = Modifier.size(9.dp)) {
                    drawCircle(RecRed)
                }
                RecTimeBadge(state.recordingStartMs, state.recordingPaused)
            }
        }

        // ---------------- degenerate-stream banner ----------------
        // The live probe found perfectly flat frames at every resolution:
        // the camera is feeding broken data. Point at the diagnostics.
        if (state.streamSuspect && !state.recording) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.Black.copy(alpha = 0.65f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 96.dp)
            ) {
                Text(
                    text = "Camera stream looks corrupted - run Settings > Camera diagnostics",
                    color = ChromeOn,
                    fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }

        // ---------------- compatibility viewfinder chip ----------------
        // The GL preview path was declared degenerate at every resolution;
        // the viewfinder is now running on CPU-converted frames (working,
        // slightly softer). Reassure instead of alarming.
        if (state.vfCompat && !state.vfSystem && !state.streamSuspect && !state.recording) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 96.dp)
            ) {
                Text(
                    text = "Compatibility viewfinder",
                    color = ChromeOn,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }

        // ---------------- system viewfinder chip ----------------
        // The last-resort rung: the live view comes from the platform's own
        // renderer. Photos still get every effect; only the live preview
        // shows the clean image.
        if (state.vfSystem && !state.streamSuspect && !state.recording) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 96.dp)
            ) {
                Text(
                    text = "System viewfinder - effects still apply to photos",
                    color = ChromeOn,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }

        // ---------------- bottom chrome ----------------
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 14.dp)
        ) {
            // preset strip + adjust + menu
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color.Black.copy(alpha = 0.45f),
                    modifier = Modifier.clickable {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                        vm.setAdjustOpen(true)
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_tune),
                            contentDescription = null,
                            tint = AccentOrange,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Adjust", color = ChromeOn, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.width(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(state.presets, key = { it.id }) { preset ->
                        PresetChip(
                            name = preset.name,
                            selected = preset.id == state.activePresetId,
                            modified = preset.id == state.activePresetId && vm.isPresetModified()
                        ) {
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                            vm.selectPreset(preset.id)
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
                Box {
                    TopIcon(R.drawable.ic_more_vert, "Preset actions", onClick = { presetMenuOpen = true })
                    DropdownMenu(expanded = presetMenuOpen, onDismissRequest = { presetMenuOpen = false }) {
                        val active = state.activePreset
                        DropdownMenuItem(
                            text = { Text("Reset to preset") },
                            onClick = { presetMenuOpen = false; vm.resetPreset() },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_restart_alt), null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Save as new preset") },
                            onClick = { presetMenuOpen = false; saveAsOpen = true },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_add), null) }
                        )
                        if (active != null && !active.builtin) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = {
                                    presetMenuOpen = false
                                    vm.renamePreset(active.id, active.name + " 2")
                                },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_edit), null) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Duplicate") },
                            onClick = { presetMenuOpen = false; vm.duplicatePreset(state.activePresetId) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_content_copy), null) }
                        )
                        if (active != null && !active.builtin) {
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = { presetMenuOpen = false; vm.deletePreset(active.id) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_delete), null) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Export JSON") },
                            onClick = { presetMenuOpen = false; exportOpen = true },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_download), null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Import JSON") },
                            onClick = { presetMenuOpen = false; importOpen = true },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_upload), null) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // mode switch
            Segmented(
                options = listOf("Photo", "Video"),
                selected = if (state.photoMode) 0 else 1,
                onSelect = {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                    vm.setPhotoMode(it == 0)
                },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(190.dp)
            )

            Spacer(Modifier.height(14.dp))

            // shutter row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp)
            ) {
                GalleryThumb(vm = vm, modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(if (state.recording) 68.dp else 76.dp)
                        .clip(CircleShape)
                        .border(4.dp, ChromeOn, CircleShape)
                        .clickable {
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                            vm.onShutter()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (state.recording) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(RecRed)
                        )
                    } else if (!state.photoMode) {
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .clip(CircleShape)
                                .background(RecRed)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(CircleShape)
                                .background(ChromeOn)
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (state.recording) {
                        TopIcon(
                            if (state.recordingPaused) R.drawable.ic_play_arrow else R.drawable.ic_pause,
                            "Pause"
                        ) {
                            if (state.recordingPaused) vm.resumeRecording() else vm.pauseRecording()
                        }
                    }
                    TopIcon(R.drawable.ic_cameraswitch, "Switch camera") {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                        vm.switchCamera()
                    }
                }
            }
        }

        CountdownOverlay(state.countdown)
    }

    // ---------------- dialogs ----------------
    if (state.adjustOpen) {
        AdjustSheet(vm, sheet)
    }

    if (exportOpen) {
        val json = vm.exportActivePreset()
        AlertDialog(
            onDismissRequest = { exportOpen = false },
            title = { Text("Export preset") },
            text = {
                Column {
                    OutlinedTextField(
                        value = json,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth().height(160.dp)
                    )
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(json))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }) { Text("Copy JSON") }
                }
            },
            confirmButton = { TextButton(onClick = { exportOpen = false }) { Text("Close") } }
        )
    }

    if (importOpen) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { importOpen = false },
            title = { Text("Import preset") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    label = { Text("Paste preset JSON") }
                )
            },
            confirmButton = {
                TextButton(onClick = { importOpen = false; vm.importPreset(text) }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { importOpen = false }) { Text("Cancel") } }
        )
    }

    if (saveAsOpen) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { saveAsOpen = false },
            title = { Text("Save as new preset") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Preset name") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    saveAsOpen = false
                    vm.saveAsNewPreset(name)
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saveAsOpen = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun TopIcon(icon: Int, desc: String, onClick: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(icon),
            contentDescription = desc,
            tint = ChromeOn,
            modifier = Modifier.size(21.dp)
        )
    }
}

@Composable
private fun PresetChip(name: String, selected: Boolean, modified: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (selected) Color.White.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.45f),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Text(
                text = name,
                color = if (selected) ChromeOn else ChromeDim,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            if (modified) {
                Spacer(Modifier.width(5.dp))
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(AccentOrange)
                )
            }
        }
    }
}

@Composable
private fun GalleryThumb(vm: CameraViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    var thumb by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var latestUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(state.lastSaved, state.loaded) {
        val pair = loadLatestThumb(context)
        thumb = pair?.first
        latestUri = pair?.second
    }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.12f))
                .clickable {
                    val uri = latestUri
                    if (uri != null) {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            )
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val bmp = thumb
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Gallery",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_photo_library),
                    contentDescription = "Gallery",
                    tint = ChromeDim,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}

private fun loadLatestThumb(context: android.content.Context): Pair<android.graphics.Bitmap?, Uri?>? {
    return runCatching {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        context.contentResolver.query(
            collection, projection,
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("%ACam%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                val uri = Uri.withAppendedPath(collection, id.toString())
                val bmp = if (android.os.Build.VERSION.SDK_INT >= 29) {
                    context.contentResolver.loadThumbnail(uri, android.util.Size(96, 96), null)
                } else {
                    MediaStore.Images.Thumbnails.getThumbnail(
                        context.contentResolver, id,
                        MediaStore.Images.Thumbnails.MINI_KIND, null
                    )
                }
                Pair(bmp, uri)
            } else null
        }
    }.getOrNull()
}

private fun flashIcon(mode: FlashMode): Int = when (mode) {
    FlashMode.OFF -> R.drawable.ic_flash_off
    FlashMode.AUTO -> R.drawable.ic_flash_auto
    FlashMode.ON -> R.drawable.ic_flash_on
    FlashMode.TORCH -> R.drawable.ic_flashlight_on
}

private fun gridIcon(mode: GridMode): Int = when (mode) {
    GridMode.OFF -> R.drawable.ic_grid_off
    GridMode.THIRDS -> R.drawable.ic_grid_on
    GridMode.SQUARE -> R.drawable.ic_crop_free
}
