package com.nahsor1234.dailytracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * Android entry point.
 *
 * Product screens are intentionally deferred until the UI/UX implementation
 * is explicitly authorized.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            // UI implementation will be added in a separately approved stage.
        }
    }
}
