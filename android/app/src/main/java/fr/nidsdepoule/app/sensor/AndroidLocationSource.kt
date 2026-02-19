package fr.nidsdepoule.app.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Android platform adapter for GPS using Fused Location Provider.
 * Requests location updates at 1Hz for speed/bearing, with high accuracy.
 *
 * Note: Requires Google Play Services. If not available, a fallback
 * using android.location.LocationManager would be needed.
 */
class AndroidLocationSource(private val context: Context) : LocationSource {

    private var fusedClient: FusedLocationProviderClient? = null
    private var locationCallback: com.google.android.gms.location.LocationCallback? = null

    override fun start(callback: LocationCallback) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return  // Permission not granted; caller should request it first
        }

        fusedClient = LocationServices.getFusedLocationProviderClient(context)

        val request = LocationRequest.Builder(1000)  // 1Hz
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMinUpdateDistanceMeters(1f)
            .build()

        locationCallback = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc: Location = result.lastLocation ?: return
                callback.onLocation(
                    LocationReading(
                        timestampMs = loc.time,
                        latMicrodeg = (loc.latitude * 1_000_000).toInt(),
                        lonMicrodeg = (loc.longitude * 1_000_000).toInt(),
                        accuracyM = loc.accuracy.toInt(),
                        speedMps = if (loc.hasSpeed()) loc.speed else 0f,
                        bearingDeg = if (loc.hasBearing()) loc.bearing else 0f,
                    )
                )
            }
        }

        fusedClient?.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
    }

    override fun stop() {
        locationCallback?.let { fusedClient?.removeLocationUpdates(it) }
        locationCallback = null
        fusedClient = null
    }
}
