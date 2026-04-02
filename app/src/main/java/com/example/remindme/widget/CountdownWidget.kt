package com.example.remindme.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.*
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.text.FontWeight
import androidx.glance.unit.ColorProvider
import com.example.remindme.db.AppDatabase
import com.example.remindme.db.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

class CountdownWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val db = AppDatabase.getDatabase(context)

        provideContent {
            // Read the specific event ID we saved during configuration
            val prefs = currentState<androidx.datastore.preferences.core.Preferences>()
            val eventId = prefs[intPreferencesKey("configured_event_id")] ?: -1

            var event by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Event?>(null) }

            // Fetch the event
            androidx.compose.runtime.LaunchedEffect(eventId) {
                if (eventId != -1) {
                    event = withContext(Dispatchers.IO) { db.eventDao().getEventById(eventId) }
                }
            }

            Column(
                modifier = GlanceModifier.fillMaxSize().padding(12.dp).background(Color(0xFF1A1A1A)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val currentEvent = event
                if (currentEvent != null) {
                    val diffMillis = currentEvent.startDateTimeInMillis - System.currentTimeMillis()
                    val countdownText = formatCountdown(diffMillis)

                    Text(
                        text = countdownText,
                        style = TextStyle(fontWeight = FontWeight.Bold, color = ColorProvider(Color(0xFF4A90E2)), fontSize = 24.sp)
                    )
                    Spacer(modifier = GlanceModifier.height(4.dp))
                    Text(
                        text = currentEvent.title,
                        style = TextStyle(color = ColorProvider(Color.White), fontSize = 14.sp)
                    )
                } else {
                    Text("Loading or deleted...", style = TextStyle(color = ColorProvider(Color.Gray)))
                }
            }
        }
    }

    private fun formatCountdown(diffMillis: Long): String {
        if (diffMillis <= 0) return "It's Time!"

        val days = diffMillis / (1000 * 60 * 60 * 24)
        val hours = (diffMillis / (1000 * 60 * 60)) % 24
        val minutes = (diffMillis / (1000 * 60)) % 60

        return when {
            days > 0 -> "$days Days left"
            hours > 0 -> "$hours Hrs left"
            else -> "$minutes Mins left"
        }
    }
}