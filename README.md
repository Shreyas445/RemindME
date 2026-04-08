# ⏳ Remind ME - Time Dashboard & Widget App

**Remind ME** is a modern, premium Android application built with Jetpack Compose that shifts the focus from a traditional calendar grid to a **Time Dashboard**. Instead of just telling you *when* an event is, it tells you exactly *how much time is left* with beautiful, live home screen widgets.

## ✨ Features

* **Premium Dark Mode UI:** A clean, professional pure black and amber aesthetic designed for both casual users and business professionals.
* **Live Home Screen Widgets:** Built with Jetpack Glance, featuring live countdowns (Days, Hours, or Minutes) directly on your home screen.
* **Smart Widget Configuration:** Select exactly which event a widget should track when you drop it on your screen.
* **Advanced Recurrence Engine:** Support for complex repeating events (Daily, Weekly with specific day selection, Monthly, Yearly) without cluttering the database.
* **Local First:** Fully offline architecture powered by Room Database. Your data stays on your device.

## 🛠️ Tech Stack

This project uses the latest modern Android development standards:
* **Language:** [Kotlin 2.0+](https://kotlinlang.org/)
* **UI Toolkit:** [Jetpack Compose](https://developer.android.com/jetpack/compose)
* **Local Database:** [Room](https://developer.android.com/training/data-storage/room) (with KSP)
* **Widgets:** [Jetpack Glance](https://developer.android.com/jetpack/compose/glance)
* **Navigation:** Jetpack Navigation for Compose
* **Build Configuration:** Kotlin DSL (`build.gradle.kts`)
* **Asynchronous Programming:** Kotlin Coroutines & Flow

## 🚀 Getting Started

### Prerequisites
* [Android Studio Koala](https://developer.android.com/studio) (or newer)
* Minimum SDK: API 29 (Android 10.0)
* Target/Compile SDK: API 36

### Installation
1. Clone the repository:
   ```bash
   git clone https://github.com/Shreyas445/RemindME.git
   ```
2. Open the project in Android Studio.
3. Wait for the Gradle sync to finish downloading the Jetpack dependencies.
4. Click **Run** to install the app on your emulator or connected device.

## 📱 How to Use

1. **Add an Event:** Open the app and tap the `+` Floating Action Button.
2. **Set Details:** Enter a title, select a date/time, and choose a category.
3. **Set Rules (Optional):** If it's a recurring event, select Weekly and pick specific days (e.g., M, W, F).
4. **Deploy a Widget:** Go to your Android Home Screen, long-press, select "Widgets", and drag "Remind ME" to your screen.
5. **Select Target:** The app will prompt you to pick which event this specific widget should track.

## 🏗️ Architecture Notes

The app is built in three core layers:
1. **The Database (Room):** Uses `EventDao` to expose `Flow<List<Event>>` for reactive UI updates and one-off queries for the widget engine.
2. **The App UI (Compose):** A single-activity architecture (`MainActivity.kt`) using `NavHost` to smoothly transition between the Dashboard and the Event Creation screens.
3. **The Widget Engine (Glance):** Uses `CountdownWidget.kt` alongside `WidgetConfigActivity.kt` to securely store `eventId` preferences via DataStore, allowing multiple widgets on the same screen to track entirely different events.

## 📄 License
This project is open-source and available under the [MIT License](LICENSE).
