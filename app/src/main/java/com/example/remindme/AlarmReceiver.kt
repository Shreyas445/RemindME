package com.example.remindme

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        var mediaPlayer: MediaPlayer? = null
        var wakeLock: PowerManager.WakeLock? = null
        var loudnessEnhancer: LoudnessEnhancer? = null

        fun stopAlarmAudio(context: Context, eventId: Int) {
            try {
                loudnessEnhancer?.release()
                loudnessEnhancer = null

                if (mediaPlayer?.isPlaying == true) mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
            } catch (e: Exception) {}

            try {
                if (wakeLock?.isHeld == true) wakeLock?.release()
                wakeLock = null
            } catch (e: Exception) {}

            // FIX: We NO LONGER cancel the notification here!
            // It will stay in the tray until the user swipes it away.
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getIntExtra("EVENT_ID", -1)
        val title = intent.getStringExtra("EVENT_TITLE") ?: "Event Reminder"
        val category = intent.getStringExtra("EVENT_CATEGORY") ?: "Event"
        val vibrationEnabled = intent.getBooleanExtra("EVENT_VIBRATION", true)
        val ringtoneUriString = intent.getStringExtra("EVENT_RINGTONE")

        val isLooping = intent.getBooleanExtra("EVENT_IS_LOOPING", false)
        val loopCount = intent.getIntExtra("EVENT_LOOP_COUNT", 1)
        val volumeLevel = intent.getFloatExtra("EVENT_VOLUME", 1.0f)

        stopAlarmAudio(context, eventId)

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemindMe::AlarmAudioWakeLock")
        wakeLock?.acquire(3 * 60 * 1000L)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "remindme_silent_alarm_v4_$vibrationEnabled"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Event Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "High priority alarms for events"
                setSound(null, null)
                enableVibration(vibrationEnabled)
                if (vibrationEnabled) vibrationPattern = longArrayOf(0, 500, 500, 500)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val openAlarmIntent = Intent(context, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("EVENT_ID", eventId)
            putExtra("EVENT_TITLE", title)
            putExtra("EVENT_CATEGORY", category)
        }
        val pendingIntent = PendingIntent.getActivity(context, eventId, openAlarmIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setColor(android.graphics.Color.parseColor("#FFC107"))
            .setContentTitle("It's Time!")
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .build()

        // This pushes the notification to the tray where it will stay!
        notificationManager.notify(eventId, notification)

        if (ringtoneUriString != "NONE" && !ringtoneUriString.isNullOrBlank()) {
            try {
                val uri = Uri.parse(ringtoneUriString)

                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .build()
                    )
                    setDataSource(context, uri)
                    prepare()
                }

                if (volumeLevel > 1.0f) {
                    mediaPlayer?.setVolume(1.0f, 1.0f)
                    try {
                        loudnessEnhancer = LoudnessEnhancer(mediaPlayer!!.audioSessionId)
                        loudnessEnhancer?.setTargetGain(((volumeLevel - 1.0f) * 2000).toInt())
                        loudnessEnhancer?.enabled = true
                    } catch (e: Exception) {}
                } else {
                    mediaPlayer?.setVolume(volumeLevel, volumeLevel)
                }

                if (isLooping) {
                    var playsCompleted = 0
                    mediaPlayer?.setOnCompletionListener { player ->
                        playsCompleted++
                        if (playsCompleted < loopCount) {
                            player.seekTo(0)
                            player.start()
                        } else {
                            stopAlarmAudio(context, eventId)
                        }
                    }
                } else {
                    mediaPlayer?.setOnCompletionListener {
                        stopAlarmAudio(context, eventId)
                    }
                }

                mediaPlayer?.setOnErrorListener { _, _, _ ->
                    stopAlarmAudio(context, eventId)
                    true
                }

                mediaPlayer?.start()
            } catch (e: Exception) {
                stopAlarmAudio(context, eventId)
                e.printStackTrace()
            }
        } else {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
    }
}