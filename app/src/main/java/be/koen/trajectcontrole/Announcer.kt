package be.koen.trajectcontrole

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import be.koen.trajectcontrole.core.EngineEvent
import be.koen.trajectcontrole.core.Level
import be.koen.trajectcontrole.core.Status
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Spraakmeldingen. Loopt via de telefoon -> in de auto via Android Auto naar de speakers;
 * Waze/muziek wordt even zachter gezet (audio focus 'may duck').
 *
 * Meldingen (bewust weinig):
 *  - binnenrijden: lengte + limiet
 *  - niveau verandert (groen/oranje/rood), pas na 3 s stabiel
 *  - rood: herinnering elke 45 s
 *  - einde: eindgemiddelde
 */
class Announcer(context: Context, private val settings: Settings) : TextToSpeech.OnInitListener {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs).build()
    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile private var ready = false
    private var pending = 0

    // toestand voor niveaumeldingen
    private var announcedLevel: Level? = null
    private var candidateLevel: Level? = null
    private var candidateSince = 0.0
    private var lastRedReminder = 0.0

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val nl = Locale("nl", "BE")
        if (tts.isLanguageAvailable(nl) >= TextToSpeech.LANG_AVAILABLE) tts.setLanguage(nl)
        else tts.setLanguage(Locale("nl", "NL"))
        tts.setAudioAttributes(attrs)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) = release()
            @Deprecated("Deprecated in Java") override fun onError(id: String?) = release()
        })
        ready = true
    }

    @Synchronized private fun release() {
        pending = (pending - 1).coerceAtLeast(0)
        if (pending == 0) audio.abandonAudioFocusRequest(focus)
    }

    @Synchronized fun say(text: String) {
        if (!ready) return
        if (pending == 0) audio.requestAudioFocus(focus)
        pending++
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime())
    }

    private fun km(m: Double): String {
        val k = m / 1000.0
        return if (k >= 10) "${k.roundToInt()}" else String.format(Locale("nl", "BE"), "%.1f", k)
    }

    fun onEvent(e: EngineEvent) {
        when (e) {
            is EngineEvent.Approach -> if (settings.speakApproach) {
                val d = ((e.distanceM / 100.0).roundToInt() * 100).coerceAtLeast(100)
                say("Over $d meter trajectcontrole, ${e.segment.maxSpeed}.")
            }
            is EngineEvent.Start -> {
                announcedLevel = Level.GREEN; candidateLevel = null
                if (settings.speakStart) {
                    val extra = if (e.estimated) ", begin geschat" else ""
                    say("Trajectcontrole, ${km(e.segment.poly.length)} kilometer, ${e.segment.maxSpeed}$extra.")
                }
            }
            is EngineEvent.End -> {
                announcedLevel = null
                if (settings.speakEnd) say("Traject afgesloten. Gemiddeld ${e.avgKmh.roundToInt()}.")
            }
            is EngineEvent.Left -> {
                announcedLevel = null
                if (settings.speakEnd) say("Trajectcontrole verlaten.")
            }
        }
    }

    /** Aanroepen bij elke status-update (t in seconden, monotone klok). */
    fun onStatus(s: Status?, t: Double) {
        if (s == null || !settings.speakTooFast) return
        // pas na ~10 s in het traject niveaus melden: het gemiddelde is in het begin onrustig
        if (s.lengthM - s.remainingM < 150) return
        if (s.level != candidateLevel) { candidateLevel = s.level; candidateSince = t }
        if (t - candidateSince >= 3.0 && s.level != announcedLevel) {
            announcedLevel = s.level
            val avg = s.avgKmh.roundToInt()
            when (s.level) {
                Level.GREEN -> say("Weer onder de limiet. Gemiddeld $avg.")
                Level.ORANGE -> say("Gemiddeld $avg. Maximaal ${s.maxRestKmh.roundToInt()} voor de rest.")
                Level.RED -> { say("Te snel. Gemiddeld $avg."); lastRedReminder = t }
            }
        } else if (announcedLevel == Level.RED && t - lastRedReminder > 45) {
            lastRedReminder = t
            val mr = s.maxRestKmh.roundToInt()
            say(if (mr > 0) "Gemiddeld ${s.avgKmh.roundToInt()}. Maximaal $mr." else "Gemiddeld ${s.avgKmh.roundToInt()}.")
        }
    }

    fun shutdown() { tts.stop(); tts.shutdown(); audio.abandonAudioFocusRequest(focus) }
}
