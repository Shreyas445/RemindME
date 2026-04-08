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
    val hasEndTime: Boolean = false,
    val endDateTimeInMillis: Long? = null,
    val repeatMode: String = "None",
    val repeatDays: String? = null,
    // --- New Fields for the Premium UI ---
    val notes: String? = null,
    val location: String? = null,
    val invitees: String? = null
)