package com.example.exercisehome

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.exercisehome.databinding.ActivityLocationPickerBinding
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.snackbar.Snackbar

class LocationPickerActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        private const val TAG = "LocationPickerActivity"
        private const val DEFAULT_ZOOM = 15f
        // Default location (Cyberjaya, Malaysia) - Replace if needed
        private val DEFAULT_LOCATION = LatLng(2.9213, 101.6559)
    }

    private lateinit var binding: ActivityLocationPickerBinding
    private var map: GoogleMap? = null
    private var pickedLatLng: LatLng? = null
    private var currentMarker: Marker? = null

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationPermissionGranted = false

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d(TAG, "Fine Location permission granted.")
                locationPermissionGranted = true
                updateLocationUI() // Enable blue dot etc.
                centerMapOnUserLocation()
            } else {
                Log.w(TAG, "Fine Location permission denied.")
                locationPermissionGranted = false
                // Explain why it was needed and center on default
                Snackbar.make(binding.root, "Permission denied. Showing default location.", Snackbar.LENGTH_LONG)
                    .setAction("Settings") { openAppSettings() }
                    .show()
                centerMap(DEFAULT_LOCATION, "Cyberjaya")
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLocationPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as? SupportMapFragment
        if (mapFragment == null) {
            Log.e(TAG, "SupportMapFragment not found!")
            Toast.makeText(this, "Error loading map", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        mapFragment.getMapAsync(this)

        binding.confirmButton.setOnClickListener {
            confirmLocation()
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        Log.d(TAG, "GoogleMap is ready.")
        map = googleMap

        googleMap.uiSettings.isZoomControlsEnabled = true
        googleMap.uiSettings.isMapToolbarEnabled = false // Keep UI clean

        googleMap.setOnMapClickListener { latLng ->
            Log.d(TAG, "Map clicked at: $latLng")
            updateSelectedLocation(latLng)
        }

        // Check permissions and attempt to show user location
        checkLocationPermissionAndCenterMap()
    }

    private fun checkLocationPermissionAndCenterMap() {
        when {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                Log.d(TAG, "Permission already granted.")
                locationPermissionGranted = true
                updateLocationUI()
                centerMapOnUserLocation()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) -> {
                Log.d(TAG, "Showing permission rationale.")
                Snackbar.make(binding.root, "Location permission needed to center map.", Snackbar.LENGTH_INDEFINITE)
                    .setAction("Grant") {
                        requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }.show()
                // Also center on default while rationale is shown
                centerMap(DEFAULT_LOCATION, "Cyberjaya")
            }
            else -> {
                Log.d(TAG, "Requesting permission directly.")
                requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                // Center on default until permission result
                centerMap(DEFAULT_LOCATION, "Cyberjaya")
            }
        }
    }

    private fun updateLocationUI() {
        if (map == null) return
        try {
            map?.isMyLocationEnabled = locationPermissionGranted
            map?.uiSettings?.isMyLocationButtonEnabled = locationPermissionGranted
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception in updateLocationUI: ${e.message}")
        }
    }

    private fun centerMapOnUserLocation() {
        if (!locationPermissionGranted || map == null) {
            Log.w(TAG, "Cannot center map: Permission=$locationPermissionGranted, Map Ready=${map != null}")
            if (!locationPermissionGranted) centerMap(DEFAULT_LOCATION, "Cyberjaya") // Ensure default shown if no permission
            return
        }
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                val targetLocation = location?.let { LatLng(it.latitude, it.longitude) } ?: DEFAULT_LOCATION
                val title = if (location != null) "Approx. Current Location" else "Cyberjaya (Default)"
                Log.d(TAG, "Centering map on: $title ($targetLocation)")
                centerMap(targetLocation, title)
            }.addOnFailureListener { e ->
                Log.e(TAG, "Error getting last known location", e)
                centerMap(DEFAULT_LOCATION, "Cyberjaya (Default)") // Fallback on error
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission error in centerMapOnUserLocation", e)
        }
    }

    private fun centerMap(latLng: LatLng, title: String?) {
        // Move camera smoothly
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, DEFAULT_ZOOM))
    }

    private fun updateSelectedLocation(latLng: LatLng) {
        pickedLatLng = latLng
        currentMarker?.remove() // Remove old marker

        val markerOptions = MarkerOptions()
            .position(latLng)
            .title("Selected Start Point")
        // .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)) // Optional: Customize marker

        currentMarker = map?.addMarker(markerOptions)
        binding.confirmButton.isEnabled = true // Enable confirm button
    }

    private fun confirmLocation() {
        pickedLatLng?.let {
            Log.d(TAG, "Confirming location: $it")
            val data = Intent().apply {
                putExtra("lat", it.latitude)
                putExtra("lon", it.longitude)
            }
            setResult(RESULT_OK, data)
            finish()
        } ?: run {
            Log.w(TAG, "Confirm button clicked, but no location picked.")
            Toast.makeText(this, "Please tap on the map first.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            val uri = Uri.fromParts("package", packageName, null)
            intent.data = uri
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG,"Failed to open app settings", e)
            Toast.makeText(this, "Failed to open settings", Toast.LENGTH_SHORT).show()
        }
    }
}