package com.example.planewatch

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifier {
    const val CHANNEL_ALERTS = "alerts"
    const val CHANNEL_MONITOR = "monitor"
    const val MONITOR_NOTIFICATION_ID = 1

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Flight alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Tells you when a watched aircraft takes off or lands" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITOR, "Monitoring status", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Persistent notice while PlaneWatch is running" }
        )
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun monitorNotification(context: Context, watching: Int) =
        NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("PlaneWatch is running")
            .setContentText(if (watching == 1) "Watching 1 aircraft" else "Watching $watching aircraft")
            .setOngoing(true)
            .setContentIntent(openAppIntent(context))
            .build()

    @SuppressLint("MissingPermission")
    fun alert(context: Context, registration: String, title: String, text: String) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        // One notification slot per registration, never colliding with the monitor notice (id 1).
        nm.notify(registration.hashCode() or 0x100, n)
    }
}
