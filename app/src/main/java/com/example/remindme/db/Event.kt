package com.example.remindme.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "events")
data class Event(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val category: String,
    val startDateTimeInMillis: Long,
    val repeatMode: String,
    val repeatDays: String? = null,
    val notes: String? = null,
    val location: String? = null,
    val invitees: String? = null,
    val customCategory: String? = null,

    val isVibrationEnabled: Boolean = true,
    val ringtoneUri: String? = null,

    // --- NEW: Loop Settings ---
    val isLooping: Boolean = false,
    val loopCount: Int = 2
)