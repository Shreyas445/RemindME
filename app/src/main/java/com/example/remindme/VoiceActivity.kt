package com.example.remindme

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.remindme.db.AppDatabase
import com.example.remindme.db.Event
import com.google.mlkit.nl.entityextraction.Entity
import com.google.mlkit.nl.entityextraction.EntityExtraction
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private enum class VoiceActivityState { IDLE, LISTENING_MAIN, THINKING, ASKING_TIME, LISTENING_TIME, ASKING_TITLE, LISTENING_TITLE, SAVING_UNDO }

private fun voiceMathLerp(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction

class VoiceActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        val db = AppDatabase.getDatabase(this)
        val dao = db.eventDao()

        setContent {
            val context = LocalContext.current
            val coroutineScope = rememberCoroutineScope()
            val density = LocalDensity.current

            var voiceState by remember { mutableStateOf(VoiceActivityState.LISTENING_MAIN) }
            var spokenText by remember { mutableStateOf("") }
            var rmsDb by remember { mutableStateOf(0f) }

            var draftTitle by remember { mutableStateOf("") }
            var draftDateMillis by remember { mutableStateOf(-1L) }
            var draftNeedsTime by remember { mutableStateOf(false) }
            var draftCategory by remember { mutableStateOf("Other") }

            var savedEventId by remember { mutableStateOf(-1) }
            var isUndoClicked by remember { mutableStateOf(false) }

            val entityExtractor = remember { EntityExtraction.getClient(EntityExtractorOptions.Builder(EntityExtractorOptions.ENGLISH).build()) }

            val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
            val speechIntent = remember {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                intent
            }

            val closeGhostScreen = {
                finish()
            }

            val saveAndTriggerUndo = { title: String, timeMillis: Long, category: String ->
                coroutineScope.launch(Dispatchers.IO) {
                    val event = Event(title = title, category = category, startDateTimeInMillis = timeMillis, repeatMode = "Don't repeat", alertBefore = "None", dismissMethod = "Default")
                    val id = dao.insertEvent(event).toInt()
                    scheduleEventAlarm(context, event.copy(id = id))

                    savedEventId = id

                    withContext(Dispatchers.Main) {
                        voiceState = VoiceActivityState.SAVING_UNDO
                        triggerWidgetUpdate(context)
                    }

                    delay(3500)

                    withContext(Dispatchers.Main) {
                        if (!isUndoClicked && voiceState == VoiceActivityState.SAVING_UNDO) {
                            closeGhostScreen()
                        }
                    }
                }
            }

            val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                if (isGranted) { speechRecognizer.startListening(speechIntent) } else { Toast.makeText(context, "Mic permission needed.", Toast.LENGTH_SHORT).show(); closeGhostScreen() }
            }

            LaunchedEffect(Unit) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { speechRecognizer.startListening(speechIntent) } else { audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
            }

            DisposableEffect(Unit) {
                val listener = object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() { spokenText = "" }
                    override fun onRmsChanged(rmsdB: Float) { rmsDb = rmsdB }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) { Toast.makeText(context, "Didn't catch that.", Toast.LENGTH_SHORT).show(); closeGhostScreen() }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (matches.isNullOrEmpty()) { closeGhostScreen(); return }

                        val rawText = matches[0]
                        spokenText = rawText

                        if (voiceState == VoiceActivityState.LISTENING_MAIN) {
                            voiceState = VoiceActivityState.THINKING
                            val rawLower = rawText.lowercase()

                            draftCategory = when {
                                rawLower.contains("birthday") || rawLower.contains("bday") -> "Birthdays"
                                rawLower.contains("exam") || rawLower.contains("test") || rawLower.contains("quiz") || rawLower.contains("assignment") -> "Exams"
                                rawLower.contains("medicine") || rawLower.contains("pill") || rawLower.contains("tablet") || rawLower.contains("doctor") || rawLower.contains("prescription") -> "Medicine"
                                rawLower.contains("deadline") || rawLower.contains("due") || rawLower.contains("last date") || rawLower.contains("expire") -> "Last Dates"
                                rawLower.contains("event") || rawLower.contains("meeting") || rawLower.contains("party") || rawLower.contains("appointment") || rawLower.contains("flight") -> "Events"
                                else -> "Other"
                            }

                            entityExtractor.annotate(rawText).addOnSuccessListener { annotations ->
                                var extractedTime = -1L
                                var cleanTitle = rawText

                                for (annotation in annotations) {
                                    val dateTimeEntity = annotation.entities.find { it.type == Entity.TYPE_DATE_TIME }?.asDateTimeEntity()
                                    if (dateTimeEntity != null) { extractedTime = dateTimeEntity.timestampMillis; cleanTitle = cleanTitle.replace(annotation.annotatedText, "", ignoreCase = true) }
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

                                draftTitle = finalTitle
                                draftDateMillis = extractedTime
                                draftNeedsTime = needsTime

                                if (draftTitle.isEmpty() || draftTitle.isBlank()) { voiceState = VoiceActivityState.ASKING_TITLE }
                                else if (draftNeedsTime) { voiceState = VoiceActivityState.ASKING_TIME }
                                else { saveAndTriggerUndo(draftTitle, draftDateMillis, draftCategory) }
                            }.addOnFailureListener { closeGhostScreen() }
                        }
                        else if (voiceState == VoiceActivityState.LISTENING_TITLE) {
                            voiceState = VoiceActivityState.THINKING
                            var finalT = rawText.lowercase().trim()
                            val cleanTRegex = Regex("^\\b(it is for|its for|it's for|for|it is|to)\\b")
                            finalT = finalT.replace(cleanTRegex, "").trim()
                            draftTitle = finalT.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

                            if (draftCategory == "Other") {
                                draftCategory = when {
                                    finalT.lowercase().contains("birthday") || finalT.lowercase().contains("bday") -> "Birthdays"
                                    finalT.lowercase().contains("exam") || finalT.lowercase().contains("test") -> "Exams"
                                    finalT.lowercase().contains("medicine") || finalT.lowercase().contains("pill") -> "Medicine"
                                    finalT.lowercase().contains("deadline") || finalT.lowercase().contains("due") -> "Last Dates"
                                    finalT.lowercase().contains("event") || finalT.lowercase().contains("meeting") -> "Events"
                                    else -> "Other"
                                }
                            }

                            if (draftNeedsTime) voiceState = VoiceActivityState.ASKING_TIME else saveAndTriggerUndo(draftTitle, draftDateMillis, draftCategory)
                        }
                        else if (voiceState == VoiceActivityState.LISTENING_TIME) {
                            voiceState = VoiceActivityState.THINKING
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
                                saveAndTriggerUndo(draftTitle, finalMergedTime, draftCategory)
                            }.addOnFailureListener { closeGhostScreen() }
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) { val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION); if (!matches.isNullOrEmpty()) spokenText = matches[0] }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                }
                speechRecognizer.setRecognitionListener(listener)
                onDispose { speechRecognizer.destroy() }
            }

            LaunchedEffect(voiceState) {
                if (voiceState == VoiceActivityState.ASKING_TIME || voiceState == VoiceActivityState.ASKING_TITLE) {
                    spokenText = ""; delay(1500)
                    voiceState = if (voiceState == VoiceActivityState.ASKING_TIME) VoiceActivityState.LISTENING_TIME else VoiceActivityState.LISTENING_TITLE
                    speechRecognizer.startListening(speechIntent)
                }
            }

            val infiniteTransition = rememberInfiniteTransition(label = "wave_time")
            val time by infiniteTransition.animateFloat(initialValue = 0f, targetValue = 100f, animationSpec = infiniteRepeatable(tween(20000, easing = LinearEasing)), label = "time")

            val isCircleMode = voiceState != VoiceActivityState.IDLE && voiceState != VoiceActivityState.LISTENING_MAIN && voiceState != VoiceActivityState.SAVING_UNDO
            val morphProgress by animateFloatAsState(targetValue = if (isCircleMode) 1f else 0f, animationSpec = tween(800, easing = FastOutSlowInEasing), label = "morph")

            Scaffold(containerColor = Color.Transparent) { padding ->
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF000000).copy(alpha = if (voiceState == VoiceActivityState.SAVING_UNDO) 0.5f else 0.90f))) {

                    if (voiceState != VoiceActivityState.SAVING_UNDO) {
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

                                    val x = voiceMathLerp(waveX, blobX.toFloat(), morphProgress)
                                    val y = voiceMathLerp(waveY, blobY.toFloat(), morphProgress)

                                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                }
                                drawPath(path = path, brush = gradient, style = Stroke(width = with(density) { (3f + j * 1.5f).dp.toPx() }, cap = StrokeCap.Round, join = StrokeJoin.Round), alpha = 1f - (j * 0.25f))
                            }
                        }

                        Column(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 280.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (morphProgress < 0.5f) {
                                val txt = if (voiceState == VoiceActivityState.THINKING) "Thinking..." else if (spokenText.isEmpty()) "Listening..." else spokenText
                                Text(text = txt, fontSize = 28.sp, color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                            } else {
                                val topText = if (voiceState == VoiceActivityState.ASKING_TITLE || voiceState == VoiceActivityState.LISTENING_TITLE) "What for?" else "At what time?"
                                Text(text = topText, fontSize = 28.sp, color = Color(0xFFFFC107), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                if (spokenText.isNotEmpty()) { Spacer(modifier = Modifier.height(16.dp)); Text(text = spokenText, fontSize = 20.sp, color = Color.White, textAlign = TextAlign.Center) }
                            }
                        }

                        Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp)) {
                            FloatingActionButton(
                                onClick = { speechRecognizer.cancel(); closeGhostScreen() },
                                containerColor = Color(0xFFFF5252), contentColor = Color.White, shape = RoundedCornerShape(50), modifier = Modifier.size(56.dp)
                            ) { Icon(Icons.Filled.Close, "Cancel", modifier = Modifier.size(28.dp)) }
                        }
                    }

                    AnimatedVisibility(
                        visible = voiceState == VoiceActivityState.SAVING_UNDO,
                        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp, start = 16.dp, end = 16.dp)
                    ) {
                        val undoDateString = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(draftDateMillis))
                        val undoTimeString = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(draftDateMillis))

                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF262626)),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "SAVED: ${draftCategory.uppercase()}",
                                        color = Color(0xFFFFC107),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = draftTitle.ifEmpty { "Event" },
                                        color = Color.White,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.Schedule, contentDescription = null, tint = Color(0xFFA0A0A0), modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "$undoDateString • $undoTimeString",
                                            color = Color(0xFFA0A0A0),
                                            fontSize = 14.sp
                                        )
                                    }
                                }

                                TextButton(
                                    onClick = {
                                        isUndoClicked = true
                                        coroutineScope.launch(Dispatchers.IO) {
                                            cancelEventAlarm(context, savedEventId)
                                            dao.deleteEvents(listOf(savedEventId))
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(context, "Cancelled", Toast.LENGTH_SHORT).show()
                                                triggerWidgetUpdate(context)
                                                closeGhostScreen()
                                            }
                                        }
                                    }
                                ) {
                                    Text("UNDO", color = Color(0xFFFFC107), fontWeight = FontWeight.Black, fontSize = 16.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}