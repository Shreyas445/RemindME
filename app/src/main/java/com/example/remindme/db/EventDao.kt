package com.example.remindme.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: Event)

    // Changed dateInMillis to startDateTimeInMillis
    @Query("SELECT * FROM events ORDER BY startDateTimeInMillis ASC LIMIT 1")
    suspend fun getNextEvent(): Event?

    // Changed dateInMillis to startDateTimeInMillis
    @Query("SELECT * FROM events ORDER BY startDateTimeInMillis ASC")
    fun getAllEventsFlow(): Flow<List<Event>>

    // Add this new function below your other queries
    @Query("SELECT * FROM events WHERE id = :eventId")
    suspend fun getEventById(eventId: Int): Event?
}