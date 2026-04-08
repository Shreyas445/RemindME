package com.example.remindme

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.remindme.db.AppDatabase
import com.example.remindme.db.Event
import com.example.remindme.db.EventDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

val PureBlack = Color(0xFF000000)
val DarkGrayCard = Color(0xFF141414)
val CardOutline = Color(0xFF262626)
val AccentColor = Color(0xFFFFC107)
val TextPrimary = Color(0xFFF5F5F5)
val TextSecondary = Color(0xFFA0A0A0)
val SuccessGreen = Color(0xFF4CAF50)

// --- THE TIME-TRAVEL ENGINE ---
fun getNextOccurrence(event: Event): Long {
    if (event.repeatMode == "None" || event.repeatMode == "Don't repeat") {
        return event.startDateTimeInMillis
    }

    val calendar = Calendar.getInstance()
    calendar.timeInMillis = event.startDateTimeInMillis
    val now = System.currentTimeMillis()

    if (calendar.timeInMillis > now) return calendar.timeInMillis

    while (calendar.timeInMillis <= now) {
        when (event.repeatMode) {
            "Daily" -> calendar.add(Calendar.DAY_OF_YEAR, 1)
            "Weekly" -> {
                if (event.repeatDays.isNullOrBlank()) {
                    calendar.add(Calendar.WEEK_OF_YEAR, 1)
                } else {
                    val activeDays = event.repeatDays.split(",").mapNotNull { it.toIntOrNull() }
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                    if (activeDays.isNotEmpty()) {
                        var safetyCount = 0
                        while (!activeDays.contains(calendar.get(Calendar.DAY_OF_WEEK) - 1) && safetyCount < 7) {
                            calendar.add(Calendar.DAY_OF_YEAR, 1)
                            safetyCount++
                        }
                    } else {
                        calendar.add(Calendar.WEEK_OF_YEAR, 1)
                    }
                }
            }
            "Monthly" -> calendar.add(Calendar.MONTH, 1)
            "Yearly" -> calendar.add(Calendar.YEAR, 1)
            else -> break
        }
    }
    return calendar.timeInMillis
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.getDatabase(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = PureBlack, surface = DarkGrayCard)) {
                Surface(modifier = Modifier.fillMaxSize(), color = PureBlack) {
                    RemindMeApp(db.eventDao())
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
        composable(
            "details/{eventId}",
            arguments = listOf(navArgument("eventId") { type = NavType.IntType })
        ) { backStackEntry ->
            val eventId = backStackEntry.arguments?.getInt("eventId") ?: -1
            EventDetailsScreen(navController, dao, eventId)
        }
        composable(
            "add_edit/{eventId}",
            arguments = listOf(navArgument("eventId") { type = NavType.IntType })
        ) { backStackEntry ->
            val eventId = backStackEntry.arguments?.getInt("eventId") ?: -1
            AddEditEventScreen(navController, dao, eventId)
        }
        composable("settings") {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Settings coming soon", color = TextSecondary)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController, dao: EventDao) {
    val rawEvents by dao.getAllEventsFlow().collectAsState(initial = emptyList())
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    var selectedEventIds by remember { mutableStateOf(setOf<Int>()) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    // --- NEW: 'Upcoming' is the default filter ---
    var selectedFilter by remember { mutableStateOf("Upcoming") }
    val categories = listOf("Upcoming", "All", "Birthdays", "Events", "Exams", "Last Dates", "Other")
    var isMenuExpanded by remember { mutableStateOf(false) }

    val isSelectionMode = selectedEventIds.isNotEmpty()

    val processedEvents = remember(rawEvents) {
        rawEvents.map { event ->
            val nextDate = getNextOccurrence(event)
            event to nextDate
        }.sortedBy { it.second }
    }

    // Determine when "Today" started at exactly 00:00:00 for the Upcoming filter
    val todayStart = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val filteredEvents = processedEvents.filter { (event, nextDate) ->
        val matchesCategory = when (selectedFilter) {
            "Upcoming" -> nextDate >= todayStart // Hides yesterday and older
            "All" -> true // Shows literally everything
            else -> event.category == selectedFilter
        }
        val matchesSearch = event.title.contains(searchQuery, ignoreCase = true)
        matchesCategory && matchesSearch
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = DarkGrayCard,
            title = { Text("Delete Events", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to delete ${selectedEventIds.size} event(s)? This cannot be undone.", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    coroutineScope.launch(Dispatchers.IO) {
                        dao.deleteEvents(selectedEventIds.toList())
                        selectedEventIds = emptySet()
                        showDeleteDialog = false
                    }
                }) { Text("Delete", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel", color = TextPrimary) } }
        )
    }

    Scaffold(
        containerColor = PureBlack,
        topBar = {
            AnimatedVisibility(visible = isSelectionMode, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                TopAppBar(
                    title = { Text("${selectedEventIds.size} Selected", color = TextPrimary, fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = CardOutline),
                    navigationIcon = { IconButton(onClick = { selectedEventIds = emptySet() }) { Icon(Icons.Default.Close, "Cancel", tint = TextPrimary) } },
                    actions = { IconButton(onClick = { showDeleteDialog = true }) { Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFFF5252)) } }
                )
            }
        },
        floatingActionButton = {
            if (!isSelectionMode) {
                FloatingActionButton(
                    onClick = { navController.navigate("add_edit/-1") },
                    containerColor = AccentColor, contentColor = Color.Black,
                    shape = RoundedCornerShape(16.dp),
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp)
                ) { Icon(Icons.Filled.Add, "Add Event", modifier = Modifier.size(28.dp)) }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 20.dp)) {

            AnimatedVisibility(visible = !isSelectionMode) {
                Column {
                    AnimatedContent(
                        targetState = isSearchActive,
                        label = "search_header_animation",
                        modifier = Modifier.padding(top = 16.dp)
                    ) { searchActive ->
                        if (searchActive) {
                            TextField(
                                value = searchQuery, onValueChange = { searchQuery = it },
                                placeholder = { Text("Search events...", color = TextSecondary, fontSize = 18.sp) },
                                textStyle = LocalTextStyle.current.copy(fontSize = 18.sp),
                                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = AccentColor, unfocusedIndicatorColor = CardOutline,
                                    cursorColor = AccentColor, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
                                ),
                                trailingIcon = { IconButton(onClick = { isSearchActive = false; searchQuery = "" }) { Icon(Icons.Default.Close, "Close Search", tint = TextPrimary) } },
                                singleLine = true
                            )
                            LaunchedEffect(Unit) { focusRequester.requestFocus() }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Remind Me", fontSize = 34.sp, fontWeight = FontWeight.Black, color = TextPrimary, letterSpacing = (-1).sp)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { isSearchActive = true }) { Icon(Icons.Default.Search, "Search", tint = TextPrimary) }
                                    Box {
                                        IconButton(onClick = { isMenuExpanded = true }) { Icon(Icons.Default.MoreVert, "Menu", tint = TextPrimary) }
                                        DropdownMenu(expanded = isMenuExpanded, onDismissRequest = { isMenuExpanded = false }, modifier = Modifier.background(DarkGrayCard)) {
                                            DropdownMenuItem(text = { Text("Settings", color = TextPrimary) }, onClick = { isMenuExpanded = false; navController.navigate("settings") }, leadingIcon = { Icon(Icons.Default.Settings, null, tint = TextSecondary) })
                                        }
                                    }
                                }
                            }
                        }
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 16.dp, bottom = 12.dp)) {
                        items(categories) { category ->
                            val isSelected = selectedFilter == category
                            Box(
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (isSelected) AccentColor else Color.Transparent).border(1.dp, if (isSelected) AccentColor else CardOutline, RoundedCornerShape(50)).clickable { selectedFilter = category }.padding(horizontal = 20.dp, vertical = 10.dp)
                            ) { Text(category, color = if (isSelected) Color.Black else TextSecondary, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp) }
                        }
                    }
                }
            }

            if (filteredEvents.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Outlined.CalendarToday, null, modifier = Modifier.size(80.dp), tint = CardOutline)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(if (searchQuery.isNotEmpty()) "No results found" else "Clear Schedule", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(if (searchQuery.isNotEmpty()) "Try a different search term." else "You have no events matching this filter.\nTap + to create one.", fontSize = 14.sp, color = TextSecondary, textAlign = TextAlign.Center, lineHeight = 20.sp)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.weight(1f).padding(top = 8.dp), contentPadding = PaddingValues(bottom = 80.dp)) {
                    items(filteredEvents) { (event, nextDate) ->
                        val isSelected = selectedEventIds.contains(event.id)
                        EventCard(
                            event = event,
                            nextDateMillis = nextDate,
                            isSelected = isSelected,
                            modifier = Modifier.animateContentSize().combinedClickable(
                                onClick = { if (isSelectionMode) { selectedEventIds = if (isSelected) selectedEventIds - event.id else selectedEventIds + event.id } else { navController.navigate("details/${event.id}") } },
                                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); selectedEventIds = if (isSelected) selectedEventIds - event.id else selectedEventIds + event.id }
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EventCard(event: Event, nextDateMillis: Long, isSelected: Boolean, modifier: Modifier = Modifier) {
    val formatter = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault())
    val dateString = formatter.format(Date(nextDateMillis))

    val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    val targetDate = Calendar.getInstance().apply { timeInMillis = nextDateMillis; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    val diffMillis = targetDate - today
    val daysLeft = (diffMillis / (1000 * 60 * 60 * 24)).toInt()

    val (primaryText, secondaryText, textTint, badgeBg) = when {
        daysLeft < 0 -> listOf(Math.abs(daysLeft).toString(), "Days Ago", Color.Gray, PureBlack)
        daysLeft == 0 -> listOf("!", "TODAY", SuccessGreen, SuccessGreen.copy(alpha = 0.15f))
        else -> listOf(daysLeft.toString(), "Days", AccentColor, if (isSelected) DarkGrayCard else PureBlack)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = if (isSelected) CardOutline else DarkGrayCard),
        shape = RoundedCornerShape(24.dp),
        border = if (isSelected) BorderStroke(2.dp, AccentColor) else if (daysLeft == 0) BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.5f)) else BorderStroke(1.dp, Color(0xFF1E1E1E)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(event.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary, lineHeight = 28.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(dateString, fontSize = 13.sp, color = if (daysLeft == 0) SuccessGreen else TextSecondary)
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (event.category == "Other") event.customCategory ?: "Other" else event.category,
                        fontSize = 11.sp, color = AccentColor, fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(AccentColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    if (event.repeatMode != "None" && event.repeatMode != "Don't repeat") {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                modifier = Modifier.size(70.dp).clip(RoundedCornerShape(16.dp)).background(badgeBg as Color)
            ) {
                Text(primaryText as String, fontSize = 26.sp, fontWeight = FontWeight.Black, color = textTint as Color)
                Text(secondaryText as String, fontSize = 11.sp, color = textTint, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailsScreen(navController: NavController, dao: EventDao, eventId: Int) {
    var event by remember { mutableStateOf<Event?>(null) }
    LaunchedEffect(eventId) { event = withContext(Dispatchers.IO) { dao.getEventById(eventId) } }

    Scaffold(
        containerColor = PureBlack,
        topBar = {
            TopAppBar(
                title = { }, colors = TopAppBarDefaults.topAppBarColors(containerColor = PureBlack),
                navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary) } },
                actions = { IconButton(onClick = { navController.navigate("add_edit/${eventId}") }) { Icon(Icons.Filled.Edit, "Edit", tint = AccentColor) } }
            )
        }
    ) { padding ->
        val currentEvent = event
        if (currentEvent != null) {
            val actualNextDate = getNextOccurrence(currentEvent)
            val formatter = SimpleDateFormat("EEEE, MMM dd, yyyy \n hh:mm a", Locale.getDefault())
            val dateString = formatter.format(Date(actualNextDate))

            Column(modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(currentEvent.title, fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Black, color = TextPrimary)
                Spacer(modifier = Modifier.height(28.dp))

                Card(colors = CardDefaults.cardColors(containerColor = DarkGrayCard), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        DetailRow(Icons.Filled.Schedule, "Next Occurrence", dateString)
                        HorizontalDivider(color = CardOutline, modifier = Modifier.padding(vertical = 16.dp))
                        DetailRow(Icons.Filled.Refresh, "Repeats", currentEvent.repeatMode)
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
        Column(modifier = Modifier.weight(1f).padding(top = 2.dp)) {
            Text(label, fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 16.sp, color = TextPrimary, lineHeight = 24.sp, fontWeight = FontWeight.Normal)
        }
    }
}

@Composable
fun IconRow(icon: ImageVector, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Icon(icon, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Box(modifier = Modifier.weight(1f)) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditEventScreen(navController: NavController, dao: EventDao, eventId: Int) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isEditMode = eventId != -1

    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var invitees by remember { mutableStateOf("") }
    val categories = listOf("Birthdays", "Events", "Exams", "Last Dates", "Other")
    var selectedCategory by remember { mutableStateOf(categories[0]) }
    val repeatOptions = listOf("Don't repeat", "Daily", "Weekly", "Monthly", "Yearly")
    var selectedRepeat by remember { mutableStateOf(repeatOptions[0]) }
    val weekDays = listOf("S", "M", "T", "W", "T", "F", "S")
    var selectedDays by remember { mutableStateOf(setOf<Int>()) }
    val calendar = remember { Calendar.getInstance() }
    var dateText by remember { mutableStateOf("Select Date") }
    var timeText by remember { mutableStateOf("Select Time") }

    LaunchedEffect(eventId) {
        if (isEditMode) {
            val event = withContext(Dispatchers.IO) { dao.getEventById(eventId) }
            event?.let {
                title = it.title; selectedCategory = it.category; selectedRepeat = it.repeatMode
                notes = it.notes ?: ""; location = it.location ?: ""; invitees = it.invitees ?: ""
                calendar.timeInMillis = it.startDateTimeInMillis
                dateText = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(calendar.time)
                timeText = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(calendar.time)
                if (it.repeatMode == "Weekly" && it.repeatDays != null) { selectedDays = it.repeatDays.split(",").mapNotNull { d -> d.toIntOrNull() }.toSet() }
            }
        }
    }

    Scaffold(
        containerColor = PureBlack,
        topBar = {
            TopAppBar(
                title = { Text(if (isEditMode) "Edit Event" else "New Event", color = TextPrimary, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PureBlack),
                navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary) } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                placeholder = { Text("Event Title", fontSize = 24.sp, color = TextSecondary, fontWeight = FontWeight.Bold) },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = AccentColor),
                textStyle = LocalTextStyle.current.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            )

            Card(colors = CardDefaults.cardColors(containerColor = DarkGrayCard), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("WHEN (STARTING DATE)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { DatePickerDialog(context, { _, y, m, d -> calendar.set(y, m, d); dateText = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(calendar.time) }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = CardOutline), shape = RoundedCornerShape(12.dp)) { Text(dateText, color = TextPrimary) }
                        Button(onClick = { TimePickerDialog(context, { _, h, m -> calendar.set(Calendar.HOUR_OF_DAY, h); calendar.set(Calendar.MINUTE, m); timeText = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(calendar.time) }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), false).show() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = CardOutline), shape = RoundedCornerShape(12.dp)) { Text(timeText, color = TextPrimary) }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(repeatOptions) { option ->
                            val isSelected = selectedRepeat == option
                            Box(modifier = Modifier.background(if (isSelected) AccentColor else CardOutline, RoundedCornerShape(12.dp)).clickable { selectedRepeat = option }.padding(horizontal = 16.dp, vertical = 10.dp)) { Text(option, color = if (isSelected) Color.Black else TextSecondary, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp) }
                        }
                    }
                    if (selectedRepeat == "Weekly") {
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            weekDays.forEachIndexed { i, day ->
                                val isSelected = selectedDays.contains(i)
                                Box(modifier = Modifier.size(38.dp).background(if (isSelected) AccentColor else CardOutline, RoundedCornerShape(50)).clickable { selectedDays = if (isSelected) selectedDays - i else selectedDays + i }, contentAlignment = Alignment.Center) { Text(day, color = if (isSelected) Color.Black else TextSecondary, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            Card(colors = CardDefaults.cardColors(containerColor = DarkGrayCard), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("DETAILS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                        items(categories) { category ->
                            val isSelected = selectedCategory == category
                            Box(modifier = Modifier.background(if (isSelected) AccentColor else CardOutline, RoundedCornerShape(12.dp)).clickable { selectedCategory = category }.padding(horizontal = 16.dp, vertical = 10.dp)) { Text(category, color = if (isSelected) Color.Black else TextSecondary, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp) }
                        }
                    }
                    OutlinedTextField(value = notes, onValueChange = { notes = it }, placeholder = { Text("Notes", color = TextSecondary) }, leadingIcon = { Icon(Icons.Filled.Notes, null, tint = TextSecondary) }, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, focusedContainerColor = CardOutline, unfocusedContainerColor = CardOutline, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(value = location, onValueChange = { location = it }, placeholder = { Text("Location", color = TextSecondary) }, leadingIcon = { Icon(Icons.Filled.LocationOn, null, tint = TextSecondary) }, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, focusedContainerColor = CardOutline, unfocusedContainerColor = CardOutline, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(value = invitees, onValueChange = { invitees = it }, placeholder = { Text("Invitees (emails)", color = TextSecondary) }, leadingIcon = { Icon(Icons.Filled.Person, null, tint = TextSecondary) }, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, focusedContainerColor = CardOutline, unfocusedContainerColor = CardOutline, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { navController.popBackStack() }, modifier = Modifier.weight(1f).height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = DarkGrayCard), shape = RoundedCornerShape(16.dp)) { Text("Cancel", fontSize = 16.sp, color = TextPrimary, fontWeight = FontWeight.Bold) }
                Button(
                    onClick = {
                        if (title.isBlank() || dateText == "Select Date" || timeText == "Select Time") { Toast.makeText(context, "Title, Date, and Time are required", Toast.LENGTH_SHORT).show(); return@Button }
                        coroutineScope.launch(Dispatchers.IO) {
                            val eventToSave = Event(
                                id = if (isEditMode) eventId else 0, title = title, category = selectedCategory, startDateTimeInMillis = calendar.timeInMillis, repeatMode = selectedRepeat,
                                repeatDays = if (selectedRepeat == "Weekly") selectedDays.joinToString(",") else null, notes = notes.takeIf { it.isNotBlank() }, location = location.takeIf { it.isNotBlank() }, invitees = invitees.takeIf { it.isNotBlank() }
                            )
                            if (isEditMode) dao.updateEvent(eventToSave) else dao.insertEvent(eventToSave)
                            withContext(Dispatchers.Main) { if (isEditMode) navController.navigate("home") { popUpTo("home") { inclusive = true } } else navController.popBackStack() }
                        }
                    },
                    modifier = Modifier.weight(1f).height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = AccentColor), shape = RoundedCornerShape(16.dp)
                ) { Text("Save Event", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black) }
            }
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}