package com.duoopen.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.duoopen.DualScreen
import com.duoopen.fold.DuoShader
import com.duoopen.fold.FoldLine
import com.duoopen.fold.HingeAngleSource
import com.duoopen.fold.isInnerPanel
import com.duoopen.overlay.OverlayFeature
import com.duoopen.overlay.OverlayState
import com.duoopen.settings.DuoSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Composable
fun DuoApp(foldLineFlow: StateFlow<FoldLine?>, dualScreen: DualScreen? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by DuoSettings.config.collectAsStateWithLifecycle()
    val foldLine by foldLineFlow.collectAsStateWithLifecycle()

    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumeTick++
        onPauseOrDispose { }
    }
    val overlayEnabled = remember(resumeTick) { OverlayFeature.isEnabled(context) }
    val liveBlurSupported = remember(resumeTick) { OverlayFeature.liveBlurSupported(context) }
    val supportedDevice = remember { OverlayFeature.supportedDevice(context) }
    val overlayRunning by OverlayState.running.collectAsStateWithLifecycle()
    val shizukuStatus by OverlayFeature.shizukuStatus.collectAsStateWithLifecycle()
    LaunchedEffect(resumeTick) { OverlayFeature.refreshShizuku() }
    val dualStatus by (dualScreen?.status ?: MutableStateFlow("")).collectAsStateWithLifecycle()
    val dualActive by (dualScreen?.active ?: MutableStateFlow(false)).collectAsStateWithLifecycle()

    // The overlay service's hinge has Samsung's real angle (Shizuku); the
    // app's own only has the platform sensor's 0/90/180 stops.
    var localAngle by remember { mutableFloatStateOf(Float.NaN) }
    val localHinge = remember { HingeAngleSource(context.applicationContext) { localAngle = it } }
    DisposableEffect(localHinge) {
        localHinge.start()
        onDispose { localHinge.stop() }
    }
    val serviceHinge = remember(resumeTick, overlayEnabled, shizukuStatus) { OverlayFeature.hinge() }
    val hinge = serviceHinge ?: localHinge
    var serviceAngle by remember { mutableFloatStateOf(Float.NaN) }
    var tracked by remember { mutableStateOf(false) }
    if (serviceHinge != null) {
        LaunchedEffect(serviceHinge) {
            // Every frame while the hinge moves; a few times a second at rest.
            var still = 0
            while (true) {
                val a = withFrameNanos { now -> serviceHinge.estimate(now) }
                still = if (a == serviceAngle) still + 1 else 0
                serviceAngle = a
                tracked = serviceHinge.externalFeedRunning
                if (still > STILL_FRAMES) kotlinx.coroutines.delay(REST_POLL_MS)
            }
        }
    }
    val hingeAngle = if (serviceHinge != null && serviceAngle.isFinite()) serviceAngle else localAngle

    // Without a hinge sensor (emulator, non-foldable) the slider is the only input.
    var simulate by rememberSaveable { mutableStateOf(hinge.sensor == null) }
    var simulatedAngle by rememberSaveable { mutableFloatStateOf(120f) }
    val angle = if (simulate || hinge.sensor == null) simulatedAngle else hingeAngle
    // Re-read the panel on every configuration change (the fold swaps panels).
    LocalConfiguration.current
    val onCover = !simulate && hinge.sensor != null && !context.display.isInnerPanel()
    val targetTilt = if (angle.isNaN() || onCover) 0f else DuoShader.tiltForHinge(angle, config)
    val paneTilt by animateFloatAsState(
        targetValue = targetTilt,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 900f),
        label = "paneTilt",
    )

    val shader = remember { DuoShader.create(context) }
    val pxPerMm = remember(context) { DuoShader.pxPerMm(context) }

    var showSheet by remember { mutableStateOf(false) }
    val onShizuku = {
        // Running and waiting for us: ask. Otherwise it needs starting (or installing) first.
        if (OverlayFeature.shizukuNeedsPermission()) OverlayFeature.requestShizuku() else openShizuku(context)
    }
    val playFold = {
        showSheet = false
        // Let the sheet finish closing so it isn't in the snapshot.
        scope.launch {
            kotlinx.coroutines.delay(450)
            if (!OverlayFeature.playDemo()) {
                Toast.makeText(context, "Turn on the whole-screen fold first", Toast.LENGTH_SHORT).show()
            }
        }
        Unit
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HomePreview(
            hingeAngle = angle,
            paneTilt = paneTilt,
            simulated = simulate,
            tracked = !simulate && tracked,
            setup = SetupState(
                overlayEnabled = overlayEnabled,
                shizukuReady = OverlayFeature.shizukuReady(),
                shizukuInstalled = OverlayFeature.shizukuInstalled(),
                supportedDevice = supportedDevice,
            ),
            onEnableOverlay = { openAccessibilitySettings(context) },
            onShizuku = onShizuku,
            onTest = playFold,
            onTune = { showSheet = true },
            modifier = if (shader != null && !overlayRunning) {
                Modifier.foldEffect(shader, { paneTilt }, config, pxPerMm, foldLine)
            } else {
                Modifier
            },
        )

        if (showSheet) {
            ControlSheet(
                config = config,
                hinge = hinge,
                hingeAngle = angle,
                paneTilt = paneTilt,
                simulate = simulate,
                onSimulateChange = { simulate = it },
                simulatedAngle = simulatedAngle,
                onSimulatedAngleChange = { simulatedAngle = it },
                overlayEnabled = overlayEnabled,
                liveBlurSupported = liveBlurSupported,
                dualStatus = dualStatus,
                shizukuStatus = shizukuStatus,
                shizukuReady = OverlayFeature.shizukuReady(),
                shizukuInstalled = OverlayFeature.shizukuInstalled(),
                onShizukuAuthorize = onShizuku,
                onOpenShizuku = { openShizuku(context) },
                angleFeedStatus = { OverlayFeature.angleFeedStatus() },
                dualActive = dualActive,
                onDualChange = { on -> if (on) dualScreen?.start() else dualScreen?.stop() },
                onEnableOverlay = { openAccessibilitySettings(context) },
                onTestOverlay = playFold,
                onDismiss = { showSheet = false },
            )
        }
    }
}

private const val STILL_FRAMES = 30
private const val REST_POLL_MS = 120L

private fun openShizuku(context: Context) {
    val launch = OverlayFeature.shizukuPackage()?.let { context.packageManager.getLaunchIntentForPackage(it) }
    val intent = launch ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/"))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(intent) }.isFailure) {
        Toast.makeText(context, "Couldn't open Shizuku", Toast.LENGTH_SHORT).show()
    }
}

private fun openAccessibilitySettings(context: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(intent) }.isFailure) {
        Toast.makeText(context, "Couldn't open Accessibility settings", Toast.LENGTH_SHORT).show()
    }
}
