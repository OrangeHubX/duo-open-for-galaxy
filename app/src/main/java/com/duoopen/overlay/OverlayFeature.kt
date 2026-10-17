package com.duoopen.overlay

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.duoopen.fold.HingeAngleSource
import android.view.WindowManager
import com.duoopen.shell.ShizukuBridge
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted

/** The system-wide fold via [FoldOverlayService], plus the Shizuku helpers. */
object OverlayFeature {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Called once per process (app and service share it). */
    fun initProcess(context: Context) {
        runCatching {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/view/ViewRootImpl;",
                "Landroid/hardware/devicestate/",
            )
        }
        ShizukuBridge.init(context)
    }

    fun isEnabled(context: Context): Boolean = FoldOverlayService.isEnabled(context)

    /** Whether the system will blur behind our windows (the "live blur" engine). */
    fun liveBlurSupported(context: Context): Boolean =
        runCatching { context.getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true }
            .getOrDefault(false)

    /** Replays the effect over the current screen; false if the service isn't connected. */
    fun playDemo(): Boolean {
        val service = FoldOverlayService.instance ?: return false
        service.playDemo()
        return true
    }

    // ---- Shizuku mode --------------------------------------------------------

    /** Human-readable Shizuku state, live. */
    val shizukuStatus: StateFlow<String> =
        ShizukuBridge.state.map { it.summary }.stateIn(scope, SharingStarted.Eagerly, ShizukuBridge.state.value.summary)

    fun shizukuReady(): Boolean = ShizukuBridge.ready
    fun shizukuInstalled(): Boolean = ShizukuBridge.installed
    fun shizukuPackage(): String? = ShizukuBridge.managerPackage
    fun shizukuNeedsPermission(): Boolean = ShizukuBridge.state.value is ShizukuBridge.State.NeedsPermission
    fun requestShizuku() = ShizukuBridge.requestPermission()
    fun refreshShizuku() = ShizukuBridge.refresh()

    /** The running service's hinge (Samsung's real angle with Shizuku), or null. */
    fun hinge(): HingeAngleSource? = FoldOverlayService.instance?.hingeSource

    /** A Samsung phone with a hinge: what this build is made for. */
    fun supportedDevice(context: Context): Boolean =
        Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE)

    /** Status of the hinge-sensor reader, from the running service. */
    fun angleFeedStatus(): String = FoldOverlayService.instance?.angleFeedStatus() ?: "Service not running"
}
