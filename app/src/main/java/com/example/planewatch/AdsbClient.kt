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
    private val failures = mutableMapOf<String, Int>()

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

    companion object {
        private const val POLL_INTERVAL_MS = 60_000L
    }
}
package com.example.planewatch

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Sighting(
    val airborne: Boolean,
    val altitudeFt: Int?,
    val speedKt: Int?,
    val type: String?,
    val lat: Double?,
    val lon: Double?,
)

/**
 * Looks up a registration on free community ADS-B aggregators.
 * Both expose the same readsb/tar1090-style JSON: GET <base>/<registration> -> {"ac":[{...}]}
 * The first host that answers is used; the second is a fallback.
 */
object AdsbClient {
    private val bases = listOf(
        "https://api.airplanes.live/v2/reg/",
        "https://api.adsb.lol/v2/reg/",
    )

    /**
     * Success(null)      -> feed answered, aircraft is not currently being tracked
     * Success(sighting)  -> aircraft is being tracked (check .airborne)
     * Failure            -> no feed could be reached; caller should keep previous state
     */
    fun lookup(registration: String): Result<Sighting?> {
        val errors = mutableListOf<String>()
        for (base in bases) {
            val host = URL(base).host
            // Try each feed up to twice before moving on to the next one.
            repeat(2) { attempt ->
                try {
                    val (code, body) = httpGet(base + URLEncoder.encode(registration, "UTF-8"))
                    when {
                        code == 200 -> return Result.success(parse(body))
                        // Some feeds answer 404 when nothing matches: that means "not tracked", not an error.
                        code == 404 -> return Result.success(null)
                        else -> errors += "$host HTTP $code"
                    }
                } catch (t: Throwable) {
                    errors += "$host ${t.javaClass.simpleName}"
                }
                if (attempt == 0) Thread.sleep(1_500)
            }
        }
        return Result.failure(java.io.IOException(errors.distinct().joinToString("; ")))
    }

    private fun httpGet(url: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "PlaneWatch-Android/1.0")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to body
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): Sighting? {
        val ac = JSONObject(body).optJSONArray("ac") ?: return null
        if (ac.length() == 0) return null
        val o = ac.getJSONObject(0)

        val altRaw = o.opt("alt_baro")
        val onGround = altRaw is String && altRaw.equals("ground", ignoreCase = true)
        val altitude = (altRaw as? Number)?.toInt()
        val speed = if (o.has("gs")) o.optDouble("gs").toInt() else null

        // Airborne if the feed doesn't say "ground" and we have either a real altitude
        // or a speed that isn't just taxiing.
        val airborne = !onGround && ((altitude != null && altitude > 0) || (speed != null && speed > 60))

        return Sighting(
            airborne = airborne,
            altitudeFt = altitude,
            speedKt = speed,
            type = o.optString("t").ifBlank { null },
            lat = if (o.has("lat")) o.optDouble("lat") else null,
            lon = if (o.has("lon")) o.optDouble("lon") else null,
        )
    }
}package com.example.planewatch

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Sighting(
    val airborne: Boolean,
    val altitudeFt: Int?,
    val speedKt: Int?,
    val type: String?,
    val lat: Double?,
    val lon: Double?,
)

/**
 * Looks up a registration on free community ADS-B aggregators.
 * Both expose the same readsb/tar1090-style JSON: GET <base>/<registration> -> {"ac":[{...}]}
 * The first host that answers is used; the second is a fallback.
 */
object AdsbClient {
    private val bases = listOf(
        "https://api.airplanes.live/v2/reg/",
        "https://api.adsb.lol/v2/reg/",
    )

    /**
     * Success(null)      -> feed answered, aircraft is not currently being tracked
     * Success(sighting)  -> aircraft is being tracked (check .airborne)
     * Failure            -> no feed could be reached; caller should keep previous state
     */
    fun lookup(registration: String): Result<Sighting?> {
        var lastError: Throwable = IllegalStateException("No feeds configured")
        for (base in bases) {
            try {
                val body = httpGet(base + URLEncoder.encode(registration, "UTF-8"))
                return Result.success(parse(body))
            } catch (t: Throwable) {
                lastError = t
            }
        }
        return Result.failure(lastError)
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "PlaneWatch-Android/1.0")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code != 200) throw java.io.IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): Sighting? {
        val ac = JSONObject(body).optJSONArray("ac") ?: return null
        if (ac.length() == 0) return null
        val o = ac.getJSONObject(0)

        val altRaw = o.opt("alt_baro")
        val onGround = altRaw is String && altRaw.equals("ground", ignoreCase = true)
        val altitude = (altRaw as? Number)?.toInt()
        val speed = if (o.has("gs")) o.optDouble("gs").toInt() else null

        // Airborne if the feed doesn't say "ground" and we have either a real altitude
        // or a speed that isn't just taxiing.
        val airborne = !onGround && ((altitude != null && altitude > 0) || (speed != null && speed > 60))

        return Sighting(
            airborne = airborne,
            altitudeFt = altitude,
            speedKt = speed,
            type = o.optString("t").ifBlank { null },
            lat = if (o.has("lat")) o.optDouble("lat") else null,
            lon = if (o.has("lon")) o.optDouble("lon") else null,
        )
    }
}
