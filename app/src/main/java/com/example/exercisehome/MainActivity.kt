package com.example.exercisehome

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.example.exercisehome.databinding.ActivityMainBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.snackbar.Snackbar
import org.osmdroid.api.IMapController
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val MAP_DEFAULT_ZOOM = 16.0
        private const val PREFS_NAME = "ExerciseHomePrefs"
        private const val KEY_LAST_LAT = "last_lat"
        private const val KEY_LAST_LON = "last_lon"
        private const val KEY_GPX_DIR_URI = "gpx_directory_uri"
    }

    private lateinit var binding: ActivityMainBinding
    private var trackingService: TrackingService? = null
    private var isBound = false
    private var userSelectedStartPoint: GeoPoint? = null
    private val simulateTrail = true

    private lateinit var mapController: IMapController
    private val pathPolyline = Polyline().apply { outlinePaint.color = Color.BLUE; outlinePaint.strokeWidth = 8f }
    private var startMarker: Marker? = null

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val prefs: SharedPreferences by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private var latestTmpUri: Uri? = null
    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { isSuccess ->
        if (isSuccess) {
            latestTmpUri?.let { uri -> processImageWithExif(uri) }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.any { !it }) {
            showPermissionDeniedSnackbar()
        }
    }

    private val locationPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let {
                val lat = it.getDoubleExtra("lat", 0.0)
                val lon = it.getDoubleExtra("lon", 0.0)
                userSelectedStartPoint = GeoPoint(lat, lon)
                setStartingLocationOnMap(userSelectedStartPoint!!)
                binding.startButton.isEnabled = true
            }
        }
    }

    private val selectGpxDirectoryLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            saveGpxDirectory(it)
            Snackbar.make(binding.root, "GPX files will be saved to this directory.", Snackbar.LENGTH_LONG).show()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            trackingService = (service as TrackingService.LocalBinder).getService()
            isBound = true
            subscribeToServiceObservers()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            trackingService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        checkAndRequestPermissions()
        setupMap()
        setupButtonClickListeners()
        restoreSavedState()

        Intent(this, TrackingService::class.java).also { bindService(it, serviceConnection, Context.BIND_AUTO_CREATE) }
    }

    private fun subscribeToServiceObservers() {
        trackingService?.isTracking?.observe(this) { isTracking ->
            updateTrackingUI(isTracking, trackingService?.isPaused?.value ?: false)
        }
        trackingService?.isPaused?.observe(this) { isPaused ->
            updateTrackingUI(trackingService?.isTracking?.value ?: false, isPaused)
        }
        trackingService?.elapsedTimeSeconds?.observe(this) { binding.timerTextView.text = formatDuration(it) }
        trackingService?.stepCount?.observe(this) { binding.stepCountTextView.text = "Steps: $it" }
        trackingService?.caloriesBurned?.observe(this) { calories ->
            binding.caloriesBurnedTextView.text = "Calories: ${calories.toInt()}"
        }
        trackingService?.currentTrack?.observe(this) { updateMapTrack(it) }
        trackingService?.lastGpxFileUri?.observe(this) { uri ->
            uri?.let {
                Snackbar.make(binding.root, "Track saved successfully!", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun setupButtonClickListeners() {
        binding.startButton.setOnClickListener {
            userSelectedStartPoint?.let {
                saveLastLocation(it)
                val serviceIntent = Intent(this, TrackingService::class.java)
                ContextCompat.startForegroundService(this, serviceIntent)
                trackingService?.startTracking(it, simulateTrail)
            } ?: Toast.makeText(this, "Please set a start location", Toast.LENGTH_SHORT).show()
        }

        binding.pauseButton.setOnClickListener {
            if (trackingService?.isPaused?.value == true) {
                trackingService?.resumeTracking()
            } else {
                trackingService?.pauseTracking()
            }
        }

        binding.resetButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Finish Workout")
                .setMessage("This will stop the current workout and save the GPX file.")
                .setPositiveButton("Finish") { _, _ ->
                    trackingService?.stopTracking()
                    pathPolyline.setPoints(emptyList())
                    startMarker?.let { marker -> binding.mapView.overlays.remove(marker) }
                    startMarker = null
                    binding.mapView.invalidate()
                    binding.timerTextView.text = formatDuration(0L)
                    binding.stepCountTextView.text = "Steps: 0"
                    binding.caloriesBurnedTextView.text = "Calories: 0"
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.takePictureButton.setOnClickListener { handleTakePictureClick() }
        binding.currentLocationButton.setOnClickListener { useCurrentLocationAsStart() }
        binding.customLocationButton.setOnClickListener {
            locationPickerLauncher.launch(Intent(this, LocationPickerActivity::class.java))
        }
    }

    override fun onResume() { super.onResume(); binding.mapView.onResume() }
    override fun onPause() { super.onPause(); binding.mapView.onPause() }
    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun handleTakePictureClick() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isEmpty()) {
            launchCamera()
        } else {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun launchCamera() {
        latestTmpUri = getTmpFileUri()
        latestTmpUri?.let { takePictureLauncher.launch(it) }
    }

    private fun getTmpFileUri(): Uri? {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "JPEG_${timestamp}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ExerciseHome")
            }
        }
        return contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
    }

    private fun processImageWithExif(imageUri: Uri) {
        val latestGeoPoint = trackingService?.currentTrack?.value?.lastOrNull()
        if (latestGeoPoint == null) {
            Toast.makeText(this, "No location available to tag photo.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            contentResolver.openFileDescriptor(imageUri, "rw")?.use { pfd ->
                val exifInterface = ExifInterface(pfd.fileDescriptor)
                val location = Location("").apply {
                    latitude = latestGeoPoint.latitude
                    longitude = latestGeoPoint.longitude
                }
                exifInterface.setGpsInfo(location)
                exifInterface.saveAttributes()
                Toast.makeText(this, "Photo saved with location!", Toast.LENGTH_LONG).show()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error writing EXIF data", e)
        }
    }

    private fun saveLastLocation(geoPoint: GeoPoint) {
        prefs.edit().putFloat(KEY_LAST_LAT, geoPoint.latitude.toFloat()).putFloat(KEY_LAST_LON, geoPoint.longitude.toFloat()).apply()
    }

    private fun loadLastLocation(): GeoPoint? {
        val lat = prefs.getFloat(KEY_LAST_LAT, 0f)
        val lon = prefs.getFloat(KEY_LAST_LON, 0f)
        return if (lat != 0f && lon != 0f) GeoPoint(lat.toDouble(), lon.toDouble()) else null
    }

    private fun saveGpxDirectory(uri: Uri) {
        prefs.edit().putString(KEY_GPX_DIR_URI, uri.toString()).apply()
    }

    private fun restoreSavedState() {
        loadLastLocation()?.let {
            userSelectedStartPoint = it
            setStartingLocationOnMap(it)
            binding.startButton.isEnabled = true
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_set_gpx_directory -> {
                selectGpxDirectoryLauncher.launch(null)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun formatDuration(totalSeconds: Long): String {
        val hours = TimeUnit.SECONDS.toHours(totalSeconds)
        val minutes = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun updateTrackingUI(isTracking: Boolean, isPaused: Boolean) {
        binding.startButton.isEnabled = !isTracking
        binding.pauseButton.isEnabled = isTracking
        binding.takePictureButton.isEnabled = isTracking && !isPaused
        binding.resetButton.isEnabled = isTracking || pathPolyline.points.isNotEmpty()

        if (isPaused) {
            binding.pauseButton.text = "Resume"
        } else {
            binding.pauseButton.text = "Pause"
        }
    }

    private fun updateMapTrack(trackPoints: List<GeoPoint>) {
        pathPolyline.setPoints(trackPoints)
        if (trackPoints.isNotEmpty()) {
            val lastPoint = trackPoints.last()
            mapController.animateTo(lastPoint)
        }
        binding.mapView.invalidate()
    }

    private fun setupMap() {
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        mapController = binding.mapView.controller
        mapController.setZoom(MAP_DEFAULT_ZOOM)
        binding.mapView.overlays.add(pathPolyline)
    }

    private fun setStartingLocationOnMap(geoPoint: GeoPoint) {
        mapController.animateTo(geoPoint)
        startMarker?.let { binding.mapView.overlays.remove(it) }
        startMarker = Marker(binding.mapView).apply {
            position = geoPoint
            title = "Start Point"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        binding.mapView.overlays.add(startMarker)
        binding.mapView.invalidate()
    }

    @SuppressLint("MissingPermission")
    private fun useCurrentLocationAsStart() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location permission not granted", Toast.LENGTH_SHORT).show()
            return
        }
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    val geoPoint = GeoPoint(location.latitude, location.longitude)
                    userSelectedStartPoint = geoPoint
                    setStartingLocationOnMap(geoPoint)
                    binding.startButton.isEnabled = true
                } else {
                    Toast.makeText(this, "Could not get current location", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun showPermissionDeniedSnackbar() {
        Snackbar.make(binding.root, "Required permissions are needed for the app to function.", Snackbar.LENGTH_INDEFINITE)
            .setAction("Settings") {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }.show()
    }
}