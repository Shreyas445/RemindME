package com.example.remindme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.remindme.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Check if the broadcast is actually the phone finishing its boot process
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {

            // goAsync() tells Android: "Hey, don't kill this receiver yet, I need a second to check my database!"
            val pendingResult = goAsync()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getDatabase(context)
                    // Fetch all events from the database once
                    val allEvents = db.eventDao().getAllEventsFlow().first()
                    val now = System.currentTimeMillis()

                    // Loop through them and reschedule the ones that are in the future
                    for (event in allEvents) {
                        val nextTrigger = getNextOccurrence(event)
                        if (nextTrigger > now) {
                            scheduleEventAlarm(context, event)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    // Tell Android we are done and it can put the receiver to sleep
                    pendingResult.finish()
                }
            }
        }
    }
}