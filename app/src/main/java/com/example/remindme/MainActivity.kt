package com.example.remindme

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.remindme.db.AppDatabase
import com.example.remindme.db.Event
import com.example.remindme.db.EventDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

val PureBlack = Color(0xFF000000)
val DarkGrayCard = Color(0xFF151515)
val CardOutline = Color(0xFF2A2A2A)
val AccentColor = Color(0xFFFFC107)

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
        composable("add_event") { AddEventScreen(navController, dao) }
    }
}

@Composable
fun HomeScreen(navController: NavController, dao: EventDao) {
    val events by dao.getAllEventsFlow().collectAsState(initial = emptyList())

    Scaffold(
        containerColor = PureBlack,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { navController.navigate("add_event") },
                containerColor = AccentColor,
                contentColor = Color.White
            ) {
                Icon(Icons.Filled.Add, "Add Event")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Upcoming", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, modifier = Modifier.padding(bottom = 16.dp))

            if (events.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No events yet. Tap + to add one.", color = Color.Gray)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(events) { event -> EventCard(event) }
                }
            }
        }
    }
}

@Composable
fun EventCard(event: Event) {
    val formatter = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault())
    val dateString = formatter.format(Date(event.startDateTimeInMillis))

    val diff = event.startDateTimeInMillis - System.currentTimeMillis()
    val daysLeft = if (diff > 0) (diff / (1000 * 60 * 60 * 24)).toString() else "0"

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkGrayCard),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(event.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(4.dp))
                Text(dateString, fontSize = 14.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (event.category == "Other") event.customCategory ?: "Other" else event.category,
                    fontSize = 12.sp,
                    color = AccentColor,
                    modifier = Modifier.background(AccentColor.copy(alpha = 0.1f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            // Cool Countdown Badge
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(CardOutline).padding(12.dp)
            ) {
                Text(daysLeft, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AccentColor)
                Text("Days", fontSize = 12.sp, color = Color.Gray)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEventScreen(navController: NavController, dao: EventDao) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var title by remember { mutableStateOf("") }
    val categories = listOf("Birthdays", "Events", "Exams", "Last Dates", "Other")
    var selectedCategory by remember { mutableStateOf(categories[0]) }

    val repeatOptions = listOf("None", "Daily", "Weekly", "Monthly", "Yearly")
    var selectedRepeat by remember { mutableStateOf(repeatOptions[0]) }

    // For Weekly selection: 0=Sun, 1=Mon, 2=Tue, 3=Wed, 4=Thu, 5=Fri, 6=Sat
    val weekDays = listOf("S", "M", "T", "W", "T", "F", "S")
    var selectedDays by remember { mutableStateOf(setOf<Int>()) }

    val calendar = remember { Calendar.getInstance() }
    var dateText by remember { mutableStateOf("Select Date") }
    var timeText by remember { mutableStateOf("Select Time") }

    Scaffold(
        containerColor = PureBlack,
        topBar = {
            TopAppBar(title = { Text("New Event", color = Color.White) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = PureBlack))
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {

            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text("Event Title", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentColor, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Date & Time Selectors
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        DatePickerDialog(context, { _, year, month, day ->
                            calendar.set(year, month, day)
                            dateText = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(calendar.time)
                        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(dateText)
                }

                OutlinedButton(
                    onClick = {
                        TimePickerDialog(context, { _, hour, minute ->
                            calendar.set(Calendar.HOUR_OF_DAY, hour)
                            calendar.set(Calendar.MINUTE, minute)
                            timeText = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(calendar.time)
                        }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), false).show()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(timeText)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Repeat Mode Selector
            Text("Repeat", color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(repeatOptions) { option ->
                    val isSelected = selectedRepeat == option
                    Box(
                        modifier = Modifier.background(if (isSelected) AccentColor else DarkGrayCard, RoundedCornerShape(20.dp))
                            .clickable { selectedRepeat = option }.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) { Text(option, color = if (isSelected) Color.Black else Color.Gray, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) }
                }
            }

            // Show Days of Week ONLY if "Weekly" is selected
            if (selectedRepeat == "Weekly") {
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    weekDays.forEachIndexed { index, day ->
                        val isSelected = selectedDays.contains(index)
                        Box(
                            modifier = Modifier.size(40.dp).background(if (isSelected) AccentColor else DarkGrayCard, RoundedCornerShape(50))
                                .clickable {
                                    selectedDays = if (isSelected) selectedDays - index else selectedDays + index
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(day, color = if (isSelected) Color.Black else Color.Gray, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Category Selector
            Text("Category", color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(categories) { category ->
                    val isSelected = selectedCategory == category
                    Box(
                        modifier = Modifier.background(if (isSelected) AccentColor else DarkGrayCard, RoundedCornerShape(20.dp))
                            .clickable { selectedCategory = category }.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) { Text(category, color = if (isSelected) Color.Black else Color.Gray, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Save Button
            Button(
                onClick = {
                    if (title.isBlank() || dateText == "Select Date" || timeText == "Select Time") {
                        Toast.makeText(context, "Please fill all fields", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (selectedRepeat == "Weekly" && selectedDays.isEmpty()) {
                        Toast.makeText(context, "Select at least one day for weekly repeat", Toast.LENGTH_SHORT).show()
                        return@Button
                    }

                    coroutineScope.launch(Dispatchers.IO) {
                        dao.insertEvent(Event(
                            title = title,
                            category = selectedCategory,
                            startDateTimeInMillis = calendar.timeInMillis,
                            repeatMode = selectedRepeat,
                            repeatDays = if (selectedRepeat == "Weekly") selectedDays.joinToString(",") else null
                        ))
                        withContext(Dispatchers.Main) { navController.popBackStack() }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
                shape = RoundedCornerShape(12.dp)
            ) { Text("Save Event", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black) }
        }
    }
}