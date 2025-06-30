package com.example.exercisehome

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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*
import org.osmdroid.util.GeoPoint
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class TrackingService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "TrackingService"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "TrackingServiceChannel"
        private val GPX_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        private const val PREFS_NAME = "ExerciseHomePrefs"
        private const val KEY_GPX_DIR_URI = "gpx_directory_uri"

        private const val STEPS_PER_CALORIE = 25f
        private const val SIMULATION_DIRECTION_VARIABILITY_DEG = 45.0
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

    private var timerJob: Job? = null
    private var gpxFileWriter: FileWriter? = null
    private var gpxOutputStream: OutputStream? = null
    private var gpxFileForProvider: File? = null
    private var gpxFileUri: Uri? = null

    private var currentBearingDegrees = 0.0
    private var lastSimulatedGeoPoint: GeoPoint? = null
    private var strideLengthMeters = 0.7f
    private var stepsSinceLastAdvance = 0
    private var isCurrentlyPaused = true

    private val prefs: SharedPreferences by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    inner class LocalBinder : Binder() { fun getService(): TrackingService = this@TrackingService }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    fun startTracking(startPoint: GeoPoint, simulate: Boolean) {
        if (isTracking.value == true) return

        strideLengthMeters = prefs.getFloat(SettingsActivity.KEY_STRIDE_LENGTH, 0.7f)
        Log.d(TAG, "Starting workout with stride length: $strideLengthMeters m")

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

        lastSimulatedGeoPoint = startPoint
        currentBearingDegrees = Random.nextDouble() * 360.0

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
                    distanceMeters.postValue(newDistance.toDouble())

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

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startListenersAndTimer() {
        registerStepSensor()
        startTimer()
    }

    private fun stopListenersAndTimer() {
        timerJob?.cancel()
        unregisterStepSensor()
    }

    private fun advanceSimulationForBatch() {
        val lastPoint = lastSimulatedGeoPoint ?: return

        val angleChange = (Random.nextDouble() * SIMULATION_DIRECTION_VARIABILITY_DEG) - (SIMULATION_DIRECTION_VARIABILITY_DEG / 2.0)
        currentBearingDegrees = (currentBearingDegrees + angleChange + 360) % 360

        val distanceForBatch = (STEP_BATCH_SIZE * strideLengthMeters).toDouble()

        // **THE CRITICAL FIX**: Replaced the faulty library function with a standard, reliable formula.
        val newPoint = calculateDestinationPoint(lastPoint, distanceForBatch, currentBearingDegrees)

        addPointToTrack(newPoint)
        lastSimulatedGeoPoint = newPoint
    }

    // **NEW FUNCTION**: A reliable implementation of the Haversine formula to find a destination point.
    private fun calculateDestinationPoint(startPoint: GeoPoint, distanceMeters: Double, bearingDegrees: Double): GeoPoint {
        val earthRadiusMeters = 6371000.0

        val lat1Rad = Math.toRadians(startPoint.latitude)
        val lon1Rad = Math.toRadians(startPoint.longitude)
        val bearingRad = Math.toRadians(bearingDegrees)

        val lat2Rad = asin(sin(lat1Rad) * cos(distanceMeters / earthRadiusMeters) +
                cos(lat1Rad) * sin(distanceMeters / earthRadiusMeters) * cos(bearingRad))

        var lon2Rad = lon1Rad + atan2(sin(bearingRad) * sin(distanceMeters / earthRadiusMeters) * cos(lat1Rad),
            cos(distanceMeters / earthRadiusMeters) - sin(lat1Rad) * sin(lat2Rad))

        // Normalize longitude to -180 to +180
        lon2Rad = (lon2Rad + 3 * Math.PI) % (2 * Math.PI) - Math.PI

        return GeoPoint(Math.toDegrees(lat2Rad), Math.toDegrees(lon2Rad))
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
