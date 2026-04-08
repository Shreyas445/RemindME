package com.example.remindme.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: Event)

    // NEW: Updates an existing event after editing
    @Update
    suspend fun updateEvent(event: Event)

    // NEW: Deletes a list of selected event IDs
    @Query("DELETE FROM events WHERE id IN (:idList)")
    suspend fun deleteEvents(idList: List<Int>)

    @Query("SELECT * FROM events ORDER BY startDateTimeInMillis ASC LIMIT 1")
    suspend fun getNextEvent(): Event?

    @Query("SELECT * FROM events WHERE id = :eventId")
    suspend fun getEventById(eventId: Int): Event?

    @Query("SELECT * FROM events ORDER BY startDateTimeInMillis ASC")
    fun getAllEventsFlow(): Flow<List<Event>>
}