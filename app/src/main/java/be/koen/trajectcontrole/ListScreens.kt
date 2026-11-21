package be.koen.trajectcontrole

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import be.koen.trajectcontrole.ui.Theme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Gemeenschappelijke opbouw voor de eenvoudige lijstschermen (donker, zelfde stijl als het hoofdscherm). */
abstract class DarkListActivity : Activity() {
    protected lateinit var list: LinearLayout
    protected fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).roundToInt()
    protected fun rounded(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(24f), dp(16f), dp(24f)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Theme.BG); addView(list) })
        list.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            v.setPadding(dp(16f), dp(24f) + b.top, dp(16f), dp(24f) + b.bottom); insets
        }
    }

    override fun onResume() { super.onResume(); build() }
    abstract fun build()

    protected fun heading(t: String, sub: String? = null) {
        list.addView(TextView(this).apply { text = t; textSize = 24f; setTextColor(Theme.TEXT); typeface = Typeface.DEFAULT_BOLD })
        sub?.let { list.addView(TextView(this).apply { text = it; textSize = 14f; setTextColor(Theme.MUTED); setPadding(0, dp(2f), 0, dp(10f)) }) }
    }

    protected fun section(t: String) = list.addView(TextView(this).apply {
        text = t.uppercase(); textSize = 12f; setTextColor(Theme.MUTED); typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(14f), 0, dp(6f))
    })

    protected fun row(title: String, sub: String, state: String = "", stateColor: Int = Theme.MUTED, onClick: (() -> Unit)? = null) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(Theme.CARD2, 16f); setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            onClick?.let { c -> setOnClickListener { c() } }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = title; textSize = 15f; setTextColor(Theme.TEXT); typeface = Typeface.DEFAULT_BOLD })
        col.addView(TextView(this).apply { text = sub; textSize = 13f; setTextColor(Theme.MUTED) })
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (state.isNotEmpty()) r.addView(TextView(this).apply { text = state; textSize = 12f; setTextColor(stateColor); typeface = Typeface.DEFAULT_BOLD })
        list.addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8f) })
    }

    protected fun button(t: String, primary: Boolean = false, onClick: () -> Unit) = list.addView(Button(this).apply {
        text = t; isAllCaps = false; textSize = 15f
        setTextColor(if (primary) Theme.BG else Theme.TEXT)
        background = rounded(if (primary) Theme.BLUE else Theme.CARD2, 14f)
        setOnClickListener { onClick() }
    }, LinearLayout.LayoutParams(-1, dp(52f)).apply { topMargin = dp(8f) })

    protected fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
}

/** Mijn markeringen: nieuwe trajecten, correcties en losse markeringen; indienen of exporteren. */
class MarksActivity : DarkListActivity() {

    override fun build() {
        MarkStore.resolvePending(this)
        list.removeAllViews()
        heading("Mijn markeringen", "Actief op dit toestel. Na indienen komen ze in de gedeelde data bij de volgende update.")
        val marks = MarkStore.all(this).sortedByDescending { it.id }
        val nieuw = marks.filter { it.kind == MarkStore.EINDE }
        val corr = marks.filter { it.kind == MarkStore.CORR }
        val open = marks.filter { it.kind == MarkStore.OPEN || (it.kind == MarkStore.BEGIN && it.state != "gekoppeld") }

        if (marks.isEmpty()) row("Nog geen markeringen", "Tik op Markeer tijdens het rijden om een begin, einde of correctie vast te leggen.")
        if (nieuw.isNotEmpty()) section("Nieuwe trajecten")
        nieuw.forEach { m ->
            val len = m.geom?.let { be.koen.trajectcontrole.core.Track.length(be.koen.trajectcontrole.core.SegmentStore.parsePoints(it)) }
            row(m.name ?: "Eigen traject", "${MarkStore.fmt(m.pairId ?: m.id)} · ${len?.let { "%.1f km".format(Locale.GERMANY, it / 1000) } ?: "?"} · limiet ${m.limit}",
                stateLabel(m), stateColor(m)) { manage(m) }
        }
        if (corr.isNotEmpty()) section("Correcties")
        corr.forEach { m ->
            row("${if (m.corrAtStart == true) "Begin" else "Einde"}: ${m.name}", MarkStore.fmt(m.id), stateLabel(m), stateColor(m)) { manage(m) }
        }
        if (open.isNotEmpty()) section("Nog te beslissen")
        open.forEach { m ->
            row(if (m.kind == MarkStore.BEGIN) "Begin zonder einde" else "Losse markering",
                "${MarkStore.fmt(m.id)} · %.5f, %.5f".format(Locale.US, m.lat, m.lon), if (m.kind == MarkStore.BEGIN) "BEGIN" else "OPEN") { decide(m) }
        }

        val ready = (nieuw + corr).filter { it.state == "actief" && !it.submitted }
        if (ready.isNotEmpty()) {
            button("Indienen (${ready.size})", primary = true) {
                if (IssueSubmit.open(this, ready)) {
                    dialog().setTitle("Ingediend?").setMessage("Heb je het issue op GitHub aangemaakt? Dan worden deze markeringen als ingediend aangeduid.")
                        .setPositiveButton("Ja") { _, _ -> MarkStore.markSubmitted(this, ready.map { it.id }); build() }
                        .setNegativeButton("Nog niet", null).show()
                } else Toast.makeText(this, "Te groot voor één issue; gebruik Exporteren.", Toast.LENGTH_LONG).show()
            }
            button("Exporteren als tekst") { IssueSubmit.share(this, ready) }
        }
    }

    private fun stateLabel(m: MarkStore.Mark) = when {
        m.submitted -> "INGEDIEND"
        m.state == "actief" -> "ACTIEF"
        m.state.startsWith("wacht") -> "WACHT"
        else -> "FOUT"
    }
    private fun stateColor(m: MarkStore.Mark) = when (stateLabel(m)) { "ACTIEF", "INGEDIEND" -> Theme.OK; "WACHT" -> Theme.ORANGE; else -> Theme.RED }

    private fun manage(m: MarkStore.Mark) {
        dialog().setTitle(m.name ?: "Markering").setMessage(m.state.ifEmpty { "—" })
            .setPositiveButton("Verwijderen") { _, _ -> MarkStore.delete(this, m.id); TrackingService.current?.reloadSegments(); build() }
            .setNegativeButton("Sluiten", null).show()
    }

    private fun decide(m: MarkStore.Mark) {
        val sug = MarkStore.suggestionFor(this, m)
        val labels = mutableListOf("Begin van een traject", "Einde van een traject")
        if (sug != null) labels += "Correctie: ${sug.segment.name} – ${sug.describe()}"
        labels += "Verwijderen"
        dialog().setTitle(MarkStore.fmt(m.id)).setItems(labels.toTypedArray()) { _, i ->
            when {
                i == 0 -> { MarkStore.setBegin(this, m.id); build() }
                i == 1 -> MarkChoice.chooseLimit(this) { lim -> MarkStore.setEinde(this, m.id, lim); build() }
                sug != null && i == 2 -> { MarkStore.setCorrectie(this, m.id, sug); build() }
                else -> { MarkStore.delete(this, m.id); build() }
            }
        }.show()
    }
}

/** Rittenlog: elke afgewerkte passage met het eindgemiddelde. Eigen controle, geen bewijs (GPS). */
class PassagesActivity : DarkListActivity() {
    override fun build() {
        list.removeAllViews()
        heading("Passages", "Eindgemiddelde per traject volgens GPS. Je teller wijst doorgaans iets hoger aan dan GPS; de flitspaal meet zoals GPS de werkelijke snelheid.")
        val all = PassageLog.all(this)
        if (all.isEmpty()) row("Nog geen passages", "Afgewerkte trajecten verschijnen hier.")
        val f = SimpleDateFormat("EEE d/MM HH:mm", Locale("nl", "BE"))
        all.take(300).forEach { p ->
            val thr = be.koen.trajectcontrole.core.fineThreshold(p.limit)
            val col = when { p.avgKmh <= p.limit -> Theme.OK; p.avgKmh <= thr -> Theme.ORANGE; else -> Theme.RED }
            row(p.name, "${f.format(Date(p.wallMs))} · limiet ${p.limit}" + if (p.estimated) " · begin geschat" else "",
                "%.0f km/u".format(p.avgKmh), col)
        }
    }
}
