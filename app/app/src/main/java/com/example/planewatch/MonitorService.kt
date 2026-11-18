package com.example.planewatch

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
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
    private val failures = mutableMapOf<String, Int>()
    private val seenRare = mutableMapOf<String, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Notifier.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notifier.monitorNotification(this, Store.registrations.value.size)
        val dataSync = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        // Only claim the location type once the user has actually granted location access.
        val withLocation = Store.rareEnabled.value && LocationHelper.hasPermission(this)
        try {
            ServiceCompat.startForeground(
                this,
                Notifier.MONITOR_NOTIFICATION_ID,
                notification,
                if (withLocation) dataSync or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else dataSync,
            )
        } catch (e: SecurityException) {
            ServiceCompat.startForeground(this, Notifier.MONITOR_NOTIFICATION_ID, notification, dataSync)
        }
        if (loop?.isActive != true) {
            loop = scope.launch {
                while (isActive) {
                    pollAll()
                    try {
                        checkRare()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                    }
                    delay(Store.intervalSeconds.value * 1_000L)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun pollAll() {
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
            // Stay well under the free feeds' rate limits when watching several aircraft.
            delay(1_500)
        }
    }

    /** Alerts once per aircraft when a selected rare type is inside the chosen radius. */
    private suspend fun checkRare() {
        if (!Store.rareEnabled.value) return
        val types = Store.rareTypes.value
        val military = Store.rareMilitary.value
        if (types.isEmpty() && !military) return

        val loc = LocationHelper.current(this) ?: return
        val planes = NearbyClient.fetch(loc.latitude, loc.longitude, Store.rareRadiusNm.value)
            .getOrNull() ?: return

        val now = System.currentTimeMillis()
        for (p in planes) {
            if (!RareTypes.matches(p, types, military)) continue
            val isNew = seenRare[p.hex] == null
            seenRare[p.hex] = now
            if (!isNew) continue

            val dist = Geo.distanceNm(loc.latitude, loc.longitude, p.lat, p.lon)
            val dir = Geo.compass(Geo.bearingDeg(loc.latitude, loc.longitude, p.lat, p.lon))
            val name = RareTypes.label(p.type) ?: p.desc ?: p.type ?: "Military aircraft"
            val parts = mutableListOf<String>()
            (p.callsign ?: p.reg)?.let { parts += it }
            parts += "%.0f nm %s".format(dist, dir)
            if (p.onGround) parts += "on the ground"
            else p.altitudeFt?.let { parts += "%,d ft".format(it) }
            p.speedKt?.let { parts += "$it kt" }
            Notifier.alert(
                this,
                p.hex,
                "Rare aircraft nearby: $name",
                parts.joinToString(" · "),
                Notifier.CHANNEL_RARE,
            )
        }
        // Forget aircraft not seen for 15 minutes so they can alert again on a later pass.
        seenRare.entries.removeAll { now - it.value > 15 * 60_000L }
    }

    private fun checkOne(reg: String) {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val result = AdsbClient.lookup(reg)

        if (result.isFailure) {
            // Keep the previous airborne state so a flaky connection can't trigger false alerts.
            val fails = (failures[reg] ?: 0) + 1
            failures[reg] = fails
            // One-off blips are normal on free feeds; only surface the error if it keeps happening.
            if (fails >= 3) {
                Store.setStatus(
                    reg,
                    "Feed problem: ${result.exceptionOrNull()?.message.orEmpty()} (checked $time)",
                )
            }
            return
        }
        failures[reg] = 0

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
}
