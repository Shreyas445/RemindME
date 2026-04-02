package com.example.remindme

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.datastore.preferences.core.intPreferencesKey
import com.example.remindme.db.AppDatabase
import com.example.remindme.db.Event
import com.example.remindme.widget.CountdownWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WidgetConfigActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get the Widget ID from the Intent
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // If they back out, cancel the widget placement
        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_CANCELED, resultValue)

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val db = AppDatabase.getDatabase(this)

        setContent {
            val events by db.eventDao().getAllEventsFlow().collectAsState(initial = emptyList())
            val coroutineScope = rememberCoroutineScope()

            MaterialTheme(colorScheme = darkColorScheme(background = PureBlack, surface = DarkGrayCard)) {
                Surface(modifier = Modifier.fillMaxSize(), color = PureBlack) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Select an Event for Widget", color = Color.White, fontSize = 24.sp, modifier = Modifier.padding(bottom = 16.dp))

                        if (events.isEmpty()) {
                            Text("No events saved! Open the app to add one.", color = Color.Gray)
                        } else {
                            LazyColumn {
                                items(events) { event ->
                                    EventSelectionCard(event) {
                                        // When clicked, save the selection to the widget and finish
                                        coroutineScope.launch(Dispatchers.IO) {
                                            saveWidgetConfiguration(event.id)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun saveWidgetConfiguration(eventId: Int) {
        val glanceAppWidgetManager = GlanceAppWidgetManager(this)
        val glanceId = glanceAppWidgetManager.getGlanceIdBy(appWidgetId)

        // Save the Event ID into the Widget's local preferences
        updateAppWidgetState(this, glanceId) { prefs ->
            prefs[intPreferencesKey("configured_event_id")] = eventId
        }

        // Force the widget to update its UI
        CountdownWidget().update(this, glanceId)

        // Tell Android it was successful and close the screen
        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_OK, resultValue)
        finish()
    }
}

@Composable
fun EventSelectionCard(event: Event, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkGrayCard),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { onClick() }
    ) {
        Text(event.title, color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(16.dp))
    }
}