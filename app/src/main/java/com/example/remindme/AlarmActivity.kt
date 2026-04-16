package com.example.remindme

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.remindme.db.AppDatabase
import com.example.remindme.db.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wake up the screen and bypass the lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK

        // Get the event data passed from AlarmReceiver
        val eventId = intent.getIntExtra("EVENT_ID", -1)
        val fallbackTitle = intent.getStringExtra("EVENT_TITLE") ?: "Reminder"
        val fallbackCategory = intent.getStringExtra("EVENT_CATEGORY") ?: "Event"
        val dismissMethod = intent.getStringExtra("EVENT_DISMISS_METHOD") ?: "Default"

        val db = AppDatabase.getDatabase(this)

        setContent {
            var currentEvent by remember { mutableStateOf<Event?>(null) }

            LaunchedEffect(eventId) {
                currentEvent = withContext(Dispatchers.IO) {
                    db.eventDao().getEventById(eventId)
                }
            }

            var showDetails by remember { mutableStateOf(false) }

            // --- MATH PROBLEM STATE ---
            var showMathDialog by remember { mutableStateOf(false) }
            var mathAnswer by remember { mutableStateOf("") }
            // Generate two random numbers between 10 and 50
            var num1 by remember { mutableStateOf((10..50).random()) }
            var num2 by remember { mutableStateOf((10..50).random()) }
            var mathError by remember { mutableStateOf(false) }

            // Animations
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 1f, targetValue = 1.3f,
                animationSpec = infiniteRepeatable(animation = tween(1000, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse), label = "pulseScale"
            )
            val pulseAlpha by infiniteTransition.animateFloat(
                initialValue = 0.5f, targetValue = 0f,
                animationSpec = infiniteRepeatable(animation = tween(1000, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Restart), label = "pulseAlpha"
            )

            // Reusable function to actually kill the alarm
            val triggerStopAlarm = {
                AlarmReceiver.stopAlarmAudio(this@AlarmActivity, eventId)
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(eventId)
                finish()
            }

            MaterialTheme(colorScheme = darkColorScheme(background = PureBlack)) {
                Surface(modifier = Modifier.fillMaxSize(), color = PureBlack) {

                    val displayTitle = currentEvent?.title ?: fallbackTitle
                    val displayCategory = if (currentEvent?.category == "Other") {
                        currentEvent?.customCategory?.takeIf { it.isNotBlank() } ?: "Other"
                    } else {
                        currentEvent?.category ?: fallbackCategory
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {

                        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(modifier = Modifier.height(32.dp))

                            Box(contentAlignment = Alignment.Center) {
                                Box(modifier = Modifier.size(120.dp).scale(pulseScale).clip(CircleShape).background(AccentColor.copy(alpha = pulseAlpha)))
                                Box(modifier = Modifier.size(100.dp).clip(CircleShape).background(AccentColor.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Alarm, contentDescription = null, tint = AccentColor, modifier = Modifier.size(50.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(32.dp))
                            Text("IT'S TIME", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccentColor, letterSpacing = 4.sp)

                            Spacer(modifier = Modifier.height(24.dp))

                            Text(displayTitle, fontSize = 42.sp, fontWeight = FontWeight.Black, color = TextPrimary, textAlign = TextAlign.Center, lineHeight = 48.sp)
                            Spacer(modifier = Modifier.height(16.dp))
                            Box(modifier = Modifier.background(AccentColor.copy(alpha = 0.15f), RoundedCornerShape(8.dp)).border(1.dp, AccentColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text(displayCategory, fontSize = 14.sp, color = AccentColor, fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.height(32.dp))

                            if (currentEvent != null) {
                                AnimatedVisibility(visible = showDetails, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = DarkGrayCard),
                                        shape = RoundedCornerShape(20.dp),
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(20.dp)) {
                                            val formatter = SimpleDateFormat("EEEE, MMM dd • hh:mm a", Locale.getDefault())
                                            val dateString = formatter.format(Date(currentEvent!!.startDateTimeInMillis))

                                            AlarmDetailRow(Icons.Filled.Schedule, "Time", dateString)

                                            if (!currentEvent!!.location.isNullOrBlank()) {
                                                HorizontalDivider(color = CardOutline, modifier = Modifier.padding(vertical = 16.dp))
                                                AlarmDetailRow(Icons.Filled.LocationOn, "Location", currentEvent!!.location!!)
                                            }
                                            if (!currentEvent!!.notes.isNullOrBlank()) {
                                                HorizontalDivider(color = CardOutline, modifier = Modifier.padding(vertical = 16.dp))
                                                AlarmDetailRow(Icons.Filled.Notes, "Notes", currentEvent!!.notes!!)
                                            }
                                            if (!currentEvent!!.invitees.isNullOrBlank()) {
                                                HorizontalDivider(color = CardOutline, modifier = Modifier.padding(vertical = 16.dp))
                                                AlarmDetailRow(Icons.Filled.Person, "Invitees", currentEvent!!.invitees!!)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            TextButton(onClick = { showDetails = !showDetails }) {
                                Text(if (showDetails) "Hide Details" else "See Details", fontSize = 16.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(if (showDetails) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = TextSecondary)
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = {
                                    // THE INTERCEPTOR: Does this alarm require math?
                                    if (dismissMethod == "Math Problem") {
                                        showMathDialog = true // Trigger the puzzle! The audio keeps playing!
                                    } else {
                                        triggerStopAlarm() // Normal stop
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(80.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                Text("STOP ALARM", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color.Black)
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }

                    // --- THE MATH PUZZLE POPUP ---
                    if (showMathDialog) {
                        AlertDialog(
                            onDismissRequest = {
                                // Intentionally left blank! They cannot tap outside to dismiss this.
                            },
                            containerColor = DarkGrayCard,
                            title = {
                                Text("Prove you're awake!", color = TextPrimary, fontWeight = FontWeight.Bold)
                            },
                            text = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Solve this to stop the alarm:", color = TextSecondary, modifier = Modifier.padding(bottom = 16.dp))
                                    Text("$num1 + $num2 = ?", fontSize = 36.sp, fontWeight = FontWeight.Black, color = AccentColor, modifier = Modifier.padding(bottom = 24.dp))

                                    OutlinedTextField(
                                        value = mathAnswer,
                                        onValueChange = {
                                            mathAnswer = it
                                            mathError = false // Clear error when they start typing
                                        },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = TextPrimary,
                                            unfocusedTextColor = TextPrimary,
                                            focusedBorderColor = AccentColor,
                                            unfocusedBorderColor = CardOutline,
                                            cursorColor = AccentColor
                                        ),
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        placeholder = { Text("Enter answer", color = TextSecondary) }
                                    )

                                    if (mathError) {
                                        Text("Incorrect! Keep trying.", color = Color(0xFFFF5252), fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                                    }
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        // Verify the answer
                                        val userAnswer = mathAnswer.trim().toIntOrNull()
                                        if (userAnswer == num1 + num2) {
                                            showMathDialog = false
                                            triggerStopAlarm() // Math is correct, finally stop the noise!
                                        } else {
                                            mathError = true
                                            mathAnswer = "" // Clear it so they can try again quickly
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentColor)
                                ) {
                                    Text("Verify & Stop", color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun AlarmDetailRow(icon: ImageVector, label: String, value: String) {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.background(CardOutline, RoundedCornerShape(12.dp)).padding(10.dp)) {
                Icon(icon, contentDescription = null, tint = AccentColor, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f).padding(top = 2.dp)) {
                Text(label, fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(value, fontSize = 16.sp, color = TextPrimary, lineHeight = 24.sp, fontWeight = FontWeight.Normal)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Safety net: if the activity is destroyed (like swiping it away from recents), make sure audio dies
        val eventId = intent.getIntExtra("EVENT_ID", -1)
        AlarmReceiver.stopAlarmAudio(this, eventId)
    }
}