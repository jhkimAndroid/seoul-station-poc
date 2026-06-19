package com.hubilon.seoulstationpoc.data.location.processor

import com.hubilon.seoulstationpoc.model.GeoPos
import com.hubilon.seoulstationpoc.model.LinkData

enum class LocationSourceType { ANCHOR, TRACKER }

data class ProcessContext(
    val previousFinal: GeoPos?,
    val sourceType: LocationSourceType,
    val isKalmanEnabled: Boolean,
    val smoothStep: Double,
    val linkData: List<LinkData>
)

interface LocationProcessor {
    fun process(input: GeoPos, context: ProcessContext): GeoPos
}
