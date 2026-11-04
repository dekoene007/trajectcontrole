package be.koen.trajectcontrole

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import be.koen.trajectcontrole.core.Level
import be.koen.trajectcontrole.core.Status
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Zwevend venster over andere apps (Waze op de telefoon), zoals de Flitsmeister-overlay:
 * links het gemiddelde in een gekleurde cirkel, rechts de limiet in een rode ring.
 * Enkel zichtbaar binnen een traject. Verslepen = positie wordt onthouden.
 * Werkt op het telefoonscherm, niet in Android Auto (daar bestaan geen overlays).
 */
class OverlayController(private val ctx: Context, private val settings: Settings) {

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: PillView? = null
    private var params: WindowManager.LayoutParams? = null

    fun canShow() = AndroidSettings.canDrawOverlays(ctx)

    fun update(st: Status?) {
        if (!settings.overlay || !canShow() || st == null) { hide(); return }
        val v = view ?: create()
        v.avg = st.avgKmh.roundToInt()
        v.limit = st.limit
        v.level = st.level
        v.invalidate()
    }

    fun hide() {
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun create(): PillView {
        val d = ctx.resources.displayMetrics.density
        val v = PillView(ctx)
        val lp = WindowManager.LayoutParams(
            (132 * d).toInt(), (68 * d).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = settings.overlayX; y = settings.overlayY }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downX).toInt(); lp.y = startY + (e.rawY - downY).toInt()
                    try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(e.rawX - downX) > 5 || abs(e.rawY - downY) > 5) { settings.overlayX = lp.x; settings.overlayY = lp.y }
                    true
                }
                else -> false
            }
        }
        wm.addView(v, lp)
        view = v; params = lp
        return v
    }

    private class PillView(ctx: Context) : View(ctx) {
        var avg = 0; var limit = 0; var level = Level.GREEN
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 40, 48, 62) }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(230, 60, 60); style = Paint.Style.STROKE }
        private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(22, 28, 40) }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        }

        override fun onDraw(c: Canvas) {
            val h = height.toFloat(); val w = width.toFloat(); val r = h / 2f
            c.drawRoundRect(RectF(0f, 0f, w, h), r, r, bg)
            // links: gemiddelde
            fill.color = when (level) {
                Level.GREEN -> Color.rgb(40, 150, 70); Level.ORANGE -> Color.rgb(230, 140, 0); Level.RED -> Color.rgb(200, 40, 40)
            }
            val r2 = r * 0.86f
            c.drawCircle(r, r, r2, fill)
            text.textSize = r2 * (if (avg >= 100) 0.82f else 0.95f)
            c.drawText(avg.toString(), r, r - (text.descent() + text.ascent()) / 2, text)
            // rechts: limiet in rode ring
            val cx = w - r
            ring.strokeWidth = r * 0.16f
            c.drawCircle(cx, r, r2, inner)
            c.drawCircle(cx, r, r2 - ring.strokeWidth / 2, ring)
            text.textSize = r2 * (if (limit >= 100) 0.72f else 0.85f)
            c.drawText(limit.toString(), cx, r - (text.descent() + text.ascent()) / 2, text)
        }
    }
}
