package com.example.exercisehome

import android.Manifest
import android.accounts.Account
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
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.ClearCredentialException
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.example.exercisehome.databinding.ActivityMainBinding
import com.google.android.gms.auth.api.identity.BeginSignInRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.SignInClient
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.snackbar.Snackbar
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import com.google.api.services.drive.model.File as DriveFile

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val MAP_DEFAULT_ZOOM = 16.0
        private const val PREFS_NAME = "ExerciseHomePrefs"
        private const val KEY_LAST_LAT = "last_lat"
        private const val KEY_LAST_LON = "last_lon"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_GPX_DIR_URI = "gpx_directory_uri"
        private const val DRIVE_FOLDER_NAME = "ExerciseHome_GPX_Uploads"
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

    private lateinit var credentialManager: CredentialManager
    private lateinit var oneTapClient: SignInClient
    private var driveService: Drive? = null
    private var lastGpxUriForUpload: Uri? = null

    private val oneTapSignInLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                val signInCredential = oneTapClient.getSignInCredentialFromIntent(result.data)
                handleSignInSuccess(signInCredential.id, signInCredential.displayName)
            } catch (e: Exception) {
                handleSignInFailure(e.localizedMessage ?: "Sign-in failed.")
            }
        }
    }

    private val requestGoogleDriveConsentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            lastGpxUriForUpload?.let { uploadGpxToDrive(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Configuration.getInstance().load(applicationContext, getPreferences(Context.MODE_PRIVATE))
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        credentialManager = CredentialManager.create(this)
        oneTapClient = Identity.getSignInClient(this)

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
        trackingService?.currentTrack?.observe(this) { updateMapTrack(it) }
        trackingService?.lastGpxFileUri?.observe(this) { uri ->
            uri?.let {
                lastGpxUriForUpload = it
                if (driveService != null) {
                    attemptDriveUpload(it)
                } else {
                    Snackbar.make(binding.root, "Track saved. Sign in to auto-upload.", Snackbar.LENGTH_LONG)
                        .setAction("Sign In") { startSignInFlow() }
                        .show()
                }
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
                .setMessage("This will stop the current workout and clear the map. The GPX file will be saved and automatically uploaded if you are signed in.")
                .setPositiveButton("Finish") { _, _ ->
                    trackingService?.stopTracking()
                    pathPolyline.setPoints(emptyList())
                    startMarker?.let { marker -> binding.mapView.overlays.remove(marker) }
                    startMarker = null
                    binding.mapView.invalidate()
                    binding.timerTextView.text = formatDuration(0L)
                    binding.stepCountTextView.text = "Steps: 0"
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.takePictureButton.setOnClickListener { handleTakePictureClick() }
        binding.signInButton.setOnClickListener { if (driveService == null) startSignInFlow() else signOut() }
        binding.currentLocationButton.setOnClickListener { useCurrentLocationAsStart() }
        binding.customLocationButton.setOnClickListener {
            locationPickerLauncher.launch(Intent(this, LocationPickerActivity::class.java))
        }
    }

    private suspend fun getOrCreateDriveFolderId(): String? {
        val drive = driveService ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val query = "mimeType='application/vnd.google-apps.folder' and name='$DRIVE_FOLDER_NAME' and trashed=false"
                val files = drive.files().list().setQ(query).setSpaces("drive").setFields("files(id)").execute()

                if (files.files.isNotEmpty()) {
                    return@withContext files.files[0].id
                }

                val folderMetadata = DriveFile().apply {
                    name = DRIVE_FOLDER_NAME
                    mimeType = "application/vnd.google-apps.folder"
                }
                val createdFolder = drive.files().create(folderMetadata).setFields("id").execute()
                return@withContext createdFolder.id

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Could not access Drive folder.", Toast.LENGTH_SHORT).show()
                }
                return@withContext null
            }
        }
    }

    private fun uploadGpxToDrive(localGpxUri: Uri) {
        if (driveService == null) {
            lastGpxUriForUpload = localGpxUri
            return
        }
        Toast.makeText(this, "Uploading to Google Drive…", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val folderId = getOrCreateDriveFolderId()
            if (folderId == null) {
                Toast.makeText(this@MainActivity, "Upload failed: Could not get Drive folder.", Toast.LENGTH_LONG).show()
                return@launch
            }

            withContext(Dispatchers.IO) {
                try {
                    val metadata = DriveFile().apply {
                        name = "Workout_${System.currentTimeMillis()}.gpx"
                        mimeType = "application/gpx+xml"
                        parents = listOf(folderId)
                    }
                    contentResolver.openInputStream(localGpxUri)?.use { inputStream ->
                        val mediaContent = InputStreamContent("application/gpx+xml", inputStream)
                        driveService!!.files().create(metadata, mediaContent).execute()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "GPX file uploaded!", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: UserRecoverableAuthIOException) {
                    withContext(Dispatchers.Main) {
                        requestGoogleDriveConsentLauncher.launch(e.intent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Google Drive upload failed", e)
                    val errorMessage = if (e is GoogleJsonResponseException) {
                        "Google API Error ${e.statusCode}: ${e.details?.message}"
                    } else {
                        e.localizedMessage ?: "An unknown error occurred"
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Upload failed: $errorMessage", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private suspend fun initializeDriveService(email: String) {
        withContext(Dispatchers.IO) {
            try {
                val credential = GoogleAccountCredential.usingOAuth2(
                    this@MainActivity,
                    // THIS IS THE KEY FIX: Using a broader scope to allow folder searching and creation.
                    listOf(DriveScopes.DRIVE)
                ).setSelectedAccount(Account(email, "com.google"))

                driveService = Drive.Builder(
                    NetHttpTransport(),
                    GsonFactory.getDefaultInstance(),
                    credential
                ).setApplicationName(getString(R.string.app_name)).build()

                withContext(Dispatchers.Main) {
                    lastGpxUriForUpload?.let {
                        attemptDriveUpload(it)
                        lastGpxUriForUpload = null
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Drive service initialization failed", e)
            }
        }
    }

    private fun attemptDriveUpload(gpxFileUri: Uri) {
        if (driveService != null) {
            uploadGpxToDrive(gpxFileUri)
        } else {
            Snackbar.make(binding.root, "Please sign in to upload.", Snackbar.LENGTH_LONG)
                .setAction("Sign In") { startSignInFlow() }
                .show()
        }
    }

    // --- All other helper methods remain the same ---
    // (methods for lifecycle, permissions, map, camera, state, sign-in flow, menus, etc.)

    override fun onResume() { super.onResume() }
    override fun onPause() { super.onPause() }
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

    private fun saveLogin(email: String) {
        prefs.edit().putString(KEY_USER_EMAIL, email).apply()
    }

    private fun getSavedLogin(): String? = prefs.getString(KEY_USER_EMAIL, null)

    private fun saveGpxDirectory(uri: Uri) {
        prefs.edit().putString(KEY_GPX_DIR_URI, uri.toString()).apply()
    }

    private fun restoreSavedState() {
        loadLastLocation()?.let {
            userSelectedStartPoint = it
            setStartingLocationOnMap(it)
            binding.startButton.isEnabled = true
        }
        getSavedLogin()?.let { email ->
            updateSignInButtonUI(true, email)
            lifecycleScope.launch { initializeDriveService(email) }
        }
    }

    private fun startSignInFlow() {
        val oneTapRequest = BeginSignInRequest.builder()
            .setGoogleIdTokenRequestOptions(
                BeginSignInRequest.GoogleIdTokenRequestOptions.builder()
                    .setSupported(true)
                    .setServerClientId(getString(R.string.your_web_client_id))
                    .setFilterByAuthorizedAccounts(false)
                    .build()
            )
            .setAutoSelectEnabled(true)
            .build()

        oneTapClient.beginSignIn(oneTapRequest)
            .addOnSuccessListener { result ->
                try {
                    val intentSenderRequest = IntentSenderRequest.Builder(result.pendingIntent.intentSender).build()
                    oneTapSignInLauncher.launch(intentSenderRequest)
                } catch (e: Exception) {
                    handleSignInFailure("Could not launch sign-in UI.")
                }
            }
            .addOnFailureListener { e ->
                handleSignInFailure(e.localizedMessage ?: "Failed to start sign-in.")
            }
    }

    private fun handleSignInSuccess(email: String, displayName: String?) {
        saveLogin(email)
        updateSignInButtonUI(true, email)
        lifecycleScope.launch { initializeDriveService(email) }
        Toast.makeText(this, "Signed in as ${displayName ?: email}", Toast.LENGTH_SHORT).show()
    }

    private fun handleSignInFailure(errorMessage: String) {
        Log.w(TAG, "Sign-In Failed: $errorMessage")
        Toast.makeText(this, "Sign-In Failed: $errorMessage", Toast.LENGTH_LONG).show()
    }

    private fun signOut() {
        lifecycleScope.launch {
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
                driveService = null
                updateSignInButtonUI(false, null)
                prefs.edit().remove(KEY_USER_EMAIL).apply()
                Toast.makeText(this@MainActivity, "Signed out", Toast.LENGTH_SHORT).show()
            } catch (e: ClearCredentialException) {
                Log.e(TAG, "Sign out failed", e)
            }
        }
    }

    private fun updateSignInButtonUI(isSignedIn: Boolean, email: String?) {
        if (isSignedIn) {
            binding.signInButton.text = "Sign Out (${email?.substringBefore("@")})"
        } else {
            binding.signInButton.text = "Sign In (Drive)"
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
            R.id.action_upload_last_track -> {
                lastGpxUriForUpload?.let { attemptDriveUpload(it) } ?: Toast.makeText(this, "No recent track to upload.", Toast.LENGTH_SHORT).show()
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
            mapController.animateTo(trackPoints.last())
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
