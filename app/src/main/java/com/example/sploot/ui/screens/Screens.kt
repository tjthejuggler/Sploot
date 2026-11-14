package com.example.sploot.ui.screens

/**
 * Navigation routes used by MainActivity's NavHost. Screens live in their own
 * files: HomeScreen, NightDetailScreen, ProcessingScreen, TrendsScreen,
 * ExportScreen, SettingsScreen.
 */
object Routes {
    const val HOME = "home"
    const val NIGHT = "night/{sessionId}"
    const val PROCESSING = "processing/{sessionId}?snoopUri={snoopUri}"
    const val TRENDS = "trends"
    const val EXPORT = "export"
    const val SETTINGS = "settings"
}
