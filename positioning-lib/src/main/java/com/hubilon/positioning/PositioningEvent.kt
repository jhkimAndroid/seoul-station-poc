package com.hubilon.positioning

import com.hubilon.positioning.model.FingerprintEntry
import com.hubilon.positioning.model.GeoPos
import com.hubilon.positioning.model.LocationResult
import com.hubilon.positioning.model.ScanData

sealed class PositioningEvent {
    data class LocationUpdate(val update: PositioningUpdate) : PositioningEvent()
    data class GpsUpdate(val location: LocationResult) : PositioningEvent()
    object OutsideMbr : PositioningEvent()
    data class FeaturesLoaded(val anchorCount: Int, val trackerCount: Int) : PositioningEvent()
    data class FeatureLoadError(val message: String) : PositioningEvent()
    data class DebugInfo(val message: String) : PositioningEvent()
    object AutoPositioningStarted : PositioningEvent()
    object AutoPositioningStopped : PositioningEvent()
}
