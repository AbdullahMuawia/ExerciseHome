package com.example.exercisehome

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.initialize
import okhttp3.*
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : AppCompatActivity(), SensorEventListener, LocationListener {

    // UI elements
    private lateinit var stepCountTextView: TextView
    private lateinit var timerTextView: TextView
    private lateinit var mapView: MapView
    private lateinit var currentLocationButton: Button
    private lateinit var customLocationButton: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var resetButton: Button
    // Removed logoutButton as it referenced LoginActivity
    // private lateinit var logoutButton: Button

    // Sensor and location (for real sensor mode)
    private lateinit var sensorManager: SensorManager
    private var stepCounterSensor: Sensor? = null
    private var stepCount = 0
    private var initialStepCount: Int? = null

    // For simulated mode (for display purposes)
    private var simulatedStepCount = 0
    // Accumulate simulated distance in meters (for summary)
    private var simulatedDistanceMeters = 0.0

    private lateinit var locationManager: LocationManager

    // Map and path tracking – we use the Polyline for overlay.
    private val path = Polyline()
    // Our last known (or updated) GeoPoint.
    private var lastKnownLocation: GeoPoint? = null

    // Timer
    private var timerStarted = false
    private var elapsedTime = 0L
    private lateinit var handler: Handler
    private lateinit var runnable: Runnable

    // ----- SIMULATION PARAMETERS -----
    // Set simulateTrail = true to run a simulated workout.
    private val simulateTrail = false
    // Simulated step properties:
    // Each simulated step is about 0.7 m, and every 10 steps (~7m) we generate a candidate GPX point.
    private val stepDistanceMeters = 0.7           // meters per step
    private val stepsForPoint = 10                 // new candidate every 10 steps
    private val displacementMeters = stepsForPoint * stepDistanceMeters  // ~7m per candidate point
    private val displacementDegrees = displacementMeters / 111111.0       // rough conversion (1° ~111,111 m)

    // For gradual change in movement direction (in radians)
    private var currentDirection: Double = 0.0     // initial direction (0 = east)
    private val angleVariability: Double = Math.PI / 7  // maximum random change per update

    // Simulation: simulate 1 step per second.
    private lateinit var simulationHandler: Handler
    private lateinit var simulationRunnable: Runnable
    // simTime is our simulated timestamp.
    private var simTime: Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

    // ----- Buffer for Snap-to-Roads Integration -----
    // We accumulate candidate GPX points (from simulated workout) and then call the API once we have enough
    private val candidatePointsBuffer = mutableListOf<GeoPoint>()
    private val bufferThreshold = 3  // e.g. process every 3 candidate points at once

    // ----- GPX TRACKING PROPERTIES -----
    private lateinit var gpxFile: File
    private lateinit var gpxEditorFile: File
    private var gpxClosed = false


    // --- Snap-to-Road API function using OkHttp ---
    private fun snapToRoadPoints(candidates: List<GeoPoint>, onResult: (List<GeoPoint>?) -> Unit) {
        val apiKey = "AIzaSyA3M8G1xhY7_9T_bIQJVNccpd7dvlgjWMc"
        // Build the 'path' parameter by concatenating candidate points with "|"
        val pathParam = candidates.joinToString(separator = "|") { "${it.latitude},${it.longitude}" }
        // Use interpolate=true for better curve smoothing.
        val url = "https://roads.googleapis.com/v1/snapToRoads?path=$pathParam&interpolate=true&key=$apiKey"
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(null)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        onResult(null)
                        return
                    }
                    val responseData = response.body?.string()
                    if (responseData != null) {
                        try {
                            val jsonObject = JSONObject(responseData)
                            val snappedPointsArray = jsonObject.getJSONArray("snappedPoints")
                            val snappedPoints = mutableListOf<GeoPoint>()
                            for (i in 0 until snappedPointsArray.length()) {
                                val snappedPoint = snappedPointsArray.getJSONObject(i)
                                val locObj = snappedPoint.getJSONObject("location")
                                val lat = locObj.getDouble("latitude")
                                val lon = locObj.getDouble("longitude")
                                snappedPoints.add(GeoPoint(lat, lon))
                            }
                            onResult(snappedPoints)
                        } catch (ex: Exception) {
                            onResult(null)
                        }
                    } else {
                        onResult(null)
                    }
                }
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this)
        setContentView(R.layout.activity_main)
        
        // Initialize UI elements
        stepCountTextView = findViewById(R.id.stepCountTextView)
        timerTextView = findViewById(R.id.timerTextView)
        mapView = findViewById(R.id.mapView)
        currentLocationButton = findViewById(R.id.currentLocationButton)
        customLocationButton = findViewById(R.id.customLocationButton)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        resetButton = findViewById(R.id.resetButton)
        // Removed logoutButton initialization
        // logoutButton = findViewById(R.id.logoutButton)

        // Initialize sensor and location
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(15.0)

        handler = Handler(Looper.getMainLooper())
        runnable = object : Runnable {
            override fun run() {
                if (timerStarted) {
                    elapsedTime++
                    updateTimerText()
                    handler.postDelayed(this, 1000)
                }
            }
        }

        // Always register sensor listener even if simulation mode is on (for display)
        stepCounterSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }

        // Button listeners
        currentLocationButton.setOnClickListener { useCurrentLocation() }
        customLocationButton.setOnClickListener { showCustomLocationDialog() }
        startButton.setOnClickListener { startTracking() }
        stopButton.setOnClickListener { stopTracking() }
        resetButton.setOnClickListener { resetTracking() }
        // Removed logout button functionality that referenced LoginActivity

        requestPermissions()
        loadStartingLocation()
        initializeGPXFiles()
        gpxClosed = false

        // Initialize simulation parameters for simulated workout.
        currentDirection = 0.0   // Start heading east.
        simulatedStepCount = 0
        simulatedDistanceMeters = 0.0
        simTime = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        simulationHandler = Handler(Looper.getMainLooper())
    }

    // Removed onStart override that referenced LoginActivity

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACTIVITY_RECOGNITION
            ),
            102
        )
    }

    private fun showCustomLocationDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_custom_location, null)
        val latitudeEditText = dialogView.findViewById<EditText>(R.id.latitudeEditText)
        val longitudeEditText = dialogView.findViewById<EditText>(R.id.longitudeEditText)

        AlertDialog.Builder(this)
            .setTitle("Specify Custom Location")
            .setView(dialogView)
            .setPositiveButton("OK") { _, _ ->
                val lat = latitudeEditText.text.toString().toDoubleOrNull()
                val lon = longitudeEditText.text.toString().toDoubleOrNull()
                if (lat != null && lon != null) {
                    val customLocation = GeoPoint(lat, lon)
                    setStartingLocation(customLocation)
                    mapView.controller.setCenter(customLocation)
                    Toast.makeText(this, "Custom Location Set: $lat, $lon", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Invalid coordinates entered", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setStartingLocation(location: GeoPoint) {
        lastKnownLocation = location
        saveStartingLocation(location)
        Toast.makeText(this, "Starting Location Set: ${location.latitude}, ${location.longitude}", Toast.LENGTH_SHORT).show()
    }

    private fun saveStartingLocation(location: GeoPoint) {
        val prefs = getSharedPreferences("MyAppPrefs", Context.MODE_PRIVATE)
        with(prefs.edit()) {
            putFloat("start_latitude", location.latitude.toFloat())
            putFloat("start_longitude", location.longitude.toFloat())
            apply()
        }
    }

    private fun loadStartingLocation() {
        val prefs = getSharedPreferences("MyAppPrefs", Context.MODE_PRIVATE)
        val lat = prefs.getFloat("start_latitude", 0f)
        val lon = prefs.getFloat("start_longitude", 0f)
        if (lat != 0f && lon != 0f) {
            lastKnownLocation = GeoPoint(lat.toDouble(), lon.toDouble())
        }
    }

    private fun useCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1f, this)
            val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (location != null) {
                val currentGeoPoint = GeoPoint(location.latitude, location.longitude)
                setStartingLocation(currentGeoPoint)
                mapView.controller.setCenter(currentGeoPoint)
                Toast.makeText(this, "Current Location Set", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Waiting for GPS signal...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- Tracking Methods with GPX integration ---
    private fun startTracking() {
        if (!timerStarted) {
            timerStarted = true
            handler.post(runnable)
            if (simulateTrail) {
                startSimulatedWorkout()  // In simulation mode, we use our multi-point buffering & snapping.
            } else {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1f, this)
                }
            }
            Toast.makeText(this, "Tracking Started", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopTracking() {
        if (timerStarted) {
            timerStarted = false
            handler.removeCallbacks(runnable)
            if (simulateTrail) {
                if (::simulationRunnable.isInitialized) {
                    simulationHandler.removeCallbacks(simulationRunnable)
                }
            } else {
                sensorManager.unregisterListener(this)
                locationManager.removeUpdates(this)
            }
            closeGPXTracking()
            val miles = simulatedDistanceMeters / 1609.34
            Toast.makeText(
                this,
                "Workout Finished: ${"%.2f".format(miles)} miles in $elapsedTime seconds",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun resetTracking() {
        timerStarted = false
        elapsedTime = 0
        stepCount = 0
        initialStepCount = null
        simulatedStepCount = 0
        simulatedDistanceMeters = 0.0
        candidatePointsBuffer.clear()
        path.setPoints(emptyList())
        updateTimerText()
        stepCountTextView.text = "Steps: 0"
        lastKnownLocation = null
        mapView.overlays.clear()
        mapView.invalidate()
        sensorManager.unregisterListener(this)
        Toast.makeText(this, "Tracking Reset", Toast.LENGTH_SHORT).show()
    }

    // --- GPX Tracking Methods ---
    private fun initializeGPXFiles() {
        val externalDir = getExternalFilesDir(null) ?: return
        gpxFile = File(externalDir, "GPXfile.gpx")
        gpxEditorFile = File(externalDir, "GPXeditor.gpx")
        try {
            FileWriter(gpxFile, false).use { writer ->
                writer.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                writer.append("<gpx creator=\"ExerciseHome\" version=\"1.1\" \n")
                writer.append("     xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\" \n")
                writer.append("     xmlns=\"http://www.topografix.com/GPX/1/1\" \n")
                writer.append("     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n")
                writer.append("  <trk>\n")
                writer.append("   <name>Endless Running exercise game</name>\n")
                writer.append("   <trkseg>\n")
            }
            FileWriter(gpxEditorFile, false).use { writer ->
                writer.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                writer.append("<gpx creator=\"ExerciseHome\" version=\"1.1\" \n")
                writer.append("     xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\" \n")
                writer.append("     xmlns=\"http://www.topografix.com/GPX/1/1\" \n")
                writer.append("     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n")
                writer.append("  <trk>\n")
                writer.append("   <name>Endless Running exercise game</name>\n")
                writer.append("   <trkseg>\n")
            }
            gpxClosed = false
        } catch (e: Exception) {
            Toast.makeText(this, "GPX Tracking initialization failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Accept an optional timestamp parameter.
    private fun updateGPXTracking(latitude: Double, longitude: Double, timestamp: String? = null) {
        val timeStampToUse = timestamp ?: getCurrentTimestamp()
        try {
            FileWriter(gpxFile, true).use { writer ->
                writer.append("      <trkpt lat=\"$latitude\" lon=\"$longitude\">\n")
                writer.append("        <time>$timeStampToUse</time>\n")
                writer.append("      </trkpt>\n")
            }
            FileWriter(gpxEditorFile, true).use { writer ->
                writer.append("      <trkpt lat=\"$latitude\" lon=\"$longitude\">\n")
                writer.append("        <time>$timeStampToUse</time>\n")
                writer.append("      </trkpt>\n")
            }
        } catch (e: Exception) {
            Toast.makeText(this, "GPX Tracking update failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun closeGPXTracking() {
        if (gpxClosed) return
        try {
            FileWriter(gpxFile, true).use { writer ->
                writer.append("   </trkseg>\n")
                writer.append("  </trk>\n")
                writer.append("</gpx>\n")
            }
            FileWriter(gpxEditorFile, true).use { writer ->
                writer.append("   </trkseg>\n")
                writer.append("  </trk>\n")
                writer.append("</gpx>\n")
            }
            gpxClosed = true
            Toast.makeText(this, "GPX File saved at: ${gpxFile.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Closing GPX Tracking failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Simulated Workout Mode with Multi-Point Snap-to-Roads ---
    // In simulated mode, we simulate one step per second.
    // Every 10 simulated steps (~7m) a candidate point is generated; when the candidate buffer reaches a threshold, we call the Roads API.
    private fun startSimulatedWorkout() {
        if (lastKnownLocation == null) {
            Toast.makeText(this, "Starting location not set", Toast.LENGTH_SHORT).show()
            return
        }
        // Reset simulated counters and simulation time.
        simulatedStepCount = 0
        simulatedDistanceMeters = 0.0
        candidatePointsBuffer.clear()
        simTime = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        // Record an initial GPX point at the starting location.
        val initialTimestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(simTime.time)
        updateGPXTracking(lastKnownLocation!!.latitude, lastKnownLocation!!.longitude, initialTimestamp)
        path.addPoint(lastKnownLocation)
        mapView.invalidate()

        simulationHandler = Handler(Looper.getMainLooper())
        simulationRunnable = object : Runnable {
            override fun run() {
                simulatedStepCount++  // Simulate one step per second.
                stepCountTextView.text = "Steps: $simulatedStepCount"
                // Every 10 simulated steps, calculate a candidate point.
                if (simulatedStepCount % stepsForPoint == 0) {
                    val candidateLat = lastKnownLocation!!.latitude + displacementDegrees * cos(currentDirection)
                    val candidateLon = lastKnownLocation!!.longitude + displacementDegrees * sin(currentDirection)
                    val candidatePoint = GeoPoint(candidateLat, candidateLon)
                    candidatePointsBuffer.add(candidatePoint)
                    // When enough candidate points are accumulated, perform snapping.
                    if (candidatePointsBuffer.size >= bufferThreshold) {
                        snapToRoadPoints(candidatePointsBuffer) { snappedPoints ->
                            // Use snappedPoints if available; otherwise, fallback to candidate points.
                            val finalPoints = snappedPoints ?: candidatePointsBuffer
                            for (pt in finalPoints) {
                                simulatedDistanceMeters += displacementMeters
                                simTime.add(Calendar.SECOND, 10)
                                val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
                                    .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(simTime.time)
                                updateGPXTracking(pt.latitude, pt.longitude, timestamp)
                                path.addPoint(pt)
                                lastKnownLocation = pt
                            }
                            candidatePointsBuffer.clear()
                            runOnUiThread { mapView.invalidate() }
                            currentDirection += Random.nextDouble(-angleVariability / 2, angleVariability / 2)
                        }
                    }
                }
                simulationHandler.postDelayed(this, 1000)
            }
        }
        simulationHandler.post(simulationRunnable)
    }

    // --- SENSOR EVENT (for step counter) ---
    // In simulated mode, we rely on simulatedStepCount; in non-simulated mode, real sensor events update stepCount.
    override fun onSensorChanged(event: SensorEvent) {
        if (!simulateTrail && event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            if (initialStepCount == null) {
                initialStepCount = event.values[0].toInt()
            }
            stepCount = event.values[0].toInt() - initialStepCount!!
            stepCountTextView.text = "Steps: $stepCount"
        }
    }

    override fun onLocationChanged(location: Location) {
        lastKnownLocation = GeoPoint(location.latitude, location.longitude)
        if (!simulateTrail) {
            updateGPXTracking(location.latitude, location.longitude)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateTimerText() {
        val time = String.format("%02d:%02d:%02d", elapsedTime / 3600, (elapsedTime % 3600) / 60, elapsedTime % 60)
        timerTextView.text = "Timer: $time"
    }

    private fun getCurrentTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
}
