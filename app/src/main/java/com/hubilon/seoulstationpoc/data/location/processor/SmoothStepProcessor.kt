package com.hubilon.seoulstationpoc.data.location.processor

import com.hubilon.seoulstationpoc.model.GeoPos
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

private const val TRACKER_THRESHOLD_M = 5.0
private const val ANCHOR_THRESHOLD_M  = 10.0

class SmoothStepProcessor : LocationProcessor {

    override fun process(input: GeoPos, context: ProcessContext): GeoPos {
        val prev = context.previousFinal ?: return input

        val threshold = when (context.sourceType) {
            LocationSourceType.TRACKER -> TRACKER_THRESHOLD_M
            LocationSourceType.ANCHOR  -> ANCHOR_THRESHOLD_M
        }

        val metersPerDegLat = 111320.0
        val metersPerDegLng = metersPerDegLat * cos(prev.lat * PI / 180.0)

        val dLatM = (input.lat - prev.lat) * metersPerDegLat
        val dLngM = (input.lng - prev.lng) * metersPerDegLng
        val dist  = sqrt(dLatM * dLatM + dLngM * dLngM)

        if (dist < threshold) return input

        val scale = context.smoothStep / dist
        return GeoPos(
            lat = prev.lat + dLatM * scale / metersPerDegLat,
            lng = prev.lng + dLngM * scale / metersPerDegLng
        )
    }
}
