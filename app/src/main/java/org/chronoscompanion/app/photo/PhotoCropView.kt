package org.chronoscompanion.app.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max

/**
 * Square view that frames a photo in the watch's round screen: drag to move it, pinch to
 * zoom. Outside the circle is dimmed (the watch has no pixels there), and the time is
 * drawn where the "Foto" face will put it, over the same darkening band, so what you see
 * here is what the watch shows. [render] produces the 466x466 image to send.
 */
class PhotoCropView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            fitPending = true
            invalidate()
        }

    /** Where the face puts the time: 0 top, 1 centre, 2 bottom. */
    var timePos: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    /** Image pixels -> view pixels. */
    private val matrix = Matrix()
    private var fitPending = false
    private val circle = RectF()

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint().apply { color = 0xC0000000.toInt() }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x80FFFFFF.toInt()
        strokeWidth = 2f
    }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private val shadowPaint = Paint(timePaint).apply { color = 0xBE000000.toInt() }
    private val bandPaint = Paint()

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val b = bitmap ?: return false
            val cur = currentScale()
            val minS = coverScale(b) * 0.5f
            val maxS = coverScale(b) * 6f
            val f = (cur * d.scaleFactor).coerceIn(minS, maxS) / cur
            matrix.postScale(f, f, d.focusX, d.focusY)
            invalidate()
            return true
        }
    })
    private var lastX = 0f
    private var lastY = 0f
    private var dragPointer = -1

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val m = w * 0.06f
        circle.set(m, m, w - m, h - m)
        fitPending = true
    }

    private fun coverScale(b: Bitmap) = max(circle.width() / b.width, circle.height() / b.height)

    private fun currentScale(): Float {
        val v = FloatArray(9)
        matrix.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    private fun fit() {
        val b = bitmap ?: return
        val s = coverScale(b)
        matrix.reset()
        matrix.postScale(s, s)
        matrix.postTranslate(circle.centerX() - b.width * s / 2, circle.centerY() - b.height * s / 2)
        fitPending = false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bitmap == null) return false
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragPointer = e.getPointerId(0)
                lastX = e.x; lastY = e.y
                parent?.requestDisallowInterceptTouchEvent(true) // not the ScrollView's
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // Restart the drag from whichever finger stays, or the jump would move it.
                val keep = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && e.actionIndex == 0) 1 else 0
                dragPointer = e.getPointerId(keep)
                lastX = e.getX(keep); lastY = e.getY(keep)
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(dragPointer)
                if (i >= 0) {
                    if (!scaleDetector.isInProgress) matrix.postTranslate(e.getX(i) - lastX, e.getY(i) - lastY)
                    lastX = e.getX(i); lastY = e.getY(i)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragPointer = -1
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val b = bitmap
        if (b != null) {
            if (fitPending) fit()
            canvas.drawBitmap(b, matrix, bitmapPaint)
        }
        // Dim everything outside the circle.
        val outside = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            addOval(circle, Path.Direction.CW)
        }
        canvas.drawPath(outside, dimPaint)
        if (b != null) drawFaceOverlay(canvas)
        canvas.drawOval(circle, ringPaint)
    }

    /** The face's band and time (positions from src/faces/nm_foto/nm_foto.c, 466 px). */
    private fun drawFaceOverlay(canvas: Canvas) {
        val k = circle.width() / WATCH_PX
        val cy = circle.centerY()
        canvas.save()
        canvas.clipPath(Path().apply { addOval(circle, Path.Direction.CW) })
        val bandH = 250 * k
        when (timePos) {
            0 -> {
                bandPaint.shader = LinearGradient(0f, circle.top, 0f, circle.top + bandH, 0xC8000000.toInt(), 0, Shader.TileMode.CLAMP)
                canvas.drawRect(circle.left, circle.top, circle.right, circle.top + bandH, bandPaint)
            }
            2 -> {
                bandPaint.shader = LinearGradient(0f, circle.bottom - bandH, 0f, circle.bottom, 0, 0xC8000000.toInt(), Shader.TileMode.CLAMP)
                canvas.drawRect(circle.left, circle.bottom - bandH, circle.right, circle.bottom, bandPaint)
            }
            else -> {
                bandPaint.shader = null
                bandPaint.color = 0x6E000000
                val r = RectF(circle.centerX() - 200 * k, cy + 22 * k - 100 * k, circle.centerX() + 200 * k, cy + 22 * k + 100 * k)
                canvas.drawRoundRect(r, 48 * k, 48 * k, bandPaint)
            }
        }
        val yTime = when (timePos) { 0 -> -110; 2 -> 70; else -> -20 }
        val yDate = yTime + 70
        text(canvas, "10:09", 110 * k * 0.98f, cy + yTime * k, 4 * k)
        text(canvas, "Domingo 25 Octubre", 28 * k, cy + yDate * k, 2 * k)
        canvas.restore()
    }

    private fun text(canvas: Canvas, s: String, size: Float, centreY: Float, off: Float) {
        timePaint.textSize = size
        shadowPaint.textSize = size
        val fm = timePaint.fontMetrics
        val base = centreY - (fm.ascent + fm.descent) / 2
        canvas.drawText(s, circle.centerX() + off, base + off, shadowPaint)
        canvas.drawText(s, circle.centerX(), base, timePaint)
    }

    /** The framed photo at the watch's resolution, black outside the circle. */
    fun render(): Bitmap? {
        val b = bitmap ?: return null
        if (fitPending) fit()
        val out = Bitmap.createBitmap(WATCH_PX, WATCH_PX, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        val k = WATCH_PX / circle.width()
        val m = Matrix(matrix)
        m.postTranslate(-circle.left, -circle.top)
        m.postScale(k, k)
        c.save()
        c.clipPath(Path().apply { addCircle(WATCH_PX / 2f, WATCH_PX / 2f, WATCH_PX / 2f, Path.Direction.CW) })
        c.drawBitmap(b, m, bitmapPaint)
        c.restore()
        return out
    }

    companion object {
        const val WATCH_PX = 466
    }
}

/** Largest side of a picked photo kept in memory - plenty for a 466 px crop at 4x zoom. */
internal const val PHOTO_MAX_SIDE = 2048

internal fun clampSide(w: Int, h: Int): Int {
    var s = 1
    while (max(w, h) / (s * 2) >= PHOTO_MAX_SIDE) s *= 2
    return s
}
