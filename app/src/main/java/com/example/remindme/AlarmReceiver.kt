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
import com.example.remindme.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar

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
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "remindme_alarms_v5"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Event Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "High priority alarms and alerts"
            }
            notificationManager.createNotificationChannel(channel)
        }

        // ==========================================
        // 🌅 THE DAILY MORNING BRIEFING LOGIC
        // ==========================================
        if (intent.getBooleanExtra("IS_DAILY_BRIEFING", false)) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getDatabase(context)
                    val allEvents = db.eventDao().getAllEventsFlow().first()

                    // Find bounds for "Today"
                    val todayStart = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                    val todayEnd = todayStart + (24 * 60 * 60 * 1000)

                    // Filter events happening today
                    val todaysEvents = allEvents.filter { event ->
                        val nextTrigger = getNextOccurrence(event)
                        nextTrigger in todayStart until todayEnd
                    }

                    if (todaysEvents.isNotEmpty()) {
                        val title = "Good Morning! ☀️"
                        val text = "You have ${todaysEvents.size} event(s) today, including: ${todaysEvents.first().title}"

                        val openAppIntent = Intent(context, MainActivity::class.java)
                        val pendingIntent = PendingIntent.getActivity(context, 999999, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                        val notification = NotificationCompat.Builder(context, channelId)
                            .setSmallIcon(android.R.drawable.ic_popup_reminder)
                            .setColor(android.graphics.Color.parseColor("#FFC107"))
                            .setContentTitle(title)
                            .setContentText(text)
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                            .setAutoCancel(true)
                            .setContentIntent(pendingIntent)
                            .build()

                        notificationManager.notify(999999, notification)
                    }

                    // Reschedule for tomorrow morning
                    scheduleDailyBriefing(context)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    pendingResult.finish()
                }
            }
            return // Stop here!
        }

        // ==========================================
        // 🧠 THE PRE-ALERT LOGIC
        // ==========================================
        val eventId = intent.getIntExtra("EVENT_ID", -1)
        val title = intent.getStringExtra("EVENT_TITLE") ?: "Event Reminder"
        val category = intent.getStringExtra("EVENT_CATEGORY") ?: "Event"
        val isPreAlert = intent.getBooleanExtra("IS_PRE_ALERT", false)
        val alertBeforeStr = intent.getStringExtra("ALERT_BEFORE_STR") ?: ""
        val dismissMethod = intent.getStringExtra("EVENT_DISMISS_METHOD") ?: "Default"

        if (isPreAlert) {
            val smartTitle = when (alertBeforeStr) {
                "1 day" -> "Tomorrow"
                "1 hour" -> "In 1 Hour"
                "30 mins" -> "In 30 Minutes"
                else -> "Upcoming"
            }

            val smartText = when (category) {
                "Birthdays" -> if (alertBeforeStr == "1 day") "Don't forget, tomorrow is $title's Birthday! 🎂" else "$title's Birthday is coming up! 🎉"
                "Medicine" -> "Time for your medicine: $title 💊"
                "Exams" -> "Good luck studying! Your $title exam is $smartTitle 📚"
                "Last Dates" -> "Deadline Alert: $title is due $smartTitle ⚠️"
                else -> "$title is $smartTitle"
            }

            val openAppIntent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(context, eventId + 500000, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setColor(android.graphics.Color.parseColor("#FFC107"))
                .setContentTitle(smartTitle)
                .setContentText(smartText)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            notificationManager.notify(eventId + 500000, notification)
            return
        }

        // ==========================================
        // 🚨 THE MAIN WAKE-UP ALARM LOGIC
        // ==========================================
        val vibrationEnabled = intent.getBooleanExtra("EVENT_VIBRATION", true)
        val ringtoneUriString = intent.getStringExtra("EVENT_RINGTONE")
        val isLooping = intent.getBooleanExtra("EVENT_IS_LOOPING", false)
        val loopCount = intent.getIntExtra("EVENT_LOOP_COUNT", 1)
        val volumeLevel = intent.getFloatExtra("EVENT_VOLUME", 1.0f)

        stopAlarmAudio(context, eventId)

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemindMe::AlarmAudioWakeLock")
        wakeLock?.acquire(3 * 60 * 1000L)

        val openAlarmIntent = Intent(context, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("EVENT_ID", eventId)
            putExtra("EVENT_TITLE", title)
            putExtra("EVENT_CATEGORY", category)
            putExtra("EVENT_DISMISS_METHOD", dismissMethod)
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

        notificationManager.notify(eventId, notification)

        if (ringtoneUriString != "NONE" && !ringtoneUriString.isNullOrBlank()) {
            try {
                val uri = Uri.parse(ringtoneUriString)
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).setUsage(AudioAttributes.USAGE_ALARM).build())
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
                            player.seekTo(0); player.start()
                        } else { stopAlarmAudio(context, eventId) }
                    }
                } else {
                    mediaPlayer?.setOnCompletionListener { stopAlarmAudio(context, eventId) }
                }

                mediaPlayer?.setOnErrorListener { _, _, _ -> stopAlarmAudio(context, eventId); true }
                mediaPlayer?.start()
            } catch (e: Exception) {
                stopAlarmAudio(context, eventId)
            }
        } else {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
    }
}