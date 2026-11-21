package be.koen.trajectcontrole

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast

/** Keuzemenu na het vastleggen van een markering (gsm). De positie staat dan al vast. */
object MarkChoice {
    val LIMITS = listOf(30, 50, 70, 90, 100, 120)

    fun show(a: Activity, m: MarkStore.Mark, onDone: () -> Unit = {}) {
        val sug = MarkStore.suggestionFor(a, m)
        val labels = mutableListOf("Begin van een traject", "Einde van een traject")
        if (sug != null) labels += "Correctie: ${sug.segment.name} – ${sug.describe()}"
        labels += "Later beslissen"
        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Positie vastgelegd om ${MarkStore.fmt(m.id).substringAfter(' ')}")
            .setItems(labels.toTypedArray()) { _, i ->
                when {
                    i == 0 -> { MarkStore.setBegin(a, m.id); toast(a, "Begin opgeslagen. Markeer opnieuw bij het einde."); onDone() }
                    i == 1 -> chooseLimit(a) { lim -> MarkStore.setEinde(a, m.id, lim); report(a, m.id); onDone() }
                    sug != null && i == 2 -> { MarkStore.setCorrectie(a, m.id, sug); report(a, m.id); onDone() }
                    else -> { toast(a, "Bewaard bij je markeringen."); onDone() }
                }
            }
            .setOnCancelListener { onDone() }
            .show()
    }

    fun chooseLimit(a: Activity, onPick: (Int) -> Unit) {
        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Snelheidslimiet van dit traject")
            .setItems(LIMITS.map { "$it km/u" }.toTypedArray()) { _, i -> onPick(LIMITS[i]) }
            .show()
    }

    private fun report(a: Activity, id: Long) {
        val m = MarkStore.all(a).firstOrNull { it.id == id } ?: return
        toast(a, when {
            m.state == "actief" && m.kind == MarkStore.EINDE -> "Nieuw traject actief op dit toestel."
            m.state == "actief" -> "Correctie actief op dit toestel."
            m.state.startsWith("wacht") -> "Correctie bewaard; wordt actief zodra je het oude begin/einde passeert."
            else -> m.state.removePrefix("fout: ").replaceFirstChar { it.uppercase() }
        })
    }

    private fun toast(a: Activity, t: String) = Toast.makeText(a, t, Toast.LENGTH_LONG).show()
}
