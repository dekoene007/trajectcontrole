package be.koen.trajectcontrole

import android.content.Context
import android.content.Intent
import android.net.Uri
import be.koen.trajectcontrole.core.SegmentStore
import be.koen.trajectcontrole.core.Track
import java.net.URLEncoder

/**
 * Indienen van eigen trajecten en correcties: opent een vooraf ingevuld GitHub-issue in de browser.
 * De app krijgt bewust geen schrijftoegang tot GitHub. De workflow 'meldingen-verwerken' leest het
 * blok ```trajectcontrole uit het issue (enkel van de eigenaar van de repository) en neemt het op in de data.
 */
object IssueSubmit {
    private const val MAX_URL = 7500

    fun lines(marks: List<MarkStore.Mark>, tolM: Double = 4.0): List<String> = marks.mapNotNull { m ->
        val g = m.geom ?: return@mapNotNull null
        val pts = SegmentStore.formatPoints(Track.simplify(SegmentStore.parsePoints(g), tolM))
        when (m.kind) {
            MarkStore.EINDE -> "nieuw;id=${m.resultId};limiet=${m.limit ?: 50};beide=${if (m.both) 1 else 0};naam=${(m.name ?: "Eigen traject").replace(';', ',')};punten=$pts"
            MarkStore.CORR -> "correctie;id=${m.corrSeg};kant=${if (m.corrAtStart == true) "begin" else "einde"};naam=${(m.name ?: "").replace(';', ',')};punten=$pts"
            else -> null
        }
    }

    /** Geeft de URL terug, of null als de inhoud te groot is voor een URL (dan CSV delen). */
    fun url(ctx: Context, marks: List<MarkStore.Mark>): String? {
        var tol = 4.0
        while (tol <= 32.0) {
            val body = "Ingediend vanuit de app (${BuildConfigLite.version(ctx)}).\n\n```trajectcontrole\n" +
                lines(marks, tol).joinToString("\n") + "\n```\n"
            val title = "Melding: " + marks.joinToString(", ") { it.name ?: "markering" }.take(80)
            val u = "https://github.com/${SegmentData.REPO}/issues/new?labels=melding&title=" +
                URLEncoder.encode(title, "UTF-8") + "&body=" + URLEncoder.encode(body, "UTF-8")
            if (u.length <= MAX_URL) return u
            tol *= 2
        }
        return null
    }

    fun open(ctx: Context, marks: List<MarkStore.Mark>): Boolean {
        val u = url(ctx, marks) ?: return false
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** Zelfde inhoud als tekstbestand delen (mail, Drive, …). */
    fun share(ctx: Context, marks: List<MarkStore.Mark>) {
        val text = "```trajectcontrole\n" + lines(marks).joinToString("\n") + "\n```\n"
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Trajectcontrole – markeringen").putExtra(Intent.EXTRA_TEXT, text), "Delen")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

object BuildConfigLite {
    fun version(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }
}
