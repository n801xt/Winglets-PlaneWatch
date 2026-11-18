package com.example.planewatch

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Each icon colour is an <activity-alias> in the manifest that points at MainActivity.
 * Exactly one alias is enabled at a time; that one provides the launcher icon.
 */
enum class AppIcon(val label: String, val color: Long) {
    BLUE("Blue", 0xFF0B2545),
    RED("Red", 0xFFB3261E),
    GREEN("Green", 0xFF1B5E20),
    ORANGE("Orange", 0xFFE65100),
    PURPLE("Purple", 0xFF4A148C),
    BLACK("Black", 0xFF111111);

    val aliasName: String
        get() = "com.example.planewatch.Icon" + name.lowercase().replaceFirstChar { it.uppercase() }
}

object AppIcons {
    fun apply(context: Context, selected: AppIcon) {
        val pm = context.packageManager
        // Enable the new one first so there is never a moment with no launcher entry.
        pm.setComponentEnabledSetting(
            ComponentName(context.packageName, selected.aliasName),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        for (icon in AppIcon.values()) {
            if (icon == selected) continue
            pm.setComponentEnabledSetting(
                ComponentName(context.packageName, icon.aliasName),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
