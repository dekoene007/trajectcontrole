package be.koen.trajectcontrole

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import be.koen.trajectcontrole.core.Engine
import be.koen.trajectcontrole.core.EngineEvent
import be.koen.trajectcontrole.core.Fix
import be.koen.trajectcontrole.core.Segment
import be.koen.trajectcontrole.core.SegmentStore
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Voorgrondservice: GPS (1 Hz) -> rekenmotor -> spraak, logboek, notificatie, overlay en schermen.
 * Gebruikt de GPS-provider van Android zelf (geen Google Play-services nodig).
 *
 * Automatisch stoppen:
 *  - gestart via Bluetooth: stopt <btStopDelaySec> s nadat de auto-Bluetooth verbreekt
 *    (opnieuw verbinden binnen die tijd = niet stoppen);
 *  - anders (optioneel): na 5 min stilstand, maar nooit midden in een traject.
 */
class TrackingService : Service(), LocationListener {

    companion object {
        private const val CHANNEL = "tracking"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "be.koen.trajectcontrole.STOP"
        const val EXTRA_FROM_BT = "from_bt"
        private const val IDLE_STOP_S = 300.0

        fun start(ctx: Context, fromBluetooth: Boolean = false) {
            val i = Intent(ctx, TrackingService::class.java).putExtra(EXTRA_FROM_BT, fromBluetooth)
            ctx.startForegroundService(i)
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TrackingService::class.java))
        }

        /** De lopende service (null als ze niet draait). */
        @Volatile var current: TrackingService? = null
            private set

        /**
         * Trajecten laden: nieuwste van meegeleverd/bijgewerkt, dan manuele correcties (Android/data/…/correcties.tsv),
         * dan eigen trajecten en eigen correcties uit markeringen (files/eigen*.tsv).
         */
        fun loadSegments(ctx: Context): List<Segment> {
            val base = SegmentData.openBest(ctx)   // bijgewerkte versie uit GitHub als die nieuwer is
            val extra = listOf(File(ctx.getExternalFilesDir(null), "correcties.tsv"), MarkStore.ownSegmentsFile(ctx))
                .filter { it.exists() }.map { it.reader() }
            val geom = listOf(MarkStore.ownCorrectionsFile(ctx)).filter { it.exists() }.map { it.reader() }
            return SegmentStore.parse(base, extra, geom)
        }
    }

    private lateinit var settings: Settings
    private lateinit var engine: Engine
    private lateinit var announcer: Announcer
    private lateinit var overlay: OverlayController
    private var log: DriveLog? = null
    private lateinit var lm: LocationManager
    private val main = Handler(Looper.getMainLooper())
    private var lastNotif = 0L
    private var lastFixT = 0.0
    private var lastMovingT = 0.0
    private var inSegment = false
    private var started = false
    private var fromBluetooth = false
    private var btReceiverRegistered = false
    private val stopRunnable = Runnable {
        log?.note(lastFixT, "gestopt: Bluetooth verbroken")
        stopSelf()
    }

    /** Volgt verbinden/verbreken van de gekozen auto-Bluetooth zolang de service loopt. */
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val addr = i.btDevice()?.address ?: return
            if (addr !in settings.btDevices) return
            when (i.action) {
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> if (settings.btAutostart) {
                    main.removeCallbacks(stopRunnable)
                    main.postDelayed(stopRunnable, settings.btStopDelaySec * 1000L)
                    log?.note(lastFixT, "Bluetooth verbroken, stop over ${settings.btStopDelaySec} s")
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    main.removeCallbacks(stopRunnable)
                    fromBluetooth = true
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.getBooleanExtra(EXTRA_FROM_BT, false) == true) fromBluetooth = true
        if (started) return START_STICKY
        started = true

        settings = Settings(this)
        createChannel()
        val notif = buildNotification("Wacht op GPS…")
        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)

        current = this
        FixHistory.clear()
        val segs = loadSegments(this)
        SegmentData.maybeUpdate(this)   // op de achtergrond; enkel op wifi, hoogstens 1x per dag
        announcer = Announcer(this, settings)
        overlay = OverlayController(this, settings)
        log = DriveLog(this).also { it.note(0.0, if (fromBluetooth) "gestart via Bluetooth" else "gestart manueel") }
        engine = Engine(segs) { e -> onEngineEvent(e) }
        StatusBus.update { it.copy(rideFile = log?.file?.name) }
        lastMovingT = SystemClock.elapsedRealtimeNanos() / 1e9
        StatusBus.update { it.copy(running = true, segmentCount = segs.size, status = null) }

        if (Perms.btConnect(this)) {
            val f = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED); addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            // systeem-broadcasts: RECEIVER_EXPORTED is vereist om ze te ontvangen
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(btReceiver, f, Context.RECEIVER_EXPORTED)
            else registerReceiver(btReceiver, f)
            btReceiverRegistered = true
        }

        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        requestUpdates()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")   // permissie wordt in MainActivity gevraagd
    private fun requestUpdates() {
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        } catch (e: SecurityException) {
            StatusBus.update { it.copy(running = false) }
            stopSelf()
        }
    }

    private fun onEngineEvent(e: EngineEvent) {
        announcer.onEvent(e)
        log?.event(lastFixT, e)
        when (e) {
            is EngineEvent.Start -> inSegment = true
            is EngineEvent.Left -> inSegment = false
            is EngineEvent.Approach -> {}
            is EngineEvent.End -> {
                inSegment = false
                PassageLog.add(this, PassageLog.Passage(System.currentTimeMillis(), e.segment.id, e.segment.name,
                    engine.limitOf(e.segment), e.avgKmh, e.segment.poly.length, e.estimated))
                main.post { MarkStore.resolvePending(this); if (reloadPending) reloadSegments() }
                val txt = "${e.segment.name}: gemiddeld %.1f km/u (limiet %d)".format(Locale("nl", "BE"), e.avgKmh, e.segment.maxSpeed)
                StatusBus.update { it.copy(lastResult = txt) }
            }
        }
    }

    override fun onLocationChanged(loc: Location) {
        // monotone klok: immuun voor wijzigingen van de wandklok
        val t = loc.elapsedRealtimeNanos / 1e9
        lastFixT = t
        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        val course = if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 2f) loc.bearing.toDouble() else null
        val f = Fix(t, loc.latitude, loc.longitude, speed,
            if (loc.hasAccuracy()) loc.accuracy.toDouble() else 99.0, course)
        log?.fix(f)
        FixHistory.add(f)
        val st = engine.onFix(f)
        inSegment = st != null
        announcer.onStatus(st, t)
        overlay.update(st)
        StatusBus.update { it.copy(status = st, gpsOk = f.accuracy <= 30.0, lat = f.lat, lon = f.lon,
            heading = course ?: it.heading, gpsKmh = speed * 3.6) }

        // automatisch afsluiten na 5 min stilstand (niet bij Bluetooth-start: dan stopt hij in de file)
        if (speed > 2.0) lastMovingT = t
        if (settings.autoStopIdle && !fromBluetooth && !inSegment && t - lastMovingT > IDLE_STOP_S) {
            log?.note(t, "gestopt: 5 min stilstand")
            stopSelf()
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastNotif > 2000) {
            lastNotif = now
            val text = if (st == null) "Geen trajectcontrole" else
                "Gem. ${st.avgKmh.roundToInt()} / ${st.limit} · max ${st.maxRestThrKmh.roundToInt()} · nog ${"%.1f".format(Locale("nl", "BE"), st.remainingM / 1000)} km"
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        }
    }

    // Oudere Android-versies vragen deze nog
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) { StatusBus.update { it.copy(gpsOk = false) } }

    // ---------------- voor schermen en markeringen ----------------

    private var reloadPending = false

    fun engineSegments(): Collection<Segment> = engine.segments

    /** Wisselende limiet voor het traject dat nu getoond wordt (null = terug naar de vaste limiet). */
    fun setLimit(limit: Int?) {
        val id = StatusBus.state.status?.segmentId ?: return
        engine.setLimitOverride(id, limit)
        log?.note(lastFixT, "limiet manueel: ${limit ?: "vast"} voor $id")
        val st = engine.status(lastFixT)
        StatusBus.update { it.copy(status = st) }
    }

    /** Trajecten opnieuw laden (na een markering). Binnen een traject wordt gewacht tot het einde. */
    fun reloadSegments() {
        if (engine.activeIds.isNotEmpty()) { reloadPending = true; return }
        reloadPending = false
        val segs = loadSegments(this)
        engine = Engine(segs) { e -> onEngineEvent(e) }
        StatusBus.update { it.copy(segmentCount = segs.size, dataVersion = it.dataVersion + 1) }
    }

    override fun onDestroy() {
        if (current === this) current = null
        main.removeCallbacks(stopRunnable)
        if (started) {
            try { lm.removeUpdates(this) } catch (_: Exception) {}
            if (btReceiverRegistered) try { unregisterReceiver(btReceiver) } catch (_: Exception) {}
            overlay.hide()
            announcer.shutdown()
            log?.close()
        }
        StatusBus.update { it.copy(running = false, status = null) }
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Trajectcontrole", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_dot)
            .setContentTitle(if (fromBluetooth) "Trajectcontrole actief (auto)" else "Trajectcontrole actief")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stoppen", stop).build())
            .build()
    }
}
