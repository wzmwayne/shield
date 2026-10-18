package wzmwayne.shield.keepalive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import wzmwayne.shield.MainActivity
import wzmwayne.shield.R
import wzmwayne.shield.core.Constants

object Notifications {

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(Constants.CHANNEL_KEEPALIVE) != null) return
        val channel = NotificationChannel(
            Constants.CHANNEL_KEEPALIVE,
            context.getString(R.string.keepalive_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.keepalive_channel_desc)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, text: String): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, Constants.CHANNEL_KEEPALIVE)
            .setSmallIcon(R.drawable.ic_shield_notification)
            .setContentTitle(context.getString(R.string.keepalive_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setColor(0xFF000000.toInt())
            .setContentIntent(contentIntent)
            .build()
    }
}
