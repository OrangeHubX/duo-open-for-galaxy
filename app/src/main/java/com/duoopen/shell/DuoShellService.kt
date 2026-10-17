package com.duoopen.shell

import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.view.SurfaceControl
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.function.ObjIntConsumer
import java.util.regex.Pattern

/**
 * Runs in a process Shizuku spawns with ADB (shell) privileges. Two jobs the
 * app process can't do itself:
 *
 * 1. **Display capture without the accessibility rate limit**, through the
 *    hidden `IWindowManager.captureDisplay`, with our own overlay layers
 *    excluded — so the fold can re-capture the live screen while it plays.
 * 2. **Samsung's hinge angle.** Only Samsung's own components may read the
 *    hinge sensors, but the shell user may read logcat, and the sensor HAL
 *    logs every 10–11° step of its two hinge sensors with the time it was
 *    sampled. This tails those lines and calls back with each reading.
 *
 * The capture technique (and the idea of reading the hinge through logcat)
 * was worked out by Duo Fold Live (github.com/joeconsorti/duo-fold-live, MIT).
 *
 * Nothing here is reachable without the user authorising this app in
 * Shizuku, and every transaction checks the calling uid.
 */
class DuoShellService : Binder() {

    private var owner = -1
    private var captureApi: CaptureApi? = null
    private var reader: AngleReader? = null

    init {
        attachInterface(null, ShellProtocol.TOKEN)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) {
            reply?.writeString(ShellProtocol.TOKEN)
            return true
        }
        if (code == SHIZUKU_DESTROY) {
            reader?.stop()
            runCatching { baseState(-1) }
            System.exit(0)
            return true
        }
        data.enforceInterface(ShellProtocol.TOKEN)
        val caller = getCallingUid()
        if (owner < 0) owner = caller
        if (caller != owner) throw SecurityException("wrong caller")
        val out = reply ?: return false
        when (code) {
            ShellProtocol.PING -> {
                out.writeNoException()
                out.writeBundle(Bundle().apply {
                    putInt("uid", Process.myUid())
                    putInt("pid", Process.myPid())
                    putString("capture", runCatching { api().name }.getOrElse { "unavailable: ${it.message}" })
                })
            }
            ShellProtocol.CAPTURE -> {
                val displayId = data.readInt()
                val n = data.readInt()
                val excluded = Array(n) { data.readTypedObject(SurfaceControl.CREATOR) }
                val scale = data.readFloat()
                val identity = clearCallingIdentity()
                val result = try {
                    capture(displayId, excluded.filterNotNull().toTypedArray(), scale)
                } catch (t: Throwable) {
                    var c: Throwable = t
                    while (c.cause != null) c = c.cause!!
                    Bundle().apply { putString("error", "${c.javaClass.simpleName}: ${c.message}") }
                } finally {
                    excluded.forEach { runCatching { it?.release() } }
                    restoreCallingIdentity(identity)
                }
                out.writeNoException()
                out.writeBundle(result)
            }
            ShellProtocol.START_ANGLES -> {
                val callback = data.readStrongBinder() ?: throw IllegalArgumentException("callback")
                reader?.stop()
                reader = AngleReader(callback).also { it.start() }
                out.writeNoException()
            }
            ShellProtocol.STOP_ANGLES -> {
                reader?.stop()
                reader = null
                out.writeNoException()
            }
            ShellProtocol.ANGLE_STATUS -> {
                out.writeNoException()
                out.writeBundle(reader?.status() ?: Bundle().apply { putString("state", "not started") })
            }
            ShellProtocol.BASE_STATE -> {
                val state = data.readInt()
                val identity = clearCallingIdentity()
                val result = try {
                    baseState(state)
                } catch (t: Throwable) {
                    var c: Throwable = t
                    while (c.cause != null) c = c.cause!!
                    Bundle().apply { putString("error", "${c.javaClass.simpleName}: ${c.message}") }
                } finally {
                    restoreCallingIdentity(identity)
                }
                out.writeNoException()
                out.writeBundle(result)
            }
            else -> return super.onTransact(code, data, reply, flags)
        }
        return true
    }

    // ---- device state ------------------------------------------------------

    /**
     * Identifies our override to the system. Note: on a Z Fold 8 Ultra the
     * override was measured to *outlive* this process, so the app side
     * remembers one may be left and cancels it on reconnect.
     */
    private val overrideToken = Binder()

    /**
     * Reports [state] to the system as the phone's physical posture (what
     * `cmd device_state base-state` does), or with -1 hands back to the real
     * one. Lets the app switch to the cover screen partway through a close.
     */
    private fun baseState(state: Int): Bundle {
        // Deliberately no `cmd device_state base-state` fallback: a failure here
        // must stay a failure, so the app never believes an override is in
        // place (or gone) when it isn't.
        val dsm = systemService("device_state", "android.hardware.devicestate.IDeviceStateManager\$Stub")
        // The service only takes requests from processes it can call back.
        if (!deviceStateCallbackRegistered) {
            val cbType = Class.forName("android.hardware.devicestate.IDeviceStateManagerCallback")
            val cb = Class.forName("android.hardware.devicestate.IDeviceStateManagerCallback\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, deviceStateCallback)
            dsm.javaClass.getMethod("registerCallback", cbType).invoke(dsm, cb)
            deviceStateCallbackRegistered = true
        }
        if (state < 0) {
            dsm.javaClass.getMethod("cancelBaseStateOverride").invoke(dsm)
        } else {
            dsm.javaClass.getMethod("requestBaseStateOverride", IBinder::class.java, Integer.TYPE, Integer.TYPE)
                .invoke(dsm, overrideToken, state, 0)
        }
        return Bundle().apply { putBoolean("ok", true) }
    }

    private var deviceStateCallbackRegistered = false

    /** Receives the service's (one-way) state updates; nothing here needs them. */
    private val deviceStateCallback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = true
    }

    // ---- capture -----------------------------------------------------------

    private class CaptureApi(
        val name: String,
        val builderCtor: Constructor<*>,
        val builder: Class<*>,
        val listenerCtor: Constructor<*>,
        val statusCallback: Boolean,
        val captureDisplay: Method,
    )

    private fun systemService(name: String, stub: String): Any {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, name) as IBinder
        return Class.forName(stub).getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }

    /**
     * Finds whichever generation of the hidden capture API this Android has:
     * `android.window.ScreenCaptureInternal` (status callback) or
     * `android.window.ScreenCapture` (plain consumer), driven through
     * `IWindowManager.captureDisplay`.
     */
    private fun api(): CaptureApi {
        captureApi?.let { return it }
        val wm = Class.forName("android.view.IWindowManager")
        val errors = StringBuilder()
        for (family in FAMILIES) {
            try {
                val args = Class.forName("$family\$CaptureArgs")
                val builder = Class.forName("$family\$CaptureArgs\$Builder")
                val listener = Class.forName("$family\$ScreenCaptureListener")
                val ctor = builder.getConstructor()
                builder.getMethod("setSourceCrop", Rect::class.java)
                builder.getMethod("setFrameScale", java.lang.Float.TYPE)
                builder.getMethod("setExcludeLayers", Array<SurfaceControl>::class.java)
                builder.getMethod("build")
                var status = true
                val lctor = try {
                    listener.getConstructor(ObjIntConsumer::class.java)
                } catch (e: NoSuchMethodException) {
                    status = false
                    listener.getConstructor(Consumer::class.java)
                }
                val capture = wm.getMethod("captureDisplay", Integer.TYPE, args, listener)
                return CaptureApi(family, ctor, builder, lctor, status, capture).also { captureApi = it }
            } catch (e: ReflectiveOperationException) {
                errors.append(family).append(": ").append(e).append("; ")
            }
        }
        throw ClassNotFoundException("no compatible display capture API: $errors")
    }

    private fun capture(displayId: Int, excluded: Array<SurfaceControl>, scale: Float): Bundle {
        val t0 = SystemClock.elapsedRealtime()
        val api = api()
        val wm = systemService("window", "android.view.IWindowManager\$Stub")
        val dm = systemService("display", "android.hardware.display.IDisplayManager\$Stub")
        val info = Class.forName("android.hardware.display.IDisplayManager").getMethod("getDisplayInfo", Integer.TYPE)
            .invoke(dm, displayId) ?: throw IllegalStateException("no display $displayId")
        val w = info.javaClass.getField("logicalWidth").getInt(info)
        val h = info.javaClass.getField("logicalHeight").getInt(info)
        if (w <= 0 || h <= 0) throw IllegalStateException("display $displayId has no size")

        val b = api.builderCtor.newInstance()
        api.builder.getMethod("setSourceCrop", Rect::class.java).invoke(b, Rect(0, 0, w, h))
        api.builder.getMethod("setFrameScale", java.lang.Float.TYPE).invoke(b, scale.coerceIn(0.1f, 1f))
        if (excluded.isNotEmpty()) {
            api.builder.getMethod("setExcludeLayers", Array<SurfaceControl>::class.java).invoke(b, excluded)
        }
        val args = api.builder.getMethod("build").invoke(b)

        val latch = CountDownLatch(1)
        var shot: Any? = null
        val callback: Any = if (api.statusCallback) {
            ObjIntConsumer<Any?> { s, _ -> shot = s; latch.countDown() }
        } else {
            Consumer<Any?> { s -> shot = s; latch.countDown() }
        }
        val listener = api.listenerCtor.newInstance(callback)
        api.captureDisplay.invoke(wm, displayId, args, listener)
        if (!latch.await(400, TimeUnit.MILLISECONDS)) throw IllegalStateException("capture timed out")
        java.lang.ref.Reference.reachabilityFence(callback)
        java.lang.ref.Reference.reachabilityFence(listener)
        val result = shot ?: throw IllegalStateException("no frame")

        var buffer: HardwareBuffer? = null
        try {
            buffer = result.javaClass.getMethod("getHardwareBuffer").invoke(result) as? HardwareBuffer
            val secure = runCatching {
                result.javaClass.getMethod("containsSecureLayers").invoke(result) as Boolean
            }.getOrDefault(false)
            val hardware = result.javaClass.getMethod("asBitmap").invoke(result) as? Bitmap
                ?: throw IllegalStateException("frame not readable")
            val bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false)
            hardware.recycle()
            return Bundle().apply {
                putBoolean("ok", true)
                putParcelable("bitmap", bitmap)
                putInt("width", w)
                putInt("height", h)
                putBoolean("secure", secure)
                putLong("ms", SystemClock.elapsedRealtime() - t0)
            }
        } finally {
            buffer?.close()
        }
    }

    // ---- Samsung hinge angle reader ------------------------------------------

    /** Tails logcat for the sensor HAL's hinge-sensor lines ([parse]). */
    private class AngleReader(private val callback: IBinder) {
        @Volatile private var process: java.lang.Process? = null
        @Volatile private var stopped = false
        @Volatile private var state = "starting"
        private var lines = 0
        private var parsed = 0
        private var rejected = 0
        private var lastAngle = Float.NaN
        private var lastUptime = 0L

        fun start() {
            val thread = Thread({
                var child: java.lang.Process? = null
                try {
                    child = ProcessBuilder(
                        "logcat", "-v", "epoch", "-T", "1", "-s", "sensors-hal:I", "*:S",
                    ).redirectErrorStream(true).start()
                    process = child
                    state = "listening"
                    BufferedReader(InputStreamReader(child.inputStream)).use { input ->
                        while (!stopped) {
                            val line = input.readLine() ?: break
                            lines++
                            val value = parse(line) ?: continue
                            // Never treat a buffered line as current.
                            val epoch = line.trim().split(Regex("\\s+"), 2).firstOrNull()?.toDoubleOrNull()
                            val age = if (epoch == null) Long.MAX_VALUE else System.currentTimeMillis() - (epoch * 1000).toLong()
                            if (age < -100 || age > 1500) {
                                rejected++
                                continue
                            }
                            parsed++
                            lastAngle = value
                            // The HAL stamps each reading with when it was sampled.
                            val sensorAge = if (halTs > 0) (SystemClock.elapsedRealtimeNanos() - halTs) / 1_000_000 else -1
                            lastUptime = SystemClock.uptimeMillis() - (if (sensorAge in 0..1500) sensorAge else age.coerceAtLeast(0))
                            state = "receiving"
                            val p = Parcel.obtain()
                            try {
                                p.writeInterfaceToken(ShellProtocol.CALLBACK_TOKEN)
                                p.writeFloat(value)
                                p.writeLong(lastUptime)
                                p.writeInt(halSource)
                                callback.transact(ShellProtocol.CB_ANGLE, p, null, IBinder.FLAG_ONEWAY)
                            } catch (e: Exception) {
                                state = "callback gone: ${e.message}"
                                break
                            } finally {
                                p.recycle()
                            }
                        }
                    }
                    if (!stopped) state = "log reader ended"
                } catch (e: Exception) {
                    state = "reader error: $e"
                } finally {
                    child?.destroy()
                }
            }, "duo-angle-reader")
            thread.isDaemon = true
            thread.start()
        }

        fun stop() {
            stopped = true
            process?.destroy()
            state = "stopped"
        }

        fun status(): Bundle = Bundle().apply {
            putString("state", state)
            putInt("lines", lines)
            putInt("parsed", parsed)
            putInt("rejected", rejected)
            putFloat("angle", lastAngle)
            putLong("last", lastUptime)
        }

        /** Sensor timestamp (boot-time ns) of the line [parse] just accepted, or 0 if it had none. */
        private var halTs = 0L
        /** Which sensor logged that line (ShellProtocol.SOURCE_*). */
        private var halSource = 0

        /**
         * Samsung's sensor HAL logs each step of its two hinge sensors
         * (`folding_angle … value [ 89/0]` every 11°, `lid_angle_fusion … value
         * [2/ 82/3]` every 10°), interleaved, over the whole range from shut
         * to flat and whatever is on screen. Found on a Galaxy Z Fold 8 Ultra.
         */
        private fun parse(line: String): Float? {
            halTs = 0L
            // `[0]folding_angle device_mode(0 -> 1)`: the hall sensor, logged
            // the moment the phone shuts (1) or opens (0), whatever the
            // device state says meanwhile.
            val mode = HAL_DEVICE_MODE.matcher(line)
            if (mode.find()) {
                halSource = if (mode.group(1) == "1") ShellProtocol.SOURCE_SHUT else ShellProtocol.SOURCE_UNSHUT
                return 0f
            }
            val folding = HAL_FOLDING.matcher(line)
            val m = if (folding.find()) folding else HAL_LID.matcher(line).takeIf { it.find() } ?: return null
            val v = m.group(2)?.toFloatOrNull() ?: return null
            if (v !in 0f..180f) return null
            halTs = m.group(1)?.toLongOrNull() ?: 0L
            halSource = if (m === folding) ShellProtocol.SOURCE_FOLDING else ShellProtocol.SOURCE_LID
            return v
        }

        private companion object {
            val HAL_DEVICE_MODE: Pattern = Pattern.compile("\\]folding_angle device_mode\\(\\d+ -> (\\d+)\\)")
            val HAL_FOLDING: Pattern = Pattern.compile("\\]folding_angle ts=(\\d+) ns value \\[\\s*(\\d+)/")
            val HAL_LID: Pattern = Pattern.compile("\\]lid_angle_fusion ts=(\\d+) ns value \\[\\d+/\\s*(\\d+)/")
        }
    }

    private companion object {
        /** Shizuku asks user services to exit with this code. */
        const val SHIZUKU_DESTROY = 16777115
        val FAMILIES = listOf("android.window.ScreenCaptureInternal", "android.window.ScreenCapture")
    }
}
