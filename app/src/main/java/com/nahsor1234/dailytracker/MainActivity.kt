package com.nahsor1234.dailytracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * Android entry point. Daily Progress is the first complete product workflow;
 * other destinations will be added as working features, not placeholders.
 */
class MainActivity : ComponentActivity() {

    private val repository by lazy(LazyThreadSafetyMode.NONE) {
        DailyEntryRepository(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(17, 21, 16)
        window.navigationBarColor = android.graphics.Color.rgb(17, 21, 16)
        window.decorView.systemUiVisibility = 0

        setContent {
            DailyTrackerTheme {
                DailyProgressScreen(repository = repository)
            }
        }
    }
}
