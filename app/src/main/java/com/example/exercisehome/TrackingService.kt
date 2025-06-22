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
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
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
        private const val MET_WALKING = 3.5
        private const val AVERAGE_BODY_WEIGHT_KG = 70.0
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    // LiveData for UI updates
    val isTracking = MutableLiveData(false)
    val isPaused = MutableLiveData(true)
    val stepCount = MutableLiveData(0)
    val elapsedTimeSeconds = MutableLiveData(0L)
    val caloriesBurned = MutableLiveData(0.0)
    val currentTrack = MutableLiveData<List<GeoPoint>>(emptyList())
    val lastGpxFileUri = MutableLiveData<Uri?>(null)

    // Internal state variables for reliable service logic
    private var mIsTracking = false
    private var mIsPaused = true

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
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createLocationCallback()
        createLocationRequest()
        Log.d(TAG, "Service Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    fun startTracking(startPoint: GeoPoint, simulate: Boolean) {
        if (mIsTracking) return
        Log.d(TAG, "startTracking called")

        isSimulating = simulate
        updateState(isTracking = true, isPaused = false)

        // Reset all tracking data
        stepCount.postValue(0)
        elapsedTimeSeconds.postValue(0L)
        caloriesBurned.postValue(0.0)
        currentTrack.postValue(listOf(startPoint))
        lastGpxFileUri.postValue(null)
        initialStepCount = null

        if (initializeGpxFile()) {
            addPointToGpx(startPoint, System.currentTimeMillis())
            startForeground(NOTIFICATION_ID, createNotification())
            startListenersAndTimer()
        }
    }

    fun stopTracking() {
        if (!mIsTracking) return
        Log.d(TAG, "stopTracking called")
        updateState(isTracking = false, isPaused = true)
        stopListenersAndTimer()
        closeGpxFile()
        currentTrack.postValue(emptyList())
        stopForeground(true)
    }

    fun pauseTracking() {
        if (!mIsTracking || mIsPaused) return
        Log.d(TAG, "pauseTracking called")
        updateState(isTracking = true, isPaused = true)
        stopListenersAndTimer()
    }

    fun resumeTracking() {
        if (!mIsTracking || !mIsPaused) return
        Log.d(TAG, "resumeTracking called")
        updateState(isTracking = true, isPaused = false)
        startListenersAndTimer()
    }

    private fun updateState(isTracking: Boolean, isPaused: Boolean) {
        this.mIsTracking = isTracking
        this.mIsPaused = isPaused
        this.isTracking.postValue(isTracking)
        this.isPaused.postValue(isPaused)
        Log.d(TAG, "State Updated: mIsTracking=${this.mIsTracking}, mIsPaused=${this.mIsPaused}")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!mIsTracking || mIsPaused) return
        event?.let {
            if (it.sensor.type == Sensor.TYPE_STEP_COUNTER) {
                val currentSensorValue = it.values[0].toInt()
                if (initialStepCount == null) { initialStepCount = currentSensorValue }
                val steps = currentSensorValue - (initialStepCount ?: 0)
                stepCount.postValue(steps)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startListenersAndTimer() {
        Log.d(TAG, "startListenersAndTimer called")
        // No sensor implementation yet
        startTimer()
        if (isSimulating) {
            startSimulation()
        } else {
            startLocationUpdates()
        }
    }

    private fun stopListenersAndTimer() {
        Log.d(TAG, "stopListenersAndTimer called")
        timerJob?.cancel()
        simulationJob?.cancel()
        // No sensor implementation yet
        stopLocationUpdates()
    }

    private fun createLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                Log.d(TAG, "onLocationResult received. State: mIsTracking=$mIsTracking, mIsPaused=$mIsPaused")
                if (mIsTracking && !mIsPaused) {
                    locationResult.lastLocation?.let {
                        Log.d(TAG, "Adding point to track: ${it.latitude}, ${it.longitude}")
                        addPointToTrack(GeoPoint(it.latitude, it.longitude))
                    }
                } else {
                    Log.w(TAG, "Location received but ignored due to state.")
                }
            }
        }
    }

    private fun addPointToTrack(point: GeoPoint) {
        val newTrack = currentTrack.value.orEmpty() + point
        currentTrack.postValue(newTrack)
        addPointToGpx(point, System.currentTimeMillis())
    }

    private fun startTimer() {
        if (timerJob?.isActive == true) return
        timerJob = serviceScope.launch {
            while (isActive) {
                delay(1000L)
                elapsedTimeSeconds.postValue((elapsedTimeSeconds.value ?: 0L) + 1)
                val caloriesPerSecond = (MET_WALKING * AVERAGE_BODY_WEIGHT_KG * 3.5) / 200 / 60
                val newTotalCalories = (caloriesBurned.value ?: 0.0) + caloriesPerSecond
                caloriesBurned.postValue(newTotalCalories)
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

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        Log.d(TAG, "Requesting location updates...")
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        Log.d(TAG, "Stopping location updates.")
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun createLocationRequest() {
        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_UPDATE_INTERVAL_MS)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(FASTEST_LOCATION_UPDATE_INTERVAL_MS)
            .build()
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
                    finalUri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.provider", file)
                }
            }
            lastGpxFileUri.postValue(finalUri)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to close GPX file", e)
        } finally {
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
            if (gpxDirUriStr != null) {
                val dirUri = Uri.parse(gpxDirUriStr)
                val dir = DocumentFile.fromTreeUri(this, dirUri)
                val newFile = dir?.createFile("application/gpx+xml", filename)
                gpxFileUri = newFile?.uri
                gpxOutputStream = gpxFileUri?.let { contentResolver.openOutputStream(it, "w") }
                gpxOutputStream?.write(header.toByteArray())
            } else {
                val externalDir = getExternalFilesDir(null)
                gpxFileForProvider = File(externalDir, filename)
                gpxFileWriter = FileWriter(gpxFileForProvider)
                gpxFileWriter?.append(header)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize GPX file", e)
            false
        }
    }

    private fun addPointToGpx(geoPoint: GeoPoint, timestampMs: Long) {
        val formattedTime = GPX_DATE_FORMAT.format(Date(timestampMs))
        val trackPoint = "<trkpt lat=\"${geoPoint.latitude}\" lon=\"${geoPoint.longitude}\"><time>$formattedTime</time></trkpt>\n"
        try {
            gpxOutputStream?.write(trackPoint.toByteArray()) ?: gpxFileWriter?.append(trackPoint)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write to GPX file", e)
        }
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
        Log.d(TAG, "Service Destroyed")
        serviceScope.cancel()
        if (mIsTracking) {
            stopTracking()
        }
    }
}