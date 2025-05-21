package com.example.exercisehome // <-- CORRECTED PACKAGE NAME

import android.app.Application
import android.util.Log
import com.example.exercisehome.BuildConfig // Make sure this import is correct
import org.osmdroid.config.Configuration
import java.io.File

class MainApplication : Application() {

    companion object {
        private const val TAG = "MainApplication"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application onCreate")
        initializeOsmDroid()
    }

    private fun initializeOsmDroid() {
        try {
            val osmConfig = Configuration.getInstance()
            // Load default preferences
            osmConfig.load(
                this,
                getSharedPreferences("osmdroid", MODE_PRIVATE)
            )

            // Set a writable cache path within app's private storage
            val osmBasePath = File(filesDir, "osmdroid")
            val osmTileCache = File(osmBasePath, "tiles")

            // Attempt to create directories if they don't exist
            if (!osmBasePath.exists()) {
                if (!osmBasePath.mkdirs()) {
                    Log.w(TAG, "Could not create osmdroid base path: ${osmBasePath.absolutePath}")
                }
            }
            if (!osmTileCache.exists()) {
                if (!osmTileCache.mkdirs()) {
                    Log.w(TAG, "Could not create osmdroid tile cache path: ${osmTileCache.absolutePath}")
                }
            }

            // Set paths ONLY if directories exist or were created successfully
            if (osmBasePath.exists()) {
                osmConfig.osmdroidBasePath = osmBasePath
                if (osmTileCache.exists()) {
                    osmConfig.osmdroidTileCache = osmTileCache
                } else {
                    Log.w(TAG, "Tile cache directory verification failed, using default.")
                }
            } else {
                Log.w(TAG, "Base path directory verification failed, using default.")
            }


            // Set User Agent (important for OSM tile policy)
            // Ensure BuildConfig is correctly generated and imported
            osmConfig.userAgentValue = BuildConfig.APPLICATION_ID

            Log.i(TAG, "OSMDroid configured. Base path: ${osmConfig.osmdroidBasePath?.absolutePath ?: "Default"}, Tile Cache: ${osmConfig.osmdroidTileCache?.absolutePath ?: "Default"}")

        } catch (e: Exception) {
            Log.e(TAG, "Error configuring OSMDroid", e)
        }
    }
}