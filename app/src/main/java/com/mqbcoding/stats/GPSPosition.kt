package com.mqbcoding.stats


import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.*
import kotlin.math.max

class GPSPosition(private val context:Context) {

    interface GPSPositionCallback {
        fun onLocationCallback(location: Location?)
    }

    var gpsPositionCallback: GPSPositionCallback? = null

    private var requestingLocationUpdates = true
    val isRequestingLocationUpdates: Boolean
        get() = requestingLocationUpdates

    companion object {
        const val TAG = "GPSPosition"
        const val INTERVAL = 750L // 1000L
        const val FASTEST_INTERVAL = 500L
        const val ACCEPTABLE_DURATION = 1000L * 1000 * 1000 * 4
        const val ACCEPTABLE_ACCURACY = 8//10
        const val ACCEPTABLE_SPEED_ACCURACY = 0.25 //0.27
    }

    private var fusedLocationClient: FusedLocationProviderClient =
            LocationServices.getFusedLocationProviderClient(context)

    private val acceptableAccuracy = if (BuildConfig.DEBUG) max(ACCEPTABLE_ACCURACY*2,20) else ACCEPTABLE_ACCURACY

    private val locationRequest = LocationRequest.create()?.apply {
        interval = INTERVAL
        fastestInterval = FASTEST_INTERVAL
        priority = LocationRequest.PRIORITY_HIGH_ACCURACY
    }


    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                null /* Looper */
        )
    }

    fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }


    private var locationCallback: LocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult?) {
            locationResult ?: return

            try {
                var bestLocation: Location? = null

                var bestAccuracy = 1000f
                val now = SystemClock.elapsedRealtimeNanos()

                for (location in locationResult.locations) {
                    if (now - location.elapsedRealtimeNanos < ACCEPTABLE_DURATION) {
                        if (location.accuracy < bestAccuracy) {
                            bestLocation = location
                            bestAccuracy = location.accuracy
                        }
                    }
                }

                if (bestLocation != null && bestLocation.accuracy <= acceptableAccuracy
                        && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bestLocation.speedAccuracyMetersPerSecond<ACCEPTABLE_SPEED_ACCURACY))) {

                    Log.d(TAG, "Receive Location")

                    try {
                        gpsPositionCallback?.onLocationCallback(bestLocation)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                } else {

                    if (bestLocation==null) {
                        Log.w(TAG, "Location to old")
                    } else {
                        Log.w(TAG, "Location not accurate enough")
                    }

                    try {
                        gpsPositionCallback?.onLocationCallback(null)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}