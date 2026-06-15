package com.hubilon.seoulstationpoc.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.hubilon.seoulstationpoc.domain.model.LocationResult as AppLocationResult
import com.hubilon.seoulstationpoc.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = AppLog.VM

class FusedLocationProvider(context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)

    private val _locationFlow = MutableStateFlow<AppLocationResult?>(null)
    val locationFlow: StateFlow<AppLocationResult?> = _locationFlow.asStateFlow()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc ->
                _locationFlow.value = AppLocationResult(loc.latitude, loc.longitude)
                Log.d(TAG, "퓨즈드 위치 — lat=${loc.latitude}, lng=${loc.longitude} acc=${loc.accuracy}m")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start(intervalMs: Long) {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            Log.i(TAG, "퓨즈드 위치 수집 시작 — 간격=${intervalMs}ms")
        } catch (e: SecurityException) {
            Log.e(TAG, "퓨즈드 위치 권한 없음: ${e.message}")
        }
    }

    fun stop() {
        client.removeLocationUpdates(callback)
        _locationFlow.value = null
        Log.i(TAG, "퓨즈드 위치 수집 중지")
    }
}
