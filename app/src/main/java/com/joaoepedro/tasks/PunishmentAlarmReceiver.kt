package com.joaoepedro.tasks

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
        private const val CHANNEL_ID = "punishment_alerts_v2"

        fun playAlertSound(context: Context) {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                runCatching {
                    val ringtone = RingtoneManager.getRingtone(context.applicationContext, soundUri)
                    ringtone?.play()
                    Handler(Looper.getMainLooper()).postDelayed({ ringtone?.stop() }, 2_500L)
                }.onFailure {
                    vibrate(context)
                }
            } else {
                vibrate(context)
            }
        }

        private fun vibrate(context: Context) {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val pattern = longArrayOf(0L, 350L, 160L, 350L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        }

        fun showNotification(context: Context, sessionId: String, personName: String) {
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Alertas de castigo",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Avisos quando um castigo termina"
                    enableVibration(true)
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
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setSilent(true)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build()

            manager.notify(sessionId.hashCode(), notification)
        }
    }
}
