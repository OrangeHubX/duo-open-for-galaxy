package com.duoopen.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.duoopen.fold.DuoShader
import com.duoopen.fold.HingeAngleSource
import com.duoopen.fold.TiltFollower
import com.duoopen.fold.isInnerPanel
import com.duoopen.settings.DuoSettings
import com.duoopen.shell.ShizukuBridge
import android.view.SurfaceControl
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The fold effect on one lit display: screenshot → touch-transparent overlay
 * drawn through the shader → tracks the hinge → removed at rest.
 *
 * One engine runs per live built-in panel (see [FoldOverlayService]). On a
 * phone that only ever lights one panel (OnePlus Open) that's a single engine
 * whose display flips between the cover and inner modes mid-fold; on a phone
 * that keeps both panels on, the cover and inner engines run side by side and
 * the effect hands over with no gap.
 *
 * Opening: leaving closed → capture on the cover → frost sweeps in; the inner
 * panel lights up → capture (retried while the panel is still black) → frost
 * clears to flat. Closing is the reverse. A frozen snapshot for the fraction
 * of a second of a fold is invisible in practice; if the hinge stops partway
 * (tent) the overlay fades out so live content isn't hidden.
 *
 * On a stops-only hinge sensor (Galaxy Z Fold 7 and earlier: 0/90/180) the
 * overlay can't follow the hinge, so each stop change plays a timed ease
 * instead — the same path the close-onto-cover already uses.
 *
 * Two ways to draw ([FoldSurface]): a warped screenshot (default), or — when
 * [com.duoopen.settings.DuoConfig.liveBlur] is on and the system allows
 * cross-window blur — the system blur over the live screen, which needs no
 * capture at all and so starts the instant a phase begins.
 */
class PanelEngine(
    private val service: AccessibilityService,
    val display: Display,
    private val hinge: HingeAngleSource,
    private val handler: Handler,
    private val scope: CoroutineScope,
    /** True when another engine is live on an inner panel right now. */
    private val hasLiveInnerElsewhere: () -> Boolean,
    private val onShowingChanged: () -> Unit,
    /** Last capture of each panel kind, shared by all engines (see [SnapshotCache]). */
    private val cache: SnapshotCache,
) {
    private enum class Phase { IDLE, CAPTURING, SHOWING }

    private val displayId = display.displayId
    private val windowManager: WindowManager by lazy {
        service.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            .getSystemService(WindowManager::class.java)
    }

    private var phase = Phase.IDLE
    private var surface: FoldSurface? = null
    private var follower: TiltFollower? = null
    /** Smooths the hinge angle for [handoverDim]. */
    private var dimFollower: TiltFollower? = null

    /** Which panel this display is driving; the effect restarts whenever it flips mid-fold. */
    var innerPanel = display.isInnerPanel()
        private set
    /** Set at a rest pose so leaving it plays once. */
    private var restArmed = true
    private var panelSwitched = false
    private var lastHingeMoveMs = 0L
    private var demoRunning = false
    /** Bumped per capture so a late or hung screenshot can't act on a newer phase. */
    private var captureGen = 0
    /** Attempt number of the capture in flight, so only its own timeout can give up. */
    private var captureAttempt = 0
    /** Overlay is resolving on a timer, ignoring the hinge (see [show]). */
    private var timedResolve = false
    /** Showing a cached picture of this panel while a fresh capture is in flight. */
    private var bridging = false
    /** Live re-capture loop (Shizuku mode) is scheduled. */
    private var liveLoop = false
    /** The overlay being put up is the bridge picture: show it at once, no fade over the waking panel. */
    private var presentingBridge = false
    /** Hinge angle at which the inner panel is fully frosted: where it lit up on this open. */
    private var innerFullAt = restInnerFullAt()
    /** Hinge angle at which the cover panel is fully frosted: where it lit up on this close; NaN = the open's swap angle. */
    private var coverFullAt = Float.NaN

    /** From rest the inner panel frosts fully by the time it would hand over to the cover. */
    private fun restInnerFullAt(): Float = hinge.coverTakeoverHinge.takeIf { it.isFinite() } ?: DuoShader.PANEL_ON_HINGE

    val showing: Boolean get() = phase == Phase.SHOWING

    private val settleCheck = object : Runnable {
        override fun run() {
            val o = surface ?: return
            if (o.tilt < DuoShader.FLAT_EPSILON) return
            val timeout = settleTimeoutMs()
            if (!demoRunning && SystemClock.uptimeMillis() - lastHingeMoveMs >= timeout) {
                stalledAt = hinge.lastAngle
                dismiss(fadeMs = FADE_OUT_STALLED_MS)
            } else {
                handler.postDelayed(this, if (timeout == Long.MAX_VALUE) 500 else 100)
            }
        }
    }

    /**
     * A tracked fold that stops partway keeps its frost as long as the user
     * chose (by default, for as long as it's held). Without tracking the
     * angle is only a guess, so a parked hinge clears soon.
     */
    private fun settleTimeoutMs(): Long {
        if (!hinge.externalFeedRunning) return SETTLE_TIMEOUT_MS
        val hold = DuoSettings.config.value.holdFrostS
        return if (hold <= 0) Long.MAX_VALUE else hold * 1_000L
    }

    /** Hinge angle where a tracked fold stalled and the overlay was let go; NaN otherwise. */
    private var stalledAt = Float.NaN

    /** A timed frost-up that no panel swap has replaced: the fold stalled, let the live screen through. */
    private val peakHold = Runnable {
        if (timedResolve && !demoRunning) dismiss(fadeMs = FADE_OUT_STALLED_MS)
    }

    init {
        // A panel that lights up mid-fold is the second half of a fold in
        // progress; one that is already at rest just waits to leave it.
        val tilt = currentTilt()
        panelSwitched = tilt >= DuoShader.FLAT_EPSILON
        restArmed = !panelSwitched
        if (panelSwitched && innerPanel) innerFullAt = hinge.lastAngle
        if (panelSwitched && !innerPanel) coverFullAt = hinge.lastAngle
        Log.i(TAG, "engine display=$displayId inner=$innerPanel midFold=$panelSwitched")
    }

    /** Whether this cover panel is lit alongside an inner panel (no swap will hand over). */
    private fun concurrentCover(): Boolean = !innerPanel && hasLiveInnerElsewhere()

    private fun tiltFor(angle: Float): Float {
        if (angle.isNaN()) return 0f
        val config = DuoSettings.config.value
        return when {
            innerPanel -> DuoShader.tiltForHinge(angle, config, innerFullAt)
            concurrentCover() -> DuoShader.concurrentCoverTiltForHinge(angle, config)
            else -> DuoShader.coverTiltForHinge(angle, config, coverFullAt.takeIf { it.isFinite() } ?: hinge.panelSwapHinge)
        }
    }

    private fun currentTilt(): Float = tiltFor(hinge.lastAngle)

    fun onHinge(angle: Float) {
        lastHingeMoveMs = SystemClock.uptimeMillis()
        evaluate()
        dimFollower?.follow { now -> hinge.estimate(now) }
        val tilt = tiltFor(angle)
        if (timedResolve && hinge.externalActive && phase == Phase.SHOWING && !demoRunning &&
            (innerPanel || concurrentCover())
        ) {
            // A continuous angle (Shizuku) arrived mid-ease: follow the hand from here.
            Log.i(TAG, "display $displayId: continuous angle $angle mid-ease; following")
            timedResolve = false
            handler.removeCallbacks(peakHold)
            handler.removeCallbacks(settleCheck)
            handler.postDelayed(settleCheck, SETTLE_TIMEOUT_MS)
            follower?.tauS = TiltFollower.DEFAULT_TAU_S
        }
        if (tilt < DuoShader.FLAT_EPSILON && phase == Phase.SHOWING && !demoRunning) {
            val f = follower
            if (timedResolve && hinge.isCoarse && f != null && f.current > DuoShader.FLAT_EPSILON) {
                // Stops-only sensor: the rest stop (180° / 0°) is the first news
                // that the fold has finished, so clear from here — the frost
                // was held meanwhile. The follower dismisses at flat.
                handler.removeCallbacks(peakHold)
                f.tauS = COARSE_CLEAR_TAU_S
                f.setTarget(0f)
            } else if (hinge.externalActive && !timedResolve && f != null && f.current > DuoShader.FLAT_EPSILON) {
                // Continuous angle: a quick final push can jump 25° in one
                // reading; let the frost clear rather than cut it. The
                // follower dismisses at flat.
                f.setTarget(0f)
            } else {
                // At rest: drop the overlay now rather than easing the last degrees.
                dismiss(fadeMs = FADE_OUT_FLAT_MS)
            }
        } else if (!timedResolve) {
            val f = follower
            if (f != null && hinge.externalActive) {
                // Samsung's readings are ~5° apart: follow the tracked hinge
                // every frame instead of stepping to each reading.
                f.tauS = TiltFollower.FOLLOW_TAU_S
                f.follow { now -> tiltFor(hinge.estimate(now)) }
            } else {
                f?.setTarget(tilt)
            }
        }
    }

    /**
     * Drives the effect from two signals: which panel this display shows and
     * the hinge angle. The picture is flat at the panel's rest pose and fully
     * frosted at the swap, so an open or a close is one continuous frost-up on
     * the first panel and frost-down on the second. A capture starts on
     * leaving rest and again on each swap.
     */
    fun evaluate() {
        if (demoRunning) return
        val inner = display.isInnerPanel()
        if (inner != innerPanel) {
            innerPanel = inner
            panelSwitched = true
            innerFullAt = if (inner) hinge.lastAngle.takeIf { it.isFinite() } ?: DuoShader.PANEL_ON_HINGE else restInnerFullAt()
            coverFullAt = if (inner) Float.NaN else hinge.lastAngle
            if (phase != Phase.IDLE) removeOverlay(retire = true) // old panel's snapshot is meaningless now
        }
        val angle = hinge.lastAngle
        if (angle.isNaN()) return
        val tilt = tiltFor(angle)
        if (tilt < DuoShader.FLAT_EPSILON) {
            armRest()
            return
        }
        if (phase != Phase.IDLE) return
        when {
            panelSwitched -> {
                panelSwitched = false
                restArmed = false
                stalledAt = Float.NaN
                Log.i(TAG, "display $displayId: panel swapped (inner=$inner) at hinge=$angle")
                // The fresh panel may still be lighting up: retry if black.
                startEffect(afterSwap = true)
            }
            // Moving again after a stall: with a continuous angle the fold can pick up where it is.
            stalledAt.isFinite() && hinge.externalActive && abs(angle - stalledAt) >= RESUME_AFTER_STALL_DEG -> {
                stalledAt = Float.NaN
                Log.i(TAG, "display $displayId: moving again after a stall at hinge=$angle")
                startEffect(afterSwap = false)
            }
            restArmed && tilt >= REST_LEAVE_TILT -> {
                restArmed = false
                Log.i(TAG, "display $displayId: leaving rest (inner=$inner) at hinge=$angle")
                startEffect(afterSwap = false)
            }
        }
    }

    /**
     * At a rest pose (flat open, or shut on the cover): the next movement away
     * starts the fold afresh. Called when a reading says so, and when the
     * tracked hinge has carried the frost there — the last reading of an open
     * or a close lands a few degrees short of the end, so the readings alone
     * might never say it.
     */
    private fun armRest() {
        restArmed = true
        panelSwitched = false
        innerFullAt = restInnerFullAt()
        coverFullAt = Float.NaN
        stalledAt = Float.NaN
    }

    /** Live blur needs no capture: the system blurs whatever is on screen. */
    private fun liveMode(): Boolean =
        DuoSettings.config.value.liveBlur &&
            runCatching { windowManager.isCrossWindowBlurEnabled }.getOrDefault(false)

    private fun startEffect(afterSwap: Boolean, startTilt: Float? = null) {
        if (liveMode()) {
            phase = Phase.CAPTURING // same gate as a capture in flight, resolved synchronously
            present(bitmap = null, afterSwap = afterSwap, startTilt = startTilt, t0 = SystemClock.uptimeMillis())
            return
        }
        // Shizuku: a panel that has just switched on gets the overlay at once,
        // in black — what the panel shows anyway while it wakes and apps
        // relaunch or resize on it — and the refresh loop fades it up into the
        // picture once the panel has settled (see [startRefreshLoop]).
        if (afterSwap && startTilt == null && shellCapture()) {
            phase = Phase.CAPTURING
            val bounds = windowManager.maximumWindowMetrics.bounds
            val placeholder = cache.get(innerPanel, bounds.width(), bounds.height()) ?: BLACK_PLACEHOLDER
            presentingBridge = true
            present(placeholder, afterSwap = true, startTilt = null, t0 = SystemClock.uptimeMillis())
            presentingBridge = false
            bridging = phase == Phase.SHOWING
            return
        }
        // A panel that has just switched on takes ~0.3 s to screenshot. Start
        // at once with the picture it showed last time and swap in the fresh
        // capture when it lands — the frost hides the difference.
        if (afterSwap && startTilt == null && DuoSettings.config.value.instantStart) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            val stale = cache.get(innerPanel, bounds.width(), bounds.height())
            if (stale != null) {
                phase = Phase.CAPTURING
                Log.i(TAG, "display $displayId: bridging with a ${cache.ageMs(innerPanel)}ms-old snapshot")
                presentingBridge = true
                present(stale, afterSwap = true, startTilt = null, t0 = SystemClock.uptimeMillis())
                presentingBridge = false
                bridging = phase == Phase.SHOWING
            }
        }
        startCapture(afterSwap, startTilt)
    }

    private fun startCapture(afterSwap: Boolean, startTilt: Float? = null) {
        if (!bridging) phase = Phase.CAPTURING
        capture(gen = ++captureGen, attempt = 1, afterSwap = afterSwap, startTilt = startTilt)
    }

    private fun capture(gen: Int, attempt: Int, afterSwap: Boolean, startTilt: Float?) {
        val t0 = SystemClock.uptimeMillis()
        captureAttempt = attempt
        fun stale() = gen != captureGen || (phase != Phase.CAPTURING && !bridging)
        // The framework refuses captures closer than ~333 ms apart, measured
        // from the previous request — so a slow capture costs no extra wait.
        fun retry() {
            val wait = (t0 + SCREENSHOT_MIN_INTERVAL_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)
            handler.postDelayed({ if (!stale()) capture(gen, attempt + 1, afterSwap, startTilt) }, wait)
        }
        // A screenshot requested as a panel switches off may never call back.
        // Only the latest attempt's timeout counts: an earlier one must not
        // give up on behalf of a retry that is still in flight.
        handler.postDelayed({
            if (!stale() && captureAttempt == attempt) {
                Log.w(TAG, "display $displayId: capture $attempt timed out; giving up")
                if (bridging) {
                    bridging = false // keep playing on the stale picture
                } else {
                    phase = Phase.IDLE
                    demoRunning = false
                }
            }
        }, CAPTURE_TIMEOUT_MS)
        // Shizuku mode: the shell-side capture has no rate limit and takes
        // ~50 ms even on a waking panel. Falls back to the accessibility
        // screenshot if it fails.
        if (shellCapture()) {
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    ShizukuBridge.capture(displayId, excludedLayers(), INITIAL_SHELL_SCALE)
                }
                if (stale()) return@launch
                if (bitmap == null) {
                    Log.i(TAG, "display $displayId: shell capture unavailable; using accessibility screenshot")
                    accessibilityCapture(gen, attempt, afterSwap, startTilt, t0, ::retry, ::stale)
                    return@launch
                }
                if (afterSwap && attempt < MAX_CAPTURE_ATTEMPTS) {
                    val black = withContext(Dispatchers.Default) { isMostlyBlack(bitmap) }
                    if (stale()) return@launch
                    if (black && !demoRunning) {
                        Log.i(TAG, "display $displayId: shell capture $attempt is black after ${SystemClock.uptimeMillis() - t0}ms; retrying")
                        handler.postDelayed({ if (!stale()) capture(gen, attempt + 1, afterSwap, startTilt) }, SHELL_RETRY_MS)
                        return@launch
                    }
                }
                onCaptured(bitmap, afterSwap, startTilt, t0)
            }
            return
        }
        accessibilityCapture(gen, attempt, afterSwap, startTilt, t0, ::retry, ::stale)
    }

    private fun shellCapture(): Boolean = DuoSettings.config.value.shizukuCapture && ShizukuBridge.ready

    /** Our own overlay's layer, so a capture taken while it's up sees the screen beneath it. */
    private fun excludedLayers(): List<SurfaceControl> {
        val view = (surface as? SnapshotSurface)?.view ?: return emptyList()
        return listOfNotNull(rootSurfaceControl(view))
    }

    private fun accessibilityCapture(
        gen: Int,
        attempt: Int,
        afterSwap: Boolean,
        startTilt: Float?,
        t0: Long,
        retry: () -> Unit,
        stale: () -> Boolean,
    ) {
        service.takeScreenshot(displayId, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                buffer.close()
                if (stale()) {
                    Log.i(TAG, "display $displayId: stale capture after ${SystemClock.uptimeMillis() - t0}ms; dropped")
                    bitmap?.recycle()
                    return
                }
                if (bitmap == null) {
                    Log.w(TAG, "display $displayId: screenshot buffer could not be wrapped")
                    if (bridging) bridging = false else phase = Phase.IDLE
                    return
                }
                if (!afterSwap || attempt >= MAX_CAPTURE_ATTEMPTS) {
                    onCaptured(bitmap, afterSwap, startTilt, t0)
                    return
                }
                scope.launch {
                    val black = withContext(Dispatchers.Default) { isMostlyBlack(bitmap) }
                    if (stale()) {
                        bitmap.recycle()
                        return@launch
                    }
                    if (black && !demoRunning) {
                        bitmap.recycle()
                        Log.i(TAG, "display $displayId: capture $attempt is black after ${SystemClock.uptimeMillis() - t0}ms; retrying")
                        retry()
                    } else {
                        onCaptured(bitmap, afterSwap, startTilt, t0)
                    }
                }
            }

            override fun onFailure(errorCode: Int) {
                if (stale()) return
                // Secure content (banking, DRM video) and rate limits land here.
                Log.w(TAG, "display $displayId: screenshot failed: $errorCode")
                if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT &&
                    attempt < MAX_CAPTURE_ATTEMPTS
                ) {
                    retry()
                } else if (bridging) {
                    bridging = false // secure content etc.: keep playing on the stale picture
                } else {
                    phase = Phase.IDLE
                    demoRunning = false
                }
            }
        })
    }

    private fun onCaptured(bitmap: Bitmap, afterSwap: Boolean, startTilt: Float?, t0: Long) {
        cache.put(innerPanel, bitmap)
        if (bridging) {
            bridging = false
            val s = surface as? SnapshotSurface
            if (s != null && phase == Phase.SHOWING) {
                Log.i(TAG, "display $displayId: fresh capture (${SystemClock.uptimeMillis() - t0}ms) replaces the bridge at tilt=${s.tilt}")
                s.replaceSnapshot(bitmap, crossfadeMs = FRESH_FADE_MS)
            }
            return
        }
        present(bitmap, afterSwap, startTilt, t0)
    }

    /**
     * Puts the effect on screen: [bitmap] through the shader, or with no
     * bitmap the live blur over whatever is there.
     */
    private fun present(bitmap: Bitmap?, afterSwap: Boolean, startTilt: Float?, t0: Long) {
        if (phase != Phase.CAPTURING) {
            return
        }
        val angle = hinge.lastAngle
        val live = startTilt == null
        val coarse = live && hinge.isCoarse
        val peak = DuoShader.MAX_TILT * DuoSettings.config.value.intensity.coerceAtMost(1f)
        // A stops-only sensor can't be followed. Between stops the fold is in
        // motion, so: frost in on leaving rest and hold; on the fresh panel
        // start frosted and hold; clear only when the rest stop arrives (see
        // onHinge) or the hold cap expires.
        val tilt = when {
            startTilt != null -> startTilt
            coarse -> if (afterSwap) peak else DuoShader.FLAT_EPSILON * 1.2f
            else -> currentTilt()
        }
        // A capture lands late; the live blur is instant, so it's never "too late".
        val nearlyDone = bitmap != null && afterSwap && live &&
            if (innerPanel) angle > SKIP_INNER_ABOVE_HINGE else angle < SKIP_COVER_BELOW_HINGE
        if (tilt < DuoShader.FLAT_EPSILON || nearlyDone) {
            // Too late to be worth a pop-in: the fold is (almost) over.
            Log.i(TAG, "display $displayId: hinge=$angle by capture time (${SystemClock.uptimeMillis() - t0}ms); skipping")
            phase = Phase.IDLE
            return
        }
        // Closing onto a cover that only lights after the swap: the hinge HAL
        // goes quiet around 30°, so the overlay would never hear "closed".
        // Resolve on a timer instead — by the time this capture lands the
        // phone is shut anyway, so it reads as the cover settling into focus.
        // A cover lit alongside the inner panel keeps hearing the hinge.
        val easeTo = when {
            coarse -> peak
            // (With a continuous angle — the cover lit early — follow the hinge instead.)
            afterSwap && !innerPanel && !concurrentCover() && live && !hinge.externalActive -> 0f
            else -> null
        }
        val how = if (bitmap != null) "${bitmap.width}x${bitmap.height} snapshot (capture ${SystemClock.uptimeMillis() - t0}ms)" else "live blur"
        Log.i(TAG, "display $displayId: showing $how at tilt=$tilt${easeTo?.let { " easing to $it" } ?: ""}${if (bitmap != null && shellCapture()) " [shizuku]" else ""}")
        show(bitmap, tilt, afterSwap = afterSwap, easeTo = easeTo)
    }

    /** Samples a coarse grid; true when nothing on screen is brighter than near-black. */
    private fun isMostlyBlack(hw: Bitmap): Boolean {
        val sw = runCatching { hw.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return false
        try {
            val n = 24
            var maxSum = 0
            for (iy in 0 until n) {
                val y = ((iy + 0.5f) * sw.height / n).toInt()
                for (ix in 0 until n) {
                    val x = ((ix + 0.5f) * sw.width / n).toInt()
                    val c = sw.getPixel(x, y)
                    val sum = ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
                    if (sum > maxSum) maxSum = sum
                }
            }
            return maxSum < BLACK_THRESHOLD
        } finally {
            sw.recycle()
        }
    }

    /**
     * [easeTo] non-null plays a timed ease to that tilt, ignoring the hinge
     * until it's back at rest; easing up to a frosted peak holds there
     * briefly, then fades unless a panel swap has replaced it.
     */
    private fun show(bitmap: Bitmap?, startTilt: Float, afterSwap: Boolean = false, easeTo: Float? = null) {
        val inner = innerPanel
        val config = DuoSettings.config.value
        val foldLine = { w: Float, h: Float, c: com.duoopen.settings.DuoConfig -> DuoShader.foldFor(inner, w, h, c) }
        val created: FoldSurface? = if (bitmap != null) {
            SnapshotSurface(service, windowManager, bitmap, config, foldLine).takeIf { it.attached }
        } else {
            LiveBlurSurface(service, windowManager, config, DuoShader.pxPerMm(service.createDisplayContext(display)), foldLine)
                .takeIf { it.attached }
        }
        if (created == null) {
            Log.e(TAG, "display $displayId: could not attach overlay")
            phase = Phase.IDLE
            return
        }
        created.tilt = startTilt
        surface = created
        phase = Phase.SHOWING
        onShowingChanged()
        if (afterSwap && presentingBridge && shellCapture()) {
            (created as? SnapshotSurface)?.veil()
        } else if (afterSwap && !presentingBridge) {
            // Content was already live on this panel; ease the frost in. (A
            // bridge goes up at once: what's beneath is still waking up.)
            created.fadeIn(FADE_IN_MS)
        }
        // On a panel that just lit up, first wait for it to settle.
        if (bitmap != null && shellCapture()) startRefreshLoop(settling = afterSwap, shown = bitmap)
        // Heading for a hand-over this panel won't survive: dim it out on the way.
        if (!afterSwap && !demoRunning && hinge.externalActive && created is SnapshotSurface) {
            dimFollower = TiltFollower { a -> created.setHandoverDim(handoverDim(a)) }
                .also { it.snap(hinge.estimate()) }
        }
        follower = TiltFollower { t ->
            created.tilt = t
            if (t < DuoShader.FLAT_EPSILON && !demoRunning) {
                dismiss(fadeMs = FADE_OUT_FLAT_MS)
                armRest()
            }
        }.also { it.snap(startTilt) }
        lastHingeMoveMs = SystemClock.uptimeMillis()
        timedResolve = easeTo != null
        if (easeTo != null) {
            follower?.tauS = when {
                // Opening from shut: nothing reports the angle until the panels
                // swap (~1–1.5 s on a Z Fold 8 Ultra), so spread the frost over that.
                !inner && easeTo > DuoShader.FLAT_EPSILON && hinge.posture == HingeAngleSource.Posture.TENT -> COVER_OPEN_TAU_S
                hinge.isCoarse -> COARSE_EASE_TAU_S
                else -> TIMED_RESOLVE_TAU_S
            }
            follower?.setTarget(easeTo)
            if (easeTo > DuoShader.FLAT_EPSILON) {
                handler.postDelayed(peakHold, if (hinge.isCoarse) COARSE_PEAK_HOLD_MS else PEAK_HOLD_MS)
            }
        } else {
            handler.postDelayed(settleCheck, SETTLE_TIMEOUT_MS)
        }
    }

    /**
     * How dark this panel should be at [angle]: black by [DIM_GAP] degrees short
     * of where it hands over (the cover on an open: ~85°; the inner panel on a
     * close: the early cover takeover, else shut), ramping in over [DIM_SPAN]
     * before that, so the panel switching off is a fade rather than a cut.
     */
    private fun handoverDim(angle: Float): Float {
        val distance = if (innerPanel) {
            angle - (hinge.coverTakeoverHinge.takeIf { it.isFinite() } ?: SHUT_HANDOVER_HINGE)
        } else {
            hinge.panelSwapHinge - angle
        }
        val t = (1f - (distance - DIM_GAP) / DIM_SPAN).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun dismiss(fadeMs: Long) {
        val s = surface ?: return
        Log.i(TAG, "display $displayId: dismiss (fade ${fadeMs}ms) at tilt=${s.tilt}")
        clearOverlayState()
        s.fadeOut(fadeMs) { s.detach() }
    }

    /** @param retire the panel is handing over: leave the window up in black for a moment (see [SnapshotSurface.retire]). */
    private fun removeOverlay(retire: Boolean = false) {
        phase = Phase.IDLE
        timedResolve = false
        val s = surface ?: return
        clearOverlayState()
        if (retire && s is SnapshotSurface) s.retire(handler, RETIRE_MS) else s.detach()
    }

    private class Shot(val bitmap: Bitmap, val signature: IntArray, val pyramid: FoldOverlayView.Pyramid?)

    /**
     * Shizuku mode: keeps the picture under the frost current without ever
     * cutting to a new one.
     *
     * Settling (a panel that has just lit up, behind the black veil): fades
     * up from black at once into [shown] — the panel's last picture — or, with
     * none, into the first capture that isn't dark. It keeps capturing every
     * [SETTLE_INTERVAL_MS] until two in a row match (the system's transition
     * over, apps done relaunching or resizing for the new screen) or
     * [SETTLE_MAX_MS] pass, and only if that settled screen differs from what
     * is showing is it blended in.
     * After that, live: every [LIVE_INTERVAL_MS], a new capture is faded in
     * only if the screen clearly changed, so a still screen costs nothing.
     */
    private fun startRefreshLoop(settling: Boolean, shown: Bitmap) {
        if (liveLoop) return
        liveLoop = true
        val myGen = captureGen
        val started = SystemClock.uptimeMillis()
        var settle = settling
        // What's on screen now, and (settling) the previous capture.
        var shownSignature = shown.takeIf { it !== BLACK_PLACEHOLDER && it.config != Bitmap.Config.HARDWARE }?.let(::signature)
        var lastSignature: IntArray? = null
        var unveiled = !settling
        if (settling && shownSignature != null) {
            (surface as? SnapshotSurface)?.unveil(WAKE_FADE_MS)
            unveiled = true
        }
        var fades = 0
        // A fold held still: what's under the frost changes less often, look less often.
        fun interval() = when {
            settle -> SETTLE_INTERVAL_MS
            SystemClock.uptimeMillis() - lastHingeMoveMs > STILL_AFTER_MS -> LIVE_IDLE_INTERVAL_MS
            else -> LIVE_INTERVAL_MS
        }
        fun tick() {
            if (!liveLoop || phase != Phase.SHOWING || myGen != captureGen) { liveLoop = false; return }
            val s = surface as? SnapshotSurface
            if (settle && s != null && SystemClock.uptimeMillis() - started >= SETTLE_GIVE_UP_MS) {
                // Nothing usable came (the panel stayed dark?): show what there is.
                Log.i(TAG, "display $displayId: nothing settled")
                if (!unveiled) s.unveil(WAKE_FADE_MS)
                unveiled = true
                bridging = false
                settle = false
            }
            val excluded = excludedLayers()
            if (s == null || s.tilt < DuoShader.FLAT_EPSILON || excluded.isEmpty()) {
                handler.postDelayed({ tick() }, interval())
                return
            }
            val pw = s.view.pyramidWidth
            val ph = s.view.pyramidHeight
            val scale = if (settle) SETTLE_SHELL_SCALE else LIVE_SHELL_SCALE
            scope.launch {
                val shot = withContext(Dispatchers.IO) {
                    ShizukuBridge.capture(displayId, excluded, scale)?.let { f ->
                        Shot(f, signature(f), FoldOverlayView.Pyramid.build(f, pw, ph))
                    }
                }
                if (!liveLoop || phase != Phase.SHOWING || myGen != captureGen) return@launch
                val target = surface as? SnapshotSurface
                val waited = SystemClock.uptimeMillis() - started
                if (shot != null && target != null && shot.signature.max() >= BLACK_SIGNATURE) {
                    val showing = shownSignature
                    if (settle && !unveiled) {
                        // No earlier picture of this panel: the first real one, up from black.
                        Log.i(TAG, "display $displayId: first picture after ${waited}ms; fading up at tilt=${target.tilt}")
                        target.replaceSnapshot(shot.bitmap, shot.pyramid)
                        target.unveil(WAKE_FADE_MS)
                        unveiled = true
                        shownSignature = shot.signature
                    } else if (settle) {
                        val prev = lastSignature
                        if ((prev != null && difference(prev, shot.signature) < SETTLE_DIFF) || waited >= SETTLE_MAX_MS) {
                            val changed = showing == null || difference(showing, shot.signature) >= SETTLE_DIFF
                            Log.i(TAG, "display $displayId: settled after ${waited}ms${if (changed) "; blending it in" else ""}")
                            if (changed) {
                                target.replaceSnapshot(shot.bitmap, shot.pyramid, SETTLED_FADE_MS)
                                shownSignature = shot.signature
                            }
                            cache.put(innerPanel, shot.bitmap)
                            bridging = false
                            settle = false
                        }
                    } else if (showing == null || difference(showing, shot.signature) >= LIVE_CHANGE_DIFF) {
                        target.replaceSnapshot(shot.bitmap, shot.pyramid, LIVE_FADE_MS)
                        shownSignature = shot.signature
                        if (++fades % 25 == 1) Log.i(TAG, "display $displayId: live change $fades (${shot.bitmap.width}x${shot.bitmap.height})")
                    }
                    lastSignature = shot.signature
                }
                handler.postDelayed({ tick() }, interval())
            }
        }
        handler.postDelayed({ tick() }, interval())
    }

    /** Luma on a coarse grid; resolution-independent, for comparing captures. */
    private fun signature(b: Bitmap): IntArray {
        val n = SIGNATURE_GRID
        return IntArray(n * n) { i ->
            val c = b.getPixel(((i % n + 0.5f) * b.width / n).toInt(), ((i / n + 0.5f) * b.height / n).toInt())
            (((c shr 16) and 0xFF) * 3 + ((c shr 8) and 0xFF) * 6 + (c and 0xFF)) / 10
        }
    }

    /** Mean absolute luma difference, 0–255. */
    private fun difference(a: IntArray, b: IntArray): Float {
        var sum = 0L
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum.toFloat() / a.size
    }

    private fun clearOverlayState() {
        liveLoop = false
        handler.removeCallbacks(settleCheck)
        handler.removeCallbacks(peakHold)
        follower?.cancel()
        follower = null
        dimFollower?.cancel()
        dimFollower = null
        surface = null
        timedResolve = false
        bridging = false
        phase = Phase.IDLE
        onShowingChanged()
    }

    /** The display went away or the service is stopping. */
    /** @param handover the display went dark for a panel swap, not for good: see [removeOverlay]. */
    fun destroy(handover: Boolean = false) {
        captureGen++ // orphan any capture in flight
        removeOverlay(retire = handover)
        Log.i(TAG, "engine display=$displayId destroyed")
    }

    /**
     * Manual check without folding: snapshot the screen and play what this
     * panel shows during a fold. Inner panel: an unfold from full frost to
     * flat. Cover panel: frost sweeping in (opening) then back out (closing).
     */
    fun playDemo(durationMs: Long = 1400) {
        if (phase != Phase.IDLE || demoRunning) return
        demoRunning = true
        val inner = innerPanel
        val peak = DuoShader.MAX_TILT * DuoSettings.config.value.intensity.coerceAtMost(1f)
        // Frost at the very start so the overlay is visibly there; on the
        // cover it starts flat and sweeps in first.
        startEffect(afterSwap = false, startTilt = if (inner) peak else 0.06f)
        handler.postDelayed({
            val f = follower
            if (f == null) {
                demoRunning = false
                return@postDelayed
            }
            // Slow ease so the demo reads as a fold rather than a snap.
            f.tauS = durationMs / 4000f
            if (inner) {
                f.setTarget(0f)
                handler.postDelayed({
                    demoRunning = false
                    dismiss(fadeMs = FADE_OUT_FLAT_MS)
                }, durationMs)
            } else {
                f.setTarget(peak)
                handler.postDelayed({ f.setTarget(0f) }, durationMs)
                handler.postDelayed({
                    demoRunning = false
                    dismiss(fadeMs = FADE_OUT_FLAT_MS)
                }, durationMs * 2)
            }
        }, 450)
    }

    private companion object {
        const val TAG = "DuoOverlay"
        /** The framework rejects screenshots closer together than ~333 ms. */
        const val SCREENSHOT_MIN_INTERVAL_MS = 340L
        const val MAX_CAPTURE_ATTEMPTS = 3
        /** A screenshot of a panel that is still lighting up can take most of a second. */
        const val CAPTURE_TIMEOUT_MS = 1_100L
        const val BLACK_THRESHOLD = 30
        /** Ease time constant for the timed resolve (≈ 250 ms to settle). */
        const val TIMED_RESOLVE_TAU_S = 0.07f
        /** Slower ease for stops-only sensors, so a frost-in reads as a fold (≈ 450 ms). */
        const val COARSE_EASE_TAU_S = 0.12f
        /** Clear-out once a stops-only sensor reports the rest stop (≈ 300 ms). */
        const val COARSE_CLEAR_TAU_S = 0.08f
        /** How long a timed frost-up stays before it fades, absent a panel swap. */
        const val PEAK_HOLD_MS = 1_200L
        /** Stops-only sensors: hold the frost between stops, but not forever (flex mode). */
        const val COARSE_PEAK_HOLD_MS = 2_500L
        /** Tilt hysteresis for leaving a rest pose, so hinge jitter doesn't fire. */
        const val REST_LEAVE_TILT = 3f
        /** After a swap, don't bother if the fold is nearly finished by capture time. */
        const val SKIP_INNER_ABOVE_HINGE = 135f
        const val SKIP_COVER_BELOW_HINGE = 10f
        const val SETTLE_TIMEOUT_MS = 700L
        const val RESUME_AFTER_STALL_DEG = 4f
        /** Cover frost-in from TENT, ≈ 1 s to full — about how long until the inner panel takes over. */
        const val COVER_OPEN_TAU_S = 0.33f
        const val FADE_IN_MS = 140L
        const val FADE_OUT_FLAT_MS = 120L
        const val FADE_OUT_STALLED_MS = 300L
        /**
         * Shizuku capture scales. Half size is already finer than the fold
         * view renders, and at a light frost the overlay hands over to the real
         * screen, so a full-size picture is never on show. Live frames are
         * smaller still: each is a texture upload, which costs the frame it
         * lands in more than the shader does.
         */
        const val INITIAL_SHELL_SCALE = 0.5f
        const val SETTLE_SHELL_SCALE = 0.5f
        const val LIVE_SHELL_SCALE = 1f / 3f
        /** A lit-up panel is re-captured this often until two captures match… */
        const val SETTLE_INTERVAL_MS = 100L
        /** …or this long has passed. */
        const val SETTLE_MAX_MS = 1_200L
        /** No usable capture at all by now: lift the black anyway. */
        const val SETTLE_GIVE_UP_MS = 1_600L
        /** Fade up from black as a panel lights up — about as long as it takes to settle. */
        const val WAKE_FADE_MS = 380L
        /** Blend for a settled screen that turned out different (an app relaunched). */
        const val SETTLED_FADE_MS = 160L
        /** Hand-over dim: black this many degrees short of the hand-over, ramping in over the span before. */
        const val DIM_GAP = 2f
        const val DIM_SPAN = 14f
        /** How long a handed-over panel's overlay stays up in black, under the new one. */
        const val RETIRE_MS = 400L
        /** Without an early cover takeover the inner panel hands over as the phone shuts. */
        const val SHUT_HANDOVER_HINGE = 2f
        /** Mean luma difference (0–255) below which two captures count as the same screen. */
        const val SETTLE_DIFF = 2f
        /** Live refresh: how often to look, and how much must change to fade a new picture in. */
        const val LIVE_INTERVAL_MS = 200L
        const val LIVE_IDLE_INTERVAL_MS = 1_000L
        const val STILL_AFTER_MS = 2_000L
        const val LIVE_CHANGE_DIFF = 6f
        /** Accessibility path: a fresh capture replacing the bridge. */
        const val FRESH_FADE_MS = 260L
        const val LIVE_FADE_MS = 120L
        const val SIGNATURE_GRID = 16
        /** A capture whose brightest grid sample is below this is a panel still dark. */
        const val BLACK_SIGNATURE = 12
        const val SHELL_RETRY_MS = 60L

        /**
         * The window's root layer (hidden `ViewRootImpl.getSurfaceControl`),
         * needed to exclude our overlay from a display capture. Null if the
         * hidden-API exemption isn't in place.
         */
        /** Stands in under the black veil when a panel lights up with no earlier picture of it. */
        val BLACK_PLACEHOLDER: Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }

        fun rootSurfaceControl(view: View): SurfaceControl? = runCatching {
            val root = view.rootView.parent ?: return null
            val sc = root.javaClass.getMethod("getSurfaceControl").invoke(root) as? SurfaceControl
            sc?.takeIf { it.isValid }
        }.getOrNull()
    }
}
