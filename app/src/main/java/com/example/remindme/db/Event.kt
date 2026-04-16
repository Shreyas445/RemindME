package com.example.remindme.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "events")
data class Event(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val category: String,
    val customCategory: String? = null,
    val startDateTimeInMillis: Long,
    val repeatMode: String,
    val repeatDays: String? = null,
    val notes: String? = null,
    val location: String? = null,
    val invitees: String? = null,

    // --- ALARM SETTINGS ---
    val isVibrationEnabled: Boolean = true,
    val ringtoneUri: String? = null,
    val isLooping: Boolean = false,
    val loopCount: Int = 1,
    val volumeLevel: Float = 1.0f,
    val alertBefore: String = "None",

    // --- NEW: HEAVY SLEEPER ---
    val dismissMethod: String = "Default" // "Default" or "Math Problem"
)