package com.duoopen.overlay

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import com.duoopen.fold.HingeAngleSource
import com.duoopen.fold.HingeAngleSource.Posture
import com.duoopen.settings.DuoSettings
import com.duoopen.shell.ShizukuBridge

/**
 * Moves the screen hand-overs to the hinge angles the user picked.
 *
 * A Z Fold lights the inner screen at ~85° on the way open, and the cover only
 * once shut on the way down, where nobody sees its half of the fold. With
 * Shizuku's shell rights and the tracked hinge, this tells the system the
 * phone is already in the next posture (a base-state override) as the hinge
 * passes the chosen angle:
 *
 * - **Cover, closing** ([com.duoopen.settings.DuoConfig.coverOnAngle]): reports
 *   CLOSED, so the cover lights early.
 * - **Inner, opening** ([com.duoopen.settings.DuoConfig.innerOnAngle]): reports
 *   HALF_OPENED, so the inner screen lights before ~85°. It can't be made
 *   *later*: the system's own switch at ~85° is a real posture change, and any
 *   real change ends an override.
 *
 * Software only (which screen is used; nothing physical), and kept as short
 * as possible. Each override ends — and the real posture takes over again —
 * once the real posture has caught up (the hall sensor says shut / past
 * ~85°), when the movement is
 * abandoned, after [MAX_HOLD_MS] whatever happens, and when the service or the
 * hinge reader stops. The system does not drop an override if the shell
 * helper dies, so a possibly-left one is remembered and cleared as soon as the
 * helper is back ([recover]).
 */
class EarlyScreenSwitch(
    context: Context,
    private val hinge: HingeAngleSource,
    private val handler: Handler,
    private val closedId: () -> Int?,
    private val halfOpenedId: () -> Int?,
) {
    private enum class Target { COVER, INNER }

    /**
     * Set before an override is requested, cleared only once a release went
     * through. Measured on a Z Fold 8 Ultra: the system does *not* drop the
     * override when the helper process dies, so if the helper (or this app)
     * dies mid-fold this records that one may be left behind, and [recover]
     * cancels it the next time the helper is reachable.
     */
    private val prefs = context.getSharedPreferences("duo_cover_switch", Context.MODE_PRIVATE)
    private var mayBeLeft: Boolean
        get() = prefs.getBoolean("overrideMayBeLeft", false)
        set(value) { prefs.edit().putBoolean("overrideMayBeLeft", value).commit() }

    /** Call when the shell helper (re)connects: clears an override a crash may have left behind. */
    fun recover() {
        if (active != null || !mayBeLeft || !ShizukuBridge.ready) return
        if (ShizukuBridge.overrideBaseState(-1)) {
            mayBeLeft = false
            hinge.endClosedOverride()
            hinge.openOverride = false
            Log.i(TAG, "cleared an override left by an earlier run")
        }
    }

    private var active: Target? = null
    private var previous = Float.NaN

    private val shutRelease = Runnable { release(SHUT) }
    private val timeout = Runnable { release("time limit") }

    /**
     * Readings come ~5° apart, so waiting for one past the threshold switches
     * up to that much late. While a fold is heading for a threshold, the
     * tracked hinge is checked every frame instead.
     */
    private var watching: Target? = null
    private var lastReadingUptime = 0L
    private val watch = Choreographer.FrameCallback { now ->
        val target = watching ?: return@FrameCallback
        watching = null
        if (active != null || !eligible(target)) return@FrameCallback
        val at = hinge.estimate(now)
        if (passed(target, at) && hinge.estimateIsSure(now)) {
            engage(target, at)
        } else if (SystemClock.uptimeMillis() - lastReadingUptime < WATCH_MS) {
            // Not there yet, or only gliding on a guess: look again next frame.
            startWatching(target)
        }
    }

    private fun startWatching(target: Target) {
        if (watching != null) return
        watching = target
        Choreographer.getInstance().postFrameCallback(watch)
    }

    private fun coverAngle() = DuoSettings.config.value.coverOnAngle
    private fun innerAngle() = DuoSettings.config.value.innerOnAngle

    /** The inner takeover is set earlier than the system's own. */
    private fun innerSet() = innerAngle() > 0f && innerAngle() < SYSTEM_INNER_ANGLE

    private fun passed(target: Target, angle: Float): Boolean =
        if (target == Target.COVER) angle <= coverAngle() else angle >= innerAngle()

    private fun eligible(target: Target): Boolean {
        if (!hinge.externalActive || !ShizukuBridge.ready) return false
        val p = hinge.posture
        return when (target) {
            Target.COVER -> coverAngle() > 0f && (p == Posture.HALF_OPENED || p == Posture.OPENED)
            Target.INNER -> innerSet() && p == Posture.TENT
        }
    }

    fun onAngle(angle: Float) {
        val prev = previous
        previous = angle
        if (!angle.isFinite()) return
        val ready = hinge.externalFeedRunning
        hinge.coverTakeoverHinge = if (coverAngle() > 0f && ready) coverAngle() else Float.NaN
        hinge.innerTakeoverHinge = if (innerSet() && ready) innerAngle() else Float.NaN
        when (active) {
            Target.COVER -> {
                when {
                    coverAngle() <= 0f -> release("turned off")
                    angle >= coverAngle() + ABANDON_MARGIN -> release("close abandoned")
                    // Normally [onShut] ends it. A reading this low is past the
                    // hall sensor too; give the real posture a moment to agree.
                    // (Not sooner: closing, a Z Fold stays HALF_OPENED until
                    // shut, so letting go early lights the inner screen again.)
                    angle <= SHUT_ANGLE -> if (!handler.hasCallbacks(shutRelease)) handler.postDelayed(shutRelease, SHUT_SETTLE_MS)
                    else -> handler.removeCallbacks(shutRelease)
                }
                return
            }
            Target.INNER -> {
                when {
                    !innerSet() -> release("turned off")
                    angle <= innerAngle() - ABANDON_MARGIN -> release("open abandoned")
                    // Past the system's own switch the real posture agrees.
                    angle >= SYSTEM_INNER_ANGLE + ABANDON_MARGIN -> release("opened")
                }
                return
            }
            null -> Unit
        }
        lastReadingUptime = SystemClock.uptimeMillis()
        if (!prev.isFinite() || angle == prev) return
        val target = if (angle < prev) Target.COVER else Target.INNER
        if (!eligible(target)) return
        val threshold = if (target == Target.COVER) coverAngle() else innerAngle()
        when {
            // A reading already past it (a fast fold, or the frame check was too careful).
            passed(target, angle) && !passed(target, prev) -> engage(target, angle)
            !passed(target, angle) && kotlin.math.abs(angle - threshold) <= WATCH_RANGE -> startWatching(target)
        }
    }

    /** The hall sensor says the phone is shut: the real posture is CLOSED now too. */
    fun onShut() {
        if (active != null) release(SHUT)
    }

    private fun engage(target: Target, angle: Float) {
        val id = (if (target == Target.COVER) closedId() else halfOpenedId()) ?: return
        if (target == Target.COVER) hinge.closedOverride = true else hinge.openOverride = true
        mayBeLeft = true
        if (ShizukuBridge.overrideBaseState(id)) {
            active = target
            handler.postDelayed(timeout, MAX_HOLD_MS)
            Log.i(TAG, "${if (target == Target.COVER) "cover" else "inner screen"} on early at hinge=$angle")
        } else {
            hinge.closedOverride = false
            hinge.openOverride = false
            mayBeLeft = false
        }
    }

    /** Hands the posture back to the system. */
    fun release(why: String) {
        val target = active ?: return
        active = null
        handler.removeCallbacks(shutRelease)
        handler.removeCallbacks(timeout)
        // If the helper is gone, mayBeLeft stays set and recover() retries.
        val released = ShizukuBridge.overrideBaseState(-1)
        if (released) mayBeLeft = false
        val at = previous
        if (target == Target.COVER) hinge.endClosedOverride(shut = why == SHUT) else hinge.openOverride = false
        Log.i(TAG, "${target.name.lowercase()} override ${if (released) "released" else "release FAILED, will retry"} ($why) at hinge=$at")
    }

    private companion object {
        const val TAG = "DuoScreenSwitch"
        const val SHUT = "shut"
        /** Back this far past the threshold: the fold was abandoned. */
        const val ABANDON_MARGIN = 10f
        /** Where a Z Fold lights the inner screen by itself on the way open. */
        const val SYSTEM_INNER_ANGLE = 85f
        /** At or below this the phone is shut (the HAL rests at 0–2°). */
        const val SHUT_ANGLE = 4f
        const val SHUT_SETTLE_MS = 400L
        /** Start checking every frame once a fold is this close to the threshold. */
        const val WATCH_RANGE = 30f
        /** ...and stop if no reading has come for this long (the hinge stopped). */
        const val WATCH_MS = 1_200L
        /** However the fold goes, the system gets its posture back after this. */
        const val MAX_HOLD_MS = 20_000L
    }
}
