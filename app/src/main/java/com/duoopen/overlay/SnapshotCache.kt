package com.duoopen.overlay

import android.graphics.Bitmap
import android.os.SystemClock
import kotlin.math.abs

/**
 * The last screenshot taken of each panel kind, so a panel that has just
 * switched on can start frosted *immediately* — before the ~0.3 s it takes
 * the system to hand back a fresh capture of a waking panel — using the
 * picture it showed last time. Under heavy frost the difference is
 * invisible, and the fresh capture replaces it as soon as it lands.
 *
 * Each picture remembers whether the phone was locked when it was taken and
 * is only handed out in the same state: a picture of an app must never stand
 * in for the lock screen (or the other way round).
 */
class SnapshotCache(private val maxAgeMs: Long) {
    private class Entry(val bitmap: Bitmap, val at: Long, val locked: Boolean)

    private val entries = HashMap<Boolean, Entry>()

    fun put(innerPanel: Boolean, bitmap: Bitmap, locked: Boolean) {
        entries[innerPanel] = Entry(bitmap, SystemClock.uptimeMillis(), locked)
    }

    /** A recent picture of this panel kind, taken in the same lock state and orientation (any resolution), or null. */
    fun get(innerPanel: Boolean, width: Int, height: Int, locked: Boolean): Bitmap? {
        val e = entries[innerPanel] ?: return null
        if (e.locked != locked) return null
        val b = e.bitmap
        val sameShape = abs(b.width.toLong() * height - b.height.toLong() * width) <= width.toLong() * height / 50
        if (b.isRecycled || !sameShape) return null
        if (SystemClock.uptimeMillis() - e.at > maxAgeMs) return null
        return b
    }

    fun ageMs(innerPanel: Boolean): Long? = entries[innerPanel]?.let { SystemClock.uptimeMillis() - it.at }
}
