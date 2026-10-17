package com.duoopen.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import com.duoopen.fold.DuoShader
import com.duoopen.fold.FoldLine
import com.duoopen.settings.DuoConfig
import com.duoopen.settings.DuoSettings
import kotlin.math.ceil
import kotlin.math.max

/**
 * Full-screen overlay that draws a screenshot through the fold shader.
 *
 * The shader is the mip-pyramid variant (res/raw/duo_unfold_lod.agsl): each
 * pixel blurs from a pre-shrunk copy of the picture ([Pyramid]) sized to its
 * blur radius, which is smooth and cheap enough to lay the shader view out at
 * 1/[renderScale] size, render it into its own hardware layer and scale it
 * back up; at tilt 0 the plain snapshot is drawn 1:1 instead.
 *
 * At a light frost the reduced resolution would show, so below [CLEAR_TILT]
 * the overlay fades into the live screen beneath it (the window is
 * translucent): the last of an unfold, and the first of a fold, are the real
 * screen at full resolution.
 */
class FoldOverlayView(
    context: Context,
    snapshot: Bitmap,
    /** Hinge geometry for this panel; null = centered from the config. */
    foldLine: ((w: Float, h: Float, config: DuoConfig) -> FoldLine)? = null,
    private val renderScale: Float = RENDER_SCALE,
) : ViewGroup(context) {

    private val fold = FoldView(context, snapshot, renderScale, foldLine)
    private val flat = FlatView(context, snapshot)
    /** Black over everything while a just-lit panel settles; see [veil]. */
    private val veil = View(context).apply {
        setBackgroundColor(Color.BLACK)
        alpha = 0f
    }

    /**
     * Covers the effect in black — what a panel that has just switched on
     * shows anyway — until [unveil] fades it up into the picture, so neither
     * the panel waking nor apps relaunching on it are seen.
     */
    fun veil() {
        veil.animate().cancel()
        veil.alpha = 1f
    }

    fun unveil(durationMs: Long) {
        veil.animate().alpha(0f).setDuration(durationMs).setInterpolator(DecelerateInterpolator()).start()
    }

    /** Black over everything as this panel nears its hand-over, so it doesn't just cut off. */
    private val dim = View(context).apply {
        setBackgroundColor(Color.BLACK)
        alpha = 0f
    }

    /** 0 = untouched, 1 = black; set per frame from the hinge. */
    fun setHandoverDim(amount: Float) {
        dim.alpha = amount.coerceIn(0f, 1f)
    }

    /** Plain black from now on, and nothing left to redraw or resize: see [SnapshotSurface.retire]. */
    fun retire() {
        fold.visibility = GONE
        flat.visibility = GONE
        veil.animate().cancel()
        dim.alpha = 1f
    }

    var tilt: Float
        get() = fold.tilt
        set(value) {
            fold.tilt = value
            val useFold = value >= DuoShader.FLAT_EPSILON
            fold.visibility = if (useFold) VISIBLE else INVISIBLE
            flat.visibility = if (useFold) INVISIBLE else VISIBLE
            // Smoothstep from the live screen (0) to the full effect (CLEAR_TILT).
            val t = (value / CLEAR_TILT).coerceIn(0f, 1f)
            val a = t * t * (3f - 2f * t)
            fold.alpha = a
            flat.alpha = a
        }

    var config: DuoConfig
        get() = fold.config
        set(value) {
            fold.config = value
        }

    /** Size the shader view renders at, for [Pyramid.build] off the main thread; 0×0 before layout. */
    val pyramidWidth: Int get() = fold.width
    val pyramidHeight: Int get() = fold.height

    /**
     * Swaps the picture under the effect (e.g. a stale bridge → the fresh
     * capture), cross-fading over [crossfadeMs] so the change never cuts in.
     */
    fun setSnapshot(bitmap: Bitmap, pyramid: Pyramid? = null, crossfadeMs: Long = 0L) {
        fold.setSnapshot(bitmap, pyramid, crossfadeMs)
        flat.setSnapshot(bitmap)
    }

    init {
        // Transparent, so a partly faded effect shows the live screen, not black.
        setBackgroundColor(Color.TRANSPARENT)
        addView(flat)
        addView(fold)
        addView(dim)
        addView(veil)
        fold.pivotX = 0f
        fold.pivotY = 0f
        fold.scaleX = renderScale
        fold.scaleY = renderScale
        fold.setLayerType(LAYER_TYPE_HARDWARE, null)
        tilt = 0f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        flat.measure(exactly(w), exactly(h))
        veil.measure(exactly(w), exactly(h))
        dim.measure(exactly(w), exactly(h))
        fold.measure(exactly(ceil(w / renderScale).toInt()), exactly(ceil(h / renderScale).toInt()))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        flat.layout(0, 0, r - l, b - t)
        veil.layout(0, 0, r - l, b - t)
        dim.layout(0, 0, r - l, b - t)
        fold.layout(0, 0, fold.measuredWidth, fold.measuredHeight)
    }

    private fun exactly(px: Int) = MeasureSpec.makeMeasureSpec(px, MeasureSpec.EXACTLY)

    /**
     * The picture shrunk to 1/2 .. 1/32 of the shader view's size, each level
     * by halving the one before (so each texel averages the ones under it).
     */
    class Pyramid private constructor(val levels: List<Bitmap>, val width: Int, val height: Int) {
        companion object {
            /** CPU work (a few ms for a full-screen picture); fine on a background thread. */
            fun build(source: Bitmap, width: Int, height: Int): Pyramid? {
                if (width < 2 || height < 2) return null
                val sw = if (source.config == Bitmap.Config.HARDWARE) {
                    source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
                } else {
                    source
                }
                val levels = ArrayList<Bitmap>(DuoShader.LOD_LEVELS)
                var w = max(1, width / 2)
                var h = max(1, height / 2)
                // Halve down to the first level rather than skip pixels in one big step.
                var cur = sw
                while (cur.width > w * 2 && cur.height > h * 2) {
                    cur = Bitmap.createScaledBitmap(cur, max(1, cur.width / 2), max(1, cur.height / 2), true)
                }
                cur = Bitmap.createScaledBitmap(cur, w, h, true)
                levels += cur
                repeat(DuoShader.LOD_LEVELS - 1) {
                    w = max(1, w / 2)
                    h = max(1, h / 2)
                    cur = Bitmap.createScaledBitmap(cur, w, h, true)
                    levels += cur
                }
                return Pyramid(levels, width, height)
            }
        }
    }

    /** Snapshot drawn 1:1 — pixel-identical to the live screen underneath. */
    private class FlatView(context: Context, private var snapshot: Bitmap) : View(context) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var shader = BitmapShader(snapshot, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        private val matrix = Matrix()

        fun setSnapshot(bitmap: Bitmap) {
            snapshot = bitmap
            shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            if (width > 0 && height > 0) onSizeChanged(width, height, width, height)
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            matrix.setScale(w / snapshot.width.toFloat(), h / snapshot.height.toFloat())
            shader.setLocalMatrix(matrix)
            paint.shader = shader
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }

    /** Snapshot through the fold shader, at reduced resolution. */
    private class FoldView(
        context: Context,
        private var snapshot: Bitmap,
        renderScale: Float,
        private val foldLine: ((w: Float, h: Float, config: DuoConfig) -> FoldLine)?,
    ) : View(context) {
        private val shader: RuntimeShader? = DuoShader.create(context, lod = true)
        private val pxPerMm = DuoShader.pxPerMm(context) / renderScale
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var image = linear(BitmapShader(snapshot, Shader.TileMode.DECAL, Shader.TileMode.DECAL))
        private var pyramid: Pyramid? = null
        private var levelShaders: List<BitmapShader> = emptyList()

        /** The outgoing picture while a cross-fade runs. */
        private var prevImage: BitmapShader? = null
        private var prevLevels: List<BitmapShader> = emptyList()
        private var fadeStartNanos = 0L
        private var fadeNanos = 0L

        fun setSnapshot(bitmap: Bitmap, ready: Pyramid?, crossfadeMs: Long) {
            val outgoing = image
            val outgoingLevels = levelShaders
            snapshot = bitmap
            image = linear(BitmapShader(bitmap, Shader.TileMode.DECAL, Shader.TileMode.DECAL))
            pyramid = ready?.takeIf { it.width == width && it.height == height }
            if (width > 0 && height > 0) onSizeChanged(width, height, width, height)
            if (crossfadeMs > 0 && outgoingLevels.size == DuoShader.LOD_LEVELS) {
                // A fade still running restarts from the newer of its two pictures.
                prevImage = outgoing
                prevLevels = outgoingLevels
                fadeStartNanos = System.nanoTime()
                fadeNanos = crossfadeMs * 1_000_000L
            } else {
                prevImage = null
            }
            invalidate()
        }

        var config: DuoConfig = DuoSettings.config.value

        var tilt = 0f
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            image.setLocalMatrix(Matrix().apply { setScale(w / snapshot.width.toFloat(), h / snapshot.height.toFloat()) })
            val p = pyramid?.takeIf { it.width == w && it.height == h }
                ?: Pyramid.build(snapshot, w, h).also { pyramid = it }
            levelShaders = p?.levels.orEmpty().map { level ->
                linear(BitmapShader(level, Shader.TileMode.DECAL, Shader.TileMode.DECAL)).apply {
                    setLocalMatrix(Matrix().apply { setScale(w / level.width.toFloat(), h / level.height.toFloat()) })
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 1f || h <= 1f) return
            canvas.drawColor(Color.BLACK)
            val fold = shader
            if (fold == null || levelShaders.size < DuoShader.LOD_LEVELS) {
                paint.shader = image
            } else {
                val line = foldLine?.invoke(w, h, config) ?: DuoShader.centeredFold(w, h, config.foldSplitsLong)
                DuoShader.setUniforms(fold, w, h, tilt, config, pxPerMm, line)
                fold.setInputShader("content", image)
                levelShaders.forEachIndexed { i, s -> fold.setInputShader("lod${i + 1}", s) }
                // The outgoing picture's weight eases from 1 to 0 over the fade.
                val progress = if (prevImage == null) 1f
                else ((System.nanoTime() - fadeStartNanos).toFloat() / fadeNanos).coerceIn(0f, 1f)
                if (progress >= 1f) prevImage = null
                val outgoing = prevImage
                fold.setInputShader("prev", outgoing ?: image)
                (if (outgoing != null) prevLevels else levelShaders).forEachIndexed { i, s ->
                    fold.setInputShader("prevLod${i + 1}", s)
                }
                val eased = progress * progress * (3f - 2f * progress)
                fold.setFloatUniform("prevWeight", if (outgoing != null) 1f - eased else 0f)
                paint.shader = fold
                if (outgoing != null) postInvalidateOnAnimation()
            }
            canvas.drawRect(0f, 0f, w, h, paint)
        }

        private fun linear(s: BitmapShader) = s.apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }
    }

    companion object {
        /** Shader view resolution divisor; the pyramid blur affords finer than the old 3 within a 120 Hz frame. */
        const val RENDER_SCALE = 2.5f
        /** Below this pane tilt the effect fades into the live screen. */
        const val CLEAR_TILT = 7f
    }
}
