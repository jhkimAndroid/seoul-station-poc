package com.hubilon.positioning

import com.hubilon.positioning.model.FingerprintEntry
import com.hubilon.positioning.model.GeoPos
import com.hubilon.positioning.model.LocationResult
import com.hubilon.positioning.model.ScanData

data class PositioningUpdate(
    val finalLocation: LocationResult? = null,
    val serverLocation: LocationResult? = null,
    val pdrLocation: LocationResult? = null,
    val gpsLocation: LocationResult? = null,
    val locationHistory: List<GeoPos> = emptyList(),
    val locationUpdateCount: Int = 0,
    val fingerprintEntries: List<FingerprintEntry>? = null,
    val scanData: ScanData? = null
)
