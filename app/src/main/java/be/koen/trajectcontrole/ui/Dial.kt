package be.koen.trajectcontrole.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import be.koen.trajectcontrole.core.Level
import be.koen.trajectcontrole.core.Status
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Kleuren, gedeeld door gsm en Android Auto (zelfde ontwerp als op het canvas). */
object Theme {
    val BG = Color.rgb(14, 17, 22)
    val CARD = Color.rgb(26, 32, 41)
    val CARD2 = Color.rgb(34, 42, 53)
    val LINE = Color.rgb(44, 53, 66)
    val TEXT = Color.rgb(232, 237, 243)
    val MUTED = Color.rgb(163, 173, 187)
    val OK = Color.rgb(43, 212, 164)
    val ORANGE = Color.rgb(245, 165, 36)
    val RED = Color.rgb(255, 107, 107)
    val BLUE = Color.rgb(90, 169, 255)
    val OTHER = Color.rgb(107, 122, 144)
    val TRACK = Color.rgb(140, 170, 210)     // andere trajecten op de kaart

    fun level(l: Level?) = when (l) { Level.GREEN -> OK; Level.ORANGE -> ORANGE; Level.RED -> RED; null -> MUTED }
    fun hex(c: Int) = String.format("#%06X", 0xFFFFFF and c)
}

/**
 * Tekent de snelheidsmeter: schaal limiet ± 40, groen tot de limiet, oranje tot de boetedrempel,
 * volledig rood daarboven. Naald en groot getal = gemiddelde. Zonder status: grijze lege meter.
 */
object DialRenderer {
    private const val START = 135f
    private const val SWEEP = 270f

    fun draw(c: Canvas, cx: Float, cy: Float, r: Float, st: Status?) {
        val limit = st?.limit ?: 120
        val lo = maxOf(0, limit - 40).toFloat(); val hi = (limit + 40).toFloat()
        val thr = (st?.threshold ?: fineThresholdOf(limit)).toFloat()
        fun ang(v: Float) = START + (v.coerceIn(lo, hi) - lo) / (hi - lo) * SWEEP
        val w = r * 0.16f
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w }

        p.color = Theme.CARD2; p.strokeCap = Paint.Cap.ROUND; c.drawArc(oval, START, SWEEP, false, p)
        if (st != null) {
            p.strokeCap = Paint.Cap.BUTT
            p.color = Theme.OK; c.drawArc(oval, START, ang(limit.toFloat()) - START, false, p)
            p.color = Theme.ORANGE; c.drawArc(oval, ang(limit.toFloat()), ang(thr) - ang(limit.toFloat()), false, p)
            p.color = Theme.RED; c.drawArc(oval, ang(thr), START + SWEEP - ang(thr), false, p)
            // ronde uiteinden
            p.style = Paint.Style.FILL
            p.color = Theme.OK; dot(c, cx, cy, r, START, w / 2, p)
            p.color = Theme.RED; dot(c, cx, cy, r, START + SWEEP, w / 2, p)
        }

        // naald
        if (st != null) {
            val a = Math.toRadians(ang(st.avgKmh.toFloat()).toDouble())
            val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.TEXT; strokeWidth = r * 0.05f; strokeCap = Paint.Cap.ROUND }
            c.drawLine(cx, cy, cx + (r - w * 0.2f) * cos(a).toFloat(), cy + (r - w * 0.2f) * sin(a).toFloat(), needle)
            needle.style = Paint.Style.FILL; c.drawCircle(cx, cy, r * 0.09f, needle)
        }

        // groot getal
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = r * 0.62f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER
            color = Theme.level(st?.level)
        }
        c.drawText(st?.avgKmh?.roundToInt()?.toString() ?: "—", cx, cy + r * 0.78f, big)
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = r * 0.13f; color = Theme.MUTED; textAlign = Paint.Align.CENTER }
        c.drawText(if (st?.limitOverridden == true) "km/u gemiddeld · limiet aangepast" else "km/u gemiddeld", cx, cy + r * 1.0f, small)
    }

    private fun dot(c: Canvas, cx: Float, cy: Float, r: Float, angle: Float, rad: Float, p: Paint) {
        val a = Math.toRadians(angle.toDouble())
        c.drawCircle(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), rad, p)
    }

    /** Voortgangsbalk met "nog x km" en totale lengte. */
    fun drawProgress(c: Canvas, left: Float, top: Float, width: Float, h: Float, st: Status, textSize: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rr = h / 2
        p.color = Theme.CARD2; c.drawRoundRect(RectF(left, top, left + width, top + h), rr, rr, p)
        val frac = (st.progressM / st.lengthM).coerceIn(0.0, 1.0).toFloat()
        if (frac > 0) { p.color = Theme.level(st.level); c.drawRoundRect(RectF(left, top, left + width * frac, top + h), rr, rr, p) }
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.textSize = textSize; color = Theme.MUTED }
        c.drawText("nog ${km(st.remainingM)}", left, top + h + textSize * 1.4f, t)
        t.textAlign = Paint.Align.RIGHT
        c.drawText(km(st.lengthM), left + width, top + h + textSize * 1.4f, t)
    }

    /**
     * Volledig paneel (Android Auto): kaart-achtergrond, meter, tegels "Nu" (GPS) en "Limiet · boete",
     * voortgangsbalk en aansluitend traject. Tekent binnen [r].
     */
    fun drawPanel(c: Canvas, r: RectF, st: Status, gpsKmh: Double) {
        val w = r.width(); val pad = w * 0.06f
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.CARD }
        c.drawRoundRect(r, w * 0.07f, w * 0.07f, bg)
        val inner = RectF(r.left + pad, r.top + pad, r.right - pad, r.bottom - pad)
        val iw = inner.width()
        // ruimte verdelen: meter boven, daaronder tegels, voortgang onderaan, aansluitend eronder
        val nextH = if (st.nextName != null) iw * 0.09f else 0f
        val progH = iw * 0.17f
        val tileH = iw * 0.24f
        val gap = iw * 0.04f
        val dialSpace = inner.height() - nextH - progH - tileH - gap * 2
        val rad = min(iw * 0.42f, dialSpace / 2.25f)
        draw(c, inner.centerX(), inner.top + rad * 1.06f, rad, st)
        val tTop = inner.top + rad * 2.25f + gap
        val tw = (iw - gap) / 2f
        val tb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.CARD2 }
        val lab = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.MUTED; textSize = iw * 0.05f; textAlign = Paint.Align.CENTER }
        val v = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.TEXT; textSize = iw * 0.095f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        fun tile(x: Float, label: String, value: String) {
            c.drawRoundRect(RectF(x, tTop, x + tw, tTop + tileH), iw * 0.04f, iw * 0.04f, tb)
            c.drawText(label, x + tw / 2, tTop + tileH * 0.38f, lab)
            c.drawText(value, x + tw / 2, tTop + tileH * 0.82f, v)
        }
        tile(inner.left, "Nu", "${gpsKmh.roundToInt()}")
        tile(inner.left + tw + gap, "Limiet · boete", "${st.limit} · ${Math.ceil(st.threshold).toInt()}")
        drawProgress(c, inner.left, inner.bottom - nextH - progH, iw, iw * 0.035f, st, iw * 0.05f)
        st.nextName?.let {
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.MUTED; textSize = iw * 0.05f }
            var text = "Aansluitend: $it, ${km(st.nextLengthM)}"
            while (t.measureText(text) > iw && text.length > 8) text = text.dropLast(2)
            c.drawText(text, inner.left, inner.bottom - iw * 0.01f, t)
        }
    }

    fun km(m: Double): String = if (m >= 10000) "${(m / 1000).roundToInt()} km" else String.format(java.util.Locale.GERMANY, "%.1f km", m / 1000)
    private fun fineThresholdOf(limit: Int) = be.koen.trajectcontrole.core.fineThreshold(limit)
}

/** Meter als View voor het gsm-scherm. */
class DialView(ctx: Context) : View(ctx) {
    var status: Status? = null
        set(v) { field = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val r = minOf(width, (height / 1.12f).toInt()) * 0.42f
        DialRenderer.draw(canvas, width / 2f, r * 1.08f, r, status)
    }
}
