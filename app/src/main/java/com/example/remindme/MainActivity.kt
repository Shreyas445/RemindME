package com.example.remindme

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.remindme.db.AppDatabase
import com.example.remindme.db.Event
import com.example.remindme.db.EventDao
import com.google.mlkit.nl.entityextraction.Entity
import com.google.mlkit.nl.entityextraction.EntityExtraction
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.sin

val PureBlack = Color(0xFF000000)
val DarkGrayCard = Color(0xFF141414)
val CardOutline = Color(0xFF262626)
val AccentColor = Color(0xFFFFC107)
val TextPrimary = Color(0xFFF5F5F5)
val TextSecondary = Color(0xFFA0A0A0)
val SuccessGreen = Color(0xFF4CAF50)

fun triggerWidgetUpdate(context: Context) {
    androidx.work.WorkManager.getInstance(context).enqueue(androidx.work.OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build())
}

fun scheduleDailyBriefing(context: Context) {
    val prefs = context.getSharedPreferences("RemindMePrefs", Context.MODE_PRIVATE)
    val isEnabled = prefs.getBoolean("briefing_enabled", true)
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val intent = Intent(context, AlarmReceiver::class.java).apply { putExtra("IS_DAILY_BRIEFING", true) }
    val pendingIntent = PendingIntent.getBroadcast(context, 999999, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    if (!isEnabled) { alarmManager.cancel(pendingIntent); return }

    val hour = prefs.getInt("briefing_hour", 8); val minute = prefs.getInt("briefing_minute", 0)
    val calendar = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0) }
    if (calendar.timeInMillis <= System.currentTimeMillis()) calendar.add(Calendar.DAY_OF_YEAR, 1)

    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
            else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
        } else { alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent) }
    } catch (e: SecurityException) { e.printStackTrace() }
}

fun backupDataToFile(context: Context, uri: Uri, events: List<Event>) {
    try {
        val jsonArray = JSONArray()
        events.forEach { event ->
            val obj = JSONObject().apply {
                put("title", event.title); put("category", event.category); put("customCategory", event.customCategory ?: "")
                put("startDateTimeInMillis", event.startDateTimeInMillis); put("repeatMode", event.repeatMode); put("repeatDays", event.repeatDays ?: "")
                put("notes", event.notes ?: ""); put("location", event.location ?: ""); put("invitees", event.invitees ?: "")
                put("isVibrationEnabled", event.isVibrationEnabled); put("ringtoneUri", event.ringtoneUri ?: ""); put("isLooping", event.isLooping)
                put("loopCount", event.loopCount); put("volumeLevel", event.volumeLevel.toDouble()); put("alertBefore", event.alertBefore); put("dismissMethod", event.dismissMethod)
            }
            jsonArray.put(obj)
        }
        context.contentResolver.openOutputStream(uri)?.use { stream -> stream.write(jsonArray.toString(4).toByteArray()) }
        Toast.makeText(context, "Backup saved successfully!", Toast.LENGTH_LONG).show()
    } catch (e: Exception) { Toast.makeText(context, "Backup failed: ${e.message}", Toast.LENGTH_LONG).show() }
}

suspend fun restoreDataFromFile(context: Context, uri: Uri, dao: EventDao) {
    withContext(Dispatchers.IO) {
        try {
            val stringBuilder = java.lang.StringBuilder()
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream)).use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) { stringBuilder.append(line); line = reader.readLine() }
                }
            }
            val jsonArray = JSONArray(stringBuilder.toString())
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val event = Event(
                    id = 0, title = obj.getString("title"), category = obj.getString("category"), customCategory = obj.optString("customCategory").takeIf { it.isNotBlank() },
                    startDateTimeInMillis = obj.getLong("startDateTimeInMillis"), repeatMode = obj.getString("repeatMode"), repeatDays = obj.optString("repeatDays").takeIf { it.isNotBlank() },
                    notes = obj.optString("notes").takeIf { it.isNotBlank() }, location = obj.optString("location").takeIf { it.isNotBlank() }, invitees = obj.optString("invitees").takeIf { it.isNotBlank() },
                    isVibrationEnabled = obj.getBoolean("isVibrationEnabled"), ringtoneUri = obj.optString("ringtoneUri").takeIf { it.isNotBlank() }, isLooping = obj.getBoolean("isLooping"),
                    loopCount = obj.getInt("loopCount"), volumeLevel = obj.getDouble("volumeLevel").toFloat(), alertBefore = obj.optString("alertBefore", "None"), dismissMethod = obj.optString("dismissMethod", "Default")
                )
                val insertedId = dao.insertEvent(event).toInt()
                scheduleEventAlarm(context, event.copy(id = insertedId))
            }
            withContext(Dispatchers.Main) { triggerWidgetUpdate(context); Toast.makeText(context, "Data restored successfully!", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(context, "Failed to restore data.", Toast.LENGTH_LONG).show() } }
    }
}

fun scheduleEventAlarm(context: Context, event: Event) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val triggerTime = getNextOccurrence(event)

    if (triggerTime > System.currentTimeMillis()) {
        try {
            val mainIntent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra("EVENT_ID", event.id); putExtra("EVENT_TITLE", event.title); putExtra("EVENT_CATEGORY", event.category); putExtra("EVENT_VIBRATION", event.isVibrationEnabled)
                putExtra("EVENT_RINGTONE", event.ringtoneUri); putExtra("EVENT_IS_LOOPING", event.isLooping); putExtra("EVENT_LOOP_COUNT", event.loopCount); putExtra("EVENT_VOLUME", event.volumeLevel)
                putExtra("IS_PRE_ALERT", false); putExtra("EVENT_DISMISS_METHOD", event.dismissMethod)
            }
            val mainPendingIntent = PendingIntent.getBroadcast(context, event.id, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, mainPendingIntent)
                else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, mainPendingIntent)
            } else { alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, mainPendingIntent) }

            if (event.alertBefore != "None") {
                val preAlertOffsetMillis = when (event.alertBefore) { "30 mins" -> 30L * 60L * 1000L; "1 hour" -> 60L * 60L * 1000L; "1 day" -> 24L * 60L * 60L * 1000L; else -> 0L }
                val preTriggerTime = triggerTime - preAlertOffsetMillis
                if (preTriggerTime > System.currentTimeMillis()) {
                    val preIntent = Intent(context, AlarmReceiver::class.java).apply {
                        putExtra("EVENT_ID", event.id); putExtra("EVENT_TITLE", event.title); putExtra("EVENT_CATEGORY", event.category); putExtra("IS_PRE_ALERT", true); putExtra("ALERT_BEFORE_STR", event.alertBefore)
                    }
                    val prePendingIntent = PendingIntent.getBroadcast(context, event.id + 500000, preIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (alarmManager.canScheduleExactAlarms()) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, preTriggerTime, prePendingIntent)
                        else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, preTriggerTime, prePendingIntent)
                    } else { alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, preTriggerTime, prePendingIntent) }
                }
            }
        } catch (e: SecurityException) { e.printStackTrace() }
    }
}

fun cancelEventAlarm(context: Context, eventId: Int) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val mainIntent = Intent(context, AlarmReceiver::class.java)
    val mainPendingIntent = PendingIntent.getBroadcast(context, eventId, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    alarmManager.cancel(mainPendingIntent)
    val preIntent = Intent(context, AlarmReceiver::class.java)
    val prePendingIntent = PendingIntent.getBroadcast(context, eventId + 500000, preIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    alarmManager.cancel(prePendingIntent)
}

fun getNextOccurrence(event: Event): Long {
    if (event.repeatMode == "None" || event.repeatMode == "Don't repeat") return event.startDateTimeInMillis
    val originalStart = Calendar.getInstance().apply { timeInMillis = event.startDateTimeInMillis }
    val nextOccurrence = Calendar.getInstance().apply { timeInMillis = event.startDateTimeInMillis }
    val now = System.currentTimeMillis()

    if (nextOccurrence.timeInMillis > now) return nextOccurrence.timeInMillis

    var iteration = 1
    while (nextOccurrence.timeInMillis <= now) {
        nextOccurrence.timeInMillis = originalStart.timeInMillis
        when (event.repeatMode) {
            "Daily" -> nextOccurrence.add(Calendar.DAY_OF_YEAR, iteration)
            "Weekly" -> {
                if (event.repeatDays.isNullOrBlank()) { nextOccurrence.add(Calendar.WEEK_OF_YEAR, iteration) } else {
                    nextOccurrence.add(Calendar.DAY_OF_YEAR, iteration)
                    if (nextOccurrence.timeInMillis > now) {
                        val activeDays = event.repeatDays.split(",").mapNotNull { it.toIntOrNull() }
                        while (!activeDays.contains(nextOccurrence.get(Calendar.DAY_OF_WEEK) - 1)) {
                            iteration++; nextOccurrence.timeInMillis = originalStart.timeInMillis; nextOccurrence.add(Calendar.DAY_OF_YEAR, iteration)
                        }
                    }
                }
            }
            "Monthly" -> nextOccurrence.add(Calendar.MONTH, iteration)
            "Yearly" -> nextOccurrence.add(Calendar.YEAR, iteration)
            else -> break
        }
        iteration++
    }
    return nextOccurrence.timeInMillis
}

fun getDeveloperRingtones(context: Context): List<Pair<String, String>> {
    val ringtones = mutableListOf<Pair<String, String>>()
    try {
        val rawClass = R.raw::class.java
        for (field in rawClass.fields) {
            val resourceName = field.name
            val displayName = resourceName.split("_").joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() } }
            val uri = "android.resource://${context.packageName}/raw/$resourceName"
            ringtones.add(uri to displayName)
        }
    } catch (e: Exception) {}
    return ringtones
}

fun getRingtoneName(context: Context, uriString: String?): String {
    if (uriString == "NONE") return "None"
    if (uriString.isNullOrBlank()) return "Default Sound"
    if (uriString.startsWith("android.resource://")) {
        val fileName = uriString.substringAfterLast("/")
        return fileName.split("_").joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() } }
    }
    return try {
        val uri = Uri.parse(uriString)
        val ringtone = RingtoneManager.getRingtone(context, uri)
        ringtone?.getTitle(context) ?: "System Sound"
    } catch (e: Exception) { "System Sound" }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        val db = AppDatabase.getDatabase(this)

        scheduleDailyBriefing(this)
        triggerWidgetUpdate(this)

        setContent {
            val context = LocalContext.current
            var showExactAlarmDialog by remember { mutableStateOf(false) }
            val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    if (!alarmManager.canScheduleExactAlarms()) showExactAlarmDialog = true
                }
            }

            MaterialTheme(colorScheme = darkColorScheme(background = PureBlack, surface = DarkGrayCard)) {
                Surface(modifier = Modifier.fillMaxSize(), color = PureBlack) {
                    RemindMeApp(db.eventDao())

                    if (showExactAlarmDialog) {
                        AlertDialog(
                            onDismissRequest = { }, containerColor = DarkGrayCard, title = { Text("Alarm Permission Required", color = TextPrimary, fontWeight = FontWeight.Bold) },
                            text = { Text("To ensure your alarms wake up the screen and ring exactly on time, Android requires you to manually grant 'Alarms & Reminders' permission.", color = TextSecondary, lineHeight = 20.sp) },
                            confirmButton = { TextButton(onClick = { showExactAlarmDialog = false; context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply { data = Uri.parse("package:${context.packageName}") }) }) { Text("Go to Settings", color = AccentColor, fontWeight = FontWeight.Bold) } },
                            dismissButton = { TextButton(onClick = { showExactAlarmDialog = false }) { Text("Later", color = TextSecondary) } }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RemindMeApp(dao: EventDao) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") { HomeScreen(navController, dao) }
        composable("details/{eventId}", arguments = listOf(navArgument("eventId") { type = NavType.IntType })) { backStackEntry -> EventDetailsScreen(navController, dao, backStackEntry.arguments?.getInt("eventId") ?: -1) }
        composable(
            route = "add_edit/{eventId}?title={title}&time={time}",
            arguments = listOf(
                navArgument("eventId") { type = NavType.IntType },
                navArgument("title") { type = NavType.StringType; nullable = true },
                navArgument("time") { type = NavType.LongType; defaultValue = -1L }
            )
        ) { backStackEntry ->
            val eventId = backStackEntry.arguments?.getInt("eventId") ?: -1
            val title = backStackEntry.arguments?.getString("title") ?: ""
            val time = backStackEntry.arguments?.getLong("time") ?: -1L
            AddEditEventScreen(navController, dao, eventId, title, time)
        }
        composable("settings") { SettingsScreen(navController, dao) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController, dao: EventDao) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val appVersion = "1.0.0"

    val prefs = context.getSharedPreferences("RemindMePrefs", Context.MODE_PRIVATE)
    var isBriefingEnabled by remember { mutableStateOf(prefs.getBoolean("briefing_enabled", true)) }
    var briefingHour by remember { mutableStateOf(prefs.getInt("briefing_hour", 8)) }
    var briefingMinute by remember { mutableStateOf(prefs.getInt("briefing_minute", 0)) }

    val timeFormatter = SimpleDateFormat("hh:mm a", Locale.getDefault())
    val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, briefingHour); set(Calendar.MINUTE, briefingMinute) }
    var briefingTimeText by remember { mutableStateOf(timeFormatter.format(cal.time)) }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { coroutineScope.launch(Dispatchers.IO) { val events = dao.getAllEventsFlow().first(); withContext(Dispatchers.Main) { backupDataToFile(context, it, events) } } }
    }

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { coroutineScope.launch { restoreDataFromFile(context, it, dao) } }
    }

    Scaffold(
        containerColor = PureBlack,
        topBar = { TopAppBar(title = { Text("Settings", color = TextPrimary, fontWeight = FontWeight.Bold) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = PureBlack), navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary) } }) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {

            Text("DAILY BRIEFING", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 8.dp, start = 8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = DarkGrayCard), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Box(modifier = Modifier.background(CardOutline, RoundedCornerShape(8.dp)).padding(8.dp)) { Icon(Icons.Filled.WbSunny, null, tint = AccentColor, modifier = Modifier.size(20.dp)) }; Spacer(Modifier.width(16.dp)); Column { Text("Morning Digest", fontSize = 16.sp, color = TextPrimary); Text("Get a quiet summary of today's events", fontSize = 12.sp, color = TextSecondary) } }
                        Switch(checked = isBriefingEnabled, onCheckedChange = { isBriefingEnabled = it; prefs.edit().putBoolean("briefing_enabled", it).apply(); scheduleDailyBriefing(context) }, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = AccentColor, uncheckedThumbColor = TextSecondary, uncheckedTrackColor = CardOutline))
                    }
                    AnimatedVisibility(visible = isBriefingEnabled) {
                        Column {
                            HorizontalDivider(color = CardOutline)
                            Row(modifier = Modifier.fillMaxWidth().clickable { TimePickerDialog(context, { _, h, m -> briefingHour = h; briefingMinute = m; prefs.edit().putInt("briefing_hour", h).putInt("briefing_minute", m).apply(); cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, m); briefingTimeText = timeFormatter.format(cal.time); scheduleDailyBriefing(context) }, briefingHour, briefingMinute, false).show() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Row(verticalAlignment = Alignment.CenterVertically) { Box(modifier = Modifier.background(CardOutline, RoundedCornerShape(8.dp)).padding(8.dp)) { Icon(Icons.Filled.Schedule, null, tint = AccentColor, modifier = Modifier.size(20.dp)) }; Spacer(Modifier.width(16.dp)); Text("Briefing Time", fontSize = 16.sp, color = TextPrimary) }
                                Row(verticalAlignment = Alignment.CenterVertically) { Text(briefingTimeText, fontSize = 16.sp, color = TextPrimary, fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.width(8.dp)); Icon(Icons.Default.ChevronRight, null, tint = TextSecondary) }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text("DATA MANAGEMENT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 8.dp, start = 8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = DarkGrayCard), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsClickableRow(icon = Icons.Filled.SaveAlt, label = "Backup Events to File") { val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()); backupLauncher.launch("RemindMe_Backup_${dateFormat.format(Date())}.json") }
                    HorizontalDivider(color = CardOutline)
                    SettingsClickableRow(icon = Icons.Filled.Restore, label = "Restore Events from File") { restoreLauncher.launch(arrayOf("application/json")) }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text("SUPPORT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 8.dp, start = 8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = DarkGrayCard), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsClickableRow(icon = Icons.Filled.StarRate, label = "Rate the App") { try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${context.packageName}"))) } catch (e: Exception) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}"))) } }
                    HorizontalDivider(color = CardOutline)
                    SettingsClickableRow(icon = Icons.Filled.Share, label = "Share with Friends") { val shareIntent = Intent().apply { action = Intent.ACTION_SEND; putExtra(Intent.EXTRA_TEXT, "Check out Remind Me! https://play.google.com/store/apps/details?id=${context.packageName}"); type = "text/plain" }; context.startActivity(Intent.createChooser(shareIntent, "Share App")) }
                    HorizontalDivider(color = CardOutline)
                    SettingsClickableRow(icon = Icons.Filled.Email, label = "Contact Developer") { val emailIntent = Intent(Intent.ACTION_SENDTO).apply { data = Uri.parse("mailto:imaginedevs.tech@gmail.com"); putExtra(Intent.EXTRA_SUBJECT, "Remind Me App Feedback") }; try { context.startActivity(emailIntent) } catch (e: Exception) { Toast.makeText(context, "No email app found.", Toast.LENGTH_SHORT).show() } }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text("ABOUT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 8.dp, start = 8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = DarkGrayCard), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) { Column { SettingsRow(icon = Icons.Filled.Info, label = "App Version", value = appVersion) } }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun SettingsRow(icon: ImageVector, label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) { Box(modifier = Modifier.background(CardOutline, RoundedCornerShape(8.dp)).padding(8.dp)) { Icon(icon, null, tint = AccentColor, modifier = Modifier.size(20.dp)) }; Spacer(Modifier.width(16.dp)); Text(label, fontSize = 16.sp, color = TextPrimary) }
        Text(value, fontSize = 14.sp, color = TextSecondary)
    }
}

@Composable
fun SettingsClickableRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) { Box(modifier = Modifier.background(CardOutline, RoundedCornerShape(8.dp)).padding(8.dp)) { Icon(icon, null, tint = AccentColor, modifier = Modifier.size(20.dp)) }; Spacer(Modifier.width(16.dp)); Text(label, fontSize = 16.sp, color = TextPrimary) }
        Icon(Icons.Default.ChevronRight, null, tint = TextSecondary)
    }
}

enum class VoiceState { IDLE, LISTENING_MAIN, THINKING, ASKING_TIME, LISTENING_TIME, ASKING_TITLE, LISTENING_TITLE }

fun customLerp(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController, dao: EventDao) {
    val rawEvents by dao.getAllEventsFlow().collectAsState(initial = emptyList())
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    var selectedEventIds by remember { mutableStateOf(setOf<Int>()) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    var selectedFilter by remember { mutableStateOf("Upcoming") }
    val categories = listOf("Upcoming", "All", "Events", "Birthdays", "Exams", "Medicine", "Last Dates", "Other")
    var isMenuExpanded by remember { mutableStateOf(false) }

    val isSelectionMode = selectedEventIds.isNotEmpty()

    val processedEvents = remember(rawEvents) { rawEvents.map { event -> event to getNextOccurrence(event) }.sortedBy { it.second } }
    val todayStart = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    val filteredEvents = processedEvents.filter { (event, nextDate) ->
        val matchesCategory = when (selectedFilter) { "Upcoming" -> nextDate >= todayStart; "All" -> true; else -> event.category == selectedFilter }
        matchesCategory && event.title.contains(searchQuery, ignoreCase = true)
    }

    var isNavigatingToAdd by remember { mutableStateOf(false) }
    var voiceState by remember { mutableStateOf(VoiceState.IDLE) }
    var spokenText by remember { mutableStateOf("") }
    var rmsDb by remember { mutableStateOf(0f) }

    var draftTitle by remember { mutableStateOf("") }
    var draftDateMillis by remember { mutableStateOf(-1L) }
    var draftNeedsTime by remember { mutableStateOf(false) }

    val entityExtractor = remember { EntityExtraction.getClient(EntityExtractorOptions.Builder(EntityExtractorOptions.ENGLISH).build()) }
    LaunchedEffect(Unit) { entityExtractor.downloadModelIfNeeded() }

    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    val speechIntent = remember {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        intent
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) { voiceState = VoiceState.LISTENING_MAIN; speechRecognizer.startListening(speechIntent) }
        else { Toast.makeText(context, "Microphone permission is required.", Toast.LENGTH_SHORT).show() }
    }

    DisposableEffect(Unit) {
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() { spokenText = "" }
            override fun onRmsChanged(rmsdB: Float) { rmsDb = rmsdB }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { voiceState = VoiceState.IDLE; rmsDb = 0f }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches.isNullOrEmpty()) { voiceState = VoiceState.IDLE; rmsDb = 0f; return }

                val rawText = matches[0]
                spokenText = rawText

                if (voiceState == VoiceState.LISTENING_MAIN) {
                    voiceState = VoiceState.THINKING
                    val rawLower = rawText.lowercase()

                    entityExtractor.annotate(rawText).addOnSuccessListener { annotations ->
                        var extractedTime = -1L
                        var cleanTitle = rawText

                        for (annotation in annotations) {
                            val dateTimeEntity = annotation.entities.find { it.type == Entity.TYPE_DATE_TIME }?.asDateTimeEntity()
                            if (dateTimeEntity != null) {
                                extractedTime = dateTimeEntity.timestampMillis
                                cleanTitle = cleanTitle.replace(annotation.annotatedText, "", ignoreCase = true)
                            }
                        }

                        if (extractedTime == -1L) {
                            val timerRegex = Regex("(timer|alarm) for (\\d+) (min|mins|minute|minutes|hour|hours|hr|hrs)")
                            val match = timerRegex.find(rawLower)
                            if (match != null) {
                                val amount = match.groupValues[2].toInt(); val unit = match.groupValues[3]
                                val cal = Calendar.getInstance()
                                if (unit.startsWith("min")) cal.add(Calendar.MINUTE, amount) else if (unit.startsWith("h")) cal.add(Calendar.HOUR, amount)
                                extractedTime = cal.timeInMillis; cleanTitle = cleanTitle.replace(match.value, "", ignoreCase = true)
                            }
                        }

                        if (extractedTime != -1L) {
                            val cal = Calendar.getInstance().apply { timeInMillis = extractedTime }
                            val hour = cal.get(Calendar.HOUR_OF_DAY)
                            if ((rawLower.contains("evening") || rawLower.contains("night") || rawLower.contains("afternoon") || rawLower.contains("pm")) && hour < 12) { cal.add(Calendar.HOUR_OF_DAY, 12); extractedTime = cal.timeInMillis } else if ((rawLower.contains("morning") || rawLower.contains("am")) && hour >= 12) { cal.add(Calendar.HOUR_OF_DAY, -12); extractedTime = cal.timeInMillis }
                        }

                        var finalTitle = cleanTitle.lowercase().trim()
                        val prefixes = listOf("remind me to", "remind me that", "remind me", "set a reminder to", "set a reminder for", "set a reminder", "set an alarm for", "set an alarm to", "set an alarm", "set alarm for", "alarm for", "set a timer for", "set a timer to", "set a timer", "set timer for", "timer for")
                        for (prefix in prefixes) { if (finalTitle.startsWith(prefix)) { finalTitle = finalTitle.removePrefix(prefix).trim(); break } }

                        val cleanRegex = Regex("\\b(at|on|in|for|morning|afternoon|evening|night|pm|am|today|tomorrow|tonight)\\b")
                        finalTitle = finalTitle.replace(cleanRegex, "").trim()
                        finalTitle = finalTitle.replace("\\s+".toRegex(), " ")
                        if (finalTitle.isNotEmpty()) finalTitle = finalTitle.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

                        var needsTime = false
                        if (extractedTime != -1L) {
                            val calCheck = Calendar.getInstance().apply { timeInMillis = extractedTime }
                            if (calCheck.get(Calendar.HOUR_OF_DAY) == 0 && calCheck.get(Calendar.MINUTE) == 0) needsTime = true
                        } else { extractedTime = System.currentTimeMillis(); needsTime = true }

                        draftTitle = finalTitle; draftDateMillis = extractedTime; draftNeedsTime = needsTime

                        if (draftTitle.isEmpty() || draftTitle.isBlank()) { voiceState = VoiceState.ASKING_TITLE } else if (draftNeedsTime) { voiceState = VoiceState.ASKING_TIME } else { voiceState = VoiceState.IDLE; rmsDb = 0f; navController.navigate("add_edit/-1?title=${Uri.encode(draftTitle)}&time=$draftDateMillis") }
                    }.addOnFailureListener { voiceState = VoiceState.IDLE; rmsDb = 0f; navController.navigate("add_edit/-1?title=${Uri.encode(rawText)}&time=-1") }
                }
                else if (voiceState == VoiceState.LISTENING_TITLE) {
                    voiceState = VoiceState.THINKING
                    var finalT = rawText.lowercase().trim()
                    val cleanTRegex = Regex("^\\b(it is for|its for|it's for|for|it is|to)\\b")
                    finalT = finalT.replace(cleanTRegex, "").trim()
                    draftTitle = finalT.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

                    if (draftNeedsTime) { voiceState = VoiceState.ASKING_TIME } else { voiceState = VoiceState.IDLE; rmsDb = 0f; navController.navigate("add_edit/-1?title=${Uri.encode(draftTitle)}&time=$draftDateMillis") }
                }
                else if (voiceState == VoiceState.LISTENING_TIME) {
                    voiceState = VoiceState.THINKING
                    val rawLower = rawText.lowercase()

                    entityExtractor.annotate(rawText).addOnSuccessListener { annotations ->
                        var finalMergedTime = draftDateMillis
                        var parsedTimeCal: Calendar? = null

                        for (annotation in annotations) {
                            val dateTimeEntity = annotation.entities.find { it.type == Entity.TYPE_DATE_TIME }?.asDateTimeEntity()
                            if (dateTimeEntity != null) { parsedTimeCal = Calendar.getInstance().apply { timeInMillis = dateTimeEntity.timestampMillis }; break }
                        }

                        if (parsedTimeCal != null) {
                            val hour = parsedTimeCal.get(Calendar.HOUR_OF_DAY)
                            if ((rawLower.contains("evening") || rawLower.contains("night") || rawLower.contains("afternoon") || rawLower.contains("pm")) && hour < 12) { parsedTimeCal.add(Calendar.HOUR_OF_DAY, 12) } else if ((rawLower.contains("morning") || rawLower.contains("am")) && hour >= 12) { parsedTimeCal.add(Calendar.HOUR_OF_DAY, -12) }
                            val finalCal = Calendar.getInstance().apply { timeInMillis = draftDateMillis; set(Calendar.HOUR_OF_DAY, parsedTimeCal.get(Calendar.HOUR_OF_DAY)); set(Calendar.MINUTE, parsedTimeCal.get(Calendar.MINUTE)) }
                            finalMergedTime = finalCal.timeInMillis
                        }

                        voiceState = VoiceState.IDLE; rmsDb = 0f
                        navController.navigate("add_edit/-1?title=${Uri.encode(draftTitle)}&time=$finalMergedTime")
                    }.addOnFailureListener { voiceState = VoiceState.IDLE; rmsDb = 0f; navController.navigate("add_edit/-1?title=${Uri.encode(draftTitle)}&time=$draftDateMillis") }
                }
            }
            override fun onPartialResults(partialResults: Bundle?) { val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION); if (!matches.isNullOrEmpty()) spokenText = matches[0] }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
        speechRecognizer.setRecognitionListener(listener)
        onDispose { speechRecognizer.destroy() }
    }

    LaunchedEffect(voiceState) {
        if (voiceState == VoiceState.ASKING_TIME || voiceState == VoiceState.ASKING_TITLE) { spokenText = ""; delay(1500); voiceState = if (voiceState == VoiceState.ASKING_TIME) VoiceState.LISTENING_TIME else VoiceState.LISTENING_TITLE; speechRecognizer.startListening(speechIntent) }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "wave_time")
    val time by infiniteTransition.animateFloat(initialValue = 0f, targetValue = 100f, animationSpec = infiniteRepeatable(tween(20000, easing = LinearEasing)), label = "time")

    val isCircleMode = voiceState != VoiceState.IDLE && voiceState != VoiceState.LISTENING_MAIN
    val morphProgress by animateFloatAsState(targetValue = if (isCircleMode) 1f else 0f, animationSpec = tween(800, easing = FastOutSlowInEasing), label = "morph")

    val micRevealRadius by animateFloatAsState(targetValue = if (voiceState != VoiceState.IDLE) 3500f else 0f, animationSpec = tween(500, easing = FastOutSlowInEasing), label = "micReveal")
    val addRevealRadius by animateFloatAsState(targetValue = if (isNavigatingToAdd) 3500f else 0f, animationSpec = tween(400, easing = FastOutSlowInEasing), label = "addReveal")

    val targetPulseScale = if (voiceState != VoiceState.IDLE) 1f + (rmsDb.coerceAtLeast(0f) / 10f) else 1f
    val animatedPulseScale by animateFloatAsState(targetValue = targetPulseScale, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow), label = "voicePulse")

    LaunchedEffect(isNavigatingToAdd) {
        if (isNavigatingToAdd) { delay(300); navController.navigate("add_edit/-1"); delay(500); isNavigatingToAdd = false }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false }, containerColor = DarkGrayCard, title = { Text("Delete Events", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to delete ${selectedEventIds.size} event(s)? This cannot be undone.", color = TextSecondary) },
            confirmButton = { TextButton(onClick = { coroutineScope.launch(Dispatchers.IO) { selectedEventIds.forEach { cancelEventAlarm(context, it) }; dao.deleteEvents(selectedEventIds.toList()); selectedEventIds = emptySet(); showDeleteDialog = false; withContext(Dispatchers.Main) { triggerWidgetUpdate(context) } } }) { Text("Delete", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel", color = TextPrimary) } }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = PureBlack,
            topBar = {
                AnimatedVisibility(visible = isSelectionMode, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    TopAppBar(title = { Text("${selectedEventIds.size} Selected", color = TextPrimary, fontWeight = FontWeight.Bold) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = CardOutline), navigationIcon = { IconButton(onClick = { selectedEventIds = emptySet() }) { Icon(Icons.Default.Close, "Cancel", tint = TextPrimary) } }, actions = { IconButton(onClick = { showDeleteDialog = true }) { Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFFF5252)) } })
                }
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 20.dp)) {
                AnimatedVisibility(visible = !isSelectionMode) {
                    Column {
                        AnimatedContent(targetState = isSearchActive, label = "search_header", modifier = Modifier.padding(top = 16.dp)) { searchActive ->
                            if (searchActive) {
                                TextField(value = searchQuery, onValueChange = { searchQuery = it }, placeholder = { Text("Search events...", color = TextSecondary, fontSize = 18.sp) }, textStyle = LocalTextStyle.current.copy(fontSize = 18.sp), modifier = Modifier.fillMaxWidth().focusRequester(focusRequester), colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = AccentColor, unfocusedIndicatorColor = CardOutline, cursorColor = AccentColor, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary), trailingIcon = { IconButton(onClick = { isSearchActive = false; searchQuery = "" }) { Icon(Icons.Default.Close, "Close Search", tint = TextPrimary) } }, singleLine = true)
                                LaunchedEffect(Unit) { focusRequester.requestFocus() }
                            } else {
                                Row(modifier = Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Remind Me", fontSize = 34.sp, fontWeight = FontWeight.Black, color = TextPrimary, letterSpacing = (-1).sp)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { isSearchActive = true }) { Icon(Icons.Default.Search, "Search", tint = TextPrimary) }
                                        Box {
                                            IconButton(onClick = { isMenuExpanded = true }) { Icon(Icons.Default.MoreVert, "Menu", tint = TextPrimary) }
                                            DropdownMenu(expanded = isMenuExpanded, onDismissRequest = { isMenuExpanded = false }, modifier = Modifier.background(DarkGrayCard)) { DropdownMenuItem(text = { Text("Settings", color = TextPrimary) }, onClick = { isMenuExpanded = false; navController.navigate("settings") }, leadingIcon = { Icon(Icons.Default.Settings, null, tint = TextSecondary) }) }
                                        }
                                    }
                                }
                            }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 16.dp, bottom = 12.dp)) {
                            items(categories) { category -> val isSelected = selectedFilter == category; Box(modifier = Modifier.clip(RoundedCornerShape(50)).background(if (isSelected) AccentColor else Color.Transparent).border(1.dp, if (isSelected) AccentColor else CardOutline, RoundedCornerShape(50)).clickable { selectedFilter = category }.padding(horizontal = 20.dp, vertical = 10.dp)) { Text(category, color = if (isSelected) Color.Black else TextSecondary, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp) } }
                        }
                    }
                }

                if (filteredEvents.isEmpty()) {
                    Column(modifier = Modifier.fillMaxSize().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Outlined.CalendarToday, null, modifier = Modifier.size(80.dp), tint = CardOutline); Spacer(modifier = Modifier.height(16.dp))
                        Text(if (searchQuery.isNotEmpty()) "No results found" else "Clear Schedule", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary); Spacer(modifier = Modifier.height(8.dp))
                        Text(if (searchQuery.isNotEmpty()) "Try a different search term." else "You have no events matching this filter.\nTap + to create one.", fontSize = 14.sp, color = TextSecondary, textAlign = TextAlign.Center, lineHeight = 20.sp)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.weight(1f).padding(top = 8.dp), contentPadding = PaddingValues(bottom = 140.dp)) {
                        items(filteredEvents) { (event, nextDate) ->
                            val isSelected = selectedEventIds.contains(event.id)
                            EventCard(event = event, nextDateMillis = nextDate, isSelected = isSelected, modifier = Modifier.animateContentSize().combinedClickable(onClick = { if (isSelectionMode) { selectedEventIds = if (isSelected) selectedEventIds - event.id else selectedEventIds + event.id } else { navController.navigate("details/${event.id}") } }, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); selectedEventIds = if (isSelected) selectedEventIds - event.id else selectedEventIds + event.id }))
                        }
                    }
                }
            }
        }

        if (!isSelectionMode) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 48.dp)) {
                FloatingActionButton(
                    onClick = {
                        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                        if (permission == PackageManager.PERMISSION_GRANTED) { voiceState = VoiceState.LISTENING_MAIN; speechRecognizer.startListening(speechIntent) }
                        else { audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                    },
                    containerColor = DarkGrayCard, contentColor = TextPrimary, shape = RoundedCornerShape(16.dp), modifier = Modifier.size(48.dp)
                ) { Icon(Icons.Filled.Mic, "Smart Add", modifier = Modifier.size(24.dp)) }
                Spacer(modifier = Modifier.height(16.dp))
                FloatingActionButton(
                    onClick = { isNavigatingToAdd = true }, containerColor = AccentColor, contentColor = Color.Black, shape = RoundedCornerShape(16.dp), modifier = Modifier.size(56.dp), elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp)
                ) { Icon(Icons.Filled.Add, "Add Event", modifier = Modifier.size(28.dp)) }
            }
        }

        if (addRevealRadius > 0f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerX = size.width - with(density) { 52.dp.toPx() }; val centerY = size.height - with(density) { 76.dp.toPx() }
                drawCircle(color = AccentColor, radius = addRevealRadius, center = Offset(centerX, centerY))
            }
        }

        if (micRevealRadius > 0f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerX = size.width - with(density) { 52.dp.toPx() }; val centerY = size.height - with(density) { 144.dp.toPx() }
                drawCircle(color = PureBlack.copy(alpha = 0.92f), radius = micRevealRadius, center = Offset(centerX, centerY))
            }
        }

        AnimatedVisibility(
            visible = voiceState != VoiceState.IDLE, enter = fadeIn(animationSpec = tween(400, delayMillis = 150)), exit = fadeOut(animationSpec = tween(300))
        ) {
            Box(modifier = Modifier.fillMaxSize()) {

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width; val h = size.height
                    val waveCenterY = h * 0.25f
                    val blobCenterX = w / 2f; val blobCenterY = h * 0.25f
                    val baseR = with(density) { 70.dp.toPx() }

                    val gradient = Brush.linearGradient(colors = listOf(Color(0xFFFFD54F), Color(0xFFFFC107), Color(0xFFFF9800)), start = Offset(0f, 0f), end = Offset(w, h))

                    for (j in 0..2) {
                        val path = Path()
                        val points = 100
                        for (i in 0..points) {
                            val tNorm = i / points.toFloat()
                            val waveX = tNorm * w
                            val voicePulse = (rmsDb.coerceAtLeast(0f) * 2f)
                            val amplitude = with(density) { 20.dp.toPx() } + voicePulse + (j * 15f)
                            val wavePhase = time * (3f + j * 0.5f) + (j * 2f)
                            val waveY = waveCenterY + amplitude * sin(tNorm * 8f + wavePhase)
                            val theta = tNorm * 2 * Math.PI.toFloat()
                            val blobPhase = time * (2f + j) + (j * 1.5f)
                            val lobeAmplitude = with(density) { 15.dp.toPx() }
                            val currentR = baseR + lobeAmplitude * sin(3 * theta + blobPhase)
                            val blobX = blobCenterX + currentR * cos(theta)
                            val blobY = blobCenterY + currentR * sin(theta)

                            val x = customLerp(waveX, blobX.toFloat(), morphProgress)
                            val y = customLerp(waveY, blobY.toFloat(), morphProgress)

                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path = path, brush = gradient, style = Stroke(width = with(density) { (3f + j * 1.5f).dp.toPx() }, cap = StrokeCap.Round, join = StrokeJoin.Round), alpha = 1f - (j * 0.25f))
                    }
                }

                Column(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 280.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (morphProgress < 0.5f) {
                        val txt = if (voiceState == VoiceState.THINKING) "Thinking..." else if (spokenText.isEmpty()) "Listening..." else spokenText
                        Text(text = txt, fontSize = 28.sp, color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                    } else {
                        val topText = if (voiceState == VoiceState.ASKING_TITLE || voiceState == VoiceState.LISTENING_TITLE) "What for?" else "At what time?"
                        Text(text = topText, fontSize = 28.sp, color = AccentColor, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        if (spokenText.isNotEmpty()) { Spacer(modifier = Modifier.height(16.dp)); Text(text = spokenText, fontSize = 20.sp, color = Color.White, textAlign = TextAlign.Center) }
                    }
                }

                Box(modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 48.dp), contentAlignment = Alignment.Center) {
                    Box(modifier = Modifier.size(80.dp).scale(animatedPulseScale).background(AccentColor.copy(alpha = 0.2f), CircleShape))
                    Box(modifier = Modifier.size(60.dp).scale(animatedPulseScale * 0.9f).background(AccentColor.copy(alpha = 0.4f), CircleShape))
                    FloatingActionButton(onClick = { speechRecognizer.cancel(); voiceState = VoiceState.IDLE; rmsDb = 0f }, containerColor = Color(0xFFFF5252), contentColor = Color.White, shape = RoundedCornerShape(16.dp), modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Close, "Cancel", modifier = Modifier.size(24.dp)) }
                }
            }
        }
    }
}

@Composable
fun EventCard(event: Event, nextDateMillis: Long, isSelected: Boolean, modifier: Modifier = Modifier) {
    val formatterDate = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    val formatterTime = SimpleDateFormat("hh:mm a", Locale.getDefault())

    val dateString = formatterDate.format(Date(nextDateMillis))
    val timeString = formatterTime.format(Date(nextDateMillis))

    val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    val targetDate = Calendar.getInstance().apply { timeInMillis = nextDateMillis; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    val diffMillis = targetDate - today
    val daysLeft = (diffMillis / (1000 * 60 * 60 * 24)).toInt()

    val (badgeText, badgeBg, badgeTint) = when {
        daysLeft < 0 -> Triple(Math.abs(daysLeft).toString() + "d ago", CardOutline, TextSecondary)
        daysLeft == 0 -> Triple("TODAY", SuccessGreen.copy(alpha = 0.2f), SuccessGreen)
        daysLeft == 1 -> Triple("TMRW", AccentColor.copy(alpha = 0.2f), AccentColor)
        else -> Triple("In ${daysLeft}d", DarkGrayCard, AccentColor)
    }

    val categoryIcon = when (event.category) {
        "Birthdays" -> Icons.Outlined.Cake
        "Exams", "Medicine", "Last Dates" -> Icons.Outlined.Assignment
        "Events" -> Icons.Outlined.Event
        else -> Icons.Outlined.Label
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = if (isSelected) CardOutline else DarkGrayCard),
        shape = RoundedCornerShape(24.dp), border = if (isSelected) BorderStroke(2.dp, AccentColor) else BorderStroke(1.dp, Color(0xFF1E1E1E)), modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.size(44.dp).background(CardOutline, CircleShape), contentAlignment = Alignment.Center) { Icon(categoryIcon, contentDescription = null, tint = AccentColor, modifier = Modifier.size(22.dp)) }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(event.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary, lineHeight = 26.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (event.notes?.isNotBlank() == true) { Spacer(modifier = Modifier.height(4.dp)); Text(event.notes!!, fontSize = 13.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Box(modifier = Modifier.background(badgeBg, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) { Text(badgeText, fontSize = 12.sp, fontWeight = FontWeight.Black, color = badgeTint) }
            }
            Spacer(modifier = Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Schedule, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp)); Spacer(modifier = Modifier.width(8.dp)); Text(dateString, fontSize = 14.sp, color = TextSecondary, fontWeight = FontWeight.Medium); Text(" • ", fontSize = 14.sp, color = TextSecondary); Text(timeString, fontSize = 14.sp, color = TextPrimary, fontWeight = FontWeight.Bold) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (event.alertBefore != "None") { Icon(Icons.Outlined.NotificationsActive, contentDescription = "Pre-Alert On", tint = AccentColor, modifier = Modifier.size(18.dp)) }
                    if (event.repeatMode != "None" && event.repeatMode != "Don't repeat") { Icon(Icons.Outlined.Loop, contentDescription = "Repeats", tint = TextSecondary, modifier = Modifier.size(18.dp)) }
                    if (event.dismissMethod == "Math Problem") { Icon(Icons.Outlined.Calculate, contentDescription = "Math Lock", tint = Color(0xFFFF5252), modifier = Modifier.size(18.dp)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailsScreen(navController: NavController, dao: EventDao, eventId: Int) {
    val context = LocalContext.current
    var event by remember { mutableStateOf<Event?>(null) }
    LaunchedEffect(eventId) { event = withContext(Dispatchers.IO) { dao.getEventById(eventId) } }

    Scaffold(
        containerColor = PureBlack,
        topBar = { TopAppBar(title = { }, colors = TopAppBarDefaults.topAppBarColors(containerColor = PureBlack), navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary) } }, actions = { IconButton(onClick = { navController.navigate("add_edit/${eventId}") }) { Icon(Icons.Filled.Edit, "Edit", tint = AccentColor) } }) }
    ) { padding ->
        val currentEvent = event
        if (currentEvent != null) {
            val actualNextDate = getNextOccurrence(currentEvent)
            val dateString = SimpleDateFormat("EEEE, MMM dd, yyyy \n hh:mm a", Locale.getDefault()).format(Date(actualNextDate))

            Column(modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(currentEvent.title, fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Black, color = TextPrimary)
                Spacer(modifier = Modifier.height(28.dp))

                Card(colors = CardDefaults.cardColors(containerColor = DarkGrayCard), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        DetailRow(Icons.Filled.Schedule, "Next Occurrence", dateString)
                        HorizontalDivider(color = CardOutline, modifier = Modifier.padding(vertical = 16.dp))
                        DetailRow(Icons.Filled.Refresh, "Repeats", currentEvent.repeatMode)
                        if (currentEvent.alertBefore != "None") { HorizontalDivider(color = CardOutline, modifier = Modifier.padding(vertical = 16.dp)); DetailRow(Icons.Filled.NotificationsActive, "Alert Before", currentEvent.alertBefore) }
                        if (currentEvent.dismissMethod != "Default") { HorizontalDivider(color = CardOutline, modifier = Modifier.padding(vertical = 16.dp)); DetailRow(Icons.Filled.Calculate, "Stop Method", currentEvent.dismissMethod) }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                Card(colors = CardDefaults.cardColors(containerColor = DarkGrayCard), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        DetailRow(Icons.Filled.Notifications, "Alarm Sound", getRingtoneName(context, currentEvent.ringtoneUri))
                        if (currentEvent.isLooping) { HorizontalDivider(color = CardOutline, modifier = Modifier.padding(vertical = 16.dp)); DetailRow(Icons.Filled.Repeat, "Loops", "${currentEvent.loopCount} Times") }
                        HorizontalDivider(color = CardOutline, modifier = Modifier.padding(vertical = 16.dp))
                        DetailRow(Icons.Filled.VolumeUp, "Volume Level", "${(currentEvent.volumeLevel * 100).toInt()}%")
                        HorizontalDivider(color = CardOutline, modifier = Modifier.padding(vertical = 16.dp))
                        DetailRow(Icons.Filled.Vibration, "Vibration", if (currentEvent.isVibrationEnabled) "Enabled" else "Disabled")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                Card(colors = CardDefaults.cardColors(containerColor = DarkGrayCard), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        DetailRow(Icons.Filled.Label, "Category", currentEvent.category)
                        if (!currentEvent.location.isNullOrBlank()) { HorizontalDivider(color = CardOutline, modifier = Modifier.padding(vertical = 16.dp)); DetailRow(Icons.Filled.LocationOn, "Location", currentEvent.location) }
                        if (!currentEvent.notes.isNullOrBlank()) { HorizontalDivider(color = CardOutline, modifier = Modifier.padding(vertical = 16.dp)); DetailRow(Icons.Filled.Notes, "Notes", currentEvent.notes) }
                        if (!currentEvent.invitees.isNullOrBlank()) { HorizontalDivider(color = CardOutline, modifier = Modifier.padding(vertical = 16.dp)); DetailRow(Icons.Filled.Person, "Invitees", currentEvent.invitees) }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun DetailRow(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.background(CardOutline, RoundedCornerShape(12.dp)).padding(10.dp)) { Icon(icon, contentDescription = null, tint = AccentColor, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f).padding(top = 2.dp)) { Text(label, fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Medium); Spacer(Modifier.height(4.dp)); Text(value, fontSize = 16.sp, color = TextPrimary, lineHeight = 24.sp, fontWeight = FontWeight.Normal) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditEventScreen(navController: NavController, dao: EventDao, eventId: Int, prefillTitle: String = "", prefillTime: Long = -1L) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isEditMode = eventId != -1

    var title by remember { mutableStateOf(prefillTitle) }
    var notes by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var invitees by remember { mutableStateOf("") }

    val categories = listOf("Events", "Birthdays", "Exams", "Medicine", "Last Dates", "Other")
    var selectedCategory by remember { mutableStateOf(categories[0]) }
    var customCategoryText by remember { mutableStateOf("") }

    val repeatOptions = listOf("Don't repeat", "Daily", "Weekly", "Monthly", "Yearly")
    var selectedRepeat by remember { mutableStateOf(repeatOptions[0]) }
    val alertBeforeOptions = listOf("None", "30 mins", "1 hour", "1 day")
    var selectedAlertBefore by remember { mutableStateOf(alertBeforeOptions[0]) }

    val weekDays = listOf("S", "M", "T", "W", "T", "F", "S")
    var selectedDays by remember { mutableStateOf(setOf<Int>()) }
    val calendar = remember { Calendar.getInstance() }
    var dateText by remember { mutableStateOf("Select Date") }
    var timeText by remember { mutableStateOf("Select Time") }

    var isVibrationEnabled by remember { mutableStateOf(true) }
    var ringtoneUri by remember { mutableStateOf<String?>(null) }
    var showRingtoneSheet by remember { mutableStateOf(false) }
    var isLooping by remember { mutableStateOf(false) }
    var loopCount by remember { mutableStateOf(2) }
    var volumeLevel by remember { mutableStateOf(1.0f) }

    var showAdvancedSettings by remember { mutableStateOf(false) }
    val dismissOptions = listOf("Default", "Math Problem")
    var selectedDismissMethod by remember { mutableStateOf(dismissOptions[0]) }

    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var loudnessEnhancer by remember { mutableStateOf<LoudnessEnhancer?>(null) }

    DisposableEffect(Unit) { onDispose { loudnessEnhancer?.release(); mediaPlayer?.release() } }

    val ringtoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> if (result.resultCode == Activity.RESULT_OK) ringtoneUri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)?.toString() }

    LaunchedEffect(eventId, prefillTime) {
        if (!isEditMode && prefillTime != -1L) {
            calendar.timeInMillis = prefillTime
            dateText = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(calendar.time)
            timeText = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(calendar.time)
        }

        if (isEditMode) {
            val event = withContext(Dispatchers.IO) { dao.getEventById(eventId) }
            event?.let {
                title = it.title; selectedCategory = it.category; selectedRepeat = it.repeatMode
                notes = it.notes ?: ""; location = it.location ?: ""; invitees = it.invitees ?: ""
                isVibrationEnabled = it.isVibrationEnabled; ringtoneUri = it.ringtoneUri; isLooping = it.isLooping; loopCount = it.loopCount
                volumeLevel = it.volumeLevel; selectedAlertBefore = it.alertBefore; selectedDismissMethod = it.dismissMethod; customCategoryText = it.customCategory ?: ""

                calendar.timeInMillis = it.startDateTimeInMillis
                dateText = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(calendar.time)
                timeText = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(calendar.time)
                if (it.repeatMode == "Weekly" && it.repeatDays != null) { selectedDays = it.repeatDays.split(",").mapNotNull { d -> d.toIntOrNull() }.toSet() }
            }
        }
    }

    Scaffold(
        containerColor = PureBlack,
        topBar = { TopAppBar(title = { Text(if (isEditMode) "Edit Event" else "New Event", color = TextPrimary, fontWeight = FontWeight.Bold) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = PureBlack), navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary) } }) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, placeholder = { Text("Event Title", fontSize = 24.sp, color = TextSecondary, fontWeight = FontWeight.Bold) },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = AccentColor),
                    textStyle = LocalTextStyle.current.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                )

                Card(colors = CardDefaults.cardColors(containerColor = DarkGrayCard), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("WHEN (STARTING DATE)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { DatePickerDialog(context, { _, y, m, d -> calendar.set(y, m, d); dateText = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(calendar.time) }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = CardOutline), shape = RoundedCornerShape(12.dp)) { Text(dateText, color = TextPrimary) }
                            Button(onClick = { TimePickerDialog(context, { _, h, m -> calendar.set(Calendar.HOUR_OF_DAY, h); calendar.set(Calendar.MINUTE, m); timeText = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(calendar.time) }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), false).show() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = CardOutline), shape = RoundedCornerShape(12.dp)) { Text(timeText, color = TextPrimary) }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Text("ADVANCE ALERT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 12.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(alertBeforeOptions) { option -> val isSelected = selectedAlertBefore == option; Box(modifier = Modifier.background(if (isSelected) AccentColor else CardOutline, RoundedCornerShape(12.dp)).clickable { selectedAlertBefore = option }.padding(horizontal = 16.dp, vertical = 10.dp)) { Text(option, color = if (isSelected) Color.Black else TextSecondary, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp) } } }
                        Spacer(modifier = Modifier.height(24.dp))
                        Text("REPEATS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 12.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(repeatOptions) { option -> val isSelected = selectedRepeat == option; Box(modifier = Modifier.background(if (isSelected) AccentColor else CardOutline, RoundedCornerShape(12.dp)).clickable { selectedRepeat = option }.padding(horizontal = 16.dp, vertical = 10.dp)) { Text(option, color = if (isSelected) Color.Black else TextSecondary, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp) } } }
                        if (selectedRepeat == "Weekly") { Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) { weekDays.forEachIndexed { i, day -> val isSelected = selectedDays.contains(i); Box(modifier = Modifier.size(38.dp).background(if (isSelected) AccentColor else CardOutline, RoundedCornerShape(50)).clickable { selectedDays = if (isSelected) selectedDays - i else selectedDays + i }, contentAlignment = Alignment.Center) { Text(day, color = if (isSelected) Color.Black else TextSecondary, fontWeight = FontWeight.Bold) } } } }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                Card(colors = CardDefaults.cardColors(containerColor = DarkGrayCard), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("ALERTS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 12.dp))
                        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { showRingtoneSheet = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Row(verticalAlignment = Alignment.CenterVertically) { Box(modifier = Modifier.background(CardOutline, RoundedCornerShape(8.dp)).padding(8.dp)) { Icon(Icons.Filled.Notifications, null, tint = AccentColor, modifier = Modifier.size(20.dp)) }; Spacer(Modifier.width(16.dp)); Text("Ringtone", fontSize = 16.sp, color = TextPrimary) }; Text(getRingtoneName(context, ringtoneUri), fontSize = 14.sp, color = TextSecondary, modifier = Modifier.weight(1f), textAlign = TextAlign.End) }
                        HorizontalDivider(color = CardOutline, modifier = Modifier.padding(vertical = 8.dp))
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.VolumeUp, null, tint = AccentColor, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(16.dp)); Text("Volume: ${(volumeLevel * 100).toInt()}%", fontSize = 16.sp, color = TextPrimary) }
                        Slider(value = volumeLevel, onValueChange = { volumeLevel = it }, valueRange = 0f..2f, colors = SliderDefaults.colors(thumbColor = AccentColor, activeTrackColor = AccentColor, inactiveTrackColor = CardOutline))
                        HorizontalDivider(color = CardOutline, modifier = Modifier.padding(vertical = 8.dp))
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Row(verticalAlignment = Alignment.CenterVertically) { Box(modifier = Modifier.background(CardOutline, RoundedCornerShape(8.dp)).padding(8.dp)) { Icon(Icons.Filled.Vibration, null, tint = AccentColor, modifier = Modifier.size(20.dp)) }; Spacer(Modifier.width(16.dp)); Text("Vibration", fontSize = 16.sp, color = TextPrimary) }; Switch(checked = isVibrationEnabled, onCheckedChange = { isVibrationEnabled = it }, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = AccentColor, uncheckedThumbColor = TextSecondary, uncheckedTrackColor = CardOutline)) }
                        HorizontalDivider(color = CardOutline, modifier = Modifier.padding(vertical = 8.dp))
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Row(verticalAlignment = Alignment.CenterVertically) { Box(modifier = Modifier.background(CardOutline, RoundedCornerShape(8.dp)).padding(8.dp)) { Icon(Icons.Filled.Repeat, null, tint = AccentColor, modifier = Modifier.size(20.dp)) }; Spacer(Modifier.width(16.dp)); Text("Loop Ringtone", fontSize = 16.sp, color = TextPrimary) }; Switch(checked = isLooping, onCheckedChange = { isLooping = it }, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = AccentColor, uncheckedThumbColor = TextSecondary, uncheckedTrackColor = CardOutline)) }
                        AnimatedVisibility(visible = isLooping) { Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("Loop Count", fontSize = 14.sp, color = TextSecondary, modifier = Modifier.padding(start = 44.dp)); Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.background(CardOutline, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 4.dp)) { IconButton(onClick = { if (loopCount > 1) loopCount-- }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Remove, "Decrease", tint = TextPrimary, modifier = Modifier.size(18.dp)) }; Text("$loopCount", fontSize = 16.sp, color = TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp)); IconButton(onClick = { if (loopCount < 20) loopCount++ }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Add, "Increase", tint = TextPrimary, modifier = Modifier.size(18.dp)) } } } }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                Card(colors = CardDefaults.cardColors(containerColor = DarkGrayCard), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("DETAILS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 12.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) { items(categories) { category -> val isSelected = selectedCategory == category; Box(modifier = Modifier.background(if (isSelected) AccentColor else CardOutline, RoundedCornerShape(12.dp)).clickable { selectedCategory = category }.padding(horizontal = 16.dp, vertical = 10.dp)) { Text(category, color = if (isSelected) Color.Black else TextSecondary, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp) } } }
                        AnimatedVisibility(visible = selectedCategory == "Other") { OutlinedTextField(value = customCategoryText, onValueChange = { customCategoryText = it }, placeholder = { Text("Custom category name (optional)", color = TextSecondary) }, leadingIcon = { Icon(Icons.Filled.Label, null, tint = TextSecondary) }, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentColor, unfocusedBorderColor = Color.Transparent, focusedContainerColor = CardOutline, unfocusedContainerColor = CardOutline, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) }
                        OutlinedTextField(value = notes, onValueChange = { notes = it }, placeholder = { Text("Notes", color = TextSecondary) }, leadingIcon = { Icon(Icons.Filled.Notes, null, tint = TextSecondary) }, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, focusedContainerColor = CardOutline, unfocusedContainerColor = CardOutline, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(value = location, onValueChange = { location = it }, placeholder = { Text("Location", color = TextSecondary) }, leadingIcon = { Icon(Icons.Filled.LocationOn, null, tint = TextSecondary) }, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, focusedContainerColor = CardOutline, unfocusedContainerColor = CardOutline, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(value = invitees, onValueChange = { invitees = it }, placeholder = { Text("Invitees (emails)", color = TextSecondary) }, leadingIcon = { Icon(Icons.Filled.Person, null, tint = TextSecondary) }, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, focusedContainerColor = CardOutline, unfocusedContainerColor = CardOutline, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                Card(colors = CardDefaults.cardColors(containerColor = DarkGrayCard), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth().clickable { showAdvancedSettings = !showAdvancedSettings }.padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("ADVANCED SETTINGS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp); Icon(if (showAdvancedSettings) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = TextSecondary) }
                        AnimatedVisibility(visible = showAdvancedSettings) {
                            Column(modifier = Modifier.padding(top = 16.dp)) {
                                Text("HOW TO STOP ALARM", fontSize = 14.sp, color = TextPrimary, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 12.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(dismissOptions) { option -> val isSelected = selectedDismissMethod == option; Box(modifier = Modifier.background(if (isSelected) Color(0xFFFF5252) else CardOutline, RoundedCornerShape(12.dp)).clickable { selectedDismissMethod = option }.padding(horizontal = 16.dp, vertical = 10.dp)) { Text(option, color = if (isSelected) Color.White else TextSecondary, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp) } } }
                                if (selectedDismissMethod == "Math Problem") { Text("You will need to solve a random math equation to turn off the alarm audio.", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.padding(top = 8.dp)) }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }

            Surface(color = DarkGrayCard, shadowElevation = 16.dp) {
                Column {
                    HorizontalDivider(color = CardOutline)
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(onClick = { navController.popBackStack() }, modifier = Modifier.weight(1f).height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = CardOutline), shape = RoundedCornerShape(16.dp)) { Text("Cancel", fontSize = 16.sp, color = TextPrimary, fontWeight = FontWeight.Bold) }
                        Button(
                            onClick = {
                                if (title.isBlank() || dateText == "Select Date" || timeText == "Select Time") { Toast.makeText(context, "Title, Date, and Time are required", Toast.LENGTH_SHORT).show(); return@Button }
                                coroutineScope.launch(Dispatchers.IO) {
                                    val eventToSave = Event(id = if (isEditMode) eventId else 0, title = title, category = selectedCategory, startDateTimeInMillis = calendar.timeInMillis, repeatMode = selectedRepeat, repeatDays = if (selectedRepeat == "Weekly") selectedDays.joinToString(",") else null, notes = notes.takeIf { it.isNotBlank() }, location = location.takeIf { it.isNotBlank() }, invitees = invitees.takeIf { it.isNotBlank() }, isVibrationEnabled = isVibrationEnabled, ringtoneUri = ringtoneUri, customCategory = if (selectedCategory == "Other") customCategoryText.takeIf { it.isNotBlank() } else null, isLooping = isLooping, loopCount = loopCount, volumeLevel = volumeLevel, alertBefore = selectedAlertBefore, dismissMethod = selectedDismissMethod)
                                    val finalId = if (isEditMode) { dao.updateEvent(eventToSave); eventToSave.id } else { dao.insertEvent(eventToSave).toInt() }
                                    scheduleEventAlarm(context, eventToSave.copy(id = finalId))
                                    withContext(Dispatchers.Main) { triggerWidgetUpdate(context); if (isEditMode) navController.navigate("home") { popUpTo("home") { inclusive = true } } else navController.popBackStack() }
                                }
                            }, modifier = Modifier.weight(1f).height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = AccentColor), shape = RoundedCornerShape(16.dp)
                        ) { Text("Save Event", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black) }
                    }
                }
            }
        }

        if (showRingtoneSheet) {
            ModalBottomSheet(
                onDismissRequest = { showRingtoneSheet = false; loudnessEnhancer?.release(); mediaPlayer?.let { player -> if (player.isPlaying) player.stop(); player.release() }; mediaPlayer = null },
                containerColor = DarkGrayCard, dragHandle = { BottomSheetDefaults.DragHandle(color = Color.Gray) }
            ) {
                Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                    Text("Select Ringtone", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White); Spacer(modifier = Modifier.height(24.dp))
                    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { ringtoneUri = "NONE"; showRingtoneSheet = false; loudnessEnhancer?.release(); mediaPlayer?.let { if (it.isPlaying) it.stop(); it.release() }; mediaPlayer = null }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (ringtoneUri == "NONE") Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked, null, tint = if (ringtoneUri == "NONE") AccentColor else TextSecondary); Spacer(Modifier.width(16.dp)); Text("None (Silent)", fontSize = 16.sp, color = TextPrimary, fontWeight = FontWeight.Medium) }
                    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { showRingtoneSheet = false; loudnessEnhancer?.release(); mediaPlayer?.let { if (it.isPlaying) it.stop(); it.release() }; mediaPlayer = null; ringtoneLauncher.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply { putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_NOTIFICATION); putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true); putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true); ringtoneUri?.takeIf { it != "NONE" }?.let { putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it)) } }) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Box(modifier = Modifier.background(CardOutline, RoundedCornerShape(8.dp)).padding(8.dp)) { Icon(Icons.Filled.FolderOpen, null, tint = AccentColor, modifier = Modifier.size(20.dp)) }; Spacer(Modifier.width(16.dp)); Text("Browse System Ringtones", fontSize = 16.sp, color = TextPrimary, fontWeight = FontWeight.Medium) }
                    Spacer(modifier = Modifier.height(8.dp)); HorizontalDivider(color = CardOutline); Spacer(modifier = Modifier.height(16.dp))
                    Text("APP TONES", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp); Spacer(modifier = Modifier.height(8.dp))
                    val appTones = getDeveloperRingtones(context)
                    if (appTones.isEmpty()) { Text("No custom tones found. Drop MP3s into res/raw folder!", color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp)) } else {
                        appTones.forEach { (toneUri, displayName) ->
                            val isSelected = ringtoneUri == toneUri
                            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { ringtoneUri = toneUri; coroutineScope.launch { loudnessEnhancer?.release(); mediaPlayer?.let { player -> if (player.isPlaying) { for (i in 10 downTo 0) { try { player.setVolume(i / 10f, i / 10f) } catch(e: Exception){}; delay(20) }; try { player.stop() } catch(e: Exception){} }; try { player.release() } catch(e: Exception){} }; try { val newPlayer = MediaPlayer().apply { setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).setUsage(AudioAttributes.USAGE_ALARM).build()); setDataSource(context, Uri.parse(toneUri)); prepare() }; mediaPlayer = newPlayer; if (volumeLevel > 1.0f) { newPlayer.setVolume(1.0f, 1.0f); loudnessEnhancer = LoudnessEnhancer(newPlayer.audioSessionId).apply { setTargetGain(((volumeLevel - 1.0f) * 2000).toInt()); enabled = true } } else { newPlayer.setVolume(volumeLevel, volumeLevel) }; newPlayer.start() } catch (e: Exception) { e.printStackTrace() } } }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (isSelected) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked, null, tint = if (isSelected) AccentColor else TextSecondary); Spacer(Modifier.width(16.dp)); Text(displayName, fontSize = 16.sp, color = TextPrimary) }
                        }
                    }
                }
            }
        }
    }
}