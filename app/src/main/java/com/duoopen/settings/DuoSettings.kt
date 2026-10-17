package com.duoopen.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tuning shared by the in-app preview and the overlay.
 *
 * @param intensity Multiplier on the pane tilt. 1 = physically faithful;
 *   higher makes the frost heavier and linger further into the open.
 * @param blurSpread Blur radius gained per px of glass/plane separation.
 * @param darkening Fraction of light lost per px of blur radius, authored at the
 *   original's 6 units/mm reference density (normalized per display at draw time).
 * @param eyeDistanceMm Viewer distance from the flat screen plane (perspective).
 * @param foldSplitsLong Whether the fold line splits the display's longer side.
 *   Learned from Jetpack WindowManager while the app is open, so the overlay
 *   service (which can't query fold features) puts the crease in the right place.
 * @param movingSide Which half swings while the other is held still:
 *   -1 = left (top when the fold is horizontal), +1 = right (bottom), 0 = both.
 * @param coverFrostFromRight On the cover screen, frost grows from the right
 *   edge (hinge on the left); false mirrors it.
 * @param liveBlur Full-screen fold draws with the system's window blur on the
 *   live screen instead of warping a screenshot. No capture, no delay, but a
 *   stepped frost and no perspective; ignored where cross-window blur is off.
 * @param instantStart After a panel switches on, start the whole-screen fold
 *   at once from that panel's last picture and swap in the fresh capture when
 *   it lands (~0.3 s), instead of showing nothing until then.
 * @param shizukuCapture With Shizuku authorised, capture through the shell
 *   helper (no rate limit, live frames under the frost) instead of the
 *   accessibility screenshot.
 * @param shizukuAngle With Shizuku authorised, follow Samsung's hinge sensors
 *   through the sensor HAL's log.
 * @param coverOnAngle With Shizuku and the continuous angle, switch to the
 *   cover screen at this hinge angle while closing, instead of when shut, so
 *   its half of the fold is seen. 0 = leave it to the system.
 * @param innerOnAngle Likewise, switch to the inner screen at this angle while
 *   opening; 85 or more = leave it to the system (which switches at ~85°).
 * @param holdFrostS With the hinge tracked, how long a fold held still keeps
 *   its frost before the live screen comes back; 0 = for as long as it's held.
 */
data class DuoConfig(
    val intensity: Float = 1f,
    val blurSpread: Float = 0.12f,
    val darkening: Float = 0.015f,
    val eyeDistanceMm: Float = 450f,
    val foldSplitsLong: Boolean = false,
    val movingSide: Int = -1,
    val coverFrostFromRight: Boolean = true,
    val liveBlur: Boolean = false,
    val instantStart: Boolean = true,
    val shizukuCapture: Boolean = true,
    val shizukuAngle: Boolean = true,
    val coverOnAngle: Float = 85f,
    val innerOnAngle: Float = 85f,
    val holdFrostS: Int = 0,
)

object DuoSettings {
    private const val PREFS = "duo_open"

    private lateinit var prefs: SharedPreferences
    private val _config = MutableStateFlow(DuoConfig())
    val config: StateFlow<DuoConfig> = _config.asStateFlow()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = DuoConfig()
        _config.value = DuoConfig(
            intensity = prefs.getFloat("intensity", d.intensity),
            blurSpread = prefs.getFloat("blurSpread", d.blurSpread),
            darkening = prefs.getFloat("darkening", d.darkening),
            eyeDistanceMm = prefs.getFloat("eyeDistanceMm", d.eyeDistanceMm),
            foldSplitsLong = prefs.getBoolean("foldSplitsLong", d.foldSplitsLong),
            movingSide = prefs.getInt("movingSide", d.movingSide),
            coverFrostFromRight = prefs.getBoolean("coverFrostFromRight", d.coverFrostFromRight),
            liveBlur = prefs.getBoolean("liveBlur", d.liveBlur),
            instantStart = prefs.getBoolean("instantStart", d.instantStart),
            shizukuCapture = prefs.getBoolean("shizukuCapture", d.shizukuCapture),
            shizukuAngle = prefs.getBoolean("shizukuAngle", d.shizukuAngle),
            coverOnAngle = prefs.getFloat("coverOnAngle", d.coverOnAngle),
            innerOnAngle = prefs.getFloat("innerOnAngle", d.innerOnAngle),
            holdFrostS = prefs.getInt("holdFrostS", d.holdFrostS),
        )
    }

    fun update(transform: (DuoConfig) -> DuoConfig) {
        val next = transform(_config.value)
        if (next == _config.value) return
        _config.value = next
        prefs.edit {
            putFloat("intensity", next.intensity)
            putFloat("blurSpread", next.blurSpread)
            putFloat("darkening", next.darkening)
            putFloat("eyeDistanceMm", next.eyeDistanceMm)
            putBoolean("foldSplitsLong", next.foldSplitsLong)
            putInt("movingSide", next.movingSide)
            putBoolean("coverFrostFromRight", next.coverFrostFromRight)
            putBoolean("liveBlur", next.liveBlur)
            putBoolean("instantStart", next.instantStart)
            putBoolean("shizukuCapture", next.shizukuCapture)
            putBoolean("shizukuAngle", next.shizukuAngle)
            putFloat("coverOnAngle", next.coverOnAngle)
            putFloat("innerOnAngle", next.innerOnAngle)
            putInt("holdFrostS", next.holdFrostS)
        }
    }

    /** Restores the look defaults, keeping fold geometry and engine choices. */
    fun resetTuning() = update {
        DuoConfig(
            foldSplitsLong = it.foldSplitsLong,
            movingSide = it.movingSide,
            coverFrostFromRight = it.coverFrostFromRight,
            liveBlur = it.liveBlur,
            instantStart = it.instantStart,
            shizukuCapture = it.shizukuCapture,
            shizukuAngle = it.shizukuAngle,
            coverOnAngle = it.coverOnAngle,
            innerOnAngle = it.innerOnAngle,
            holdFrostS = it.holdFrostS,
        )
    }
}
