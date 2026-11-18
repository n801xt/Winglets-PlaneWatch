package com.example.planewatch

data class RareType(val code: String, val label: String)

/** A starter list of aircraft types people like to spot. Users can also add any ICAO type code. */
object RareTypes {
    val presets = listOf(
        RareType("A388", "Airbus A380-800"),
        RareType("B748", "Boeing 747-8"),
        RareType("B744", "Boeing 747-400"),
        RareType("BLCF", "Boeing 747 Dreamlifter"),
        RareType("A124", "Antonov An-124 Ruslan"),
        RareType("A3ST", "Airbus Beluga"),
        RareType("A337", "Airbus Beluga XL"),
        RareType("A346", "Airbus A340-600"),
        RareType("MD11", "McDonnell Douglas MD-11"),
        RareType("DC10", "McDonnell Douglas DC-10"),
        RareType("B703", "Boeing 707"),
        RareType("CONC", "Concorde"),
        RareType("IL76", "Ilyushin Il-76"),
        RareType("C5M", "Lockheed C-5 Galaxy"),
        RareType("C17", "Boeing C-17 Globemaster"),
        RareType("B52", "Boeing B-52 Stratofortress"),
        RareType("B1", "Rockwell B-1 Lancer"),
        RareType("B2", "Northrop B-2 Spirit"),
        RareType("V22", "Bell Boeing V-22 Osprey"),
        RareType("K35R", "Boeing KC-135 Stratotanker"),
        RareType("DC3", "Douglas DC-3"),
        RareType("B17", "Boeing B-17 Flying Fortress"),
        RareType("P51", "North American P-51 Mustang"),
        RareType("SPIT", "Supermarine Spitfire"),
    )

    fun label(code: String?): String? =
        presets.firstOrNull { it.code.equals(code, ignoreCase = true) }?.label

    fun matches(p: Plane, types: Set<String>, military: Boolean): Boolean =
        (p.type != null && p.type in types) || (military && p.military)
}
