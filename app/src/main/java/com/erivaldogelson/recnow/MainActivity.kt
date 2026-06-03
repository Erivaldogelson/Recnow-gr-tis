package com.erivaldogelson.recnow

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.delay
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.erivaldogelson.recnow.ui.theme.RecnowTheme
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (closeIfUnsafeRuntime()) return
        applyReadableSystemBars()
        setContent {
            RecnowTheme {
                RecnowApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecnowApp() {
    val context = LocalContext.current
    val projectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    var selectedQualityIndex by rememberSaveable { mutableStateOf(1) }
    val selectedQuality = RecordingQualities.getOrElse(selectedQualityIndex) { RecordingQualities[1] }
    var audioModeName by rememberSaveable { mutableStateOf(AudioMode.NONE.name) }
    var menuExpanded by remember { mutableStateOf(false) }
    val audioMode = runCatching { AudioMode.valueOf(audioModeName) }.getOrDefault(AudioMode.NONE)
    var adsReady by remember { mutableStateOf(false) }
    var recordingAd by remember { mutableStateOf<InterstitialAd?>(null) }
    val recordingAdUnitId = stringResource(R.string.admob_recording_ad_unit_id)
    val recordingOptions = RecordingOptions(
        qualityIndex = selectedQualityIndex,
        audioMode = audioMode
    )
    var recordingState by remember { mutableStateOf(RecordingState(qualityLabel = selectedQuality.shortLabel)) }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ContextCompat.startForegroundService(
                context,
                ScreenRecordService.startIntent(context, result.resultCode, result.data!!, selectedQuality, recordingOptions)
            )
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                val bundle = if (Build.VERSION.SDK_INT >= 33) {
                    intent?.getBundleExtra(ScreenRecordService.EXTRA_STATE)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.extras?.getBundle(ScreenRecordService.EXTRA_STATE)
                }
                if (bundle != null) recordingState = bundle.toRecordingState()
            }
        }
        val filter = IntentFilter(ScreenRecordService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    LaunchedEffect(Unit) {
        runCatching { MobileAds.initialize(context) }
        delay(1_200)
        adsReady = true

        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 36) add("android.permission.POST_PROMOTED_NOTIFICATIONS")
            add(Manifest.permission.RECORD_AUDIO)
        }
        if (permissions.isNotEmpty()) {
            notificationPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    fun loadRecordingAd() {
        if (recordingAd != null) return
        runCatching {
            InterstitialAd.load(
                context,
                recordingAdUnitId,
                AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        recordingAd = ad
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        recordingAd = null
                    }
                }
            )
        }
    }

    fun startCapture() {
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    fun showRecordingAdThenStart() {
        val activity = context as? Activity
        val ad = recordingAd
        if (!adsReady || ad == null) {
            startCapture()
            loadRecordingAd()
            return
        }
        val hostActivity = activity ?: run {
            startCapture()
            loadRecordingAd()
            return
        }

        recordingAd = null
        var started = false
        fun continueRecording() {
            if (!started) {
                started = true
                startCapture()
                loadRecordingAd()
            }
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() = continueRecording()

            override fun onAdFailedToShowFullScreenContent(adError: AdError) = continueRecording()
        }

        if (runCatching { ad.show(hostActivity) }.isFailure) {
            continueRecording()
        }
    }

    LaunchedEffect(adsReady, recordingAdUnitId) {
        if (adsReady) loadRecordingAd()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold)
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Text("...", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.about_us)) },
                                onClick = {
                                    menuExpanded = false
                                    context.startActivity(Intent(context, AboutActivity::class.java))
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        RecordingHome(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(padding),
            recordingState = recordingState,
            selectedQuality = selectedQuality,
            audioMode = audioMode,
            showAds = adsReady,
            onQualitySelected = { selectedQualityIndex = RecordingQualities.indexOf(it).coerceAtLeast(0) },
            onAudioModeChange = { audioModeName = it.name },
            onRecordClick = {
                if (recordingState.isRecording) {
                    ContextCompat.startForegroundService(context, ScreenRecordService.stopIntent(context))
                } else {
                    showRecordingAdThenStart()
                }
            }
        )
    }
}

@Composable
private fun RecordingHome(
    modifier: Modifier = Modifier,
    recordingState: RecordingState,
    selectedQuality: RecordingQuality,
    audioMode: AudioMode,
    showAds: Boolean,
    onQualitySelected: (RecordingQuality) -> Unit,
    onAudioModeChange: (AudioMode) -> Unit,
    onRecordClick: () -> Unit
) {
    BoxWithConstraints(modifier = modifier) {
        val compactScreen = maxWidth < 360.dp || maxHeight < 620.dp
        val expandedScreen = maxWidth >= 720.dp
        val horizontalPadding = if (compactScreen) 12.dp else 22.dp
        val verticalPadding = if (compactScreen) 10.dp else 14.dp
        val sectionGap = if (compactScreen) 12.dp else 18.dp
        val orbSize = when {
            compactScreen -> 156.dp
            expandedScreen -> 236.dp
            else -> 214.dp
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (expandedScreen) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 1080.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(0.9f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        RecordOrb(
                            isRecording = recordingState.isRecording,
                            elapsed = recordingState.formattedElapsed,
                            orbSize = orbSize,
                            onClick = onRecordClick
                        )
                        Spacer(Modifier.height(sectionGap))
                        StatusCard(recordingState, selectedQuality)
                        if (showAds && !recordingState.isRecording) {
                            Spacer(Modifier.height(sectionGap))
                            AdBanner()
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1.1f),
                        verticalArrangement = Arrangement.spacedBy(sectionGap)
                    ) {
                        QualitySelector(
                            selected = selectedQuality,
                            enabled = !recordingState.isRecording,
                            onSelected = onQualitySelected
                        )
                        RecordingOptionsSelector(
                            enabled = !recordingState.isRecording,
                            audioMode = audioMode,
                            onAudioModeChange = onAudioModeChange
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 560.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    RecordOrb(
                        isRecording = recordingState.isRecording,
                        elapsed = recordingState.formattedElapsed,
                        orbSize = orbSize,
                        onClick = onRecordClick
                    )
                    Spacer(Modifier.height(sectionGap))
                    QualitySelector(
                        selected = selectedQuality,
                        enabled = !recordingState.isRecording,
                        onSelected = onQualitySelected
                    )
                    Spacer(Modifier.height(sectionGap))
                    RecordingOptionsSelector(
                        enabled = !recordingState.isRecording,
                        audioMode = audioMode,
                        onAudioModeChange = onAudioModeChange
                    )
                    Spacer(Modifier.height(sectionGap))
                    StatusCard(recordingState, selectedQuality)
                    if (showAds && !recordingState.isRecording && !compactScreen) {
                        Spacer(Modifier.height(sectionGap))
                        AdBanner()
                    }
                }
            }
            Spacer(Modifier.height(if (compactScreen) 12.dp else 24.dp))
        }
    }
}

@Composable
private fun AdBanner() {
    val context = LocalContext.current
    val adUnitId = stringResource(R.string.admob_banner_ad_unit_id)
    val adView = remember(adUnitId) {
        runCatching {
            AdView(context).apply {
                setAdSize(AdSize.BANNER)
                this.adUnitId = adUnitId
                loadAd(AdRequest.Builder().build())
            }
        }.getOrNull()
    }

    DisposableEffect(adView) {
        onDispose { adView?.destroy() }
    }

    if (adView == null) {
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.ads_banner_title),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp)
        ) {
            AndroidView(
                modifier = Modifier.height(50.dp),
                factory = { adView }
            )
        }
    }
}

@Composable
fun AboutScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        val compactScreen = maxWidth < 360.dp || maxHeight < 620.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = if (compactScreen) 12.dp else 22.dp,
                    vertical = if (compactScreen) 10.dp else 14.dp
                ),
            verticalArrangement = Arrangement.spacedBy(if (compactScreen) 12.dp else 16.dp)
        ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.developer_name_label),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.about_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        LinkCard(
            logo = "IG",
            title = stringResource(R.string.instagram),
            subtitle = "instagram.com/erivaldo_gelson",
            color = Color(0xFFE1306C),
            onClick = { context.openExternalUrl("https://www.instagram.com/erivaldo_gelson/") }
        )
        LinkCard(
            logo = "@",
            title = stringResource(R.string.threads),
            subtitle = "threads.com/@erivaldo_gelson",
            color = Color(0xFF111111),
            onClick = { context.openExternalUrl("https://www.threads.com/@erivaldo_gelson") }
        )
        LinkCard(
            logo = "GH",
            title = stringResource(R.string.github),
            subtitle = "github.com/Erivaldogelson",
            color = Color(0xFF24292F),
            onClick = { context.openExternalUrl("https://github.com/Erivaldogelson") }
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.support_dev_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.support_dev_text),
                    style = MaterialTheme.typography.bodyMedium
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.pix_key_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Erivaldojelson8@gmail.com",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Button(
                            onClick = { context.copyPixKey("Erivaldojelson8@gmail.com") },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFF0000),
                                contentColor = Color.White
                            )
                        ) {
                            Text(stringResource(R.string.copy), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Button(
                    onClick = { context.openPix("Erivaldojelson8@gmail.com") },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF0000),
                        contentColor = Color.White
                    )
                ) {
                    Text(stringResource(R.string.open_pix), fontWeight = FontWeight.Bold)
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.license_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.license_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    }
}

@Composable
private fun LinkCard(
    logo: String,
    title: String,
    subtitle: String,
    color: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(46.dp),
                color = color,
                contentColor = Color.White,
                shape = CircleShape
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(logo, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(">", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

private fun Context.openExternalUrl(url: String) {
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(this, getString(R.string.no_app_to_open_link), Toast.LENGTH_SHORT).show()
    }
}

private fun Context.openPix(key: String) {
    copyToClipboard("Pix", key)
    val pixUri = Uri.parse("pix://payment?key=${Uri.encode(key)}")
    val openedPix = runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, pixUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (!openedPix) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, key)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            startActivity(Intent.createChooser(shareIntent, getString(R.string.open_pix)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            Toast.makeText(this, getString(R.string.pix_copied), Toast.LENGTH_LONG).show()
        }
    }
    Toast.makeText(this, getString(R.string.pix_copied), Toast.LENGTH_LONG).show()
}

private fun Context.copyToClipboard(label: String, text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
}

private fun Context.copyPixKey(key: String) {
    copyToClipboard("Pix", key)
    Toast.makeText(this, getString(R.string.pix_copied), Toast.LENGTH_LONG).show()
}

@Composable
fun RecordingOptionsSelector(
    enabled: Boolean,
    audioMode: AudioMode,
    onAudioModeChange: (AudioMode) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.options),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OptionChip(stringResource(R.string.audio_none), audioMode == AudioMode.NONE, enabled) {
                onAudioModeChange(AudioMode.NONE)
            }
            OptionChip(stringResource(R.string.microphone), audioMode == AudioMode.MICROPHONE, enabled) {
                onAudioModeChange(AudioMode.MICROPHONE)
            }
            OptionChip(stringResource(R.string.media_audio), audioMode == AudioMode.MEDIA, enabled) {
                onAudioModeChange(AudioMode.MEDIA)
            }
            OptionChip(stringResource(R.string.media_and_microphone), audioMode == AudioMode.MEDIA_AND_MICROPHONE, enabled) {
                onAudioModeChange(AudioMode.MEDIA_AND_MICROPHONE)
            }
        }
    }
}

@Composable
private fun OptionChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        label = { Text(label, fontWeight = FontWeight.SemiBold) }
    )
}

@Composable
private fun RecordOrb(
    isRecording: Boolean,
    elapsed: String,
    orbSize: Dp = 214.dp,
    onClick: () -> Unit
) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 0.96f,
        targetValue = if (isRecording) 1.08f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orbPulse"
    )
    val ringColor by animateColorAsState(
        targetValue = if (isRecording) Color(0xFFFF0000) else MaterialTheme.colorScheme.primary,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "ringColor"
    )

    Box(
        modifier = Modifier
            .size(orbSize)
            .scale(scale)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFFFF6B6B), Color(0xFFFF0000), Color(0xFFB00000)),
                    center = Offset(size.width * 0.42f, size.height * 0.34f),
                    radius = size.minDimension * 0.48f
                ),
                radius = size.minDimension * 0.38f,
                center = center
            )
            drawCircle(
                color = ringColor.copy(alpha = 0.22f),
                radius = size.minDimension * 0.47f,
                style = Stroke(width = 10.dp.toPx())
            )
            drawOval(
                color = Color.White.copy(alpha = 0.45f),
                topLeft = Offset(size.width * 0.28f, size.height * 0.2f),
                size = Size(size.width * 0.24f, size.height * 0.13f)
            )
        }
        Surface(
            modifier = Modifier.size(orbSize * 0.41f),
            color = Color.White,
            shape = CircleShape,
            shadowElevation = 10.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(orbSize * 0.2f)
                        .clip(CircleShape)
                        .background(Color(0xFFFF0000))
                )
            }
        }
        AnimatedVisibility(
            visible = isRecording,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = CircleShape
            ) {
                Text(
                    text = elapsed,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun QualitySelector(
    selected: RecordingQuality,
    enabled: Boolean,
    onSelected: (RecordingQuality) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.resolution),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RecordingQualities.forEach { quality ->
                FilterChip(
                    selected = selected == quality,
                    enabled = enabled,
                    onClick = { onSelected(quality) },
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
        }
    }
}

@Composable
private fun StatusCard(state: RecordingState, selectedQuality: RecordingQuality) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (state.isRecording) Color(0xFFFF0000) else MaterialTheme.colorScheme.outline)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (state.isRecording) stringResource(R.string.recording_now) else stringResource(R.string.ready_to_record),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = if (state.isRecording) {
                    "${state.formattedElapsed} em ${state.qualityLabel}"
                } else {
                    stringResource(R.string.selected_quality, selectedQuality.shortLabel)
                },
                style = MaterialTheme.typography.bodyLarge
            )
            state.lastFilePath?.let {
                Text(
                    text = stringResource(R.string.last_file, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start
                )
            }
        }
    }
}
