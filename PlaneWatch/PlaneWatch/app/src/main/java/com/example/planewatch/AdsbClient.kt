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
