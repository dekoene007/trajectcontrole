package be.koen.trajectcontrole

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.app.Notification

/** Hulpfuncties voor permissies. */
object Perms {
    fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    fun fineLocation(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    /** "Altijd toestaan": nodig om vanuit de achtergrond (Bluetooth) een locatieservice te mogen starten. */
    fun backgroundLocation(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    fun btConnect(ctx: Context) = Build.VERSION.SDK_INT < 31 || granted(ctx, Manifest.permission.BLUETOOTH_CONNECT)
}

@Suppress("DEPRECATION")
fun Intent.btDevice(): BluetoothDevice? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    else getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

/**
 * Start de service zodra de telefoon verbindt met het gekozen Bluetooth-toestel (de auto).
 * ACL_CONNECTED is een van de systeem-broadcasts die nog in het manifest mogen staan, en een
 * Bluetooth-broadcast geeft (vanaf Android 12) een uitzondering om een voorgrondservice te starten.
 * Stoppen bij het verbreken gebeurt in de service zelf (met wachttijd).
 */
class BtReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val s = Settings(ctx)
        val addr = intent.btDevice()?.address ?: return
        if (!s.btAutostart || addr !in s.btDevices) return
        if (StatusBus.state.running) return   // draait al; de service zelf annuleert een geplande stop
        if (!Perms.fineLocation(ctx) || !Perms.backgroundLocation(ctx)) {
            notifyTapToStart(ctx, "Locatie 'Altijd toestaan' ontbreekt. Tik om te starten.")
            return
        }
        try {
            TrackingService.start(ctx, fromBluetooth = true)
        } catch (e: Exception) {
            // bv. ForegroundServiceStartNotAllowedException op sommige toestellen/batterijbesparing
            notifyTapToStart(ctx, "Automatisch starten geweigerd door Android. Tik om te starten.")
        }
    }

    private fun notifyTapToStart(ctx: Context, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("autostart", "Automatisch starten", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(ctx, 2,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_AUTOSTART, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(2, Notification.Builder(ctx, "autostart")
            .setSmallIcon(R.drawable.ic_dot)
            .setContentTitle("Trajectcontrole")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build())
    }
}
