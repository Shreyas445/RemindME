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
    // --- Phase 3 Additions ---
    val repeatMode: String = "None", // "None", "Daily", "Weekly", "Monthly", "Yearly"
    val repeatDays: String? = null // Used for Weekly: e.g., "0,2,4" for Sun, Tue, Thu
)