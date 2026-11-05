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
}
