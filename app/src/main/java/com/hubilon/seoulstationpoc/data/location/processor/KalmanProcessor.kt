package com.hubilon.seoulstationpoc.data.location.processor

import com.hubilon.seoulstationpoc.data.filter.LocationKalmanFilter
import com.hubilon.seoulstationpoc.model.GeoPos

class KalmanProcessor(
    measurementNoiseSigma: Double = 10.0,
    processNoiseSigma: Double = 0.7
) : LocationProcessor {

    val filter = LocationKalmanFilter(measurementNoiseSigma, processNoiseSigma)

    override fun process(input: GeoPos, context: ProcessContext): GeoPos {
        if (!context.isKalmanEnabled) return input
        val result = filter.update(input.lat, input.lng)
        return GeoPos(result.lat, result.lng)
    }

    fun reset() = filter.reset()
}
