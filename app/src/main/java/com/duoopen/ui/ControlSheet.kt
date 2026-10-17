package com.duoopen.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.duoopen.fold.HingeAngleSource
import com.duoopen.settings.DuoConfig
import com.duoopen.settings.DuoSettings
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val Ok = Color(0xFF7CE0B0)

/**
 * Settings, most-used first: setup status → when each screen takes over →
 * the look → which parts move → everything else under Advanced. A modal
 * sheet is its own window, so it stays crisp while the screen behind it folds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlSheet(
    config: DuoConfig,
    hinge: HingeAngleSource,
    hingeAngle: Float,
    paneTilt: Float,
    simulate: Boolean,
    onSimulateChange: (Boolean) -> Unit,
    simulatedAngle: Float,
    onSimulatedAngleChange: (Float) -> Unit,
    overlayEnabled: Boolean,
    liveBlurSupported: Boolean,
    dualStatus: String,
    dualActive: Boolean,
    onDualChange: (Boolean) -> Unit,
    shizukuStatus: String,
    shizukuReady: Boolean,
    shizukuInstalled: Boolean,
    onShizukuAuthorize: () -> Unit,
    onOpenShizuku: () -> Unit,
    angleFeedStatus: () -> String,
    onEnableOverlay: () -> Unit,
    onTestOverlay: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val hasSensor = hinge.sensor != null
    // The sensor lines aren't Compose state; poll them while the sheet is up.
    val sensorStatus by produceState(hinge.statusText(), hinge) {
        while (true) {
            delay(500)
            value = hinge.statusText()
        }
    }
    val feedStatus by produceState(angleFeedStatus()) {
        while (true) {
            delay(1_000)
            value = angleFeedStatus()
        }
    }
    var showGuide by remember { mutableStateOf(false) }
    if (showGuide) {
        SetupGuide(
            onOpenAccessibility = { showGuide = false; onEnableOverlay() },
            onDismiss = { showGuide = false },
        )
    }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val tracked = shizukuReady && config.shizukuAngle
    val snapshot = !(config.liveBlur && liveBlurSupported)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))

            // Status ----------------------------------------------------------
            Card {
                StatusRow(
                    title = "Whole-screen fold",
                    ok = overlayEnabled,
                    okText = "On",
                    offText = "Off",
                    action = if (overlayEnabled) "Play" else "Turn on",
                    onAction = if (overlayEnabled) onTestOverlay else onEnableOverlay,
                )
                if (!overlayEnabled) {
                    TextButton(onClick = { showGuide = true }) { Text("Toggle greyed out?") }
                }
                RowDivider()
                StatusRow(
                    title = "Live hinge tracking",
                    ok = shizukuReady,
                    okText = "Shizuku connected",
                    offText = if (shizukuInstalled) "Shizuku not authorised" else "Needs Shizuku",
                    action = when {
                        shizukuReady -> null
                        shizukuInstalled -> "Authorise"
                        else -> "Get Shizuku"
                    },
                    onAction = if (shizukuInstalled) onShizukuAuthorize else onOpenShizuku,
                )
            }

            // Screens ---------------------------------------------------------
            Group("Screens")
            Card {
                AngleSlider(
                    title = "Inner screen turns on",
                    caption = "While opening",
                    valueText = if (config.innerOnAngle >= SYSTEM_INNER) "At 85° (default)" else "At ${config.innerOnAngle.roundToInt()}°",
                    value = config.innerOnAngle.coerceIn(20f, SYSTEM_INNER),
                    range = 20f..SYSTEM_INNER,
                    enabled = tracked,
                    onChange = { v -> DuoSettings.update { it.copy(innerOnAngle = (v / 5f).roundToInt() * 5f) } },
                )
                RowDivider()
                AngleSlider(
                    title = "Cover screen turns on",
                    caption = "While closing",
                    valueText = if (config.coverOnAngle <= 0f) "When shut" else "At ${config.coverOnAngle.roundToInt()}°",
                    value = config.coverOnAngle,
                    range = 0f..120f,
                    enabled = tracked,
                    onChange = { v ->
                        // The low end snaps to the system's own switch at shut.
                        val angle = if (v < 15f) 0f else (v / 5f).roundToInt() * 5f
                        DuoSettings.update { it.copy(coverOnAngle = angle) }
                    },
                )
                if (!tracked) Caption("Needs live hinge tracking.")
                RowDivider()
                Label("When you hold the phone part-folded")
                Choice(
                    options = listOf(0 to "Keep the frost", 3 to "Clear after 3 s", 10 to "After 10 s"),
                    selected = config.holdFrostS,
                    onSelect = { v -> DuoSettings.update { it.copy(holdFrostS = v) } },
                )
            }

            // Look ------------------------------------------------------------
            Group("Look")
            Card {
                PercentSlider("Strength", config.intensity, 1f, 0.5f..3f) { v -> DuoSettings.update { it.copy(intensity = v) } }
                PercentSlider("Blur", config.blurSpread, DuoConfig().blurSpread, 0.02f..0.3f) { v -> DuoSettings.update { it.copy(blurSpread = v) } }
                PercentSlider("Dimming", config.darkening, DuoConfig().darkening, 0f..0.04f) { v -> DuoSettings.update { it.copy(darkening = v) } }
                ValueSlider(
                    label = "Depth",
                    valueText = "${(config.eyeDistanceMm / 10f).roundToInt()} cm",
                    value = config.eyeDistanceMm,
                    range = 200f..800f,
                    enabled = snapshot,
                ) { v -> DuoSettings.update { it.copy(eyeDistanceMm = v) } }
                TextButton(onClick = DuoSettings::resetTuning) { Text("Reset look") }
            }

            // Fold ------------------------------------------------------------
            Group("Fold")
            Card {
                Label("Half that moves")
                Choice(
                    options = listOf(-1 to "Left", 1 to "Right", 0 to "Both"),
                    selected = config.movingSide,
                    onSelect = { side -> DuoSettings.update { it.copy(movingSide = side) } },
                )
                RowDivider()
                Label("Cover screen frost comes from")
                Choice(
                    options = listOf(true to "Right", false to "Left"),
                    selected = config.coverFrostFromRight,
                    onSelect = { v -> DuoSettings.update { it.copy(coverFrostFromRight = v) } },
                )
                RowDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Crease", style = MaterialTheme.typography.bodyLarge)
                        Caption("Across the ${if (config.foldSplitsLong) "long" else "short"} side. Flip if it's in the wrong place.")
                    }
                    TextButton(onClick = { DuoSettings.update { it.copy(foldSplitsLong = !it.foldSplitsLong) } }) { Text("Flip") }
                }
            }

            // Advanced --------------------------------------------------------
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { advanced = !advanced }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Advanced", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(if (advanced) "Hide" else "Show", color = MaterialTheme.colorScheme.primary)
            }
            AnimatedVisibility(advanced) {
                Column {
                    Card {
                        Label("Drawing")
                        Choice(
                            options = listOf(false to "Snapshot", true to "Live blur"),
                            selected = config.liveBlur,
                            enabled = { !it || liveBlurSupported },
                            onSelect = { v -> DuoSettings.update { it.copy(liveBlur = v) } },
                        )
                        Caption(
                            if (!liveBlurSupported) "Live blur is off on this phone."
                            else if (config.liveBlur) "System blur: stepped, no perspective."
                            else "Smooth frost with perspective.",
                        )
                        RowDivider()
                        SwitchRow("Live picture under the frost", "Uses Shizuku", config.shizukuCapture, shizukuReady && snapshot) { v ->
                            DuoSettings.update { it.copy(shizukuCapture = v) }
                        }
                        SwitchRow("Start instantly on a new screen", "Uses its last picture until a fresh one", config.instantStart, snapshot) { v ->
                            DuoSettings.update { it.copy(instantStart = v) }
                        }
                        SwitchRow("Follow the real hinge angle", feedStatus.takeIf { tracked }, config.shizukuAngle, shizukuReady) { v ->
                            DuoSettings.update { it.copy(shizukuAngle = v) }
                        }
                        SwitchRow("Both screens at once", dualStatus, dualActive, dualStatus.startsWith("Available") || dualActive, onDualChange)
                    }

                    Group("Diagnostics")
                    Card {
                        Caption(sensorStatus)
                        if (hasSensor && hinge.stopsOnly) {
                            Caption("Without Shizuku only 0°, 90° and 180° are known, so the fold plays as a short animation.", warn = true)
                        }
                        Caption("Hinge ${if (hingeAngle.isNaN()) "—" else "${hingeAngle.roundToInt()}°"} · tilt %.1f°".format(paneTilt))
                        Caption(shizukuStatus)
                        RowDivider()
                        SwitchRow("Simulate the hinge", "Drives the preview with a slider", simulate, hasSensor, onSimulateChange)
                        if (simulate) {
                            ValueSlider("Hinge", "${simulatedAngle.roundToInt()}°", simulatedAngle, 60f..180f, onChange = onSimulatedAngleChange)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { clipboard.setText(AnnotatedString(hinge.report())) }) { Text("Copy sensor report") }
                            TextButton(onClick = onEnableOverlay) { Text("Accessibility") }
                            TextButton(onClick = onOpenShizuku) { Text("Shizuku") }
                        }
                    }
                }
            }
        }
    }
}

/** Where the system itself lights the inner screen on the way open. */
private const val SYSTEM_INNER = 85f

@Composable
private fun Group(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), content = content)
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun Caption(text: String, warn: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (warn) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StatusRow(title: String, ok: Boolean, okText: String, offText: String, action: String?, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (ok) "✓ $okText" else offText,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) Ok else MaterialTheme.colorScheme.tertiary,
            )
        }
        if (action != null) FilledTonalButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun SwitchRow(title: String, caption: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!caption.isNullOrBlank()) Caption(caption)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun AngleSlider(
    title: String,
    caption: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Caption(caption)
        }
        Text(valueText, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
    Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled)
}

/** A setting shown as a percentage of its default. */
@Composable
private fun PercentSlider(label: String, value: Float, default: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) =
    ValueSlider(label, "${(value / default * 100f).roundToInt()}%", value, range, onChange = onChange)

@Composable
private fun ValueSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
) {
    Row {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(valueText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled)
}

@Composable
private fun <T> Choice(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    enabled: (T) -> Boolean = { true },
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, label) in options) {
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
                enabled = enabled(value),
            )
        }
    }
}
