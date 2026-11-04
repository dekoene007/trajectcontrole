package be.koen.trajectcontrole

import android.content.Context
import be.koen.trajectcontrole.core.EngineEvent
import be.koen.trajectcontrole.core.Fix
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logboek per rit (CSV) voor de vergelijkingsritten met Flitsmeister.
 * Locatie: Android/data/be.koen.trajectcontrole/files/logs/ (bereikbaar via USB of een bestandsbeheerder).
 * Regels: FIX;t;lat;lon;speed_ms;acc_m;course   en   EVT;t;type;segment;details
 * De FIX-regels hebben hetzelfde formaat als de testfixtures -> een echte rit kan je later
 * opnieuw door de rekenmotor laten lopen.
 */
class DriveLog(context: Context) {
    val file: File
    private val w: BufferedWriter
    private var lines = 0

    init {
        val dir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        file = File(dir, "rit_$stamp.csv")
        w = BufferedWriter(FileWriter(file))
        w.write("# rit $stamp, wandklok bij start=${System.currentTimeMillis()}\n")
    }

    @Synchronized fun fix(f: Fix) {
        w.write("FIX;%.3f;%.7f;%.7f;%.2f;%.1f;%s\n".format(Locale.US, f.t, f.lat, f.lon, f.speed, f.accuracy, f.course?.let { "%.0f".format(Locale.US, it) } ?: ""))
        if (++lines % 10 == 0) w.flush()
    }

    @Synchronized fun event(t: Double, e: EngineEvent) {
        val line = when (e) {
            is EngineEvent.Start -> "EVT;%.3f;START;%d;tEntry=%.3f%s".format(Locale.US, t, e.segment.id, e.tEntry, if (e.estimated) " geschat" else "")
            is EngineEvent.End -> "EVT;%.3f;EINDE;%d;gem=%.2f tEntry=%.3f tExit=%.3f".format(Locale.US, t, e.segment.id, e.avgKmh, e.tEntry, e.tExit)
            is EngineEvent.Left -> "EVT;%.3f;VERLATEN;%d;".format(Locale.US, t, e.segment.id)
            is EngineEvent.Approach -> "EVT;%.3f;NADERING;%d;afstand=%.0f".format(Locale.US, t, e.segment.id, e.distanceM)
        }
        w.write(line + "\n"); w.flush()
    }

    @Synchronized fun note(t: Double, text: String) { w.write("EVT;%.3f;NOTITIE;0;%s\n".format(Locale.US, t, text)); w.flush() }

    @Synchronized fun close() { try { w.flush(); w.close() } catch (_: Exception) {} }
}
