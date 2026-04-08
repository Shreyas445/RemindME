package com.example.remindme

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.remindme.db.AppDatabase
import com.example.remindme.widget.CountdownWidget
import com.example.remindme.widget.WidgetUpdateWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class WidgetConfigActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }

        val db = AppDatabase.getDatabase(this)

        setContent {
            val events by db.eventDao().getAllEventsFlow().collectAsState(initial = emptyList())
            val coroutineScope = rememberCoroutineScope()

            var selectedEventId by remember { mutableStateOf<Int?>(null) }
            val themes = listOf("Dark", "Light", "Amber", "Transparent")
            var selectedTheme by remember { mutableStateOf(themes[0]) }

            // Custom Theme States
            var showCustomDialog by remember { mutableStateOf(false) }
            var customBgColor by remember { mutableStateOf(Color(0xFF151515)) }
            var customTextColor by remember { mutableStateOf(Color.White) }
            var customAccentColor by remember { mutableStateOf(Color(0xFFFFC107)) }
            var customBgAlpha by remember { mutableStateOf(1f) }

            MaterialTheme(colorScheme = darkColorScheme(background = PureBlack, surface = DarkGrayCard)) {
                Surface(modifier = Modifier.fillMaxSize(), color = PureBlack) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("Customize Widget", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(bottom = 24.dp))

                        Text("1. Select an Event", color = Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                        if (events.isEmpty()) {
                            Text("No events saved! Open the app to add one.", color = Color.Gray)
                        } else {
                            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(events) { event ->
                                    val isSelected = selectedEventId == event.id
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = if (isSelected) CardOutline else DarkGrayCard),
                                        border = if (isSelected) BorderStroke(2.dp, AccentColor) else null,
                                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { selectedEventId = event.id }
                                    ) {
                                        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(event.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                            if (isSelected) Icon(Icons.Filled.Check, contentDescription = null, tint = AccentColor)
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                        Text("2. Select Theme", color = Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(themes) { theme ->
                                val isSelected = selectedTheme == theme
                                val bgColor = when(theme) {
                                    "Light" -> Color.White
                                    "Amber" -> AccentColor
                                    "Transparent" -> Color.DarkGray.copy(alpha = 0.3f)
                                    else -> DarkGrayCard
                                }
                                Box(
                                    modifier = Modifier.size(60.dp).clip(CircleShape).background(bgColor)
                                        .border(if (isSelected) 3.dp else 1.dp, if (isSelected) Color.White else Color.Transparent, CircleShape)
                                        .clickable { selectedTheme = theme },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (theme == "Transparent") Text("T", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }

                            // Custom Theme Button
                            item {
                                Box(
                                    modifier = Modifier.size(60.dp).clip(CircleShape).background(CardOutline)
                                        .border(if (selectedTheme.startsWith("Custom")) 3.dp else 1.dp, if (selectedTheme.startsWith("Custom")) AccentColor else Color.Transparent, CircleShape)
                                        .clickable { showCustomDialog = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = "Custom", tint = Color.White)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(32.dp))

                        Button(
                            onClick = {
                                if (selectedEventId == null) {
                                    Toast.makeText(this@WidgetConfigActivity, "Please select an event", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                coroutineScope.launch(Dispatchers.IO) { saveWidgetConfiguration(selectedEventId!!, selectedTheme) }
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
                            shape = RoundedCornerShape(16.dp)
                        ) { Text("Add to Home Screen", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black) }
                    }
                }

                // Custom Theme Bottom Sheet Dialog
                if (showCustomDialog) {
                    ModalBottomSheet(
                        onDismissRequest = { showCustomDialog = false },
                        containerColor = DarkGrayCard,
                        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.Gray) }
                    ) {
                        Column(modifier = Modifier.padding(24.dp).fillMaxWidth()) {
                            Text("Custom Theme Builder", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Spacer(modifier = Modifier.height(24.dp))

                            // Background Transparency
                            Text("Background Transparency", color = Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Slider(
                                value = customBgAlpha,
                                onValueChange = { customBgAlpha = it },
                                valueRange = 0f..1f,
                                colors = SliderDefaults.colors(thumbColor = AccentColor, activeTrackColor = AccentColor, inactiveTrackColor = CardOutline)
                            )

                            // Color Picker Helper Function
                            @Composable
                            fun ColorRow(label: String, selectedColor: Color, onColorSelected: (Color) -> Unit) {
                                val palette = listOf(Color.White, Color.Black, Color(0xFF151515), Color(0xFFFFC107), Color(0xFFE91E63), Color(0xFF2196F3), Color(0xFF4CAF50), Color(0xFF9C27B0))
                                Text(label, color = Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(palette) { color ->
                                        Box(
                                            modifier = Modifier.size(40.dp).clip(CircleShape).background(color)
                                                .border(2.dp, if (selectedColor == color) Color.White else Color.Gray.copy(alpha = 0.3f), CircleShape)
                                                .clickable { onColorSelected(color) }
                                        )
                                    }
                                }
                            }

                            ColorRow("Background Color", customBgColor) { customBgColor = it }
                            ColorRow("Text Color", customTextColor) { customTextColor = it }
                            ColorRow("Accent Color (Days/Icons)", customAccentColor) { customAccentColor = it }

                            Spacer(modifier = Modifier.height(32.dp))

                            Button(
                                onClick = {
                                    // Construct the Hex String: Custom:#AARRGGBB,#AARRGGBB,#AARRGGBB
                                    val finalBg = customBgColor.copy(alpha = customBgAlpha)
                                    val hexBg = String.format("#%08X", finalBg.toArgb())
                                    val hexText = String.format("#%08X", customTextColor.toArgb())
                                    val hexAccent = String.format("#%08X", customAccentColor.toArgb())

                                    selectedTheme = "Custom:$hexBg,$hexText,$hexAccent"
                                    showCustomDialog = false
                                },
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
                                shape = RoundedCornerShape(16.dp)
                            ) { Text("Apply Custom Theme", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black) }

                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }

    private suspend fun saveWidgetConfiguration(eventId: Int, theme: String) {
        val glanceAppWidgetManager = GlanceAppWidgetManager(this)
        val glanceId = glanceAppWidgetManager.getGlanceIdBy(appWidgetId)

        updateAppWidgetState(this, glanceId) { prefs ->
            prefs[intPreferencesKey("configured_event_id")] = eventId
            prefs[stringPreferencesKey("widget_theme")] = theme
        }

        CountdownWidget().update(this, glanceId)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))

        val workRequest = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(15L, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "widget_update_engine",
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )

        finish()
    }
}