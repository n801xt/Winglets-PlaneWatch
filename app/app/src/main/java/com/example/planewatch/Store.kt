package com.example.planewatch

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/** Tiny persistent store + in-process observable state shared by the UI and the service. */
object Store {
    private lateinit var prefs: SharedPreferences

    val registrations = MutableStateFlow<List<String>>(emptyList())
    val statuses = MutableStateFlow<Map<String, String>>(emptyMap())
    val monitoring = MutableStateFlow(false)
    val notifyLanding = MutableStateFlow(true)

    /** 0 = follow system, 1 = light, 2 = dark */
    val themeMode = MutableStateFlow(0)
    val iconColor = MutableStateFlow(AppIcon.BLUE.name)
    val intervalSeconds = MutableStateFlow(60)

    val rareEnabled = MutableStateFlow(false)
    val rareTypes = MutableStateFlow<Set<String>>(emptySet())
    val rareMilitary = MutableStateFlow(false)
    val rareRadiusNm = MutableStateFlow(25)

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("planewatch", Context.MODE_PRIVATE)
        registrations.value = prefs.getString("regs", "").orEmpty()
            .split(",").filter { it.isNotBlank() }
        monitoring.value = prefs.getBoolean("monitoring", false)
        notifyLanding.value = prefs.getBoolean("notifyLanding", true)
        themeMode.value = prefs.getInt("themeMode", 0)
        iconColor.value = prefs.getString("iconColor", AppIcon.BLUE.name) ?: AppIcon.BLUE.name
        intervalSeconds.value = prefs.getInt("intervalSeconds", 60)
        rareEnabled.value = prefs.getBoolean("rareEnabled", false)
        rareTypes.value = prefs.getString("rareTypes", "").orEmpty()
            .split(",").filter { it.isNotBlank() }.toSet()
        rareMilitary.value = prefs.getBoolean("rareMilitary", false)
        rareRadiusNm.value = prefs.getInt("rareRadiusNm", 25)
    }

    fun normalize(raw: String): String = raw.trim().uppercase().replace(" ", "")

    /** Returns false if blank or already present. */
    fun addRegistration(raw: String): Boolean {
        val reg = normalize(raw)
        if (reg.isEmpty() || reg in registrations.value) return false
        val updated = registrations.value + reg
        registrations.value = updated
        prefs.edit().putString("regs", updated.joinToString(",")).apply()
        return true
    }

    fun removeRegistration(reg: String) {
        val updated = registrations.value - reg
        registrations.value = updated
        statuses.value = statuses.value - reg
        prefs.edit()
            .putString("regs", updated.joinToString(","))
            .remove("air_$reg")
            .apply()
    }

    fun setMonitoring(on: Boolean) {
        monitoring.value = on
        prefs.edit().putBoolean("monitoring", on).apply()
    }

    fun setNotifyLanding(on: Boolean) {
        notifyLanding.value = on
        prefs.edit().putBoolean("notifyLanding", on).apply()
    }

    fun setThemeMode(mode: Int) {
        themeMode.value = mode
        prefs.edit().putInt("themeMode", mode).apply()
    }

    fun setIconColor(name: String) {
        iconColor.value = name
        prefs.edit().putString("iconColor", name).apply()
    }

    fun setIntervalSeconds(seconds: Int) {
        intervalSeconds.value = seconds
        prefs.edit().putInt("intervalSeconds", seconds).apply()
    }

    fun setRareEnabled(on: Boolean) {
        rareEnabled.value = on
        prefs.edit().putBoolean("rareEnabled", on).apply()
    }

    fun setRareMilitary(on: Boolean) {
        rareMilitary.value = on
        prefs.edit().putBoolean("rareMilitary", on).apply()
    }

    fun setRareRadius(nm: Int) {
        rareRadiusNm.value = nm
        prefs.edit().putInt("rareRadiusNm", nm).apply()
    }

    fun setRareType(code: String, selected: Boolean) {
        val updated = if (selected) rareTypes.value + code else rareTypes.value - code
        rareTypes.value = updated
        prefs.edit().putString("rareTypes", updated.joinToString(",")).apply()
    }

    /** ICAO type codes are 2-4 letters/digits, e.g. A388, B52, C5M. Returns false if invalid. */
    fun addRareType(raw: String): Boolean {
        val code = raw.trim().uppercase()
        if (code.length !in 2..4 || !code.all { it.isLetterOrDigit() }) return false
        setRareType(code, true)
        return true
    }

    fun wasAirborne(reg: String): Boolean = prefs.getBoolean("air_$reg", false)

    fun setAirborne(reg: String, airborne: Boolean) {
        prefs.edit().putBoolean("air_$reg", airborne).apply()
    }

    fun setStatus(reg: String, text: String) {
        statuses.value = statuses.value + (reg to text)
    }
}
