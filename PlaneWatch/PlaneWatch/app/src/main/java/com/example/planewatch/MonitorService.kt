package com.example.planewatch

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Notifier.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            Notifier.MONITOR_NOTIFICATION_ID,
            Notifier.monitorNotification(this, Store.registrations.value.size),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (loop?.isActive != true) {
            loop = scope.launch {
                while (isActive) {
                    pollAll()
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun pollAll() {
        val regs = Store.registrations.value
        // Refresh the "Watching N aircraft" text as registrations change.
        androidx.core.app.NotificationManagerCompat.from(this).let { nm ->
            if (nm.areNotificationsEnabled()) {
                @Suppress("MissingPermission")
                nm.notify(
                    Notifier.MONITOR_NOTIFICATION_ID,
                    Notifier.monitorNotification(this, regs.size),
                )
            }
        }
        for (reg in regs) {
            checkOne(reg)
        }
    }

    private fun checkOne(reg: String) {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val result = AdsbClient.lookup(reg)

        if (result.isFailure) {
            // Keep the previous airborne state so a flaky connection can't trigger false alerts.
            Store.setStatus(reg, "Couldn't reach the data feed (checked $time)")
            return
        }

        val sighting = result.getOrNull()
        val airborneNow = sighting?.airborne == true
        val wasAirborne = Store.wasAirborne(reg)

        Store.setStatus(
            reg,
            when {
                airborneNow -> "In the air · ${details(sighting!!)} · checked $time"
                sighting != null -> "On the ground · checked $time"
                else -> "Not currently tracked · checked $time"
            },
        )

        if (airborneNow && !wasAirborne) {
            Notifier.alert(this, reg, "$reg is in the air", details(sighting!!))
        } else if (!airborneNow && wasAirborne && Store.notifyLanding.value) {
            Notifier.alert(this, reg, "$reg is no longer in the air", "Landed or out of coverage")
        }
        Store.setAirborne(reg, airborneNow)
    }

    private fun details(s: Sighting): String {
        val parts = mutableListOf<String>()
        s.type?.let { parts += it }
        s.altitudeFt?.let { parts += "%,d ft".format(it) }
        s.speedKt?.let { parts += "$it kt" }
        return if (parts.isEmpty()) "Airborne" else parts.joinToString(" · ")
    }

    companion object {
        private const val POLL_INTERVAL_MS = 60_000L
    }
}
