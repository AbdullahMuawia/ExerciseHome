package com.example.exercisehome

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import org.osmdroid.util.GeoPoint
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class TrackingService : Service(), SensorEventListener {

    enum class TrackFollowingStatus { NOT_FOLLOWING, FOLLOWING, FINISHED }

    companion object {
        private const val TAG = "TrackingService"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "TrackingServiceChannel"
        private const val LOCATION_UPDATE_INTERVAL_MS = 4000L
        private const val FASTEST_LOCATION_UPDATE_INTERVAL_MS = 2000L
        private val GPX_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        private const val PREFS_NAME = "ExerciseHomePrefs"
        private const val KEY_GPX_DIR_URI = "gpx_directory_uri"
        private const val SIMULATION_POINT_INTERVAL_MS = 3000L
        private const val SIMULATION_DIRECTION_VARIABILITY_RAD = Math.PI / 7.0
        private const val SIMULATION_SCALE_DEGREES = 0.0001
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    // LiveData for the UI
    val isTracking = MutableLiveData(false)
    val isPaused = MutableLiveData(true)
    val stepCount = MutableLiveData(0)
    val elapsedTimeSeconds = MutableLiveData(0L)
    val currentTrack = MutableLiveData<List<GeoPoint>>(emptyList())
    val lastGpxFileUri = MutableLiveData<Uri?>(null)


    // Service components and state
    private lateinit var sensorManager: SensorManager
    private var stepCounterSensor: Sensor? = null
    private var isSensorRegistered = false
    private var initialStepCount: Int? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationRequest: LocationRequest

    private var timerJob: Job? = null
    private var simulationJob: Job? = null
    private var gpxFileWriter: FileWriter? = null
    private var gpxOutputStream: OutputStream? = null
    private var gpxFileForProvider: File? = null
    private var gpxFileUri: Uri? = null
    private var currentSimulatedDirectionRad = 0.0
    private var lastSimulatedGeoPoint: GeoPoint? = null
    private var isSimulating = false


    private val prefs: SharedPreferences by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    inner class LocalBinder : Binder() { fun getService(): TrackingService = this@TrackingService }
    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createLocationCallback()
        createLocationRequest()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    fun startTracking(startPoint: GeoPoint, simulate: Boolean) {
        if (isTracking.value == true) return

        isSimulating = simulate
        isPaused.postValue(false)
        stepCount.postValue(0)
        elapsedTimeSeconds.postValue(0L)
        currentTrack.postValue(listOf(startPoint))
        lastGpxFileUri.postValue(null)
        initialStepCount = null

        if (initializeGpxFile()) {
            addPointToGpx(startPoint, System.currentTimeMillis())
            isTracking.postValue(true)
            startForeground(NOTIFICATION_ID, createNotification())
            // Directly start all processes. This is the correct logic.
            registerStepSensor()
            startTimer()
            if (isSimulating) {
                startSimulation()
            } else {
                startLocationUpdates()
            }
        }
    }

    fun stopTracking() {
        if (isTracking.value == false) return
        isTracking.postValue(false)
        isPaused.postValue(false) // Reset paused state
        timerJob?.cancel()
        simulationJob?.cancel()
        unregisterStepSensor()
        stopLocationUpdates()
        closeGpxFile()
        stopForeground(true)
    }

    fun pauseTracking() {
        if (isTracking.value != true || isPaused.value == true) return
        isPaused.postValue(true)
        timerJob?.cancel()
        simulationJob?.cancel()
        unregisterStepSensor()
        stopLocationUpdates()
    }

    fun resumeTracking() {
        if (isTracking.value != true || isPaused.value == false) return
        isPaused.postValue(false)
        registerStepSensor()
        startTimer()
        if (isSimulating) {
            startSimulation()
        } else {
            startLocationUpdates()
        }
    }

    // --- THIS IS THE MAIN FIX ---
    override fun onSensorChanged(event: SensorEvent?) {
        // We remove the `isPaused` check. If this listener is active, it should process data.
        if (isTracking.value != true) return

        event?.let {
            if (it.sensor.type == Sensor.TYPE_STEP_COUNTER) {
                val currentSensorValue = it.values[0].toInt()
                if (initialStepCount == null) { initialStepCount = currentSensorValue }
                val steps = currentSensorValue - (initialStepCount ?: 0)
                stepCount.postValue(steps)
            }
        }
    }

    // --- THIS IS THE OTHER MAIN FIX ---
    private fun createLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                // We remove the `isPaused` check here as well.
                // Pausing is now handled by stopping and restarting location updates.
                if (isTracking.value == true) {
                    locationResult.lastLocation?.let { location ->
                        val currentPoint = GeoPoint(location.latitude, location.longitude)
                        addPointToTrack(currentPoint)
                    }
                }
            }
        }
    }

    // --- The rest of the functions are correct and remain the same ---

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startTimer() {
        if (timerJob?.isActive == true) return
        timerJob = serviceScope.launch {
            while (isActive) {
                delay(1000L)
                elapsedTimeSeconds.postValue((elapsedTimeSeconds.value ?: 0L) + 1)
            }
        }
    }

    private fun startSimulation() {
        if (simulationJob?.isActive == true) return
        lastSimulatedGeoPoint = currentTrack.value?.lastOrNull() ?: return
        simulationJob = serviceScope.launch {
            while (isActive) {
                val lastPoint = lastSimulatedGeoPoint ?: break

                val angleChange = (Random.nextDouble() * SIMULATION_DIRECTION_VARIABILITY_RAD) - (SIMULATION_DIRECTION_VARIABILITY_RAD / 2.0)
                currentSimulatedDirectionRad = (currentSimulatedDirectionRad + angleChange).mod(2 * Math.PI)

                val newLat = lastPoint.latitude + cos(currentSimulatedDirectionRad) * SIMULATION_SCALE_DEGREES
                val newLon = lastPoint.longitude + sin(currentSimulatedDirectionRad) * SIMULATION_SCALE_DEGREES

                if (newLat in -90.0..90.0 && newLon in -180.0..180.0) {
                    val newPoint = GeoPoint(newLat, newLon)
                    addPointToTrack(newPoint)
                    lastSimulatedGeoPoint = newPoint
                }
                delay(SIMULATION_POINT_INTERVAL_MS)
            }
        }
    }

    private fun addPointToTrack(point: GeoPoint) {
        val newTrack = currentTrack.value.orEmpty() + point
        currentTrack.postValue(newTrack)
        addPointToGpx(point, System.currentTimeMillis())
    }

    private fun closeGpxFile() {
        val footer = "</trkseg></trk></gpx>"
        var finalUri: Uri? = null
        try {
            gpxOutputStream?.use {
                it.write(footer.toByteArray())
                finalUri = gpxFileUri
            }
            gpxFileWriter?.use {
                it.append(footer)
                gpxFileForProvider?.let { file ->
                    // For files in the private directory, we need a FileProvider URI to share them
                    finalUri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.provider", file)
                }
            }
            // Notify the activity that the file is ready for upload
            lastGpxFileUri.postValue(finalUri)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to close GPX file", e)
        } finally {
            // Reset all file-related properties
            gpxOutputStream = null
            gpxFileWriter = null
            gpxFileForProvider = null
            gpxFileUri = null
        }
    }

    private fun initializeGpxFile(): Boolean {
        val gpxDirUriStr = prefs.getString(KEY_GPX_DIR_URI, null)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "Workout_$timestamp.gpx"
        val header = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\" ?><gpx xmlns=\"http://www.topografix.com/GPX/1/1\" version=\"1.1\" creator=\"ExerciseHome App\"><trk><name>Exercise Track</name><trkseg>\n"

        return try {
            // If the user has chosen a custom directory, use it
            if (gpxDirUriStr != null) {
                val dirUri = Uri.parse(gpxDirUriStr)
                val directory = DocumentFile.fromTreeUri(this, dirUri)
                val newFile = directory?.createFile("application/gpx+xml", filename)

                gpxFileUri = newFile?.uri
                gpxOutputStream = gpxFileUri?.let { contentResolver.openOutputStream(it, "w") }
                gpxOutputStream?.write(header.toByteArray())
                Log.i(TAG, "GPX file will be saved to chosen directory: $gpxFileUri")
            } else {
                // Otherwise, fall back to the app's private directory
                val externalDir = getExternalFilesDir(null)
                gpxFileForProvider = File(externalDir, filename)
                gpxFileWriter = FileWriter(gpxFileForProvider)
                gpxFileWriter?.append(header)
                Log.i(TAG, "GPX file initialized in default directory: ${gpxFileForProvider?.absolutePath}")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize GPX file", e)
            Toast.makeText(this, "Failed to create GPX file. Check storage permissions.", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun addPointToGpx(geoPoint: GeoPoint, timestampMs: Long) {
        val formattedTime = GPX_DATE_FORMAT.format(Date(timestampMs))
        val trackPoint = "<trkpt lat=\"${geoPoint.latitude}\" lon=\"${geoPoint.longitude}\"><time>$formattedTime</time></trkpt>\n"
        try {
            // Write to the output stream if it exists, otherwise use the file writer
            gpxOutputStream?.write(trackPoint.toByteArray()) ?: gpxFileWriter?.append(trackPoint)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write to GPX file", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun registerStepSensor() {
        if (!isSensorRegistered && stepCounterSensor != null) {
            sensorManager.registerListener(this, stepCounterSensor, SensorManager.SENSOR_DELAY_UI)
            isSensorRegistered = true
        }
    }

    private fun unregisterStepSensor() {
        if (isSensorRegistered) {
            sensorManager.unregisterListener(this)
            isSensorRegistered = false
        }
    }

    private fun createLocationRequest() {
        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_UPDATE_INTERVAL_MS)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(FASTEST_LOCATION_UPDATE_INTERVAL_MS)
            .build()
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Tracking Service", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Tracking Workout")
            .setContentText("Your location and steps are being recorded.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (isTracking.value == true) {
            stopTracking()
        }
    }
}