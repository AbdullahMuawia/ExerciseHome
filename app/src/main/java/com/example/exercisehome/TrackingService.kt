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


        private const val STEPS_PER_CALORIE = 25f

        private const val DEFAULT_STRIDE_LENGTH_METERS = 0.7
        private const val SIMULATION_DIRECTION_VARIABILITY_RAD = Math.PI / 8.0
        private const val METERS_PER_DEGREE_LATITUDE = 111320.0
        private const val STEP_BATCH_SIZE = 10
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    val isTracking = MutableLiveData(false)
    val isPaused = MutableLiveData(true)
    val stepCount = MutableLiveData(0)
    val elapsedTimeSeconds = MutableLiveData(0L)
    val caloriesBurned = MutableLiveData(0.0)
    val distanceMeters = MutableLiveData(0.0)
    val currentTrack = MutableLiveData<List<GeoPoint>>(emptyList())
    val lastGpxFileUri = MutableLiveData<Uri?>(null)

    private lateinit var sensorManager: SensorManager
    private var stepCounterSensor: Sensor? = null
    private var isSensorRegistered = false
    private var initialStepCount: Int? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationRequest: LocationRequest

    private var timerJob: Job? = null
    private var gpxFileWriter: FileWriter? = null
    private var gpxOutputStream: OutputStream? = null
    private var gpxFileForProvider: File? = null
    private var gpxFileUri: Uri? = null
    private var isSimulating = false

    private var currentSimulatedDirectionRad = 0.0
    private var lastSimulatedGeoPoint: GeoPoint? = null
    private var strideLengthMeters = DEFAULT_STRIDE_LENGTH_METERS
    private var stepsSinceLastAdvance = 0

    private var isCurrentlyPaused = true

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
        isTracking.postValue(true)
        isPaused.postValue(false)
        isCurrentlyPaused = false

        stepCount.postValue(0)
        elapsedTimeSeconds.postValue(0L)
        caloriesBurned.postValue(0.0)
        distanceMeters.postValue(0.0)
        currentTrack.postValue(listOf(startPoint))
        lastGpxFileUri.postValue(null)
        initialStepCount = null
        stepsSinceLastAdvance = 0

        if (isSimulating) {
            lastSimulatedGeoPoint = startPoint
            currentSimulatedDirectionRad = Random.nextDouble() * 2 * Math.PI
        }

        if (initializeGpxFile()) {
            addPointToGpx(startPoint, System.currentTimeMillis())
            startForeground(NOTIFICATION_ID, createNotification())
            startListenersAndTimer()
        }
    }

    fun stopTracking() {
        if (isTracking.value == false) return
        isTracking.postValue(false)
        isPaused.postValue(true)
        isCurrentlyPaused = true
        stopListenersAndTimer()
        closeGpxFile()
    }

    fun pauseTracking() {
        if (isTracking.value != true || isPaused.value == true) return
        isPaused.postValue(true)
        isCurrentlyPaused = true
        stopListenersAndTimer()
    }

    fun resumeTracking() {
        if (isTracking.value != true || isPaused.value == false) return
        isPaused.postValue(false)
        isCurrentlyPaused = false
        startListenersAndTimer()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (isTracking.value != true || isCurrentlyPaused) return
        event?.let {
            if (it.sensor.type == Sensor.TYPE_STEP_COUNTER) {
                if (initialStepCount == null) {
                    initialStepCount = it.values[0].toInt()
                    return@let
                }

                val currentSensorValue = it.values[0].toInt()
                val totalSteps = currentSensorValue - initialStepCount!!

                if (totalSteps > (stepCount.value ?: 0)) {
                    val stepsTakenNow = totalSteps - (stepCount.value ?: 0)
                    stepCount.postValue(totalSteps)


                    val newCalories = totalSteps / STEPS_PER_CALORIE
                    caloriesBurned.postValue(newCalories.toDouble())

                    val newDistance = totalSteps * strideLengthMeters
                    distanceMeters.postValue(newDistance)

                    if(isSimulating) {
                        stepsSinceLastAdvance += stepsTakenNow
                        if (stepsSinceLastAdvance >= STEP_BATCH_SIZE) {
                            val batchesToProcess = stepsSinceLastAdvance / STEP_BATCH_SIZE
                            for (i in 1..batchesToProcess) {
                                advanceSimulationForBatch()
                            }
                            stepsSinceLastAdvance %= STEP_BATCH_SIZE
                        }
                    }
                }
            }
        }
    }


    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startListenersAndTimer() {
        registerStepSensor()
        startTimer()
        if (!isSimulating) {
            startLocationUpdates()
        }
    }

    private fun stopListenersAndTimer() {
        timerJob?.cancel()
        unregisterStepSensor()
        if (!isSimulating) {
            stopLocationUpdates()
        }
    }

    private fun advanceSimulationForBatch() {
        val lastPoint = lastSimulatedGeoPoint ?: return

        val angleChange = (Random.nextDouble() * SIMULATION_DIRECTION_VARIABILITY_RAD) - (SIMULATION_DIRECTION_VARIABILITY_RAD / 2.0)
        currentSimulatedDirectionRad = (currentSimulatedDirectionRad + angleChange).mod(2 * Math.PI)

        val distanceForBatch = STEP_BATCH_SIZE * strideLengthMeters

        val stepDistanceInDegreesLat = distanceForBatch / METERS_PER_DEGREE_LATITUDE
        val stepDistanceInDegreesLon = distanceForBatch / (METERS_PER_DEGREE_LATITUDE * cos(Math.toRadians(lastPoint.latitude)))

        val newLat = lastPoint.latitude + sin(currentSimulatedDirectionRad) * stepDistanceInDegreesLat
        val newLon = lastPoint.longitude + cos(currentSimulatedDirectionRad) * stepDistanceInDegreesLon

        if (newLat in -90.0..90.0 && newLon in -180.0..180.0) {
            val newPoint = GeoPoint(newLat, newLon)
            addPointToTrack(newPoint)
            lastSimulatedGeoPoint = newPoint
        }
    }

    private fun createLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                if (!isCurrentlyPaused && !isSimulating) {
                    locationResult.lastLocation?.let {
                        addPointToTrack(GeoPoint(it.latitude, it.longitude))
                    }
                }
            }
        }
    }

    private fun addPointToTrack(point: GeoPoint) {
        val oldTrack = currentTrack.value.orEmpty()
        val newTrack = oldTrack + point
        currentTrack.postValue(newTrack)
        addPointToGpx(point, System.currentTimeMillis())
    }


    private fun startTimer() {
        if (timerJob?.isActive == true) return
        timerJob = serviceScope.launch {
            while (isActive) {
                delay(1000L)
                elapsedTimeSeconds.postValue((elapsedTimeSeconds.value ?: 0L) + 1)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to request location updates.", e)
        }
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
