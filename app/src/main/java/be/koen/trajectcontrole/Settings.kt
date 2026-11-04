package be.koen.trajectcontrole

import android.content.Context
import android.content.SharedPreferences

/** Instellingen (SharedPreferences). Standaardwaarden = wat Koen in Flitsmeister aan heeft staan. */
class Settings(ctx: Context) {
    private val p: SharedPreferences = ctx.applicationContext.getSharedPreferences("instellingen", Context.MODE_PRIVATE)

    // --- automatisch starten / stoppen ---
    var btAutostart: Boolean
        get() = p.getBoolean("bt_autostart", false); set(v) = p.edit().putBoolean("bt_autostart", v).apply()
    /** MAC-adressen van de gekozen Bluetooth-toestellen (de auto). */
    var btDevices: Set<String>
        get() = p.getStringSet("bt_devices", emptySet())!!.toSet(); set(v) = p.edit().putStringSet("bt_devices", v).apply()
    /** Wachttijd na het verbreken van Bluetooth voor de app stopt (korte onderbrekingen opvangen). */
    var btStopDelaySec: Int
        get() = p.getInt("bt_stop_delay", 30); set(v) = p.edit().putInt("bt_stop_delay", v).apply()
    /** Stoppen na 5 min stilstand, enkel als de app niet via Bluetooth gestart is (anders stopt hij in de file). */
    var autoStopIdle: Boolean
        get() = p.getBoolean("auto_stop_idle", true); set(v) = p.edit().putBoolean("auto_stop_idle", v).apply()

    // --- weergave ---
    var overlay: Boolean
        get() = p.getBoolean("overlay", false); set(v) = p.edit().putBoolean("overlay", v).apply()
    var overlayX: Int
        get() = p.getInt("overlay_x", 40); set(v) = p.edit().putInt("overlay_x", v).apply()
    var overlayY: Int
        get() = p.getInt("overlay_y", 300); set(v) = p.edit().putInt("overlay_y", v).apply()

    // --- spraak ---
    var speakApproach: Boolean
        get() = p.getBoolean("speak_approach", true); set(v) = p.edit().putBoolean("speak_approach", v).apply()
    var speakStart: Boolean
        get() = p.getBoolean("speak_start", true); set(v) = p.edit().putBoolean("speak_start", v).apply()
    var speakEnd: Boolean
        get() = p.getBoolean("speak_end", true); set(v) = p.edit().putBoolean("speak_end", v).apply()
    var speakTooFast: Boolean
        get() = p.getBoolean("speak_too_fast", true); set(v) = p.edit().putBoolean("speak_too_fast", v).apply()
}
