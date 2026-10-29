package com.rfsentinel.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors
import com.rfsentinel.app.R
import com.rfsentinel.app.ui.RadarModel.Kind
import com.rfsentinel.app.ui.RadarModel.Layout
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Radar-style view of nearby devices. Distance from the centre = signal strength (a stronger signal plots
 * closer). The shape says which radio heard it: circle WiFi, square Bluetooth, triangle a radio transmission.
 * A fading trail behind a blip shows where its signal was over the last 30 s, so something closing in leaves
 * a tail pointing outwards.
 *
 * The ANGLE never shows direction - a single phone antenna can't measure it. In [Layout.SPREAD] it is a stable
 * spot from the address; in [Layout.APPROACH] it shows whether the signal is rising (top) or fading (bottom),
 * see [RadarModel]. The legend along the top counts each radio and hides or shows it when tapped; the chip at
 * its right switches the layout.
 */
class RadarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Blip(
        val mac: String, val rssi: Int, val color: Int, val flagged: Boolean, val label: String?,
        val kind: Kind = Kind.BLE,
        /** Averaged readings of the last 30 s, oldest first ([RadarModel.trail]). */
        val trail: List<Int> = emptyList(),
        val motion: RadarModel.Motion = RadarModel.Motion.UNKNOWN,
    )

    var onBlipClick: ((String) -> Unit)? = null
    /** Called when the layout chip is tapped, so the choice can be remembered. */
    var onLayoutChanged: ((Layout) -> Unit)? = null

    var angleLayout: Layout = Layout.SPREAD
        set(value) { field = value; invalidate() }

    private var blips: List<Blip> = emptyList()
    private var totals: Map<Kind, Int> = emptyMap()
    private val hidden = mutableSetOf<Kind>()
    private var sweep = 0f
    private val density = resources.displayMetrics.density
    private val scope = MaterialColors.getColor(context, R.attr.rfScopeColor, 0xFF0B5C63.toInt())
    private val scopeText = MaterialColors.getColor(context, R.attr.rfScopeTextColor, 0xAA0B5C63.toInt())
    private val you = MaterialColors.getColor(context, R.attr.rfTraceColor, 0xFFE0622D.toInt())
    private fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = alpha(scope, 0x55)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        color = scopeText
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        isFakeBoldText = true
    }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = alpha(scope, 0x99)
    }
    private val blipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val youPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = you }
    private val ordinaryColor = scope
    private val sweepColor = alpha(scope, 0x55)
    private val shape = Path()

    /** Tap targets of the legend, laid out by [onDraw]. */
    private val legendHits = mutableMapOf<Kind, RectF>()
    private val layoutChip = RectF()

    private val legendH get() = 24 * density
    private val captionH get() = 28 * density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val (cx, cy) = center()
        sweepPaint.shader = SweepGradient(
            cx, cy, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, sweepColor), floatArrayOf(0f, 0.75f, 1f)
        )
    }

    fun ordinaryColor() = ordinaryColor

    /** [totals]: how many of each radio there are in all, when more are heard than [list] can show. */
    fun setBlips(list: List<Blip>, totals: Map<Kind, Int>? = null) {
        blips = list
        this.totals = totals ?: list.groupingBy { it.kind }.eachCount()
        invalidate()
    }

    private fun center() = Pair(width / 2f, legendH + (height - legendH - captionH) / 2f)
    private fun radius() = min(width.toFloat(), height - legendH - captionH) / 2f - 8 * density

    /** Maps RSSI (-30 strong ... -100 weak) to 0.08..0.96 of the radius. */
    private fun rssiToFraction(rssi: Int): Float =
        (0.08f + (((-30 - rssi).coerceIn(0, 70)) / 70f) * 0.88f)

    private fun angleOf(b: Blip) = RadarModel.angle(b.mac, angleLayout, b.motion)

    private fun position(b: Blip, rssi: Int = b.rssi): Pair<Float, Float> {
        val (cx, cy) = center()
        val r = radius() * rssiToFraction(rssi)
        val a = angleOf(b)
        return Pair(cx + (r * cos(a)).toFloat(), cy + (r * sin(a)).toFloat())
    }

    /** Circle for WiFi, square for Bluetooth, triangle for a radio transmission, [size] across its middle. */
    private fun drawShape(canvas: Canvas, kind: Kind, x: Float, y: Float, size: Float, paint: Paint) {
        when (kind) {
            Kind.WIFI -> canvas.drawCircle(x, y, size, paint)
            Kind.BLE -> canvas.drawRect(x - size * 0.85f, y - size * 0.85f, x + size * 0.85f, y + size * 0.85f, paint)
            Kind.RADIO -> {
                shape.reset()
                shape.moveTo(x, y - size * 1.15f)
                shape.lineTo(x + size, y + size * 0.75f)
                shape.lineTo(x - size, y + size * 0.75f)
                shape.close()
                canvas.drawPath(shape, paint)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val (cx, cy) = center()
        val rad = radius()
        if (rad <= 0) return

        // Signal bands: Near (>= -60), Nearby (>= -80), Far.
        val near = rad * rssiToFraction(-60)
        val nearby = rad * rssiToFraction(-80)
        canvas.drawCircle(cx, cy, near, ringPaint)
        canvas.drawCircle(cx, cy, nearby, ringPaint)
        canvas.drawCircle(cx, cy, rad, ringPaint)
        canvas.drawLine(cx - rad, cy, cx + rad, cy, ringPaint)
        canvas.drawLine(cx, cy - rad, cx, cy + rad, ringPaint)
        canvas.drawText("Near ≥ -60 dBm", cx + 4 * density, cy - near + 12 * density, textPaint)
        canvas.drawText("Nearby ≥ -80", cx + 4 * density, cy - nearby + 12 * density, textPaint)
        canvas.drawText("Far", cx + 4 * density, cy - rad + 12 * density, textPaint)
        if (angleLayout == Layout.APPROACH) {
            canvas.drawText("▲ rising", cx - rad, cy - rad + 12 * density, textPaint)
            canvas.drawText("▼ fading", cx - rad, cy + rad - 4 * density, textPaint)
        }

        // Sweep.
        canvas.save()
        canvas.rotate(sweep, cx, cy)
        canvas.drawCircle(cx, cy, rad, sweepPaint)
        canvas.restore()

        val t = (System.currentTimeMillis() % 1000L) / 1000f
        val shown = blips.filter { it.kind !in hidden }
        // Trails first, under every blip: older readings fainter.
        for (b in shown) {
            val n = b.trail.size
            b.trail.forEachIndexed { i, rssi ->
                val (x, y) = position(b, rssi)
                blipPaint.color = alpha(b.color, (if (b.flagged) 150 else 70) * (i + 1) / (n + 1))
                drawShape(canvas, b.kind, x, y, (if (b.flagged) 3.5f else 2.2f) * density, blipPaint)
            }
        }
        // Ordinary devices first so flagged ones draw on top.
        for (b in shown.sortedBy { it.flagged }) {
            val (x, y) = position(b)
            blipPaint.color = b.color
            if (b.flagged) {
                pulsePaint.color = alpha(b.color, ((1f - t) * 200).toInt())
                canvas.drawCircle(x, y, (7 + 14 * t) * density, pulsePaint)
                drawShape(canvas, b.kind, x, y, 7 * density, blipPaint)
                b.label?.let {
                    labelPaint.color = b.color
                    canvas.drawText(it.take(22), x + 10 * density, y + 4 * density, labelPaint)
                }
            } else {
                drawShape(canvas, b.kind, x, y, 4 * density, blipPaint)
            }
        }
        canvas.drawCircle(cx, cy, 5 * density, youPaint)

        drawLegend(canvas)
        canvas.drawText(RadarModel.caption(angleLayout), 8 * density, height - 8 * density, textPaint)

        sweep = (sweep + 2.4f) % 360f
        if (isShown) postInvalidateOnAnimation()
    }

    /** "● WiFi 12  ■ BLE 542  ▲ Radio 1" (a hidden radio greyed out), and the layout chip on the right. */
    private fun drawLegend(canvas: Canvas) {
        val y = 16 * density
        var x = 8 * density
        legendHits.clear()
        for (kind in Kind.entries) {
            val n = totals[kind] ?: 0
            if (n == 0 && kind == Kind.RADIO) continue
            val off = kind in hidden
            val start = x
            blipPaint.color = alpha(scope, if (off) 0x40 else 0xFF)
            drawShape(canvas, kind, x + 5 * density, y - 4 * density, 4 * density, blipPaint)
            x += 13 * density
            if (off) textPaint.alpha = 0x50
            val text = "${kind.label} $n"
            canvas.drawText(text, x, y, textPaint)
            textPaint.color = scopeText
            x += textPaint.measureText(text) + 14 * density
            legendHits[kind] = RectF(start - 4 * density, 0f, x - 6 * density, legendH)
        }
        val chip = if (angleLayout == Layout.SPREAD) "Spread" else "Approach"
        val w = textPaint.measureText(chip) + 16 * density
        layoutChip.set(width - w - 8 * density, 3 * density, width - 8 * density, legendH - 3 * density)
        canvas.drawRoundRect(layoutChip, 10 * density, 10 * density, chipPaint)
        canvas.drawText(chip, layoutChip.left + 8 * density, y, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) return true
        if (event.action == MotionEvent.ACTION_UP) {
            val slop = 6 * density
            if (RectF(layoutChip).apply { inset(-slop, -slop) }.contains(event.x, event.y)) {
                angleLayout = if (angleLayout == Layout.SPREAD) Layout.APPROACH else Layout.SPREAD
                onLayoutChanged?.invoke(angleLayout)
                performClick()
                return true
            }
            legendHits.entries.firstOrNull { it.value.contains(event.x, event.y) }?.let { (kind, _) ->
                if (!hidden.remove(kind)) hidden += kind
                invalidate()
                performClick()
                return true
            }
            val shown = blips.filter { it.kind !in hidden }
            val hit = shown.minByOrNull { b ->
                val (x, y) = position(b)
                hypot(x - event.x, y - event.y)
            }
            if (hit != null) {
                val (x, y) = position(hit)
                if (hypot(x - event.x, y - event.y) <= 28 * density) {
                    performClick()
                    onBlipClick?.invoke(hit.mac)
                }
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()
}
