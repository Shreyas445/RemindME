package com.example.remindme

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        var mediaPlayer: MediaPlayer? = null
        var wakeLock: PowerManager.WakeLock? = null

        fun stopAlarmAudio(context: Context, eventId: Int) {
            try {
                if (mediaPlayer?.isPlaying == true) mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
            } catch (e: Exception) {}

            try {
                if (wakeLock?.isHeld == true) wakeLock?.release()
                wakeLock = null
            } catch (e: Exception) {}

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(eventId)
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

        // Clean up any old alarms just in case of a double-fire
        stopAlarmAudio(context, eventId)

        // --- 1. THE POWER GRAB ---
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemindMe::AlarmAudioWakeLock")
        wakeLock?.acquire(3 * 60 * 1000L) // 3 Minute absolute maximum

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Use a brand new channel ID based purely on vibration to force the OS to forget old sound settings
        val channelId = "remindme_silent_alarm_v3_$vibrationEnabled"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Event Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "High priority alarms for events"

                // FORCE THE OS CHANNEL TO BE SILENT. WE HANDLE AUDIO MANUALLY.
                setSound(null, null)

                enableVibration(vibrationEnabled)
                if (vibrationEnabled) vibrationPattern = longArrayOf(0, 500, 500, 500)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // --- 2. LAUNCH THE DEDICATED ALARM SCREEN ---
        val openAlarmIntent = Intent(context, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("EVENT_ID", eventId)
            putExtra("EVENT_TITLE", title)
            putExtra("EVENT_CATEGORY", category)
        }
        val pendingIntent = PendingIntent.getActivity(context, eventId, openAlarmIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("It's Time!")
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true) // Wakes the screen!
            .build()

        notificationManager.notify(eventId, notification)

        // --- 3. 100% MANUAL AUDIO CONTROL ---
        if (ringtoneUriString != "NONE" && !ringtoneUriString.isNullOrBlank()) {
            try {
                val uri = Uri.parse(ringtoneUriString)
                mediaPlayer = MediaPlayer.create(context, uri)

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
                    // Even if it's not looping, we let MediaPlayer play it exactly once then kill the battery lock
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
            // If the user selected "None", release the battery lock immediately
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
    }
}