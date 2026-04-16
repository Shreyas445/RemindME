package com.example.remindme

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NextEventWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextEventWidget()
}

class NextEventWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    companion object {
        val eventIdKey = intPreferencesKey("event_id")
        val titleKey = stringPreferencesKey("event_title")
        val categoryKey = stringPreferencesKey("event_category")
        val timeKey = longPreferencesKey("event_time")
        val daysLeftKey = intPreferencesKey("event_days_left")
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val eventId = currentState(key = eventIdKey) ?: -1

            GlanceTheme {
                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(ColorProvider(Color(0xFF141414))) // Dark background
                        .cornerRadius(24.dp)
                        .padding(24.dp) // Pushed padding inward for premium feel
                        .clickable(actionStartActivity<MainActivity>())
                ) {
                    if (eventId == -1) {
                        // ==========================================
                        // 1. THE EMPTY STATE (No Events)
                        // ==========================================
                        Column(
                            modifier = GlanceModifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = GlanceModifier
                                    .background(ColorProvider(Color(0xFF262626)))
                                    .cornerRadius(16.dp)
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = "+",
                                    style = TextStyle(color = ColorProvider(Color(0xFFFFC107)), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                                )
                            }
                            Spacer(modifier = GlanceModifier.height(12.dp))
                            Text(
                                text = "Clear Schedule",
                                style = TextStyle(color = ColorProvider(Color(0xFFF5F5F5)), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            )
                            Spacer(modifier = GlanceModifier.height(4.dp))
                            Text(
                                text = "Tap to add an event",
                                style = TextStyle(color = ColorProvider(Color(0xFFA0A0A0)), fontSize = 14.sp)
                            )
                        }
                    } else {
                        // ==========================================
                        // 2. THE DATA STATE (Next Event Upcoming)
                        // ==========================================
                        val title = currentState(key = titleKey) ?: ""
                        val category = currentState(key = categoryKey) ?: "Event"
                        val timeMillis = currentState(key = timeKey) ?: 0L
                        val daysLeft = currentState(key = daysLeftKey) ?: 0

                        val dateString = SimpleDateFormat("EEEE, MMM dd", Locale.getDefault()).format(Date(timeMillis))
                        val timeString = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(timeMillis))

                        // Intelligent High-Contrast Coloring
                        val (badgeText, badgeBg, badgeTint) = when {
                            daysLeft < 0 -> Triple(Math.abs(daysLeft).toString() + " DAYS AGO", Color(0xFF262626), Color(0xFFA0A0A0))
                            daysLeft == 0 -> Triple("TODAY", Color(0xFF4CAF50), Color(0xFF000000)) // Solid green, black text
                            daysLeft == 1 -> Triple("TOMORROW", Color(0xFFFFC107), Color(0xFF000000)) // Solid yellow, black text
                            else -> Triple("IN ${daysLeft} DAYS", Color(0xFF262626), Color(0xFFFFC107)) // Dark gray, yellow text
                        }

                        Column(modifier = GlanceModifier.fillMaxSize()) {

                            // --- TOP ROW: Tags ---
                            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = GlanceModifier
                                        .background(ColorProvider(Color(0xFFFFC107).copy(alpha = 0.15f)))
                                        .cornerRadius(8.dp)
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = category.uppercase(),
                                        style = TextStyle(color = ColorProvider(Color(0xFFFFC107)), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    )
                                }

                                Spacer(modifier = GlanceModifier.defaultWeight())

                                Text(
                                    text = "NEXT UP",
                                    style = TextStyle(color = ColorProvider(Color(0xFFA0A0A0)), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                )
                            }

                            Spacer(modifier = GlanceModifier.height(12.dp))

                            // --- MIDDLE ROW: Massive Title ---
                            Text(
                                text = title,
                                maxLines = 2,
                                style = TextStyle(color = ColorProvider(Color(0xFFF5F5F5)), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            )

                            Spacer(modifier = GlanceModifier.defaultWeight())

                            // --- BOTTOM ROW: Date & Action Pill ---
                            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text(
                                        text = dateString,
                                        style = TextStyle(color = ColorProvider(Color(0xFFF5F5F5)), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    )
                                    Spacer(modifier = GlanceModifier.height(4.dp))
                                    Text(
                                        text = timeString,
                                        // FIX IS HERE: Removed the typo!
                                        style = TextStyle(color = ColorProvider(Color(0xFFA0A0A0)), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    )
                                }

                                Spacer(modifier = GlanceModifier.defaultWeight())

                                // The dynamic high-contrast pill
                                Box(
                                    modifier = GlanceModifier
                                        .background(ColorProvider(badgeBg))
                                        .cornerRadius(16.dp)
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = badgeText,
                                        style = TextStyle(color = ColorProvider(badgeTint), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}