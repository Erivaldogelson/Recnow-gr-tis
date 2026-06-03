package com.erivaldogelson.recnow

import android.app.Activity
import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.erivaldogelson.recnow.ui.theme.RecnowTheme

class CaptureRequestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (closeIfUnsafeRuntime()) return
        applyReadableSystemBars()
        applyBannerBackdrop()
        setContent {
            RecnowTheme {
                CaptureRequestScreen(
                    initialQualityIndex = intent.getIntExtra(EXTRA_QUALITY_INDEX, 1),
                    onClose = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_QUALITY_INDEX = "quality_index"

        fun intent(context: Context, qualityIndex: Int = 1): Intent {
            return Intent(context, CaptureRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_QUALITY_INDEX, qualityIndex)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureRequestScreen(
    initialQualityIndex: Int,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val projectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    var selectedQualityIndex by rememberSaveable {
        mutableStateOf(initialQualityIndex.coerceIn(RecordingQualities.indices))
    }
    var audioModeName by rememberSaveable { mutableStateOf(AudioMode.NONE.name) }
    val audioMode = runCatching { AudioMode.valueOf(audioModeName) }.getOrDefault(AudioMode.NONE)
    val selectedQuality = RecordingQualities.getOrElse(selectedQualityIndex) { RecordingQualities[1] }
    val selectedOptions = RecordingOptions(
        qualityIndex = selectedQualityIndex,
        audioMode = audioMode
    )
    val selectedResolution = selectedQuality.resolutionLabel()
    val availableResolutions = remember { RecordingQualities.map { it.resolutionLabel() }.distinct() }
    val availableFrameRates = remember(selectedResolution) {
        RecordingQualities
            .filter { it.resolutionLabel() == selectedResolution }
            .map { it.frameRate }
            .distinct()
    }
    val selectedResolutionQualities = remember(selectedResolution) {
        RecordingQualities.filter { it.resolutionLabel() == selectedResolution }
    }
    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ContextCompat.startForegroundService(
                context,
                ScreenRecordService.startIntent(context, result.resultCode, result.data!!, selectedQuality, selectedOptions)
            )
        }
        onClose()
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        captureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    Surface(color = Color.Transparent) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compactScreen = maxWidth < 420.dp || maxHeight < 620.dp
            val screenPadding = if (compactScreen) 12.dp else 22.dp
            val cardMaxWidth = if (maxWidth >= 720.dp) 680.dp else 520.dp
            val cardMaxHeight = maxHeight - screenPadding * 2

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(screenPadding),
                contentAlignment = Alignment.Center
            ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = cardMaxWidth)
                    .heightIn(max = cardMaxHeight),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                shape = RoundedCornerShape(if (compactScreen) 22.dp else 30.dp)
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(if (compactScreen) 16.dp else 22.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compactScreen) 12.dp else 16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.new_recording),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.choose_banner_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (compactScreen) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CaptureDropdownSelector(
                                modifier = Modifier.fillMaxWidth(),
                                title = stringResource(R.string.resolution),
                                value = selectedResolution,
                                options = availableResolutions,
                                onOptionSelected = { resolution ->
                                    selectedQualityIndex = RecordingQualities.indexOf(
                                        RecordingQualities.firstOrNull {
                                            it.resolutionLabel() == resolution && it.frameRate == selectedQuality.frameRate
                                        } ?: RecordingQualities.first { it.resolutionLabel() == resolution }
                                    ).coerceAtLeast(0)
                                }
                            )
                            CaptureDropdownSelector(
                                modifier = Modifier.fillMaxWidth(),
                                title = stringResource(R.string.fps),
                                value = "${selectedQuality.frameRate} FPS",
                                options = availableFrameRates.map { "$it FPS" },
                                onOptionSelected = { frameRateLabel ->
                                    val frameRate = frameRateLabel.substringBefore(" ").toIntOrNull() ?: selectedQuality.frameRate
                                    selectedQualityIndex = RecordingQualities.indexOf(
                                        RecordingQualities.first {
                                            it.resolutionLabel() == selectedResolution && it.frameRate == frameRate
                                        }
                                    ).coerceAtLeast(0)
                                }
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CaptureDropdownSelector(
                                modifier = Modifier.weight(1f),
                                title = stringResource(R.string.resolution),
                                value = selectedResolution,
                                options = availableResolutions,
                                onOptionSelected = { resolution ->
                                    selectedQualityIndex = RecordingQualities.indexOf(
                                        RecordingQualities.firstOrNull {
                                            it.resolutionLabel() == resolution && it.frameRate == selectedQuality.frameRate
                                        } ?: RecordingQualities.first { it.resolutionLabel() == resolution }
                                    ).coerceAtLeast(0)
                                }
                            )
                            CaptureDropdownSelector(
                                modifier = Modifier.weight(1f),
                                title = stringResource(R.string.fps),
                                value = "${selectedQuality.frameRate} FPS",
                                options = availableFrameRates.map { "$it FPS" },
                                onOptionSelected = { frameRateLabel ->
                                    val frameRate = frameRateLabel.substringBefore(" ").toIntOrNull() ?: selectedQuality.frameRate
                                    selectedQualityIndex = RecordingQualities.indexOf(
                                        RecordingQualities.first {
                                            it.resolutionLabel() == selectedResolution && it.frameRate == frameRate
                                        }
                                    ).coerceAtLeast(0)
                                }
                            )
                        }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.34f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.hdr_profiles),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                selectedResolutionQualities.forEach { quality ->
                                    CaptureQualityChip(
                                        quality = quality,
                                        selected = selectedQuality == quality,
                                        onClick = {
                                            selectedQualityIndex = RecordingQualities.indexOf(quality).coerceAtLeast(0)
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.audio),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CaptureOptionChip(stringResource(R.string.audio_none), audioMode == AudioMode.NONE) {
                                    audioModeName = AudioMode.NONE.name
                                }
                                CaptureOptionChip(stringResource(R.string.microphone), audioMode == AudioMode.MICROPHONE) {
                                    audioModeName = AudioMode.MICROPHONE.name
                                }
                                CaptureOptionChip(stringResource(R.string.media_audio), audioMode == AudioMode.MEDIA) {
                                    audioModeName = AudioMode.MEDIA.name
                                }
                                CaptureOptionChip(stringResource(R.string.media_and_microphone), audioMode == AudioMode.MEDIA_AND_MICROPHONE) {
                                    audioModeName = AudioMode.MEDIA_AND_MICROPHONE.name
                                }
                            }
                        }
                    }
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        onClick = {
                            val permissions = buildList {
                                if (audioMode != AudioMode.NONE) add(Manifest.permission.RECORD_AUDIO)
                            }
                            if (permissions.isEmpty()) {
                                captureLauncher.launch(projectionManager.createScreenCaptureIntent())
                            } else {
                                permissionLauncher.launch(permissions.toTypedArray())
                            }
                        }
                    ) {
                        Text(
                            text = stringResource(R.string.start_recording),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun CaptureDropdownSelector(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    options: List<String>,
    onOptionSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Box {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                onClick = { expanded = true }
            ) {
                Text(value, fontWeight = FontWeight.SemiBold)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            expanded = false
                            onOptionSelected(option)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CaptureQualityChip(
    quality: RecordingQuality,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        label = {
            Column {
                Text(quality.shortLabel, fontWeight = FontWeight.SemiBold)
                Text(
                    "${quality.landscapeWidth} x ${quality.landscapeHeight}",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    )
}

@Composable
private fun CaptureOptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        label = { Text(label, fontWeight = FontWeight.SemiBold) }
    )
}

private fun RecordingQuality.resolutionLabel(): String {
    return when (landscapeHeight) {
        1080 -> "Full HD"
        1440 -> "2K"
        2160 -> "4K"
        else -> "${landscapeHeight}p"
    }
}

private fun Activity.applyBannerBackdrop() {
    window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    window.attributes = window.attributes.apply {
        dimAmount = 0.28f
        if (Build.VERSION.SDK_INT >= 31) {
            blurBehindRadius = 48
            flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        }
    }
}
