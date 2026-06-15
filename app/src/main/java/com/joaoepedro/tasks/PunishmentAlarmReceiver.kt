package com.joaoepedro.tasks

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.joaoepedro.tasks.data.AppRepository

class PunishmentAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_PUNISHMENT_ID) ?: return
        val repository = AppRepository(context)
        val state = repository.load()
        val session = state.punishments.firstOrNull { it.id == sessionId }
        val personName = state.people.firstOrNull { it.id == session?.personId }?.name ?: "Participante"

        if (session != null) {
            repository.save(state.copy(punishments = state.punishments.map {
                if (it.id == sessionId) it.copy(active = false, completedAlerted = true, finishedAtMillis = it.endsAtMillis) else it
            }))
        }

        playAlertSound(context)
        showNotification(context, sessionId, personName)
    }

    companion object {
        const val EXTRA_PUNISHMENT_ID = "punishmentId"
        private const val CHANNEL_ID = "punishment_alerts"

        fun playAlertSound(context: Context) {
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            runCatching {
                RingtoneManager.getRingtone(context.applicationContext, soundUri)?.play()
            }
        }

        private fun showNotification(context: Context, sessionId: String, personName: String) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Alertas de castigo",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Avisos quando um castigo termina"
                    setSound(soundUri, android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                }
                manager.createNotificationChannel(channel)
            }

            val openAppIntent = Intent(context, MainActivity::class.java)
            val contentIntent = android.app.PendingIntent.getActivity(
                context,
                sessionId.hashCode(),
                openAppIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or (
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0
                )
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_nav_punishment)
                .setContentTitle("Castigo finalizado")
                .setContentText("$personName terminou o tempo de castigo.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSound(soundUri)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build()

            manager.notify(sessionId.hashCode(), notification)
        }
    }
}
