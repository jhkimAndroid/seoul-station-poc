package com.hubilon.positioning.scan

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult as GmsLocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.hubilon.positioning.model.LocationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "SSP_GPS"

class GpsScanner(context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)

    private val _locationFlow = MutableStateFlow<LocationResult?>(null)
    val locationFlow: StateFlow<LocationResult?> = _locationFlow.asStateFlow()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: GmsLocationResult) {
            result.lastLocation?.let { loc ->
                _locationFlow.value = LocationResult(loc.latitude, loc.longitude)
                Log.d(TAG, "GPS — lat=${loc.latitude}, lng=${loc.longitude} acc=${loc.accuracy}m")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start(intervalMs: Long = 1_000L) {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            Log.i(TAG, "GPS 수집 시작 — 간격=${intervalMs}ms")
        } catch (e: SecurityException) {
            Log.e(TAG, "GPS 권한 없음: ${e.message}")
        }
    }

    fun stop() {
        client.removeLocationUpdates(callback)
        _locationFlow.value = null
        Log.i(TAG, "GPS 수집 중지")
    }
}
