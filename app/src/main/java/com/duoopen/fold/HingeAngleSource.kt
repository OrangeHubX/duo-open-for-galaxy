package com.duoopen.fold

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reports the foldable's hinge angle in degrees (0 = closed, 180 = flat).
 *
 * Every sensor that looks like a real hinge *angle* is registered — the
 * platform `TYPE_HINGE_ANGLE` (non-wake-up first) plus vendor sensors that
 * mention the hinge/fold and report a ~180° range — and the finest one that
 * actually delivers events drives [onAngle]. Posture flags (0/1 range) are
 * skipped, and a sensor the OEM has permission-gated (Samsung's
 * `folding_angle` needs `com.samsung.permission.SSENSOR`) is logged and
 * ignored instead of crashing the registration.
 *
 * Some devices only expose three stops — the Galaxy Z Fold 7 and earlier
 * report 0 / 90 / 180 with `resolution 90` — which is [isCoarse]; callers
 * then play a timed fold per stop change instead of tracking the hinge.
 *
 * The sensors are on-change, so nothing arrives while the hinge is still
 * (and on the OnePlus Open nothing arrives on registration either).
 */
class HingeAngleSource(
    context: Context,
    private val onAngle: (Float) -> Unit,
) : SensorEventListener {

    private class Stats(val sensor: Sensor) {
        var events = 0
        var last = Float.NaN
        var registered = false
        /** Distinct readings seen (rounded), capped: three or fewer over many events means stops only. */
        val distinct = LinkedHashSet<Int>()
        val resolution: Float
            get() = sensor.resolution.takeIf { it.isFinite() && it > 0f } ?: 1f
        val isStandard: Boolean get() = sensor.type == Sensor.TYPE_HINGE_ANGLE

        fun observe(value: Float) {
            events++
            last = value
            if (distinct.size < MAX_DISTINCT) distinct += value.roundToInt()
        }

        /** Only ever 0/90/180 (±2°) after enough events, or declared so. */
        val looksCoarse: Boolean
            get() = resolution >= COARSE_RESOLUTION ||
                (events >= COARSE_MIN_EVENTS && distinct.size <= 3 &&
                    distinct.all { v -> STOPS.any { abs(v - it) <= 2 } })
    }

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val candidates: List<Stats> = discover().map(::Stats)

    /** Candidate sensors, best first. */
    val sensors: List<Sensor> get() = candidates.map { it.sensor }

    /** The sensor currently driving the effect, else the best guess before any reports. */
    val sensor: Sensor? get() = activeSensor ?: candidates.firstOrNull()?.sensor

    /** Null until a candidate has reported. */
    var activeSensor: Sensor? = null
        private set

    /** Latest angle from the active sensor. NaN until the first reading. */
    var lastAngle: Float = Float.NaN
        private set

    /** Smoothed event rate over the last ~half second; 0 at rest. */
    var rateHz: Float = 0f
        private set

    /** True when the best sensor we can get only reports the 0/90/180 stops. */
    val isCoarse: Boolean
        get() = !externalActive && (active ?: candidates.firstOrNull())?.looksCoarse == true

    /** Stops only, and no continuous source standing by either. */
    val stopsOnly: Boolean get() = isCoarse && !externalFeedRunning

    private var active: Stats? = null
    private var started = false

    /**
     * A continuous angle fed from outside the sensor framework (Shizuku mode:
     * Samsung's sensor HAL logs it). While fresh it overrides the sensors,
     * which on a Galaxy Z Fold only give 0/90/180.
     */
    var externalActive = false
        private set
    private var externalLastUptime = 0L

    /** Fills in the hinge between the external source's sparse readings. */
    private val tracker = HingeTracker()

    /**
     * @param source which of Samsung's hinge sensors logged it
     *   ([HingeTracker.SOURCE_FOLDING], [HingeTracker.SOURCE_LID]) or
     *   [HingeTracker.SOURCE_OTHER].
     */
    fun feedExternal(angle: Float, sampleUptimeMs: Long = SystemClock.uptimeMillis(), source: Int = HingeTracker.SOURCE_OTHER) {
        // Closed, the sensors' last words are late log lines.
        if (!angle.isFinite() || (posture == Posture.CLOSED && !closedOverride)) return
        val now = SystemClock.uptimeMillis()
        val value = angle.coerceIn(0f, 180f)
        if (!externalActive) tracker.reset(lastAngle.takeIf { it.isFinite() } ?: value, now * 1_000_000L)
        externalActive = true
        externalLastUptime = now
        lastEventUptime = now
        tickRate(now)
        tracker.add(value, sampleUptimeMs.coerceIn(now - MAX_SAMPLE_AGE_MS, now) * 1_000_000L, source)
        lastAngle = value
        onAngle(lastAngle)
    }

    fun clearExternal() {
        externalActive = false
    }

    /**
     * The hinge angle at [nowNanos] (System.nanoTime() clock): between the
     * external source's readings, where the hinge has got to since; otherwise
     * just the latest reading.
     */
    fun estimate(nowNanos: Long = System.nanoTime()): Float =
        if (externalActive) tracker.estimate(nowNanos).takeIf { it.isFinite() } ?: lastAngle else lastAngle

    /**
     * Whether the hinge has surely reached [estimate] at [nowNanos]: moving at
     * its measured speed and well short of where the next reading is due, so
     * not just gliding on in the hope that it kept going.
     */
    fun estimateIsSure(nowNanos: Long = System.nanoTime()): Boolean = externalActive && tracker.cruising(nowNanos)

    /** Coarse fold posture from the system's device state, where the platform exposes one. */
    enum class Posture(val angle: Float) { CLOSED(0f), TENT(45f), HALF_OPENED(90f), OPENED(180f) }

    /** Latest posture, or null if the device state isn't available. */
    var posture: Posture? = null
        private set

    /** A continuous source is running, even if the hinge is still and it has nothing to say. */
    var externalFeedRunning = false

    /**
     * The system has been told the phone is shut before it is (to light the
     * cover screen early), so a CLOSED posture says nothing about the hinge.
     */
    var closedOverride = false

    /**
     * The early cover switch has handed the posture back. When it did so
     * because the phone is [shut], settle on 0° (the hinge sensors' last log
     * line is a few degrees short of it, and the posture, CLOSED all along,
     * won't change to say so).
     */
    fun endClosedOverride(shut: Boolean = false) {
        if (!closedOverride) return
        closedOverride = false
        if (!shut || posture != Posture.CLOSED) return
        externalActive = false
        lastEventUptime = SystemClock.uptimeMillis()
        lastAngle = Posture.CLOSED.angle
        tracker.reset(lastAngle, lastEventUptime * 1_000_000L)
        onAngle(lastAngle)
    }

    /**
     * Hinge angle at which a close hands over to the cover screen, when the app
     * moves that earlier than the system does; NaN otherwise.
     */
    var coverTakeoverHinge = Float.NaN

    /**
     * Hinge angle at which an open hands over to the inner screen, when the
     * app moves that earlier than the system does; NaN otherwise.
     */
    var innerTakeoverHinge = Float.NaN

    /** The system has been told the phone is half open before it is (to light the inner screen early). */
    var openOverride = false

    private var learnedSwapHinge = Float.NaN

    /**
     * Hinge angle at which this phone hands over from the cover to the inner
     * panel: learned from the device state when there is one (≈85° on a Z
     * Fold 8 Ultra), else the OnePlus Open's ~20°.
     */
    val panelSwapHinge: Float
        get() = when {
            innerTakeoverHinge.isFinite() -> innerTakeoverHinge
            learnedSwapHinge.isFinite() -> learnedSwapHinge
            posture != null -> POSTURE_SWAP_HINGE
            else -> DuoShader.PANEL_ON_HINGE
        }

    /**
     * The device state changes well before a stops-only sensor does (on a Z Fold
     * 8 Ultra TENT arrives ~0.4 s before the first 90° report, and a slow open
     * may never report 90° before the panels swap). CLOSED is authoritative
     * and ends any external feed, whose last reading would otherwise linger;
     * the open postures stand in for the angle only while no continuous
     * source is live.
     */
    fun feedPosture(p: Posture) {
        if (p == posture) return
        val at = estimate()
        if (p == Posture.HALF_OPENED && posture == Posture.TENT && externalActive && !openOverride && at in 30f..140f) {
            // Opening: this is where the panels swap. Learn it so the cover
            // can be fully frosted right there rather than at a guess.
            learnedSwapHinge = if (learnedSwapHinge.isNaN()) at else learnedSwapHinge + (at - learnedSwapHinge) * 0.3f
        }
        posture = p
        if (p == Posture.CLOSED && closedOverride) {
            // Our own early switch to the cover: the hinge is still mid-close.
            return
        } else if (p == Posture.CLOSED) {
            externalActive = false
        } else if (externalFeedRunning) {
            // Real angles are on their way (Samsung's sensor log covers every
            // posture); a stand-in would only make the frost jump.
            return
        } else if (p == Posture.TENT) {
            externalActive = false
        } else if (externalActive && SystemClock.uptimeMillis() - externalLastUptime < EXTERNAL_STALE_MS) {
            return
        }
        lastEventUptime = SystemClock.uptimeMillis()
        lastAngle = p.angle
        tracker.reset(lastAngle, lastEventUptime * 1_000_000L)
        onAngle(lastAngle)
    }
    private var lastEventUptime = 0L
    private var rateWindowStart = 0L
    private var rateWindowCount = 0

    /** Milliseconds since the last reading, or [Long.MAX_VALUE] if none yet. */
    fun lastEventAgeMs(): Long =
        if (lastEventUptime == 0L) Long.MAX_VALUE else SystemClock.uptimeMillis() - lastEventUptime

    /** One line for the UI: which sensor, how fine, how fast, what it says. */
    fun statusText(): String {
        if (externalActive || externalFeedRunning) {
            val raw = if (lastAngle.isNaN()) "—" else "%.0f°".format(lastAngle)
            val age = lastEventAgeMs()
            val ageText = if (age == Long.MAX_VALUE) "none yet" else "${age / 1000} s ago"
            return "Samsung hinge sensors via Shizuku, tracked between readings · last reading $raw, $ageText"
        }
        val s = active ?: candidates.firstOrNull() ?: return "No hinge sensor found on this device"
        val res = if (s.resolution >= 1f) "${s.resolution.roundToInt()}°" else "%.2f°".format(s.resolution)
        val rate = if (rateHz > 0f) "%.0f Hz".format(rateHz) else "idle"
        val raw = if (lastAngle.isNaN()) "—" else "%.1f°".format(lastAngle)
        val age = lastEventAgeMs()
        val ageText = if (age == Long.MAX_VALUE) "no events yet" else "${age} ms ago"
        return "${s.sensor.name.trim()} · res $res · $rate · raw $raw · $ageText"
    }

    /** Full dump for a bug report: every fold-related sensor and what it did. */
    fun report(): String = buildString {
        appendLine("Duo Open hinge sensor report")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) android=${Build.VERSION.RELEASE}")
        appendLine("active=${activeSensor?.name ?: "none"} coarse=$isCoarse rate=${"%.1f".format(rateHz)}Hz")
        appendLine("candidates:")
        if (candidates.isEmpty()) appendLine("- (none)")
        for (c in candidates) {
            val s = c.sensor
            appendLine(
                "- ${s.name} type=${s.type} (${s.stringType}) vendor=${s.vendor} wakeUp=${s.isWakeUpSensor} " +
                    "res=${s.resolution} range=${s.maximumRange} mode=${s.reportingMode} minDelay=${s.minDelay}us " +
                    "registered=${c.registered} events=${c.events} distinct=${c.distinct} last=${c.last}",
            )
        }
        val others = allSensors().filter { s -> isFoldRelated(s) && candidates.none { it.sensor == s } }
        if (others.isNotEmpty()) {
            appendLine("other fold-related sensors (not angle candidates):")
            for (s in others) {
                appendLine("- ${s.name} type=${s.type} (${s.stringType}) range=${s.maximumRange} wakeUp=${s.isWakeUpSensor}")
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        val sm = sensorManager ?: return
        if (candidates.isEmpty()) {
            Log.w(TAG, "no hinge angle sensor found")
            return
        }
        for (c in candidates) {
            // Vendor sensors can be permission-gated (Samsung's folding_angle
            // needs com.samsung.permission.SSENSOR); that throws instead of
            // returning false.
            c.registered = try {
                sm.registerListener(this, c.sensor, SAMPLING_PERIOD_US)
            } catch (e: SecurityException) {
                Log.w(TAG, "register denied for ${c.sensor.name}: ${e.message}")
                false
            }
            Log.i(TAG, "candidate ${c.sensor.name} type=${c.sensor.stringType} res=${c.sensor.resolution} wakeUp=${c.sensor.isWakeUpSensor} registered=${c.registered}")
        }
    }

    fun stop() {
        if (!started) return
        started = false
        sensorManager?.unregisterListener(this)
        for (c in candidates) c.registered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val value = event.values.firstOrNull() ?: return
        val stats = candidates.firstOrNull { it.sensor == event.sensor } ?: return
        // An external continuous source wins while it's alive; if it goes
        // quiet the sensors take over again.
        if (externalActive) {
            if (SystemClock.uptimeMillis() - externalLastUptime < EXTERNAL_STALE_MS) return
            externalActive = false
        }
        // Not an angle in degrees (state code, radians, normalized): ignore.
        if (!value.isFinite() || value < -PLAUSIBLE_SLACK || value > 180f + PLAUSIBLE_SLACK) return
        stats.observe(value)
        if (choose() !== stats) return
        // A stops-only sensor knows less than the device state, reports later,
        // and on a Z Fold 8 Ultra even sends a stale 0° mid-TENT; with a
        // posture available it only feeds the statistics.
        if (posture != null && stats.looksCoarse) return

        val now = SystemClock.uptimeMillis()
        lastEventUptime = now
        tickRate(now)
        lastAngle = value.coerceIn(0f, 180f)
        tracker.reset(lastAngle, now * 1_000_000L)
        onAngle(lastAngle)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Finest reporting sensor wins, the standard type on ties; sticky once
     * chosen unless a strictly finer one starts reporting, so a slow vendor
     * sensor can't flip-flop with the platform one mid-fold.
     */
    private fun choose(): Stats? {
        val reporting = candidates.filter { it.events > 0 }
        if (reporting.isEmpty()) return null
        val best = reporting.minWithOrNull(compareBy<Stats> { it.resolution }.thenBy { !it.isStandard })!!
        val current = active
        if (current == null || best.resolution < current.resolution) {
            if (current !== best) Log.i(TAG, "hinge angle source: ${best.sensor.name} (res ${best.resolution})")
            active = best
            activeSensor = best.sensor
            return best
        }
        return current
    }

    private fun tickRate(now: Long) {
        if (rateWindowStart == 0L || now - lastEventUptime > 1_000L) {
            rateWindowStart = now
            rateWindowCount = 0
        }
        rateWindowCount++
        val elapsed = now - rateWindowStart
        if (elapsed >= 500L) {
            rateHz = rateWindowCount * 1000f / elapsed
            rateWindowStart = now
            rateWindowCount = 0
        }
    }

    private fun allSensors(): List<Sensor> =
        runCatching { sensorManager?.getSensorList(Sensor.TYPE_ALL) }.getOrNull().orEmpty()

    private fun discover(): List<Sensor> {
        val all = allSensors()
        val standard = all.filter { it.type == Sensor.TYPE_HINGE_ANGLE }
            .ifEmpty { listOfNotNull(sensorManager?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)) }
            .sortedBy { it.isWakeUpSensor }
        val vendor = all.filter { it.type >= Sensor.TYPE_DEVICE_PRIVATE_BASE && isAngleCandidate(it) }
        return (standard + vendor).distinctBy { "${it.type}|${it.name}|${it.isWakeUpSensor}" }
    }

    /** A vendor sensor that reports a hinge *angle*: named for it, with a ~180° range. */
    private fun isAngleCandidate(s: Sensor): Boolean {
        if (!isFoldRelated(s)) return false
        val range = s.maximumRange
        if (!range.isFinite() || range !in 150f..360f) return false
        return s.reportingMode == Sensor.REPORTING_MODE_CONTINUOUS ||
            s.reportingMode == Sensor.REPORTING_MODE_ON_CHANGE
    }

    private fun isFoldRelated(s: Sensor): Boolean {
        val text = "${s.name} ${s.stringType}".lowercase()
        return text.contains("hinge") || text.contains("fold")
    }

    private companion object {
        const val TAG = "DuoHinge"
        /** ~125 Hz ceiling; the sensors only report on change anyway. */
        const val SAMPLING_PERIOD_US = 8_000
        const val PLAUSIBLE_SLACK = 5f
        const val COARSE_RESOLUTION = 45f
        const val COARSE_MIN_EVENTS = 6
        const val MAX_DISTINCT = 8
        const val EXTERNAL_STALE_MS = 3_000L
        /** A reading claiming to be older than this is mistimed; clamp it. */
        const val MAX_SAMPLE_AGE_MS = 300L
        /** Where a device-state phone swaps panels before it has been seen to (Z Fold 8 Ultra: HALF_OPENED at ~83–86°). */
        const val POSTURE_SWAP_HINGE = 85f
        val STOPS = intArrayOf(0, 90, 180)
    }
}
