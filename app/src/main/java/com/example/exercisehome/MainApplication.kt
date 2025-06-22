package com.example.exercisehome

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Perform heavy initialization in the background
        CoroutineScope(Dispatchers.IO).launch {
            Configuration.getInstance().load(
                applicationContext,
                getSharedPreferences("ExerciseHomePrefs", MODE_PRIVATE)
            )
        }
    }
}