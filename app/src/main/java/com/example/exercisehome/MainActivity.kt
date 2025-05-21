package com.example.exercisehome

// Base Android Imports
import android.Manifest
import android.accounts.Account
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment // Added for Camera feature
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore // Added for Camera feature
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.TakePicture // Added for Camera feature
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider // Ensured for Camera
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.exifinterface.media.ExifInterface // Added for Camera feature
import androidx.lifecycle.lifecycleScope

// Project Specific Imports
import com.example.exercisehome.databinding.ActivityMainBinding

// Google Play Services Imports
import com.google.android.gms.auth.api.identity.BeginSignInRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.SignInClient
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.location.*
import com.google.android.material.snackbar.Snackbar

// Google API Client & Drive API Imports
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile

// Coroutine Imports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Networking Imports
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

// OSMDroid Imports
import org.json.JSONObject
import org.osmdroid.api.IMapController
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

// Java IO / Util Imports
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random


class MainActivity : AppCompatActivity(), SensorEventListener {

    // Constants
    companion object {
        private const val TAG = "MainActivity"
        // Constants for Real GPS mode (if simulateTrail = false)
        private const val LOCATION_UPDATE_INTERVAL_MS = 5000L
        private const val FASTEST_LOCATION_UPDATE_INTERVAL_MS = 2000L
        // General Constants
        private const val STEP_LENGTH_METERS = 0.7 // Used for distance estimate from REAL steps
        private const val MAP_DEFAULT_ZOOM = 16.0
        private val GPX_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        private const val DRIVE_UPLOAD_MIMETYPE = "application/gpx+xml"
        private val DRIVE_SCOPE = Scope(DriveScopes.DRIVE_FILE) // Correct scope for Drive API
        private const val GPX_BUFFER_THRESHOLD = 3 // How many simulated points to buffer before writing

        // --- Simulation specific constants (Updated for Python-like algorithm) ---
        private const val SIMULATION_POINT_INTERVAL_MS = 3000L // How often to generate a simulated point
        private const val SIMULATION_DIRECTION_VARIABILITY_RAD = Math.PI / 7.0 // Python: angleVariability
        private const val SIMULATION_SCALE_DEGREES = 0.0001 // Python: SCALE
        // ORIGINAL_SIMULATED_SPEED_MPS is the old constant from your code,
        // kept for reference if you need to use it elsewhere, but not directly for displacement
        // in the Python-like algorithm.
        private const val ORIGINAL_SIMULATED_SPEED_MPS = 1.5
    }

    // --- Mode Selection ---
    // Set to true for: Simulated GPX Track (Python-like alg) + Real Step Counting
    // Set to false for: Real GPS Track + Real Step Counting
    private val simulateTrail = true

    // Permissions Launcher
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                Log.d(TAG, "All required permissions granted.")
                onPermissionsGranted()
            } else {
                Log.w(TAG, "One or more permissions were denied.")
                val deniedPermissions = permissions.filter { !it.value }.keys.joinToString()
                Log.w(TAG, "Denied permissions: $deniedPermissions")
                showPermissionDeniedSnackbar() // Default snackbar for general denials
            }
        }

    // Required permissions - Camera permission added
    private val requiredPermissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_NETWORK_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        add(Manifest.permission.CAMERA) // Added for Camera feature
        // GET_ACCOUNTS is generally not needed with modern sign-in
        // add(Manifest.permission.GET_ACCOUNTS)
    }.toTypedArray()

    // View Binding
    private lateinit var binding: ActivityMainBinding

    // Location Services (Only active if simulateTrail = false)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    private var requestingLocationUpdates = false // Tracks if real GPS updates are active
    private var currentBestLocation: Location? = null // Holds last known real location (useful for altitude/initial map centering)

    // Start Point Selection
    private var userSelectedStartPoint: GeoPoint? = null

    // Sensor Manager & Step Counter (Now always used when tracking)
    private lateinit var sensorManager: SensorManager
    private var stepCounterSensor: Sensor? = null
    private var stepCount = 0 // REAL steps counted during session
    private var initialStepCount: Int? = null // Baseline for real sensor
    private var isSensorRegistered = false

    // --- Simulation Mode State (Simulated GPX Track - only if simulateTrail = true) ---
    private lateinit var simulationHandler: Handler
    private lateinit var simulationRunnable: java.lang.Runnable // Explicitly use java.lang.Runnable
    private var currentSimulatedDirectionRad: Double = 0.0 // 'angle' in Python script
    private var lastSimulatedGeoPoint: GeoPoint? = null // Last generated point for simulation

    // Map (OSMDroid) Components
    private lateinit var mapController: IMapController
    private val pathPolyline = Polyline().apply {
        outlinePaint.color = Color.BLUE // TODO: Use color resource
        outlinePaint.strokeWidth = 8f
    }
    private var startMarker: Marker? = null

    // Timer State
    private var timerStarted = false
    private var elapsedTimeSeconds = 0L
    private lateinit var timerHandler: Handler
    private lateinit var timerRunnable: java.lang.Runnable // Explicitly use java.lang.Runnable
    private var isTimerRunnablePosted = false

    // GPX File Handling
    private var gpxFile: File? = null
    private var gpxFileWriter: FileWriter? = null
    private var gpxFileInitialized = false
    private val gpxPointsBuffer = mutableListOf<GeoPoint>() // Buffer for simulated points
    private var lastGpxTimestampMs: Long = 0L // Tracks the timestamp of the last point written to GPX

    // Networking (OkHttp Client)
    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    // Activity Result Launchers
    private val locationPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.let {
                    val lat = it.getDoubleExtra("lat", 0.0)
                    val lon = it.getDoubleExtra("lon", 0.0)
                    if (lat != 0.0 && lon != 0.0) {
                        userSelectedStartPoint = GeoPoint(lat, lon)
                        setStartingLocationOnMap(userSelectedStartPoint!!)
                        Toast.makeText(this, "Custom start location set", Toast.LENGTH_SHORT).show()
                        binding.startButton.isEnabled = true
                    } else {
                        Log.w(TAG, "Invalid coordinates received from LocationPickerActivity")
                        Toast.makeText(this, "Failed to get custom location", Toast.LENGTH_SHORT).show()
                    }
                } ?: Log.w(TAG, "No data received from LocationPickerActivity")
            } else {
                Log.d(TAG, "LocationPickerActivity cancelled or failed (Result Code: ${result.resultCode})")
            }
        }

    private var pendingFileToUpload: File? = null // For sign-in triggered uploads

    // Launcher for Google Drive Consent Screen
    private var pendingFileToUploadForConsentRetry: File? = null // Stores file if consent is needed
    private val requestGoogleDriveConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult -> // Explicit type
            if (result.resultCode == RESULT_OK) {
                Log.i(TAG, "Google Drive consent granted by user.")
                pendingFileToUploadForConsentRetry?.let { fileToRetry ->
                    Log.d(TAG, "Retrying Drive upload for ${fileToRetry.name} after consent.")
                    uploadGpxToDrive(fileToRetry) // Retry the upload
                    pendingFileToUploadForConsentRetry = null
                } ?: Log.w(TAG, "Consent granted, but no pending file to retry.")
            } else {
                Log.w(TAG, "Google Drive consent denied or cancelled by user. Result Code: ${result.resultCode}")
                Toast.makeText(this, "Google Drive permission denied. File cannot be uploaded.", Toast.LENGTH_LONG).show()
                pendingFileToUploadForConsentRetry = null
            }
        }


    // --- Google Identity Services (GIS) & Drive Variables ---
    private lateinit var credentialManager: CredentialManager
    private lateinit var oneTapClient: SignInClient
    private var driveService: Drive? = null
    private var userIdToken: String? = null
    private var userDisplayName: String? = null
    private var userEmail: String? = null

    // Activity Result Launcher for One Tap Sign-In Intent
    private val oneTapSignInLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            Log.d(TAG, "One Tap Sign-in activity finished with result code: ${result.resultCode}")
            if (result.resultCode == RESULT_OK) {
                try {
                    val signInCredential = oneTapClient.getSignInCredentialFromIntent(result.data) // Renamed for clarity
                    val googleIdToken = signInCredential.googleIdToken
                    val emailFromSignIn = signInCredential.id // Get the email (account ID)
                    val displayNameFromSignIn = signInCredential.displayName

                    if (googleIdToken != null) {
                        userIdToken = googleIdToken
                        userDisplayName = displayNameFromSignIn
                        userEmail = emailFromSignIn // This should be the primary source for userEmail
                        Log.i(TAG, "One Tap Sign-In Success: Name='$userDisplayName', Email='$userEmail'")
                        handleSignInSuccess(userEmail, userDisplayName)

                        // Process pending upload AFTER handling sign-in success (which initializes driveService)
                        pendingFileToUpload?.let { file ->
                            Log.d(TAG, "Processing pending upload for ${file.name} after One Tap sign-in.")
                            // Check driveService status again, as initialization is async
                            if (driveService != null && userEmail != null) { // Ensure userEmail is not null here
                                uploadGpxToDrive(file)
                                pendingFileToUpload = null
                            } else {
                                Log.w(TAG, "Drive service not ready or user email invalid immediately after sign-in. Upload still pending.")
                            }
                        }
                    } else {
                        Log.e(TAG, "One Tap Sign-In failed: Google ID Token was null. Email from SignIn was '$emailFromSignIn'.")
                        handleSignInFailure("Sign-in failed: ID token was null.")
                    }
                } catch (e: ApiException) {
                    Log.e(TAG, "One Tap Sign-In failed via ApiException: Status Code ${e.statusCode}", e)
                    handleSignInFailure("Sign-in failed: API Exception Code ${e.statusCode}")
                } catch (e: NoCredentialException){ // Specific exception for no credentials
                    Log.e(TAG, "One Tap Sign-In failed: No credentials available.", e)
                    handleSignInFailure("Sign-in failed: No credentials available.")
                } catch (e: Exception) { // General catch-all
                    Log.e(TAG, "One Tap Sign-In failed with general exception", e)
                    handleSignInFailure(e.localizedMessage ?: "Sign-in failed: Unknown error")
                }
            } else {
                Log.w(TAG, "One Tap Sign-In cancelled or failed by user (Result Code: ${result.resultCode}).")
                handleSignInFailure("Sign-in cancelled or failed")
            }
        }


    // ---- START: Camera Feature Variables ----
    private var latestTmpUri: Uri? = null
    private val takePictureLauncher = registerForActivityResult(TakePicture()) { isSuccess ->
        if (isSuccess) {
            latestTmpUri?.let { uri -> Log.d(TAG, "Image captured: $uri"); processImageWithExif(uri) }
                ?: Log.e(TAG, "Image capture success but latestTmpUri is null") // Safety log
        } else {
            Log.e(TAG, "Image capture failed/cancelled.")
            latestTmpUri?.let { uri -> try { contentResolver.delete(uri, null, null); Log.d(TAG, "Temp image file deleted: $uri") } catch (e: Exception) { Log.e(TAG, "Error deleting temp file for $uri",e)} }
            Toast.makeText(this, "Image capture failed or cancelled", Toast.LENGTH_SHORT).show()
        }
    }
    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) { Log.d(TAG, "Camera permission granted by user."); launchCamera() }
            else { Log.w(TAG, "Camera permission denied by user."); Toast.makeText(this, "Camera permission is required to take pictures.", Toast.LENGTH_LONG).show(); showPermissionDeniedSnackbar("Camera permission is required. Please grant it in settings.") }
        }
    // ---- END: Camera Feature Variables ----


    // --- Lifecycle Methods ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Log.i(TAG, "onCreate")

        Configuration.getInstance().load(applicationContext, getPreferences(Context.MODE_PRIVATE))

        // Initial UI State
        binding.stopButton.isEnabled = false
        binding.startButton.isEnabled = false
        binding.currentLocationButton.isEnabled = false
        binding.takePictureButton.isEnabled = false // Initialize new camera button state

        // Initialize Core Components
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        timerHandler = Handler(Looper.getMainLooper())
        simulationHandler = Handler(Looper.getMainLooper()) // For simulation runnable
        credentialManager = CredentialManager.create(this)
        oneTapClient = Identity.getSignInClient(this)

        // Setup Features
        setupLocationTracking() // Configures callback/request (only starts if simulateTrail=false later)
        setupMap()
        setupTimer()
        if (simulateTrail) { // Only setup simulation specifics if needed
            setupSimulation()
        }
        setupButtonClickListeners()

        checkAndRequestPermissions() // Request permissions after basic setup
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume")
        binding.mapView.onResume() // Resume osmdroid map

        if (timerStarted) { // If a workout was active
            Log.d(TAG, "Resuming active session (Mode: ${if(simulateTrail) "Simulated (Python Alg)" else "Real GPS"})")
            // --- Resume logic based on mode ---
            registerStepSensor() // ALWAYS re-register real step sensor

            if (simulateTrail) {
                // If simulating track and we have a last point, restart simulation runnable
                if (lastSimulatedGeoPoint != null) {
                    startSimulation()
                }
            } else {
                // Only resume real GPS if NOT simulating track
                startLocationUpdates()
            }
            // --- End resume logic ---
            startTimer() // Always restart the timer visual update
        }
    }

    override fun onPause() {
        super.onPause()
        Log.i(TAG, "onPause")
        binding.mapView.onPause() // Pause osmdroid map

        if (timerStarted) { // If a workout is active
            Log.d(TAG, "Pausing active session")
            // --- Pause logic based on mode ---
            unregisterStepSensor() // ALWAYS unregister real step sensor

            if (simulateTrail) {
                stopSimulation() // Stop simulation runnable
            } else {
                stopLocationUpdates() // Stop real GPS updates
            }
            // --- End pause logic ---
        }
        stopTimer() // Stop timer visual updates (always safe to call)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy")
        binding.mapView.onDetach() // Detach osmdroid map
        closeGpxFile() // Ensure GPX file is properly closed
        timerHandler.removeCallbacksAndMessages(null) // Clean up timer handler
        simulationHandler.removeCallbacksAndMessages(null) // Clean up simulation handler
    }

    // --- Setup Methods ---
    private fun setupMap() {
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        mapController = binding.mapView.controller
        mapController.setZoom(MAP_DEFAULT_ZOOM)
        binding.mapView.overlays.add(pathPolyline)
        Log.d(TAG, "Map setup complete.")
    }

    private fun setupTimer() {
        timerRunnable = java.lang.Runnable { // Use explicit type
            if (timerStarted) {
                elapsedTimeSeconds++
                updateTimerText()
                timerHandler.postDelayed(timerRunnable, 1000)
                isTimerRunnablePosted = true
            } else {
                isTimerRunnablePosted = false // Ensure flag is reset when timer stops
            }
        }
        Log.d(TAG, "Timer setup complete.")
    }

    // Setup simulation runnable (only called if simulateTrail = true)
    private fun setupSimulation() {
        // This runnable now uses the Python-like algorithm in generateAndProcessSimulatedPoint
        simulationRunnable = java.lang.Runnable { // Use explicit type
            if (!timerStarted) return@Runnable // Stop if tracking stopped
            generateAndProcessSimulatedPoint() // Core logic changed here
            if (timerStarted) { // Check again in case timer stopped during generation
                simulationHandler.postDelayed(simulationRunnable, SIMULATION_POINT_INTERVAL_MS)
            }
        }
        Log.d(TAG, "Simulation (Python-like algorithm) setup complete.")
    }

    private fun setupLocationTracking() {
        // Configures location request parameters (only used if simulateTrail=false)
        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_UPDATE_INTERVAL_MS)
            .setWaitForAccurateLocation(false) // Don't wait indefinitely
            .setMinUpdateIntervalMillis(FASTEST_LOCATION_UPDATE_INTERVAL_MS)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                // This callback is only active if simulateTrail is false and updates started
                locationResult.lastLocation?.let { location ->
                    Log.v(TAG, "Real Location update: Lat=${location.latitude}, Lon=${location.longitude}, Acc=${location.accuracy}")
                    currentBestLocation = location // Keep track of best real location
                    val currentGeoPoint = GeoPoint(location.latitude, location.longitude)
                    // Only add if tracking is active (timerStarted check already included implicitly by when updates are active)
                    if (timerStarted && !simulateTrail) {
                        addPointToGpx(currentGeoPoint, location.time)
                        addPointToPolyline(currentGeoPoint)
                    }
                } ?: Log.w(TAG, "onLocationResult: lastLocation is null")
            }

            override fun onLocationAvailability(locationAvailability: LocationAvailability) {
                // This callback is only active if simulateTrail is false and updates started
                if (!locationAvailability.isLocationAvailable && timerStarted && !simulateTrail) {
                    Log.w(TAG, "Location provider unavailable.")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Location signal lost", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        Log.d(TAG, "Location tracking callback configured (updates only active when not simulating track).")
    }

    private fun setupButtonClickListeners() {
        binding.currentLocationButton.setOnClickListener {
            Log.d(TAG, "Use Current Location button clicked")
            checkLocationPermissionForCurrentLocation()
        }

        binding.customLocationButton.setOnClickListener {
            Log.d(TAG, "Specify Custom Location button clicked")
            val intent = Intent(this, LocationPickerActivity::class.java)
            locationPickerLauncher.launch(intent)
        }

        binding.startButton.setOnClickListener {
            Log.d(TAG, "Start button clicked")
            val startPoint = userSelectedStartPoint ?: run {
                Toast.makeText(this, "Please set a starting location first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startTracking(startPoint) // Pass start point explicitly
        }

        binding.stopButton.setOnClickListener {
            Log.d(TAG, "Stop button clicked")
            stopTracking()
        }

        binding.resetButton.setOnClickListener {
            Log.d(TAG, "Reset button clicked")
            resetTracking()
        }

        binding.signInButton.setOnClickListener {
            if (userEmail == null) {
                Log.d(TAG, "Sign In button clicked")
                startSignInFlow()
            } else {
                Log.d(TAG, "Sign Out button clicked")
                signOut()
            }
        }
        // Listener for the new takePictureButton (Camera Feature)
        binding.takePictureButton.setOnClickListener {
            Log.d(TAG, "Take Picture button clicked")
            if (timerStarted) { // Only allow taking pictures if workout is active
                handleTakePictureClick()
            } else {
                Toast.makeText(this, "Start a workout to take a picture.", Toast.LENGTH_SHORT).show()
            }
        }
        Log.d(TAG, "Button listeners setup complete.")
    }


    // --- Permission Handling ---
    private fun checkAndRequestPermissions() {
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isEmpty()) {
            Log.d(TAG, "All required permissions already granted.")
            onPermissionsGranted()
        } else {
            Log.i(TAG, "Requesting permissions: ${permissionsToRequest.joinToString()}")
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun onPermissionsGranted() {
        Log.d(TAG, "onPermissionsGranted - attempting initial setup.");
        getLastKnownLocation() // Try to get location for map centering
        binding.currentLocationButton.isEnabled = true // Enable button now
    }

    private fun showPermissionDeniedSnackbar(message: String = "Core permissions are required for app functionality.") {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_INDEFINITE)
            .setAction("Settings") {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    val uri = Uri.fromParts("package", packageName, null)
                    intent.data = uri
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG,"Failed to open app settings", e)
                    Toast.makeText(this, "Failed to open settings", Toast.LENGTH_SHORT).show()
                }
            }.show()
        // Only disable current location button if core permissions were denied (not just camera if that's the only one in message)
        if (message.startsWith("Core")) {
            binding.currentLocationButton.isEnabled = false
        }
    }

    private fun checkLocationPermissionForCurrentLocation() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED -> {
                useCurrentLocationAsStart()
            }
            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION) -> {
                Snackbar.make(binding.root, "Location permission needed to show current location.", Snackbar.LENGTH_LONG)
                    .setAction("Grant") {
                        requestPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                    }.show()
            }
            else -> {
                requestPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            }
        }
    }


    // --- Location Methods ---
    @SuppressLint("MissingPermission")
    private fun useCurrentLocationAsStart() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "useCurrentLocationAsStart called without permission!") // Should not happen if checkLocationPermissionForCurrentLocation is used
            Toast.makeText(this, "Location permission error", Toast.LENGTH_SHORT).show()
            return
        }
        Log.d(TAG, "Attempting to get current location for start point...")
        Toast.makeText(this, "Getting current location…", Toast.LENGTH_SHORT).show()

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    Log.d(TAG, "Got current location: ${location.latitude}, ${location.longitude}")
                    val geoPoint = GeoPoint(location.latitude, location.longitude)
                    userSelectedStartPoint = geoPoint
                    setStartingLocationOnMap(geoPoint)
                    binding.startButton.isEnabled = true
                    Toast.makeText(this, "Using current location as start", Toast.LENGTH_SHORT).show()
                } else {
                    Log.w(TAG, "Failed to get current location (null). Trying last known.")
                    getLastKnownLocation { lastKnownLocation -> // Pass a lambda to handle result
                        if (lastKnownLocation != null) {
                            val geoPoint = GeoPoint(lastKnownLocation.latitude, lastKnownLocation.longitude)
                            userSelectedStartPoint = geoPoint
                            setStartingLocationOnMap(geoPoint)
                            binding.startButton.isEnabled = true
                            Toast.makeText(this, "Using last known location as start", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Could not get current location", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error getting current location", e)
                Toast.makeText(this, "Error getting location", Toast.LENGTH_SHORT).show()
            }
    }

    @SuppressLint("MissingPermission")
    private fun getLastKnownLocation(callback: ((Location?) -> Unit)? = null) { // Made callback optional
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Permission check failed in getLastKnownLocation")
            binding.currentLocationButton.isEnabled = false // Keep disabled if permission missing
            callback?.invoke(null)
            return
        }
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    Log.d(TAG, "Got last known location: Lat=${location.latitude}, Lon=${location.longitude}")
                    currentBestLocation = location // Update currentBestLocation
                    val geoPoint = GeoPoint(location.latitude, location.longitude)
                    // Only center map if no explicit start point is selected yet.
                    if (userSelectedStartPoint == null) {
                        mapController.setCenter(geoPoint)
                    }
                    binding.currentLocationButton.isEnabled = true // Enable button as location is available
                } else {
                    Log.w(TAG, "Last known location is null.")
                    binding.currentLocationButton.isEnabled = true // Still enable button
                }
                callback?.invoke(location) // Invoke callback with location or null
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to get last known location", e)
                binding.currentLocationButton.isEnabled = true // Still enable button
                callback?.invoke(null) // Invoke callback with null on failure
            }
    }

    // Start REAL location updates (only if simulateTrail is false)
    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Missing location permission for starting real updates.")
            return
        }
        // Check flags: only start if not already requesting AND not simulating the track
        if (!requestingLocationUpdates && !simulateTrail) {
            Log.i(TAG, "Starting REAL location updates")
            try {
                fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
                    .addOnSuccessListener { Log.d(TAG, "Real location updates requested successfully.") }
                    .addOnFailureListener { e -> Log.e(TAG, "Failed to request real location updates.", e) }
                requestingLocationUpdates = true
            } catch (se: SecurityException) { // Catch explicit SecurityException
                Log.e(TAG, "SecurityException starting real location updates", se)
            }
        } else {
            // Log why updates aren't starting if conditions aren't met
            if (requestingLocationUpdates) Log.d(TAG, "Real location updates already active.")
            if (simulateTrail) Log.d(TAG, "Not starting real location updates because track simulation is active.")
        }
    }

    // Stop REAL location updates
    private fun stopLocationUpdates() {
        if (requestingLocationUpdates) { // Only stop if they were active
            Log.i(TAG, "Stopping REAL location updates")
            fusedLocationClient.removeLocationUpdates(locationCallback)
                .addOnSuccessListener { Log.d(TAG, "Real location updates stopped successfully.") }
                .addOnFailureListener { e -> Log.e(TAG, "Failed to stop real location updates.", e) }
            requestingLocationUpdates = false
        }
    }


    // --- Sensor Methods (Real Step Counter) ---
    private fun registerStepSensor() {
        if (!hasActivityPermission()) {
            Log.w(TAG, "Activity Recognition permission not granted. Cannot count real steps.")
            if (stepCounterSensor != null) Toast.makeText(this, "Step counting disabled: Activity permission needed", Toast.LENGTH_LONG).show()
            return
        }
        if (stepCounterSensor == null) {
            Log.w(TAG, "No step counter sensor available on this device.")
            Toast.makeText(this, "Step counter sensor not available", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isSensorRegistered) {
            Log.i(TAG, "Registering REAL step counter sensor.")
            val success = sensorManager.registerListener(this, stepCounterSensor, SensorManager.SENSOR_DELAY_UI)
            if (success) {
                isSensorRegistered = true
                initialStepCount = null // Reset baseline for REAL steps
                Log.d(TAG, "Step counter listener registered successfully.")
            } else {
                Log.e(TAG, "Failed to register step counter listener.")
                Toast.makeText(this, "Failed to start step counter", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun unregisterStepSensor() {
        if (isSensorRegistered && stepCounterSensor != null) {
            try {
                Log.i(TAG, "Unregistering REAL step counter sensor.")
                sensorManager.unregisterListener(this, stepCounterSensor)
                isSensorRegistered = false
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering step counter listener", e)
            }
        }
    }

    // Handles REAL step sensor events
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            val currentSensorValue = event.values[0].toInt()

            if (initialStepCount == null && isSensorRegistered) { // Check isSensorRegistered flag
                initialStepCount = currentSensorValue
                Log.d(TAG, "Initial REAL step count baseline set: $initialStepCount")
                stepCount = 0 // Start counting from zero after baseline
            } else if (initialStepCount != null) { // Only calculate if baseline is set
                // Calculate steps since the baseline
                stepCount = currentSensorValue - initialStepCount!!
                // Handle potential sensor reset or wrap-around
                if (stepCount < 0) {
                    Log.w(TAG, "Real Step counter reset detected (new value: $currentSensorValue, old baseline: $initialStepCount). Resetting baseline.")
                    initialStepCount = currentSensorValue // Set new baseline
                    stepCount = 0 // Reset count for this session
                }
            }

            // Update UI always when timer is running
            if (timerStarted) {
                updateStepCountText(stepCount) // Update with REAL steps
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Log accuracy changes if needed for debugging sensor behavior
        val accuracyLevel = when(accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "High"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "Medium"
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "Low"
            SensorManager.SENSOR_STATUS_UNRELIABLE -> "Unreliable"
            else -> "Unknown ($accuracy)"
        }
        Log.d(TAG, "Sensor ${sensor?.name ?: "Unknown"} accuracy changed to $accuracyLevel")
    }


    // --- Tracking Control ---
    // startTracking now requires the validated startPoint
    private fun startTracking(startPoint: GeoPoint) {
        if (timerStarted) { Log.w(TAG, "Start button pressed, but tracking already active."); return }

        // Log the specific mode
        val modeDescription = if (simulateTrail) "Simulated (Python Alg) / Real Steps" else "Real GPS / Real Steps"
        Log.i(TAG, "Starting Tracking - Mode: $modeDescription")

        resetCounters() // Resets stepCount to 0 and initialStepCount to null

        if (!initializeGpxFile()) {
            Toast.makeText(this, "Cannot start: GPX file initialization failed", Toast.LENGTH_LONG).show()
            return
        }

        val startTimeMs = System.currentTimeMillis()
        addPointToGpx(startPoint, startTimeMs) // Add the very first point (start point)
        addPointToPolyline(startPoint)
        setStartingLocationOnMap(startPoint)
        lastGpxTimestampMs = startTimeMs // Initialize timestamp for GPX writing

        timerStarted = true
        startTimer() // Start the UI timer

        // --- Logic based on mode ---
        registerStepSensor() // ALWAYS register real step sensor for actual step counting

        if (simulateTrail) {
            // Start GPX path simulation
            Log.i(TAG, "SIMULATION (Python Alg): Initializing simulation path from $startPoint")
            lastSimulatedGeoPoint = startPoint
            // Initialize direction only once per reset session if it's not already set
            if (currentSimulatedDirectionRad == 0.0 && lastSimulatedGeoPoint == startPoint) {
                currentSimulatedDirectionRad = Random.nextDouble(0.0, 2 * Math.PI)
            }
            startSimulation() // Starts simulation runnable
        } else {
            // Start real GPS tracking for path
            startLocationUpdates()
        }
        // --- End logic based on mode ---

        updateTrackingUI(isTracking = true)
        Toast.makeText(this, "Tracking Started", Toast.LENGTH_SHORT).show()
    }


    private fun stopTracking() {
        if (!timerStarted) { Log.w(TAG, "Stop button pressed, but tracking not active."); return }

        Log.i(TAG, "Stopping Tracking")

        timerStarted = false // Set flag first
        stopTimer() // Stop UI timer

        // --- Logic based on mode ---
        unregisterStepSensor() // ALWAYS unregister real step sensor

        if (simulateTrail) {
            stopSimulation() // Stop simulation runnable
        } else {
            stopLocationUpdates() // Stop real GPS updates
        }
        // --- End logic based on mode ---


        // --- Finalize GPX File ---
        val fileToUpload = gpxFile // Hold reference before potentially nulling gpxFile
        val completionAction: () -> Unit = { // Explicit type for safety
            closeGpxFile() // Close GPX (writes footer)
            // Attempt upload only AFTER closing the file
            fileToUpload?.let {
                if (it.exists() && it.length() > 0) { // Check if file exists and is not empty
                    attemptDriveUpload(it)
                } else {
                    Log.w(TAG, "GPX file is missing or empty after closing, not uploading: ${it.name}")
                }
            }
        }

        // Write remaining buffered points if simulating track
        if (simulateTrail && gpxPointsBuffer.isNotEmpty()) {
            Log.d(TAG,"Writing remaining ${gpxPointsBuffer.size} buffered simulation points before closing.")
            // Use the time-based writing function, pass a copy
            processAndWriteSimulatedPoints(gpxPointsBuffer.toList(), completionAction)
            gpxPointsBuffer.clear() // Clear buffer after passing it to async function
        } else {
            // Close and potentially upload immediately if not simulating or buffer empty
            completionAction()
        }
        // --- End Finalize GPX File ---


        // Update UI
        updateTrackingUI(isTracking = false)

        // Show summary toast using REAL step count
        val distanceKm = (stepCount * STEP_LENGTH_METERS) / 1000.0 // Use REAL stepCount
        val duration = formatDuration(elapsedTimeSeconds)
        val message = "Workout Finished: %.2f km in %s (%d steps)".format(Locale.US, distanceKm, duration, stepCount) // Added real steps
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        Log.i(TAG, message)
    }


    private fun resetTracking() {
        if (timerStarted) stopTracking() // Ensure tracking is stopped first

        Log.i(TAG, "Resetting Tracking State")
        closeGpxFile() // Close any open file

        // Reset all state variables
        resetCounters() // Resets stepCount and initialStepCount
        elapsedTimeSeconds = 0L // Also reset timer explicitly
        isTimerRunnablePosted = false
        userSelectedStartPoint = null
        currentBestLocation = null
        lastSimulatedGeoPoint = null // Reset simulation state
        currentSimulatedDirectionRad = 0.0 // Reset simulation direction as well
        lastGpxTimestampMs = 0L
        gpxFileInitialized = false
        gpxFile = null
        pendingFileToUpload = null
        pendingFileToUploadForConsentRetry = null // Reset consent retry state
        gpxPointsBuffer.clear()

        // Reset UI elements
        updateTimerText()
        updateStepCountText(0)
        pathPolyline.setPoints(emptyList()) // Clear the map polyline
        startMarker?.let {
            binding.mapView.overlays.remove(it)
            startMarker = null
        }
        binding.mapView.invalidate() // Redraw map

        // Update button states
        updateTrackingUI(isTracking = false)
        binding.startButton.isEnabled = false // Start disabled until location set
        binding.resetButton.isEnabled = false // Reset disabled until some data exists

        // Try to get location again for map centering / enabling current location button
        getLastKnownLocation()

        Toast.makeText(this, "Tracking Reset", Toast.LENGTH_SHORT).show()
    }

    // Centralized function to update button enable/disable states
    private fun updateTrackingUI(isTracking: Boolean) {
        binding.startButton.isEnabled = !isTracking && (userSelectedStartPoint != null)
        binding.stopButton.isEnabled = isTracking
        // Enable reset if not tracking AND there's some data OR if tracking is active.
        // This logic might need refinement based on desired UX.
        val canReset = !isTracking && (elapsedTimeSeconds > 0 || stepCount > 0 || gpxFile != null)
        binding.resetButton.isEnabled = canReset // Simplest: only enable if stopped and data exists

        // Location selection buttons disabled during tracking
        binding.currentLocationButton.isEnabled = !isTracking
        binding.customLocationButton.isEnabled = !isTracking
        // Sign in button disabled during tracking (simplifies flow)
        binding.signInButton.isEnabled = !isTracking
        binding.takePictureButton.isEnabled = isTracking // Manage camera button state
    }

    // Resets timers and REAL step counters
    private fun resetCounters() {
        elapsedTimeSeconds = 0L
        stepCount = 0 // Reset REAL steps
        initialStepCount = null // Reset REAL sensor baseline
    }


    // --- Timer Control ---
    private fun startTimer() {
        if (timerStarted && !isTimerRunnablePosted) { // Check if already posted
            Log.d(TAG, "Starting timer runnable.")
            timerHandler.post(timerRunnable) // Post immediately for first update
            isTimerRunnablePosted = true
        }
    }

    private fun stopTimer() {
        Log.d(TAG, "Stopping timer runnable.")
        timerHandler.removeCallbacks(timerRunnable)
        isTimerRunnablePosted = false // Reset flag
    }

    private fun updateTimerText() {
        binding.timerTextView.text = formatDuration(elapsedTimeSeconds)
    }


    // --- Step Count Update (UI) ---
    // Now always reflects REAL steps when tracking is active
    private fun updateStepCountText(count: Int) {
        binding.stepCountTextView.text = "Steps: %d".format(count) // Use String.format for safety with placeholders
    }


    // --- Map Interaction ---
    private fun setStartingLocationOnMap(geoPoint: GeoPoint) {
        mapController.animateTo(geoPoint)
        mapController.setZoom(MAP_DEFAULT_ZOOM)

        // Remove old marker if it exists
        startMarker?.let { binding.mapView.overlays.remove(it) }

        // Add new marker
        startMarker = Marker(binding.mapView).apply {
            position = geoPoint
            title = "Start Point"
            // Use a standard OSMDroid icon or your custom one
            icon = ContextCompat.getDrawable(this@MainActivity, org.osmdroid.library.R.drawable.ic_menu_mylocation) // Example
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        binding.mapView.overlays.add(startMarker)
        binding.mapView.invalidate() // Request map redraw
    }


    // --- GPX File Handling ---
    private fun initializeGpxFile(): Boolean {
        if (gpxFileInitialized) { Log.d(TAG, "GPX file already initialized."); return true }

        val externalDir = getExternalFilesDir(null) // App-specific external storage
        if (externalDir == null) {
            Log.e(TAG, "External storage directory is unavailable.")
            Toast.makeText(this, "Storage not available", Toast.LENGTH_SHORT).show()
            return false
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "Workout_$timestamp.gpx"
        val file = File(externalDir, filename)
        Log.i(TAG, "Initializing GPX file: ${file.absolutePath}")

        val appVersion = try { BuildConfig.VERSION_NAME } catch (e: Exception) { "N/A" } // Gracefully handle missing BuildConfig
        val currentTime = GPX_DATE_FORMAT.format(Date()) // Format current time for GPX metadata

        // Determine mode for metadata
        val trackMode = if (simulateTrail) "Simulated Path (Python Alg) / Real Steps" else "Real GPS / Real Steps" // Updated trackMode description

        val gpxHeader = """<?xml version="1.0" encoding="UTF-8" standalone="no" ?>
        <gpx xmlns="http://www.topografix.com/GPX/1/1"
             xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd http://www.garmin.com/xmlschemas/TrackPointExtension/v1 http://www.garmin.com/xmlschemas/TrackPointExtensionv1.xsd"
             version="1.1" creator="ExerciseHome App v$appVersion">
          <metadata>
            <link href="https://github.com/your-repo"> 
                <text>ExerciseHome Project</text>
            </link>
            <name>Workout Session $timestamp</name>
            <time>$currentTime</time>
          </metadata>
          <trk>
            <name>Exercise Track ($trackMode)</name>
            <trkseg>
        """.trimIndent() // Using trimIndent for cleaner multiline string

        try {
            // Ensure parent directory exists
            if (!externalDir.exists()) {
                externalDir.mkdirs()
            }
            val writer = FileWriter(file, false) // Overwrite if exists
            writer.append(gpxHeader)
            writer.appendLine() // Add a newline after the header

            gpxFileWriter = writer
            gpxFile = file
            gpxFileInitialized = true
            Log.d(TAG, "GPX file initialized successfully.")
            return true
        } catch (e: IOException) {
            Log.e(TAG, "GPX header write failed", e)
            Toast.makeText(this, "Error initializing GPX file", Toast.LENGTH_LONG).show()
            gpxFileWriter = null // Reset on failure
            gpxFile = null
            gpxFileInitialized = false
            return false
        }
    }

    // Adds a single track point (<trkpt>) to the GPX file
    private fun addPointToGpx(geoPoint: GeoPoint, timestampMs: Long) {
        val writer = gpxFileWriter ?: run {
            Log.e(TAG, "Attempted to write point, but GPX writer is null.")
            return
        }
        if (!gpxFileInitialized) {
            Log.e(TAG, "Attempted to write point, but GPX file not initialized.")
            return
        }

        val formattedTime = GPX_DATE_FORMAT.format(Date(timestampMs))
        // Use currentBestLocation for altitude if available, otherwise default to 0.0
        // This might be slightly inaccurate in pure simulation mode if no real location was ever obtained.
        val elevation = currentBestLocation?.altitude ?: 0.0

        val trackPoint = """
              <trkpt lat="${"%.7f".format(Locale.US, geoPoint.latitude)}" lon="${"%.7f".format(Locale.US, geoPoint.longitude)}">
                <ele>${"%.2f".format(Locale.US, elevation)}</ele>
                <time>$formattedTime</time>
              </trkpt>
        """.trimIndent() // Using trimIndent

        try {
            writer.appendLine(trackPoint)
            // Consider flushing periodically if buffering large amounts, but appendLine might handle it.
            // writer.flush()
        } catch (e: IOException) {
            Log.e(TAG, "GPX track point write failed", e)
        }
    }

    // Writes the GPX footer and closes the file writer
    private fun closeGpxFile() {
        val writer = gpxFileWriter
        if (writer != null && gpxFileInitialized) { // Check both flags
            val fileRef = gpxFile // Hold ref for logging
            Log.i(TAG, "Closing GPX file: ${fileRef?.name}")

            gpxFileWriter = null // Set to null before closing to prevent reuse
            gpxFileInitialized = false // Mark as not initialized

            val gpxFooter = """
               </trkseg>
             </trk>
           </gpx>
           """.trimIndent() // Using trimIndent
            try {
                writer.appendLine() // Add a newline before footer for readability
                writer.append(gpxFooter)
                writer.flush() // Ensure all buffered data is written
                writer.close() // Close the stream
                Log.i(TAG,"GPX file closed successfully: ${fileRef?.name}")
            } catch (e: IOException) {
                Log.e(TAG, "GPX footer write or close failed for ${fileRef?.name}", e)
            }
        } else {
            // Log if close was called unnecessarily
            if (writer == null) Log.d(TAG, "closeGpxFile called but writer was already null.")
            if (!gpxFileInitialized) Log.d(TAG, "closeGpxFile called but file was not marked as initialized.")
        }
    }


    // --- Simulation Logic (for GPX track only) ---
    private fun startSimulation() {
        // Guard conditions
        if (!simulateTrail || !timerStarted) return
        if (lastSimulatedGeoPoint == null) {
            Log.e(TAG, "Cannot start simulation without a starting point.")
            return
        }

        Log.i(TAG, "Starting simulation path generation runnable from: $lastSimulatedGeoPoint")

        gpxPointsBuffer.clear() // Clear buffer on start/resume to avoid duplicates

        // Remove any pending runnables and post a new one to ensure only one is active
        simulationHandler.removeCallbacks(simulationRunnable)
        simulationHandler.post(simulationRunnable) // Start generating points
    }

    private fun stopSimulation() {
        if (simulateTrail) { // Only stop if simulating
            Log.i(TAG, "Stopping simulation path generation runnable.")
            simulationHandler.removeCallbacks(simulationRunnable)
        }
    }

    // ---- MODIFIED: generateAndProcessSimulatedPoint (Python-like Algorithm) ----
    private fun generateAndProcessSimulatedPoint() {
        val lastPoint = lastSimulatedGeoPoint ?: run {
            Log.w(TAG, "Sim Error: lastSimulatedGeoPoint is null. Cannot generate point.")
            stopSimulation() // Safety stop if state is inconsistent
            Toast.makeText(this, "Simulation error: No last point.", Toast.LENGTH_SHORT).show()
            return
        }

        // 1. Update angle (direction) - Matches Python's 'angle' update logic
        // currentSimulatedDirectionRad is 'angle' in Python script (in radians)
        // SIMULATION_DIRECTION_VARIABILITY_RAD is 'angleVariability' in Python script
        val randomFactor = Random.nextDouble() // Equivalent to Python's random.random() -> [0.0, 1.0)
        val angleChange = (randomFactor * SIMULATION_DIRECTION_VARIABILITY_RAD) - (SIMULATION_DIRECTION_VARIABILITY_RAD / 2.0)
        currentSimulatedDirectionRad = (currentSimulatedDirectionRad + angleChange).mod(2 * Math.PI) // Keep angle within 0-2PI

        // 2. Calculate new coordinates using SIMULATION_SCALE_DEGREES (Python's 'SCALE')
        // This directly adds degree changes, similar to the Python script.
        // It does NOT correct for latitude convergence for longitude steps, matching Python's direct sin(angle)*SCALE.
        val newLat = lastPoint.latitude + cos(currentSimulatedDirectionRad) * SIMULATION_SCALE_DEGREES
        val newLon = lastPoint.longitude + sin(currentSimulatedDirectionRad) * SIMULATION_SCALE_DEGREES

        // Basic bounds check for latitude and longitude
        if (newLat > 90.0 || newLat < -90.0 || newLon > 180.0 || newLon < -180.0) {
            Log.w(TAG, "Simulated point out of bounds ($newLat, $newLon). Resetting direction randomly.")
            currentSimulatedDirectionRad = Random.nextDouble(0.0, 2 * Math.PI) // Assign a new random direction
            return // Skip generating this out-of-bounds point
        }

        val newPoint = GeoPoint(newLat, newLon)
        Log.v(TAG, "Generated Python-like Sim Point: Lat=${String.format(Locale.US, "%.7f",newPoint.latitude)}, Lon=${String.format(Locale.US, "%.7f",newPoint.longitude)}, Current Direction (degrees)=${Math.toDegrees(currentSimulatedDirectionRad)}")

        // Add to buffer
        gpxPointsBuffer.add(newPoint)
        lastSimulatedGeoPoint = newPoint // Update the last point for the next iteration

        // If buffer reaches threshold, process it (write to file and map)
        if (gpxPointsBuffer.size >= GPX_BUFFER_THRESHOLD) {
            Log.d(TAG, "GPX buffer threshold reached. Writing ${gpxPointsBuffer.size} Python-like points.")
            // Use the time-based writing function, pass a copy of the buffer
            processAndWriteSimulatedPoints(gpxPointsBuffer.toList(), null)
            gpxPointsBuffer.clear() // Clear buffer after passing copy
        }
    }
    // ---- END MODIFIED generateAndProcessSimulatedPoint ----


    // --- Buffering and Writing Simulated Points (No Snapping) ---
    // This function processes buffered simulated points, calculates timestamps, and writes to GPX/Map
    private fun processAndWriteSimulatedPoints(pointsToWrite: List<GeoPoint>, onComplete: (() -> Unit)?) {
        if (pointsToWrite.isEmpty()) {
            onComplete?.invoke()
            return
        }
        Log.d(TAG, "Processing ${pointsToWrite.size} simulated points for GPX/Map.")

        // Calculate time per point based on the simulation interval
        val timePerPoint = SIMULATION_POINT_INTERVAL_MS // Time between each point generation

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val writtenTimestamps = mutableListOf<Long>() // Keep track for updating lastGpxTimestampMs accurately
                pointsToWrite.forEachIndexed { index, point ->
                    // Calculate precise timestamp for this point relative to the last batch/point written
                    val pointTimestamp = lastGpxTimestampMs + (index + 1) * timePerPoint
                    addPointToGpx(point, pointTimestamp)
                    writtenTimestamps.add(pointTimestamp)
                }

                // Update the tracker to the timestamp of the LAST point actually written in this batch
                val lastWrittenTimestamp = writtenTimestamps.lastOrNull()
                if (lastWrittenTimestamp != null) {
                    lastGpxTimestampMs = lastWrittenTimestamp
                    Log.v(TAG, "Updated lastGpxTimestampMs to ${GPX_DATE_FORMAT.format(Date(lastGpxTimestampMs))}")
                } else {
                    Log.w(TAG, "processAndWriteSimulatedPoints: No timestamps were written?")
                }

                withContext(Dispatchers.Main) {
                    pointsToWrite.forEach { addPointToPolyline(it) }
                    binding.mapView.invalidate()
                    Log.d(TAG, "Finished writing batch of ${pointsToWrite.size} simulated points.")
                    onComplete?.invoke() // Call completion callback on Main thread
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error writing simulated points to GPX", e)
                // Ensure callback happens even on error, maybe on Main thread
                withContext(Dispatchers.Main) {
                    onComplete?.invoke()
                }
            }
        } // End Coroutine Scope
    }

    // --- Roads API Call (Not used in simulateTrail=true mode with Python-like alg) ---
    // Kept for potential future use or if simulating with snapping is desired later
    private fun snapToRoadsApi(candidates: List<GeoPoint>, onResult: (snappedPoints: List<GeoPoint>?) -> Unit) {
        val apiKey = try { BuildConfig.MAPS_API_KEY } catch (e: Exception) { "" }
        if (apiKey.isNullOrEmpty() || apiKey == "\"\"" || apiKey.contains("YOUR_API_KEY")) { // Check for empty or default placeholder
            Log.e(TAG, "MAPS_API_KEY is missing or invalid in local.properties / BuildConfig! Cannot snap.")
            // Optionally show a single warning toast to the user if this feature is attempted
            // Toast.makeText(this, "MAPS_API_KEY Error! Snapping to roads disabled.", Toast.LENGTH_LONG).show()
            onResult(null) // Callback with null to indicate failure
            return
        }
        if (candidates.isEmpty()) { onResult(emptyList()); return } // Handle empty candidate list

        // Format path parameter for Roads API
        val pathParam = candidates.joinToString("|") {
            // Ensure locale-independent formatting for doubles
            "${"%.7f".format(Locale.US, it.latitude)},${"%.7f".format(Locale.US, it.longitude)}"
        }
        val url = "https://roads.googleapis.com/v1/snapToRoads".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("path", pathParam)
            ?.addQueryParameter("interpolate", "true") // Interpolate to get points along road segments
            ?.addQueryParameter("key", apiKey)
            ?.build()

        if (url == null) { Log.e(TAG, "Failed to build Roads API URL."); onResult(null); return }

        val request = Request.Builder().url(url).get().build()

        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Roads API network request failed", e)
                onResult(null) // Callback with null on network failure
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res -> // Ensure response is closed
                    if (!res.isSuccessful) {
                        Log.e(TAG, "Roads API error: ${res.code} ${res.message}")
                        onResult(null) // Callback with null on API error
                        return
                    }
                    try {
                        val responseBody = res.body?.string()
                        if (responseBody.isNullOrEmpty()) {
                            Log.e(TAG, "Roads API returned an empty response body.")
                            onResult(null)
                            return
                        }
                        val jsonResponse = JSONObject(responseBody)
                        val snappedPointsArray = jsonResponse.optJSONArray("snappedPoints")
                        val snappedGeoPoints = mutableListOf<GeoPoint>()
                        if (snappedPointsArray != null) {
                            for (i in 0 until snappedPointsArray.length()) {
                                val pointObj = snappedPointsArray.getJSONObject(i).getJSONObject("location")
                                snappedGeoPoints.add(GeoPoint(pointObj.getDouble("latitude"), pointObj.getDouble("longitude")))
                            }
                        }
                        onResult(snappedGeoPoints) // Callback with the list of snapped points (could be empty)
                    } catch (e: Exception) { // Catch parsing errors or other issues
                        Log.e(TAG, "Roads API JSON parsing error or other processing error", e)
                        onResult(null) // Callback with null on processing error
                    }
                }
            }
        })
    }


    // --- Polyline Drawing ---
    private fun addPointToPolyline(geoPoint: GeoPoint) {
        runOnUiThread { // Ensure UI operations are on the main thread
            pathPolyline.addPoint(geoPoint)
            binding.mapView.invalidate() // Request map redraw
        }
    }


    // --- Google Sign In & Drive Methods ---
    private fun startSignInFlow() {
        Log.i(TAG, "Starting One Tap Sign-In flow.")
        pendingFileToUpload = null // Clear previous pending state
        pendingFileToUploadForConsentRetry = null // Clear consent retry state

        val oneTapRequest = BeginSignInRequest.builder()
            .setGoogleIdTokenRequestOptions(
                BeginSignInRequest.GoogleIdTokenRequestOptions.builder()
                    .setSupported(true)
                    .setServerClientId(getString(R.string.your_web_client_id)) // Ensure this string resource is correctly defined
                    .setFilterByAuthorizedAccounts(false) // Set to true to only show previously used accounts
                    .build()
            )
            .setAutoSelectEnabled(true) // Attempt to auto-select an account if possible
            .build()

        oneTapClient.beginSignIn(oneTapRequest)
            .addOnSuccessListener { result ->
                try {
                    val intentSenderRequest = IntentSenderRequest.Builder(result.pendingIntent.intentSender).build()
                    oneTapSignInLauncher.launch(intentSenderRequest)
                } catch (e: Exception) { // Catch generic exceptions during intent creation
                    Log.e(TAG, "Couldn't start One Tap UI: ${e.localizedMessage}", e)
                    handleSignInFailure("Could not launch sign-in UI")
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "One Tap beginSignIn failed", e)
                // Provide more specific feedback for common errors like network issues
                if (e is ApiException && e.statusCode == com.google.android.gms.common.api.CommonStatusCodes.NETWORK_ERROR) {
                    handleSignInFailure("Network error during sign-in. Please check connection.")
                } else {
                    handleSignInFailure(e.localizedMessage ?: "Failed to start sign-in process")
                }
            }
    }

    private fun handleSignInSuccess(email: String?, displayName: String?) {
        Log.d(TAG, "handleSignInSuccess - Received email: '$email', displayName: '$displayName'")

        // **Input Validation for Email** (Crucial before using it for Account object)
        if (email.isNullOrEmpty() || email.isBlank()) { // Check for null, empty, and also whitespace-only strings
            Log.e(TAG, "handleSignInSuccess - Email is NULL, EMPTY or BLANK. This is a critical error. Calling handleSignInFailure.")
            handleSignInFailure("Email is null, empty or blank after sign-in. Cannot proceed.") // Informative error
            return // Stop further processing
        }

        userEmail = email // Email is now confirmed valid (not null, empty, or blank)
        userDisplayName = displayName // displayName can be null, which is acceptable
        Log.i(TAG, "handleSignInSuccess - Proceeding with valid email: '$userEmail'")

        // Initialize Drive service AFTER confirming valid email
        lifecycleScope.launch {
            initializeDriveService(userEmail) // Pass the validated email
        }
        updateSignInButtonUI(isSignedIn = true)
        Toast.makeText(this, "Signed in as $userDisplayName", Toast.LENGTH_SHORT).show()
    }

    private fun handleSignInFailure(errorMessage: String) {
        Log.w(TAG, "Sign-In Failed: $errorMessage")
        Toast.makeText(this, "Sign-In Failed: $errorMessage", Toast.LENGTH_LONG).show()
        // Reset all auth-related state
        driveService = null // Reset Drive service
        userEmail = null
        userIdToken = null
        userDisplayName = null
        updateSignInButtonUI(isSignedIn = false)
        // Clear any pending uploads as sign-in failed
        pendingFileToUpload = null
        pendingFileToUploadForConsentRetry = null
    }


    private fun signOut() {
        Log.i(TAG, "Signing out user: $userEmail")
        lifecycleScope.launch {
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
                // Reset all auth-related state
                driveService = null
                userEmail = null
                userIdToken = null
                userDisplayName = null
                updateSignInButtonUI(isSignedIn = false)
                // Clear any pending uploads on sign-out
                pendingFileToUpload = null
                pendingFileToUploadForConsentRetry = null
                Log.i(TAG, "Sign out successful via Credential Manager.")
                Toast.makeText(this@MainActivity, "Signed out successfully", Toast.LENGTH_SHORT).show()
            } catch (e: ClearCredentialException) { // Catch specific exception
                Log.e(TAG, "Sign out failed via Credential Manager", e)
                Toast.makeText(this@MainActivity, "Sign out failed", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { // Catch any other unexpected errors
                Log.e(TAG, "Unexpected error during sign out", e)
                Toast.makeText(this@MainActivity, "Sign out failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateSignInButtonUI(isSignedIn: Boolean) {
        if (isSignedIn) {
            val name = userDisplayName?.substringBefore(" ") ?: userEmail // Show first name or full email
            binding.signInButton.text = "Sign Out (%s)".format(name)
            binding.signInButton.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light)) // Example color
        } else {
            binding.signInButton.text = "Sign In (Drive)"
            binding.signInButton.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_light)) // Example color
        }
    }

    // Initialize Drive Service using explicit Account object
    private suspend fun initializeDriveService(userAccountEmail: String?) { // Made email nullable to align with call sites
        Log.d(TAG, "initializeDriveService - Received email for Drive: '$userAccountEmail'")

        // **Input Validation** (Crucial before creating Account object)
        if (userAccountEmail.isNullOrEmpty() || userAccountEmail.isBlank()) { // Check for null, empty, and also whitespace-only strings
            Log.e(TAG, "initializeDriveService - UserAccountEmail is NULL, EMPTY or BLANK. Aborting Drive init.")
            withContext(Dispatchers.Main) { // Switch to main thread for Toast
                Toast.makeText(this@MainActivity, "Cannot initialize Drive: User email invalid.", Toast.LENGTH_SHORT).show()
            }
            if (driveService != null) driveService = null // Ensure service is null if we abort
            return // Stop further processing
        }

        Log.i(TAG, "initializeDriveService - Attempting to set up Drive for validated email: '$userAccountEmail'")

        val accountToUse: Account // Declare Account variable
        try {
            // **Create Account object directly**
            Log.d(TAG, "initializeDriveService - Attempting to create Account object with name: '$userAccountEmail' and type 'com.google'")
            accountToUse = Account(userAccountEmail, "com.google") // Use "com.google" type for Google accounts
            Log.d(TAG, "initializeDriveService - Successfully created Account object: ${accountToUse.name}, type: ${accountToUse.type}")
        } catch (e: IllegalArgumentException) { // Catch potential errors during Account creation
            Log.e(TAG, "initializeDriveService - FAILED to create Account object directly! Email was: '$userAccountEmail'. Error: $e")
            withContext(Dispatchers.Main) { // Switch to main thread for Toast
                Toast.makeText(this@MainActivity, "Failed to create account for Drive. Please check account details.", Toast.LENGTH_LONG).show()
            }
            driveService = null // Ensure driveService is null on this critical failure
            return // Stop further processing
        }

        // **Create Credential using the Account object**
        val credential = GoogleAccountCredential.usingOAuth2(this, listOf(DriveScopes.DRIVE_FILE))
            .setSelectedAccount(accountToUse) // Use the Account object
        Log.d(TAG, "initializeDriveService - GoogleAccountCredential created and account set.")

        // **Build Drive Service**
        try {
            withContext(Dispatchers.IO) { // Network operation on IO dispatcher
                driveService = Drive.Builder(
                    NetHttpTransport(), // Using NetHttpTransport
                    GsonFactory.getDefaultInstance(),
                    credential // Pass the credential with the validated Account
                )
                    .setApplicationName(getString(R.string.app_name)) // Set your app name
                    .build()
            }
            Log.i(TAG, "Google Drive service initialized successfully for ${accountToUse.name}")

            // Process pending upload (if any) AFTER service is confirmed ready
            // Use Dispatchers.Main to avoid triggering upload from IO thread directly after init
            withContext(Dispatchers.Main) {
                pendingFileToUpload?.let { file ->
                    Log.d(TAG, "Drive service initialized, processing pending upload for ${file.name}")
                    attemptDriveUpload(file) // Use attemptDriveUpload which checks conditions again
                    pendingFileToUpload = null // Clear pending flag once attempted
                }
            }
        } catch (e: Exception) { // Catch errors during Drive service build (e.g., network on IO, auth issues)
            Log.e(TAG, "Drive service initialization failed for ${accountToUse.name}", e)
            driveService = null // Ensure service is null on failure
            withContext(Dispatchers.Main) { // Switch to main thread for Toast
                Toast.makeText(this@MainActivity, "Failed to initialize Google Drive client.", Toast.LENGTH_SHORT).show()
            }
        }
    }


    // Checks if upload is possible and either uploads or prompts sign-in/consent
    private fun attemptDriveUpload(gpxFileToUpload: File) {
        // Validate file first
        if (!gpxFileToUpload.exists() || gpxFileToUpload.length() == 0L) {
            Log.e(TAG, "GPX file is missing or empty, cannot upload: ${gpxFileToUpload.name}")
            Toast.makeText(this, "GPX file missing or empty. Cannot upload.", Toast.LENGTH_SHORT).show()
            return
        }

        // Check if Drive service is ready AND user is considered signed in (email known)
        if (driveService != null && userEmail != null) {
            // If service and email are ready, proceed to actual upload function
            uploadGpxToDrive(gpxFileToUpload)
        } else {
            // User needs to sign in first, or Drive service failed initialization
            Log.w(TAG, "User not signed in or Drive service not ready. Pending upload for ${gpxFileToUpload.name}")
            pendingFileToUpload = gpxFileToUpload // Mark file as pending for after sign-in
            // Show snackbar prompting user to sign in
            runOnUiThread { // Ensure UI operations are on the main thread
                Snackbar.make(binding.root, "Sign in to Google Drive to upload GPX file?", Snackbar.LENGTH_LONG)
                    .setAction("Sign In") { startSignInFlow() }
                    .show()
            }
        }
    }


    // Performs the actual upload to Google Drive (handles consent exception)
    private fun uploadGpxToDrive(localGpxFile: File) {
        val currentDriveService = driveService // Capture instance for coroutine safety

        // Redundant checks (should be guaranteed by attemptDriveUpload), but safe to keep
        if (userEmail.isNullOrEmpty()) { // Check against the activity's userEmail state
            Log.e(TAG, "uploadGpxToDrive: Cannot upload ${localGpxFile.name}: User email is null or empty.")
            runOnUiThread { Toast.makeText(this, "Upload failed: User email missing. Please sign out and sign in again.", Toast.LENGTH_LONG).show() }
            return
        }
        if (currentDriveService == null) {
            Log.e(TAG, "uploadGpxToDrive: Drive service is null. Cannot upload ${localGpxFile.name}")
            Toast.makeText(this, "Drive service not available. Please sign in again.", Toast.LENGTH_SHORT).show()
            return
        }

        Log.i(TAG, "Starting Google Drive upload for: ${localGpxFile.name}")
        runOnUiThread { Toast.makeText(this, "Uploading to Google Drive…", Toast.LENGTH_SHORT).show() }

        lifecycleScope.launch(Dispatchers.IO) { // Use IO dispatcher for network call
            var fis: FileInputStream? = null
            try {
                val fileMetadata = DriveFile().apply {
                    name = localGpxFile.name
                    mimeType = DRIVE_UPLOAD_MIMETYPE
                    // parents = listOf("FOLDER_ID") // Optional: specify parent folder ID if needed
                }
                fis = FileInputStream(localGpxFile) // Open file stream
                val mediaContent = InputStreamContent(DRIVE_UPLOAD_MIMETYPE, fis)
                    .setLength(localGpxFile.length()) // Important for progress and resumable uploads

                Log.d(TAG, "Attempting Drive API call: files().create for ${localGpxFile.name}")
                // Execute the upload request
                val uploadedFile = currentDriveService.files().create(fileMetadata, mediaContent)
                    .setFields("id, name, webViewLink") // Request these fields in the response
                    .execute() // This can throw UserRecoverableAuthIOException or other GoogleJsonErrors

                // Success: Switch back to Main thread for UI updates
                withContext(Dispatchers.Main) {
                    Log.i(TAG, "Drive upload successful: ${uploadedFile.name} (ID: ${uploadedFile.id})")
                    Toast.makeText(this@MainActivity, "GPX file uploaded successfully!", Toast.LENGTH_LONG).show()
                    uploadedFile.webViewLink?.let { Log.d(TAG, "View Link: $it") } // Log view link if available
                    // Clear retry state if this file was the one needing consent
                    if (pendingFileToUploadForConsentRetry == localGpxFile) {
                        pendingFileToUploadForConsentRetry = null
                    }
                }
            } catch (e: UserRecoverableAuthIOException) { // **Handle consent exception specifically**
                Log.w(TAG, "Google Drive UserRecoverableAuthIOException for ${localGpxFile.name}. User consent required.", e)
                pendingFileToUploadForConsentRetry = localGpxFile // Store file to retry after consent
                withContext(Dispatchers.Main) { // Switch to main thread to launch intent
                    try {
                        Log.i(TAG, "Launching consent intent for Google Drive.")
                        requestGoogleDriveConsentLauncher.launch(e.intent) // Launch the intent provided by the exception
                    } catch (activityNotFoundException: ActivityNotFoundException) { // If no app can handle the intent
                        Log.e(TAG, "No activity found to handle Drive consent intent. Google Play Services might be missing or outdated.", activityNotFoundException)
                        Toast.makeText(this@MainActivity, "Could not request Drive permission. Please ensure Google Play Services is up to date.", Toast.LENGTH_LONG).show()
                        pendingFileToUploadForConsentRetry = null // Cannot retry if intent fails
                    } catch (ex: Exception) { // Other exceptions during intent launch
                        Log.e(TAG, "Exception launching consent intent", ex)
                        Toast.makeText(this@MainActivity, "Error requesting Drive permission.", Toast.LENGTH_LONG).show()
                        pendingFileToUploadForConsentRetry = null // Cannot retry if intent fails
                    }
                }
            } catch (e: Exception) { // General catch for other network/API errors
                Log.e(TAG, "Google Drive upload failed for ${localGpxFile.name}", e)
                withContext(Dispatchers.Main) { // Switch to main thread for Toast
                    // Provide more specific error message if possible
                    val errorMsg = if (e is com.google.api.client.googleapis.json.GoogleJsonResponseException) {
                        "Google API Error ${e.statusCode}: ${e.details?.message ?: e.message}"
                    } else {
                        e.localizedMessage ?: "Unknown upload error"
                    }
                    Toast.makeText(this@MainActivity, "Google Drive upload failed: $errorMsg", Toast.LENGTH_LONG).show()
                }
                // Clear retry state on other exceptions
                if (pendingFileToUploadForConsentRetry == localGpxFile) {
                    pendingFileToUploadForConsentRetry = null
                }
            } finally {
                try {
                    fis?.close() // Ensure file stream is closed
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to close FileInputStream for ${localGpxFile.name}", e)
                }
            }
        } // End Coroutine Scope
    }


    // --- Utility Methods ---
    // Checks if Activity Recognition permission is granted (needed for step counter Q+)
    private fun hasActivityPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        } else true // Not needed for older versions, always return true
    }

    // Formats total seconds into HH:MM:SS string
    private fun formatDuration(totalSeconds: Long): String {
        val hours = TimeUnit.SECONDS.toHours(totalSeconds)
        val minutes = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60 // Corrected calculation
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    }

    // ---- START: Camera Feature Functions ----
    private fun getTmpFileUri(): Uri {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        // Ensure directory exists (it should, but good practice)
        if (storageDir != null && !storageDir.exists()){
            storageDir.mkdirs()
        }
        // Use File.createTempFile to ensure unique names and handle temporary nature if needed
        val tmpFile = File.createTempFile("JPEG_${timestamp}_", ".jpg", storageDir).apply {
            Log.d(TAG, "Temp image file created: $absolutePath")
        }
        // Get a content URI using FileProvider
        return FileProvider.getUriForFile(
            applicationContext,
            "${BuildConfig.APPLICATION_ID}.provider", // Authority from manifest
            tmpFile
        )
    }

    private fun launchCamera() {
        Log.d(TAG, "Attempting to launch camera.")
        try {
            latestTmpUri = getTmpFileUri() // Get a new URI each time to avoid overwriting if user cancels and retries
            takePictureLauncher.launch(latestTmpUri!!) // Launch camera with the URI for saving the image
        } catch (e: Exception) { // Catch more general exceptions during file/URI creation
            Log.e(TAG, "Error launching camera or creating temp file", e)
            Toast.makeText(this, "Error preparing camera: ${e.message}", Toast.LENGTH_LONG).show()
            latestTmpUri = null // Reset URI on error
        }
    }

    private fun handleTakePictureClick() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                Log.d(TAG, "Camera permission already granted. Launching camera.")
                launchCamera()
            }
            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA) -> {
                Log.d(TAG, "Showing camera permission rationale.")
                // Explain to the user why the permission is needed
                Snackbar.make(binding.root, "Camera permission is needed to take geo-tagged pictures.", Snackbar.LENGTH_LONG)
                    .setAction("Grant") { requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA) }
                    .show()
            }
            else -> {
                // Directly request the permission
                Log.d(TAG, "Requesting camera permission directly.")
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun processImageWithExif(imageUri: Uri) {
        Log.d(TAG, "Processing image for EXIF data: $imageUri")
        try {
            contentResolver.openFileDescriptor(imageUri, "rw")?.use { pfd -> // "rw" for read-write access
                val exifInterface = ExifInterface(pfd.fileDescriptor)

                // Get current time for EXIF
                val currentTimestamp = System.currentTimeMillis()
                val dateTimeOriginal = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(currentTimestamp))

                exifInterface.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTimeOriginal)
                exifInterface.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateTimeOriginal) // Often same as original
                exifInterface.setAttribute(ExifInterface.TAG_DATETIME, dateTimeOriginal) // Often same as original
                exifInterface.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, SimpleDateFormat("XXX", Locale.US).format(Date(currentTimestamp)))


                // Determine location source for EXIF data
                var locationForExif: Location? = null
                var locationSource = "Unknown"

                if (!simulateTrail && currentBestLocation != null) {
                    locationForExif = currentBestLocation
                    locationSource = "Real GPS"
                } else if (simulateTrail) {
                    if (currentBestLocation != null) { // Prefer real GPS if available, even in sim mode
                        locationForExif = currentBestLocation
                        locationSource = "Real GPS (during sim)"
                    } else if (lastSimulatedGeoPoint != null) { // Fallback to simulated point
                        locationForExif = Location("simulated_provider").apply {
                            latitude = lastSimulatedGeoPoint!!.latitude
                            longitude = lastSimulatedGeoPoint!!.longitude
                            time = System.currentTimeMillis()
                            altitude = currentBestLocation?.altitude ?: 0.0 // Use real altitude if known, else 0
                        }
                        locationSource = "Simulated (Python Alg)" // Updated source description
                    }
                }

                if (locationForExif != null) {
                    exifInterface.setGpsInfo(locationForExif) // Handles multiple GPS tags
                    Log.d(TAG, "Writing EXIF GPS: Lat ${locationForExif.latitude}, Lon ${locationForExif.longitude}, Alt ${locationForExif.altitude} (Source: $locationSource)")
                } else {
                    Log.w(TAG, "No location data available to write to EXIF.")
                }

                // Optionally add app name or other metadata
                exifInterface.setAttribute(ExifInterface.TAG_MAKE, "ExerciseHome App")
                exifInterface.setAttribute(ExifInterface.TAG_MODEL, "Workout Photo")
                exifInterface.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "Location Source: $locationSource, Time: $dateTimeOriginal")

                exifInterface.saveAttributes() // This saves changes to the file via the FileDescriptor
                Log.i(TAG, "EXIF data saved successfully for $imageUri")
                Toast.makeText(this, "Photo saved with location & time!", Toast.LENGTH_LONG).show()

            } ?: Log.e(TAG, "Could not get ParcelFileDescriptor for URI: $imageUri") // Handle case where PFD is null

        } catch (e: Exception) { // Catch specific exceptions like IOException if preferred
            Log.e(TAG, "Error writing EXIF data to $imageUri", e)
            Toast.makeText(this, "Error saving photo metadata: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    // ---- END: Camera Feature Functions ----
}