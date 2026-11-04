package be.koen.trajectcontrole

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Instellingen, opgebouwd naar het voorbeeld van Flitsmeister. Bewust zonder extra bibliotheken. */
class SettingsActivity : Activity() {

    private lateinit var s: Settings
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = Settings(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 96) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.rgb(24, 24, 28)); addView(root) })
    }

    override fun onResume() { super.onResume(); build() }   // permissies kunnen intussen gewijzigd zijn

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults); build()
    }

    // ---------------- opbouw ----------------

    private fun build() {
        root.removeAllViews()
        header("Automatisch opstarten")
        switch("Starten bij verbinden met de auto (Bluetooth)", s.btAutostart) { s.btAutostart = it; build() }
        note("Start zodra je telefoon verbindt met het gekozen Bluetooth-toestel en stopt ${s.btStopDelaySec} s nadat de verbinding verbroken is. Even opnieuw verbinden binnen die tijd stopt de app niet.")
        if (s.btAutostart) btSection()

        header("Automatisch afsluiten")
        switch("Afsluiten na 5 minuten stilstand", s.autoStopIdle) { s.autoStopIdle = it }
        note("Enkel als je manueel gestart bent. Bij starten via Bluetooth stopt de app pas als de auto uit is, zodat hij niet in de file afsluit. Nooit midden in een traject.")

        header("Overlay")
        switch("Toon overlay over andere apps", s.overlay) { on ->
            s.overlay = on
            if (on && !AndroidSettings.canDrawOverlays(this)) {
                startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        note("Op het telefoonscherm, boven Waze: gemiddelde (groen/oranje/rood) en limiet. Enkel binnen een traject. Versleep om te verplaatsen. In Android Auto kan dit niet; daar dient het eigen scherm en de spraak." +
            if (s.overlay && !AndroidSettings.canDrawOverlays(this)) "\n⚠ Toestemming 'Weergeven boven andere apps' ontbreekt." else "")

        header("Trajectdata")
        val info = SegmentData.current(this)
        note("Versie ${info.version} · ${info.count} trajecten (${info.source})\nLaatste controle: ${SegmentData.lastMessage(this)}\n" +
            "Wordt wekelijks bijgewerkt uit OpenStreetMap via GitHub (${SegmentData.REPO}). De app kijkt hoogstens 1x per dag, enkel op wifi.")
        button("Nu controleren") {
            SegmentData.maybeUpdate(this, force = true) { msg ->
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show(); build()
            }
        }

        header("Spraak")
        switch("Melding ±400 m voor het begin", s.speakApproach) { s.speakApproach = it }
        switch("Bij begin van het traject", s.speakStart) { s.speakStart = it }
        switch("Bij einde van het traject", s.speakEnd) { s.speakEnd = it }
        switch("Bij te snel rijden (oranje/rood)", s.speakTooFast) { s.speakTooFast = it }
    }

    @SuppressLint("MissingPermission")
    private fun btSection() {
        // 1. Bluetooth-toestemming (Android 12+)
        if (!Perms.btConnect(this)) {
            note("Toestemming 'Apparaten in de buurt' is nodig om je auto te kiezen.")
            button("Toestemming geven") { requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 10) }
            return
        }
        // 2. Kies de auto uit de gekoppelde toestellen
        sub("Kies je auto")
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val bonded = adapter?.bondedDevices?.sortedBy { it.name ?: it.address } ?: emptyList()
        if (bonded.isEmpty()) note("Geen gekoppelde Bluetooth-toestellen gevonden (staat Bluetooth aan?).")
        for (d in bonded) {
            val addr = d.address
            root.addView(CheckBox(this).apply {
                text = d.name ?: addr; setTextColor(Color.WHITE); textSize = 17f
                isChecked = addr in s.btDevices
                setOnCheckedChangeListener { _, c -> s.btDevices = if (c) s.btDevices + addr else s.btDevices - addr }
            })
        }
        // 3. Locatie 'Altijd toestaan' — vereist om vanuit de achtergrond een locatieservice te mogen starten
        sub("Vereiste toestemmingen")
        val fine = Perms.fineLocation(this); val bg = Perms.backgroundLocation(this)
        status("Locatie: ${if (fine && bg) "altijd toegestaan ✓" else if (fine) "enkel tijdens gebruik ✗" else "niet toegestaan ✗"}", fine && bg)
        if (!fine) button("Locatie toestaan") {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 11)
        } else if (!bg) {
            note("Kies in het volgende scherm 'Altijd toestaan'. Zonder dit weigert Android het automatisch starten.")
            button("Altijd toestaan instellen") { requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 12) }
        }
        // 4. Batterijoptimalisatie — sommige fabrikanten blokkeren anders de Bluetooth-trigger
        val pm = getSystemService(PowerManager::class.java)
        val unrestricted = pm.isIgnoringBatteryOptimizations(packageName)
        status("Batterijoptimalisatie: ${if (unrestricted) "uitgeschakeld ✓" else "actief ✗ (kan automatisch starten blokkeren)"}", unrestricted)
        if (!unrestricted) button("Uitzondering toestaan") {
            @SuppressLint("BatteryLife")
            val i = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            startActivity(i)
        }
        if (Build.VERSION.SDK_INT >= 33 && !Perms.granted(this, Manifest.permission.POST_NOTIFICATIONS)) {
            status("Meldingen: niet toegestaan ✗", false)
            button("Meldingen toestaan") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 13) }
        }
    }

    // ---------------- bouwstenen ----------------

    private fun header(t: String) = root.addView(TextView(this).apply {
        text = t.uppercase(); setTextColor(Color.rgb(150, 160, 175)); textSize = 14f
        typeface = Typeface.DEFAULT_BOLD; setPadding(0, 56, 0, 12)
    })
    private fun sub(t: String) = root.addView(TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, 28, 0, 8)
    })
    private fun note(t: String) = root.addView(TextView(this).apply {
        text = t; setTextColor(Color.rgb(170, 175, 185)); textSize = 14f; setPadding(0, 4, 0, 16)
    })
    private fun status(t: String, ok: Boolean) = root.addView(TextView(this).apply {
        text = t; textSize = 15f; setPadding(0, 8, 0, 8)
        setTextColor(if (ok) Color.rgb(90, 200, 120) else Color.rgb(255, 170, 60))
    })
    private fun button(t: String, onClick: () -> Unit) = root.addView(Button(this).apply {
        text = t; setOnClickListener { onClick() }
    })
    @Suppress("DEPRECATION")
    private fun switch(t: String, value: Boolean, onChange: (Boolean) -> Unit) = root.addView(Switch(this).apply {
        text = t; textSize = 17f; setTextColor(Color.WHITE); isChecked = value; setPadding(0, 16, 0, 16)
        setOnCheckedChangeListener { _, c -> onChange(c) }
    })

}
