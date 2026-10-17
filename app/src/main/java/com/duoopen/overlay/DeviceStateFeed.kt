package com.duoopen.overlay

import android.content.Context
import android.util.Log
import com.duoopen.fold.HingeAngleSource.Posture
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * Follows the system's fold posture (CLOSED / TENT / HALF_OPENED / OPENED)
 * through the hidden `DeviceStateManager`. On Galaxy Z Folds, whose public
 * hinge sensor only reports 0/90/180, this is the earliest sign that a fold
 * has started — and it needs no Shizuku. Silently does nothing where the
 * class or its callback can't be reached.
 */
class DeviceStateFeed(
    private val context: Context,
    private val executor: Executor,
    private val onPosture: (Posture) -> Unit,
) {
    private var manager: Any? = null
    private var callback: Any? = null

    /** The id of this device's CLOSED state (AOSP and Samsung: 0), once known. */
    var closedId: Int? = null
        private set
    /** The id of this device's HALF_OPENED state (AOSP and Samsung: 2), once known. */
    var halfOpenedId: Int? = null
        private set

    fun start() {
        if (callback != null) return
        runCatching {
            val mgr = context.getSystemService("device_state") ?: return
            val cbClass = Class.forName("android.hardware.devicestate.DeviceStateManager\$DeviceStateCallback")
            lateinit var proxy: Any
            proxy = Proxy.newProxyInstance(cbClass.classLoader, arrayOf(cbClass)) { _, method, args ->
                when (method.name) {
                    // Android 15+: DeviceState; earlier: the int identifier.
                    "onDeviceStateChanged", "onStateChanged" -> args?.firstOrNull()?.let(::deliver)
                    "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                    "equals" -> return@newProxyInstance proxy === args?.firstOrNull()
                    "toString" -> return@newProxyInstance "DuoDeviceStateCallback"
                }
                null
            }
            mgr.javaClass.getMethod("registerCallback", Executor::class.java, cbClass).invoke(mgr, executor, proxy)
            manager = mgr
            callback = proxy
            closedId = stateId(mgr, Posture.CLOSED)
            halfOpenedId = stateId(mgr, Posture.HALF_OPENED)
            Log.i(TAG, "device state feed started (closed id $closedId, half-opened id $halfOpenedId)")
        }.onFailure { Log.w(TAG, "device state unavailable: $it") }
    }

    private fun stateId(mgr: Any, posture: Posture): Int? = runCatching {
        when (val states = mgr.javaClass.getMethod("getSupportedDeviceStates").invoke(mgr)) {
            is List<*> -> states.filterNotNull().firstOrNull { nameOf(it)?.let(::postureFor) == posture }
                ?.let { it.javaClass.getMethod("getIdentifier").invoke(it) as Int }
            is IntArray -> states.firstOrNull { id -> DEFAULT_NAMES[id]?.let(::postureFor) == posture }
            else -> null
        }
    }.getOrNull()

    fun stop() {
        val mgr = manager ?: return
        val cb = callback ?: return
        runCatching {
            val cbClass = Class.forName("android.hardware.devicestate.DeviceStateManager\$DeviceStateCallback")
            mgr.javaClass.getMethod("unregisterCallback", cbClass).invoke(mgr, cb)
        }
        manager = null
        callback = null
    }

    private fun nameOf(state: Any): String? =
        if (state is Int) DEFAULT_NAMES[state]
        else runCatching { state.javaClass.getMethod("getName").invoke(state) as? String }.getOrNull()

    private fun deliver(state: Any) {
        val name = nameOf(state) ?: return
        val posture = postureFor(name) ?: return
        Log.i(TAG, "device state $name -> $posture")
        onPosture(posture)
    }

    private companion object {
        const val TAG = "DuoDeviceState"

        /** AOSP / Samsung ids, for Androids whose callback only passes the int. */
        val DEFAULT_NAMES = mapOf(0 to "CLOSED", 1 to "TENT", 2 to "HALF_OPENED", 3 to "OPENED")

        /** Concurrent (dual-screen) and rear-display states say nothing about the hinge. */
        fun postureFor(name: String): Posture? {
            val n = name.uppercase()
            return when {
                n.contains("CONCURRENT") || n.contains("REAR") -> null
                n.contains("HALF") -> Posture.HALF_OPENED
                n.contains("TENT") -> Posture.TENT
                n.contains("CLOSE") || n.contains("FOLDED") && !n.contains("UNFOLDED") -> Posture.CLOSED
                n.contains("OPEN") || n.contains("UNFOLDED") -> Posture.OPENED
                else -> null
            }
        }
    }
}
