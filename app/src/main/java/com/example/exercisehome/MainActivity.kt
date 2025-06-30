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
import android.view.View
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

    private enum class UiState {
        CHOOSING_LOCATION,
        LOCATION_SELECTED,
        TRACKING,
        PAUSED
    }

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
    private val pathPolyline = Polyline().apply { outlinePaint.color = Color.CYAN; outlinePaint.strokeWidth = 10f }
    private var startMarker: Marker? = null

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val prefs: SharedPreferences by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private var takePictureMenuItem: MenuItem? = null

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
                updateUiForState(UiState.LOCATION_SELECTED)
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

        setSupportActionBar(binding.toolbar)

        Configuration.getInstance().load(applicationContext, getPreferences(Context.MODE_PRIVATE))
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        checkAndRequestPermissions()
        setupMap()
        setupButtonClickListeners()
        restoreSavedState()
        updateUiForState(UiState.CHOOSING_LOCATION)

        Intent(this, TrackingService::class.java).also { bindService(it, serviceConnection, Context.BIND_AUTO_CREATE) }
    }

    private fun subscribeToServiceObservers() {
        trackingService?.isTracking?.observe(this) { isTracking ->
            if (isTracking) {
                updateUiForState(if (trackingService?.isPaused?.value == true) UiState.PAUSED else UiState.TRACKING)
            } else {
                if (userSelectedStartPoint != null) {
                    updateUiForState(UiState.LOCATION_SELECTED)
                } else {
                    updateUiForState(UiState.CHOOSING_LOCATION)
                }
            }
        }
        trackingService?.isPaused?.observe(this) { isPaused ->
            if (trackingService?.isTracking?.value == true) {
                updateUiForState(if (isPaused) UiState.PAUSED else UiState.TRACKING)
            }
        }
        trackingService?.elapsedTimeSeconds?.observe(this) { binding.timerTextView.text = "Time: ${formatDuration(it)}" }

        trackingService?.stepCount?.observe(this) { steps ->
            binding.stepCountTextView.text = "Steps: $steps"
            if (trackingService?.currentTrack?.value?.size ?: 0 > 1) {
                takePictureMenuItem?.isEnabled = true
            }
        }

        trackingService?.distanceMeters?.observe(this) { distance ->
            binding.distanceTextView.text = "Dist: %.2f km".format(distance / 1000)
        }

        trackingService?.caloriesBurned?.observe(this) { calories ->
            binding.caloriesBurnedTextView.text = "Cals: ${calories.toInt()}"
        }
        trackingService?.currentTrack?.observe(this) { updateMapTrack(it) }
        trackingService?.lastGpxFileUri?.observe(this) { uri ->
            uri?.let {
                Snackbar.make(binding.root, "Track saved successfully!", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun setupButtonClickListeners() {
        binding.currentLocationButton.setOnClickListener { useCurrentLocationAsStart() }
        binding.customLocationButton.setOnClickListener {
            locationPickerLauncher.launch(Intent(this, LocationPickerActivity::class.java))
        }

        binding.startButton.setOnClickListener {
            userSelectedStartPoint?.let {
                pathPolyline.setPoints(emptyList())
                binding.mapView.invalidate()
                saveLastLocation(it)
                val serviceIntent = Intent(this, TrackingService::class.java)
                ContextCompat.startForegroundService(this, serviceIntent)
                trackingService?.startTracking(it, simulateTrail)
            } ?: Toast.makeText(this, "Please set a start location first.", Toast.LENGTH_LONG).show()
        }

        binding.pauseButton.setOnClickListener {
            if (trackingService?.isPaused?.value == true) {
                trackingService?.resumeTracking()
            } else {
                trackingService?.pauseTracking()
            }
        }

        binding.finishButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Finish Workout")
                .setMessage("This will stop the current workout and save the GPX file.")
                .setPositiveButton("Finish") { _, _ ->
                    userSelectedStartPoint = null
                    trackingService?.stopTracking()
                    pathPolyline.setPoints(emptyList())
                    startMarker?.let { marker -> binding.mapView.overlays.remove(marker) }
                    startMarker = null
                    binding.mapView.invalidate()
                    binding.timerTextView.text = "Time: 00:00:00"
                    binding.stepCountTextView.text = "Steps: 0"
                    binding.distanceTextView.text = "Dist: 0.00 km"
                    binding.caloriesBurnedTextView.text = "Cals: 0"
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun updateUiForState(state: UiState) {
        binding.locationControlsLayout.visibility = if (state == UiState.CHOOSING_LOCATION) View.VISIBLE else View.GONE
        binding.trackingControlsLayout.visibility = if (state != UiState.CHOOSING_LOCATION) View.VISIBLE else View.GONE

        binding.startButton.visibility = if (state == UiState.LOCATION_SELECTED) View.VISIBLE else View.GONE
        binding.pauseButton.visibility = if (state == UiState.TRACKING || state == UiState.PAUSED) View.VISIBLE else View.GONE
        binding.finishButton.visibility = if (state == UiState.TRACKING || state == UiState.PAUSED) View.VISIBLE else View.GONE

        if (state == UiState.PAUSED) {
            binding.pauseButton.text = "Resume"
        } else {
            binding.pauseButton.text = "Pause"
        }

        takePictureMenuItem?.isEnabled = state == UiState.TRACKING || state == UiState.PAUSED
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
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
        latestTmpUri?.let { takePictureLauncher.launch(it) } ?: Toast.makeText(this, "Could not create image file.", Toast.LENGTH_SHORT).show()
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
                    time = System.currentTimeMillis()
                }
                exifInterface.setGpsInfo(location)

                val dateTime = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault())
                val dateStamp = SimpleDateFormat("yyyy:MM:dd", Locale.getDefault())
                val timeStamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                val now = Date(location.time)

                exifInterface.setAttribute(ExifInterface.TAG_DATETIME, dateTime.format(now))
                exifInterface.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTime.format(now))
                exifInterface.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, dateStamp.format(now))
                exifInterface.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, timeStamp.format(now))

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
            updateUiForState(UiState.LOCATION_SELECTED)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        takePictureMenuItem = menu.findItem(R.id.action_take_picture)
        takePictureMenuItem?.isEnabled = false
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_take_picture -> {
                handleTakePictureClick()
                true
            }
            R.id.action_set_gpx_directory -> {
                selectGpxDirectoryLauncher.launch(null)
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
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
            Toast.makeText(this, "Location permission not granted.", Toast.LENGTH_SHORT).show()
            return
        }
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    val geoPoint = GeoPoint(location.latitude, location.longitude)
                    userSelectedStartPoint = geoPoint
                    setStartingLocationOnMap(geoPoint)
                    updateUiForState(UiState.LOCATION_SELECTED)
                    Toast.makeText(this, "Current location set as start.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Could not get current location.", Toast.LENGTH_SHORT).show()
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
