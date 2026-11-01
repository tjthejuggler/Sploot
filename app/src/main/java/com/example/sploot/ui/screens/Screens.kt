package com.example.sploot.ui.screens

/**
 * Navigation routes used by MainActivity's NavHost. Screens live in their own
 * files: HomeScreen, ProcessingScreen, DashboardScreen, TrendsScreen,
 * ABCompareScreen, ExportScreen, SettingsScreen.
 */
object Routes {
    const val HOME = "home"
    const val DASHBOARD = "dashboard?sessionId={sessionId}"
    const val PROCESSING = "processing/{sessionId}?snoopUri={snoopUri}"
    const val TRENDS = "trends"
    const val COMPARE = "compare/{sessionId}"
    const val EXPORT = "export"
    const val SETTINGS = "settings"
}
