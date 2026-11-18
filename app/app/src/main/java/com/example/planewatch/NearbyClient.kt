package com.example.planewatch

import org.json.JSONObject
import java.util.Locale

data class Plane(
    val hex: String,
    val reg: String?,
    val type: String?,
    val callsign: String?,
    val desc: String?,
    val lat: Double,
    val lon: Double,
    val altitudeFt: Int?,
    val onGround: Boolean,
    val speedKt: Int?,
    val track: Double?,
    val military: Boolean,
) {
    /** Short name to show on the radar. */
    val label: String get() = callsign ?: reg ?: type ?: hex.uppercase()
}

/** Fetches every aircraft within a radius of a point from the free community ADS-B feeds. */
object NearbyClient {
    private val bases = listOf(
        "https://api.airplanes.live/v2/point/",
        "https://api.adsb.lol/v2/point/",
    )

    fun fetch(lat: Double, lon: Double, radiusNm: Int): Result<List<Plane>> {
        val errors = mutableListOf<String>()
        // Coordinates are rounded to 2 decimals (about 1 km) before leaving the phone.
        val path = String.format(Locale.US, "%.2f/%.2f/%d", lat, lon, radiusNm.coerceIn(1, 250))
        for (base in bases) {
            val host = java.net.URL(base).host
            try {
                val (code, body) = AdsbClient.httpGet(base + path)
                if (code == 200) return Result.success(parse(body))
                errors += "$host HTTP $code"
            } catch (t: Throwable) {
                errors += "$host ${t.javaClass.simpleName}"
            }
        }
        return Result.failure(java.io.IOException(errors.distinct().joinToString("; ")))
    }

    private fun parse(body: String): List<Plane> {
        val ac = JSONObject(body).optJSONArray("ac") ?: return emptyList()
        val out = ArrayList<Plane>(ac.length())
        for (i in 0 until ac.length()) {
            val o = ac.optJSONObject(i) ?: continue
            if (!o.has("lat") || !o.has("lon")) continue
            val altRaw = o.opt("alt_baro")
            out += Plane(
                hex = o.optString("hex"),
                reg = o.optString("r").ifBlank { null },
                type = o.optString("t").ifBlank { null }?.uppercase(),
                callsign = o.optString("flight").trim().ifBlank { null },
                desc = o.optString("desc").ifBlank { null },
                lat = o.optDouble("lat"),
                lon = o.optDouble("lon"),
                altitudeFt = (altRaw as? Number)?.toInt(),
                onGround = altRaw is String && altRaw.equals("ground", ignoreCase = true),
                speedKt = if (o.has("gs")) o.optDouble("gs").toInt() else null,
                track = if (o.has("track")) o.optDouble("track") else null,
                military = (o.optInt("dbFlags", 0) and 1) != 0,
            )
        }
        return out
    }
}
