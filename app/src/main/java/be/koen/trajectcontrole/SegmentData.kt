package be.koen.trajectcontrole

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import be.koen.trajectcontrole.core.SegmentStore
import org.json.JSONObject
import java.io.File
import java.io.Reader
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Trajectdata: meegeleverde versie (asset) + automatisch bijgewerkte versie uit GitHub.
 *
 * De GitHub-workflow publiceert wekelijks data/segments.tsv + data/meta.json (versie, sha256, aantal).
 * De app controleert hoogstens 1x per dag en enkel op wifi (manueel: altijd), downloadt alleen bij
 * een hogere versie, controleert de sha256 en of het bestand leesbaar is met >= 100 trajecten,
 * en vervangt het bestand pas daarna (atomair). Lukt iets niet, dan blijft de vorige versie.
 * De nieuwe data wordt gebruikt vanaf de volgende start van de service.
 */
object SegmentData {

    /** Pas aan als de repository een andere naam krijgt. */
    const val REPO = "dekoene007/trajectcontrole"
    private const val BASE = "https://raw.githubusercontent.com/$REPO/main/data/"
    private const val MIN_SEGMENTS = 100
    private const val CHECK_EVERY_MS = 24L * 3600 * 1000

    data class Info(val version: Long, val count: Int, val source: String)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("data", Context.MODE_PRIVATE)
    private fun downloaded(ctx: Context) = File(ctx.filesDir, "segments.tsv")

    private fun header(firstLine: String?): Pair<Long, Int> {
        if (firstLine == null || !firstLine.startsWith("#")) return 0L to 0
        val v = Regex("versie=(\\d+)").find(firstLine)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val n = Regex("aantal=(\\d+)").find(firstLine)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return v to n
    }

    private fun assetHeader(ctx: Context) = ctx.assets.open("segments.tsv").bufferedReader().use { header(it.readLine()) }
    private fun fileHeader(ctx: Context): Pair<Long, Int>? =
        downloaded(ctx).takeIf { it.exists() }?.bufferedReader()?.use { header(it.readLine()) }

    /** Welke versie wordt gebruikt (de hoogste van asset en download). */
    fun current(ctx: Context): Info {
        val a = assetHeader(ctx)
        val f = fileHeader(ctx)
        return if (f != null && f.first > a.first) Info(f.first, f.second, "bijgewerkt") else Info(a.first, a.second, "meegeleverd")
    }

    fun openBest(ctx: Context): Reader =
        if (current(ctx).source == "bijgewerkt") downloaded(ctx).reader() else ctx.assets.open("segments.tsv").reader()

    fun lastMessage(ctx: Context): String = prefs(ctx).getString("msg", "nog niet gecontroleerd")!!

    private fun unmetered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * Controleer op een nieuwe versie. force = manueel (negeert dagelimiet en wifi-voorwaarde).
     * done wordt op de hoofdthread aangeroepen met een korte statusboodschap.
     */
    fun maybeUpdate(ctx: Context, force: Boolean = false, done: ((String) -> Unit)? = null) {
        val app = ctx.applicationContext
        val p = prefs(app)
        val now = System.currentTimeMillis()
        if (!force && now - p.getLong("last_check", 0) < CHECK_EVERY_MS) return
        if (!force && !unmetered(app)) return
        p.edit().putLong("last_check", now).apply()
        Thread {
            val msg = try { update(app) } catch (e: Exception) { "controle mislukt: ${e.message ?: e.javaClass.simpleName}" }
            val stamp = SimpleDateFormat("d MMM HH:mm", Locale("nl", "BE")).format(Date())
            p.edit().putString("msg", "$stamp · $msg").apply()
            Handler(Looper.getMainLooper()).post { done?.invoke(msg) }
        }.start()
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    private fun update(ctx: Context): String {
        val meta = JSONObject(String(get(BASE + "meta.json?t=" + System.currentTimeMillis()), Charsets.UTF_8))
        val remoteVersion = meta.optLong("versie", 0)
        val have = current(ctx).version
        if (remoteVersion <= have) return "al up-to-date (versie $have)"

        val bytes = get(BASE + "segments.tsv?v=$remoteVersion")
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (!sha.equals(meta.optString("sha256"), ignoreCase = true)) return "download beschadigd (sha256), vorige versie behouden"

        val text = String(bytes, Charsets.UTF_8)
        val (v, _) = header(text.lineSequence().firstOrNull())
        if (v != remoteVersion) return "versie klopt niet, vorige versie behouden"
        val segs = SegmentStore.parse(StringReader(text))
        val distinct = segs.count { it.id > 0 }
        if (distinct < MIN_SEGMENTS) return "slechts $distinct trajecten, vorige versie behouden"

        val tmp = File(ctx.filesDir, "segments.tsv.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(downloaded(ctx))) { tmp.delete(); return "opslaan mislukt" }
        return "bijgewerkt naar versie $remoteVersion ($distinct trajecten); actief vanaf de volgende start"
    }
}
