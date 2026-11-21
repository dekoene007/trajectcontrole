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

    /** Afbeelding voor het paneel in Android Auto: meter, twee tegels, voortgangsbalk onderaan. */
    fun panelBitmap(st: Status?, w: Int = 480, h: Int = 660): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val r = w * 0.38f
        draw(c, w / 2f, r + w * 0.06f, r, st)
        if (st == null) return b
        // tegels: max. tot einde en limiet · boete
        val top = r * 2.25f + w * 0.06f; val th = w * 0.21f; val gap = w * 0.03f; val tw = (w - gap) / 2f
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.CARD2 }
        val lab = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.MUTED; textSize = w * 0.045f }
        val v = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.TEXT; textSize = w * 0.085f; typeface = Typeface.DEFAULT_BOLD }
        fun tile(x: Float, label: String, value: String) {
            c.drawRoundRect(RectF(x, top, x + tw, top + th), w * 0.035f, w * 0.035f, bg)
            c.drawText(label, x + w * 0.035f, top + th * 0.36f, lab)
            c.drawText(value, x + w * 0.035f, top + th * 0.82f, v)
        }
        tile(0f, "Max. tot einde", if (st.maxRestThrKmh > 0) "${st.maxRestThrKmh.roundToInt()}" else "—")
        tile(tw + gap, "Limiet · boete", "${st.limit} · ${Math.ceil(st.threshold).toInt()}")
        drawProgress(c, 0f, h - w * 0.13f, w.toFloat(), w * 0.028f, st, w * 0.045f)
        return b
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
