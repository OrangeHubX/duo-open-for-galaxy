package com.duoopen.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt

private val Glass = Color.White.copy(alpha = 0.14f)
private val GlassEdge = Color.White.copy(alpha = 0.22f)
private val Dim = Color.White.copy(alpha = 0.72f)
private val Ink = Color(0xFF0D0A1C)
private val Done = Color(0xFF7CE0B0)

/** Where setup stands; drives the card at the bottom of the home screen. */
data class SetupState(
    val overlayEnabled: Boolean,
    val shizukuReady: Boolean,
    val shizukuInstalled: Boolean,
    /** A Samsung foldable, which this build is made for. */
    val supportedDevice: Boolean,
)

/**
 * The app's home screen, which doubles as the surface the fold is previewed
 * on: soft colour fields, a lock-screen style clock, a live hinge readout,
 * and the two setup steps until they are done.
 */
@Composable
fun HomePreview(
    hingeAngle: Float,
    paneTilt: Float,
    simulated: Boolean,
    tracked: Boolean,
    setup: SetupState,
    onEnableOverlay: () -> Unit,
    onShizuku: () -> Unit,
    onTest: () -> Unit,
    onTune: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(Ink)) {
        Backdrop()
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.35f),
                        0.35f to Color.Transparent,
                        0.7f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.55f),
                    )
                )
        )

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Clock()

            Spacer(Modifier.weight(1f))

            HingeReadout(hingeAngle, paneTilt, simulated, tracked)

            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Hinge", if (hingeAngle.isNaN()) "—" else "${hingeAngle.roundToInt()}°")
                StatTile("Pane tilt", "%.1f°".format(paneTilt))
                StatTile("Tracking", if (tracked) "Live" else "Stops")
            }

            Spacer(Modifier.weight(1f))

            if (!setup.overlayEnabled || !setup.shizukuReady || !setup.supportedDevice) {
                SetupCard(setup, onEnableOverlay, onShizuku)
                Spacer(Modifier.height(14.dp))
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = if (setup.overlayEnabled) onTest else onEnableOverlay,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF14121F)),
                ) {
                    Text(if (setup.overlayEnabled) "Play the fold" else "Turn on")
                }
                FilledTonalButton(
                    onClick = onTune,
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Glass, contentColor = Color.White),
                ) {
                    Text("Settings")
                }
            }
        }
    }
}

@Composable
private fun SetupCard(setup: SetupState, onEnableOverlay: () -> Unit, onShizuku: () -> Unit) {
    Column(
        Modifier
            .widthIn(max = 460.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Glass)
            .border(1.dp, GlassEdge, RoundedCornerShape(24.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        if (!setup.supportedDevice) {
            Text(
                "Duo Open is made for Samsung Galaxy Z Fold phones. On this device it may not work.",
                color = Color(0xFFFFC66B),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(6.dp))
        }
        SetupStep(
            n = 1,
            title = "Whole-screen fold",
            detail = if (setup.overlayEnabled) "On" else "Accessibility service, so the fold plays over everything",
            done = setup.overlayEnabled,
            action = "Turn on",
            onAction = onEnableOverlay,
        )
        SetupStep(
            n = 2,
            title = "Live hinge tracking",
            detail = when {
                setup.shizukuReady -> "Shizuku connected"
                setup.shizukuInstalled -> "Authorise Duo Open in Shizuku to follow the hinge, not just 0° / 90° / 180°"
                else -> "Needs the Shizuku app, to follow the hinge rather than just 0° / 90° / 180°"
            },
            done = setup.shizukuReady,
            action = if (setup.shizukuInstalled) "Authorise" else "Get Shizuku",
            onAction = onShizuku,
        )
    }
}

@Composable
private fun SetupStep(n: Int, title: String, detail: String, done: Boolean, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (done) "✓" else "$n",
            color = if (done) Done else Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.widthIn(min = 22.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Dim, fontSize = 12.sp)
        }
        if (!done) TextButton(onClick = onAction) { Text(action, color = Color.White) }
    }
}

/**
 * Soft colour fields over deep ink plus a fine dot grid: the fields show the
 * darkening, the dots show the frost smearing.
 */
@Composable
private fun Backdrop() {
    Canvas(Modifier.fillMaxSize()) {
        // Cover the screen with a centred square, like a wallpaper crop.
        val side = max(size.width, size.height)
        val ox = (size.width - side) / 2f
        val oy = (size.height - side) / 2f
        for ((x, y, r, color) in BLOBS) {
            val c = Offset(ox + x * side, oy + y * side)
            drawCircle(
                brush = Brush.radialGradient(listOf(color.copy(alpha = 0.86f), color.copy(alpha = 0f)), center = c, radius = r * side),
                radius = r * side,
                center = c,
            )
        }
        val step = side / 36f
        val dot = side / 620f
        val dotColor = Color.White.copy(alpha = 0.22f)
        var y = oy + step * 0.5f
        while (y < size.height) {
            var x = ox + step * 0.5f
            while (x < size.width) {
                if (x >= 0f && y >= 0f) drawCircle(dotColor, dot, Offset(x, y))
                x += step
            }
            y += step
        }
    }
}

private data class Blob(val x: Float, val y: Float, val radius: Float, val color: Color)

private val BLOBS = listOf(
    Blob(0.18f, 0.22f, 0.55f, Color(0xFF7B5CFF)),
    Blob(0.86f, 0.20f, 0.50f, Color(0xFFF0564A)),
    Blob(0.50f, 0.52f, 0.42f, Color(0xFFC44EDD)),
    Blob(0.20f, 0.86f, 0.50f, Color(0xFF19C3B2)),
    Blob(0.84f, 0.84f, 0.48f, Color(0xFFFFB020)),
)

@Composable
private fun Clock() {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(15_000)
        }
    }
    Text(
        now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")),
        color = Dim,
        fontSize = 16.sp,
    )
    Text(
        now.format(DateTimeFormatter.ofPattern("H:mm")),
        color = Color.White,
        fontSize = 96.sp,
        fontWeight = FontWeight.Light,
        letterSpacing = (-2).sp,
    )
}

@Composable
private fun HingeReadout(hingeAngle: Float, paneTilt: Float, simulated: Boolean, tracked: Boolean) {
    val hint = when {
        simulated -> "Simulated hinge — Settings ▸ drag the slider"
        hingeAngle.isNaN() -> "Waiting for the hinge…"
        paneTilt < 0.05f -> "Close the phone partway, then open it"
        else -> if (tracked) "Following your hand" else "Keep opening"
    }
    Box(
        Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Glass)
            .border(1.dp, GlassEdge, RoundedCornerShape(28.dp))
            .padding(vertical = 22.dp, horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "DUO OPEN",
                color = Dim,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 3.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (hingeAngle.isNaN()) "—" else "${hingeAngle.roundToInt()}°",
                color = Color.White,
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(hint, color = Dim, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun StatTile(label: String, value: String) {
    Column(
        Modifier
            .size(width = 104.dp, height = 72.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Glass)
            .border(1.dp, GlassEdge, RoundedCornerShape(20.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Dim, fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}
