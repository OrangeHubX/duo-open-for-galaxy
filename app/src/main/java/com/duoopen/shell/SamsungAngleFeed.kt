package com.duoopen.shell

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.duoopen.fold.HingeAngleSource

/**
 * The real hinge angle on Samsung foldables, where the public sensor only
 * reports 0/90/180. The Shizuku-side log reader ([DuoShellService]) follows
 * the sensor HAL's hinge-sensor log lines — a reading every 10–11° per sensor,
 * two sensors interleaved, over the whole fold, whatever is on screen — and
 * each one is fed into [HingeAngleSource], whose tracker fills in the hinge
 * between them.
 *
 * Requires: Shizuku authorised.
 */
class SamsungAngleFeed(
    private val handler: Handler,
    private val hinge: HingeAngleSource,
    /** The hall sensor says the phone has just shut. */
    private val onShut: () -> Unit,
) {
    private var running = false
    private var lastCallbackUptime = 0L
    private var startedAt = 0L

    /** One line for the UI. */
    @Volatile
    var status: String = "Idle"
        private set

    val active: Boolean get() = running

    private val statusTick = object : Runnable {
        override fun run() {
            if (!running) return
            val b = ShizukuBridge.angleStatus()
            val age = if (lastCallbackUptime == 0L) -1 else SystemClock.uptimeMillis() - lastCallbackUptime
            status = if (b == null) "Reader unreachable" else
                "Reader ${b.getString("state")}: ${b.getInt("parsed")} readings" +
                    (if (age >= 0) " · last ${age / 1000} s ago" else " · fold the phone to see one")
            // Nothing is logged while the hinge is still; let the posture and
            // the stops sensor speak for it until it moves again.
            if (age > STALE_MS || (age < 0 && SystemClock.uptimeMillis() - startedAt > STALE_MS)) hinge.clearExternal()
            handler.postDelayed(this, 1_000)
        }
    }

    fun start() {
        if (running) return
        if (!ShizukuBridge.ready) { status = "Shizuku not ready"; return }
        if (!ShizukuBridge.startAngles { angle, at, source -> handler.post { onAngle(angle, at, source) } }) {
            status = "Couldn't start the log reader"
            return
        }
        running = true
        startedAt = SystemClock.uptimeMillis()
        status = "Starting"
        Log.i(TAG, "angle feed started")
        handler.postDelayed(statusTick, 1_000)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(statusTick)
        ShizukuBridge.stopAngles()
        hinge.externalFeedRunning = false
        hinge.clearExternal()
        status = "Stopped"
        Log.i(TAG, "angle feed stopped")
    }

    private fun onAngle(angle: Float, sampleUptimeMs: Long, source: Int) {
        if (!running) return
        when (source) {
            ShellProtocol.SOURCE_SHUT -> return onShut()
            ShellProtocol.SOURCE_UNSHUT -> return
        }
        lastCallbackUptime = SystemClock.uptimeMillis()
        // Proven to work: real angles come from every posture, so the device
        // state needn't stand in for them from now on.
        hinge.externalFeedRunning = true
        hinge.feedExternal(angle, sampleUptimeMs, source)
    }

    private companion object {
        const val TAG = "DuoAngleFeed"
        const val STALE_MS = 2_500L
    }
}
