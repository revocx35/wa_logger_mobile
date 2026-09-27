package io.github.revocx35.walmobile.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.revocx35.walmobile.MainActivity
import io.github.revocx35.walmobile.R
import io.github.revocx35.walmobile.WaLoggerApp
import io.github.revocx35.walmobile.core.WaState
import io.github.revocx35.walmobile.core.WaStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the app process — and with it the hidden WhatsApp Web page — alive while the app is in the
 * background, as a foreground service with a permanent status notification. Optionally holds a partial
 * wake lock so events are processed at once even with the screen off (Settings → Background).
 */
class WaService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.channels(this)
        goForeground(Notifier.statusNotification(this, null))
        val graph = (application as WaLoggerApp).graph
        scope.launch {
            graph.vault.load()
            if (graph.vault.writer() == null) {
                stopSelf()
                return@launch
            }
            graph.engine.start()
        }
        scope.launch {
            graph.engine.status.combine(graph.prefs.keepAwake) { s, awake -> s to awake }.collect { (s, awake) ->
                Notifier.updateStatus(this@WaService, s)
                setWakeLock(awake)
            }
        }
    }

    private fun goForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifier.STATUS_ID, n, type)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun setWakeLock(on: Boolean) {
        if (on && wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "walm:logger").apply {
                setReferenceCounted(false)
                acquire()
            }
        } else if (!on && wakeLock != null) {
            wakeLock?.release()
            wakeLock = null
        }
    }

    override fun onDestroy() {
        setWakeLock(false)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, WaService::class.java)) }
                .onFailure { (context.applicationContext as? WaLoggerApp)?.graph?.diagnostics?.recordNonFatal("service-start", it) }
        }
    }
}

/** Starts logging after a reboot or an app update (if set up and enabled in Settings). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED && intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        val app = context.applicationContext as? WaLoggerApp ?: return
        if (!app.graph.prefs.autostart.value) return
        WaService.start(context)
    }
}

object Notifier {
    const val STATUS_ID = 1
    const val ALERT_ID = 2
    private const val DELETED_BASE = 1000
    private const val CH_STATUS = "status"
    private const val CH_ALERT = "alerts"
    private const val CH_DELETED = "deleted"
    const val EXTRA_OPEN = "open"

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Logging status", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Shown while wa_logger keeps WhatsApp Web running in the background."
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Connection problems", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "When WhatsApp is unlinked and messages are no longer logged."
        })
        nm.createNotificationChannel(NotificationChannel(CH_DELETED, "Deleted messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "When someone deletes a message for everyone (the logged copy stays in the app)."
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }

    private fun open(context: Context, target: String?): PendingIntent {
        val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (target != null) i.putExtra(EXTRA_OPEN, target)
        return PendingIntent.getActivity(context, target?.hashCode() ?: 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun statusText(s: WaStatus?): String = when (s?.state) {
        null, WaState.Idle, WaState.Starting -> "Starting WhatsApp Web…"
        WaState.Qr -> "WhatsApp is not linked — tap to link"
        WaState.Authenticating -> "Connecting to WhatsApp…"
        WaState.Syncing -> "Importing chat history…"
        WaState.Ready -> "Logging your WhatsApp messages" + if (s.mediaQueue > 0) " · ${s.mediaQueue} media pending" else ""
        WaState.Disconnected -> "Not logging: WhatsApp is unlinked — tap to link"
        WaState.Error -> s.detail ?: "Reconnecting…"
    }

    fun statusNotification(context: Context, s: WaStatus?): Notification =
        NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_logger)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(statusText(s))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open(context, if (s?.state == WaState.Qr || s?.state == WaState.Disconnected) "wa" else null))
            .build()

    private var lastAlerted: WaState? = null

    fun updateStatus(context: Context, s: WaStatus) {
        val nm = context.getSystemService(NotificationManager::class.java)
        runCatching { nm.notify(STATUS_ID, statusNotification(context, s)) }
        // Alert once when logging stops because WhatsApp was unlinked.
        if (s.state == WaState.Disconnected && lastAlerted != WaState.Disconnected) {
            runCatching {
                nm.notify(
                    ALERT_ID,
                    NotificationCompat.Builder(context, CH_ALERT)
                        .setSmallIcon(R.drawable.ic_stat_logger)
                        .setContentTitle("WhatsApp was unlinked")
                        .setContentText("New messages are not logged until you link WhatsApp again.")
                        .setAutoCancel(true)
                        .setContentIntent(open(context, "wa"))
                        .build(),
                )
            }
        }
        if (s.state == WaState.Ready) runCatching { nm.cancel(ALERT_ID) }
        lastAlerted = s.state
    }

    /** "A message was deleted in <chat>" (no content; the lock screen shows only the public version). */
    fun deleted(context: Context, chatName: String?, count: Int) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val public = NotificationCompat.Builder(context, CH_DELETED)
            .setSmallIcon(R.drawable.ic_stat_logger)
            .setContentTitle("A message was deleted")
            .build()
        val text = if (count > 1) "$count messages were deleted" else "A message was deleted"
        runCatching {
            nm.notify(
                DELETED_BASE + (chatName?.hashCode()?.and(0xFFFF) ?: 0),
                NotificationCompat.Builder(context, CH_DELETED)
                    .setSmallIcon(R.drawable.ic_stat_logger)
                    .setContentTitle(chatName ?: text)
                    .setContentText(if (chatName != null) "$text — the logged copy is in wa_logger" else "The logged copy is in wa_logger")
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(public)
                    .setAutoCancel(true)
                    .setContentIntent(open(context, "deleted"))
                    .build(),
            )
        }
    }
}
