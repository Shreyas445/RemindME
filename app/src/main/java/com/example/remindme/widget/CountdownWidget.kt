package com.example.remindme.widget

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.action.clickable
import androidx.glance.layout.*
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.text.FontWeight
import androidx.glance.unit.ColorProvider
import com.example.remindme.db.AppDatabase
import com.example.remindme.db.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CountdownWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val db = AppDatabase.getDatabase(context)

        provideContent {
            val prefs = currentState<androidx.datastore.preferences.core.Preferences>()
            val eventId = prefs[intPreferencesKey("configured_event_id")] ?: -1
            val themeStr = prefs[stringPreferencesKey("widget_theme")] ?: "Dark"

            // Parse the theme. If it starts with "Custom:", we parse the hex codes.
            val (bgColor, textColor, accentColor) = if (themeStr.startsWith("Custom:")) {
                try {
                    val parts = themeStr.removePrefix("Custom:").split(",")
                    Triple(
                        Color(android.graphics.Color.parseColor(parts[0])),
                        Color(android.graphics.Color.parseColor(parts[1])),
                        Color(android.graphics.Color.parseColor(parts[2]))
                    )
                } catch (e: Exception) {
                    Triple(Color(0xFF151515), Color.White, Color(0xFFFFC107)) // Fallback
                }
            } else {
                when(themeStr) {
                    "Light" -> Triple(Color.White, Color.Black, Color(0xFFFFC107))
                    "Amber" -> Triple(Color(0xFFFFC107), Color.Black, Color.White)
                    "Transparent" -> Triple(Color.Transparent, Color.White, Color(0xFFFFC107))
                    else -> Triple(Color(0xFF151515), Color.White, Color(0xFFFFC107)) // Dark Mode
                }
            }

            var event by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Event?>(null) }

            androidx.compose.runtime.LaunchedEffect(eventId) {
                if (eventId != -1) {
                    event = withContext(Dispatchers.IO) { db.eventDao().getEventById(eventId) }
                }
            }

            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .background(bgColor)
                    .cornerRadius(24.dp)
                    .clickable(actionRunCallback<RefreshWidgetAction>()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val currentEvent = event
                if (currentEvent != null) {
                    val diffMillis = currentEvent.startDateTimeInMillis - System.currentTimeMillis()
                    val countdownText = formatCountdown(diffMillis)

                    Text(
                        text = countdownText,
                        style = TextStyle(fontWeight = FontWeight.Bold, color = ColorProvider(accentColor), fontSize = 28.sp)
                    )
                    Spacer(modifier = GlanceModifier.height(4.dp))
                    Text(
                        text = currentEvent.title,
                        style = TextStyle(fontWeight = FontWeight.Medium, color = ColorProvider(textColor), fontSize = 14.sp)
                    )
                } else {
                    Text("Select an Event", style = TextStyle(color = ColorProvider(textColor)))
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
            days > 0 -> "$days Days"
            hours > 0 -> "$hours Hrs"
            else -> "$minutes Mins"
        }
    }
}