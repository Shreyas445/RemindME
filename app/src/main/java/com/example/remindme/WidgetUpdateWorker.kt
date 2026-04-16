package com.example.remindme

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.remindme.db.AppDatabase
import kotlinx.coroutines.flow.first
import java.util.Calendar

class WidgetUpdateWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        try {
            val db = AppDatabase.getDatabase(context)
            val allEvents = db.eventDao().getAllEventsFlow().first()

            // Find the absolute closest future event
            val now = System.currentTimeMillis()
            val nextEventPair = allEvents
                .map { it to getNextOccurrence(it) }
                .filter { it.second > now }
                .minByOrNull { it.second }

            val manager = GlanceAppWidgetManager(context)
            val glanceIds = manager.getGlanceIds(NextEventWidget::class.java)

            glanceIds.forEach { glanceId ->
                updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                    val mutablePrefs = prefs.toMutablePreferences()
                    if (nextEventPair != null) {
                        val (event, nextTime) = nextEventPair

                        // Calculate days left for the badge
                        val todayStart = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                        val targetStart = Calendar.getInstance().apply { timeInMillis = nextTime; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                        val daysLeft = ((targetStart - todayStart) / (1000 * 60 * 60 * 24)).toInt()

                        mutablePrefs[NextEventWidget.eventIdKey] = event.id
                        mutablePrefs[NextEventWidget.titleKey] = event.title
                        mutablePrefs[NextEventWidget.categoryKey] = event.category
                        mutablePrefs[NextEventWidget.timeKey] = nextTime
                        mutablePrefs[NextEventWidget.daysLeftKey] = daysLeft
                    } else {
                        // Clear the widget if no events exist
                        mutablePrefs[NextEventWidget.eventIdKey] = -1
                    }
                    mutablePrefs
                }
                // Force the UI to redraw with the new data
                NextEventWidget().update(context, glanceId)
            }
            return Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            return Result.failure()
        }
    }
}