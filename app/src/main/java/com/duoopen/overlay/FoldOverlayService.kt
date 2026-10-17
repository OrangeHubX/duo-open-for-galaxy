package com.duoopen.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import com.duoopen.fold.HingeAngleSource
import com.duoopen.fold.isLivePanel
import com.duoopen.settings.DuoSettings
import com.duoopen.shell.ShizukuBridge
import com.duoopen.shell.SamsungAngleFeed
import kotlinx.coroutines.launch
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/**
 * System-wide fold effect. Accessibility services may screenshot any display
 * and draw above every other window, which is what lets the fold cover the
 * launcher, lock screen and apps.
 *
 * Runs one [PanelEngine] per lit built-in display and keeps that set in step
 * with the DisplayManager: a panel that lights up mid-fold gets an engine
 * (which captures it as the second half of the fold), one that switches off
 * loses its engine. A Galaxy Z Fold lights one panel at a time on display 0,
 * so a single engine runs and its display flips panel at the swap; in dual
 * screen mode both panels are lit and their engines run together.
 */
class FoldOverlayService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private lateinit var hinge: HingeAngleSource
    private lateinit var displayManager: DisplayManager
    private val engines = LinkedHashMap<Int, PanelEngine>()
    private val snapshots = SnapshotCache(maxAgeMs = SNAPSHOT_MAX_AGE_MS)
    private var angleFeed: SamsungAngleFeed? = null
    private var deviceState: DeviceStateFeed? = null
    private var screenSwitch: EarlyScreenSwitch? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = syncDisplays()
        override fun onDisplayRemoved(displayId: Int) = syncDisplays()
        override fun onDisplayChanged(displayId: Int) = syncDisplays()
    }

    /** `adb shell am broadcast -a com.duoopen.DEMO` plays the effect over whatever is on screen. */
    private val demoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = playDemo()
    }
    private var receiverRegistered = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (!receiverRegistered) {
            registerReceiver(demoReceiver, IntentFilter(ACTION_DEMO), RECEIVER_EXPORTED)
            receiverRegistered = true
        }
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(displayListener, handler)
        hinge = HingeAngleSource(this) { onHinge(it) }
        hinge.start()
        deviceState = DeviceStateFeed(this, mainExecutor) {
            hinge.feedPosture(it)
            syncAngleFeed()
        }.also { it.start() }
        screenSwitch = EarlyScreenSwitch(this, hinge, handler, { deviceState?.closedId }, { deviceState?.halfOpenedId })
        ShizukuBridge.init(this)
        angleFeed = SamsungAngleFeed(handler, hinge) { screenSwitch?.onShut() }
        scope.launch { ShizukuBridge.state.collect { syncAngleFeed() } }
        scope.launch { DuoSettings.config.collect { syncAngleFeed() } }
        syncDisplays()
        Log.i(TAG, "connected; hinge=${hinge.sensor?.name} live displays=${engines.keys}")
    }

    override fun onDestroy() {
        instance = null
        screenSwitch?.release("service stopping")
        angleFeed?.stop()
        deviceState?.stop()
        if (receiverRegistered) {
            unregisterReceiver(demoReceiver)
        }
        hinge.stop()
        displayManager.unregisterDisplayListener(displayListener)
        for (e in engines.values) e.destroy()
        engines.clear()
        OverlayState.setRunning(false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    private fun onHinge(angle: Float) {
        screenSwitch?.onAngle(angle)
        syncDisplays()
        for (e in engines.values.toList()) e.onHinge(angle)
    }

    /** Starts engines for panels that lit up, stops those that went dark, then lets each re-evaluate. */
    private fun syncDisplays() {
        // `adb shell settings put global duoopen_test_displays 1` lets a
        // simulated secondary display stand in for a second panel (see Panels.kt).
        val testDisplays = Settings.Global.getInt(contentResolver, TEST_DISPLAYS_SETTING, 0) != 0
        val defaultName = runCatching { displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.name }.getOrNull()
        val live = displayManager.displays
            .filter { it.isLivePanel(defaultDisplayName = defaultName, includePresentation = testDisplays) }
            .associateBy { it.displayId }
        val gone = engines.keys.filter { it !in live }
        for (id in gone) {
            engines.remove(id)?.destroy(handover = true)
        }
        for ((id, display) in live) {
            if (id !in engines) {
                engines[id] = PanelEngine(
                    service = this,
                    display = display,
                    hinge = hinge,
                    handler = handler,
                    scope = scope,
                    hasLiveInnerElsewhere = { engines.values.any { it !== engines[id] && it.innerPanel } },
                    onShowingChanged = ::updateRunning,
                    cache = snapshots,
                )
            }
        }
        if (gone.isNotEmpty()) updateRunning()
        for (e in engines.values.toList()) e.evaluate()
    }

    /** Samsung's hinge angle via Shizuku, whenever it's authorised and switched on. */
    private fun syncAngleFeed() {
        val feed = angleFeed ?: return
        // Runs on every Shizuku state change, so also on the helper reconnecting.
        screenSwitch?.recover()
        val want = DuoSettings.config.value.shizukuAngle && ShizukuBridge.ready
        if (!want) screenSwitch?.release("no continuous angle")
        if (want && !feed.active) feed.start() else if (!want && feed.active) feed.stop()
    }

    fun angleFeedStatus(): String = angleFeed?.status ?: "Idle"

    /** The service's hinge, which has Samsung's real angle when Shizuku is on; null before connecting. */
    val hingeSource: HingeAngleSource? get() = if (::hinge.isInitialized) hinge else null

    private fun updateRunning() {
        OverlayState.setRunning(engines.values.any { it.showing })
    }

    /** Replays the effect on every lit panel — the default display is the one you're looking at. */
    fun playDemo() {
        syncDisplays()
        (engines[Display.DEFAULT_DISPLAY]?.let { listOf(it) } ?: engines.values.toList())
            .forEach { it.playDemo() }
    }

    companion object {
        private const val TAG = "DuoOverlay"
        const val ACTION_DEMO = "com.duoopen.DEMO"
        private const val TEST_DISPLAYS_SETTING = "duoopen_test_displays"
        /** How old a panel's last picture may be and still bridge the next fold. */
        private const val SNAPSHOT_MAX_AGE_MS = 15 * 60_000L

        /** The connected service, for in-process control from the app. */
        @Volatile
        var instance: FoldOverlayService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
            val self = ComponentName(context, FoldOverlayService::class.java)
            return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.let { s -> ComponentName(s.packageName, s.name) } == self }
        }
    }
}
