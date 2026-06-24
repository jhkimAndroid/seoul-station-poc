package com.hubilon.positioning.internal.estimator

import com.hubilon.positioning.estimator.LocationEstimator
import com.hubilon.positioning.internal.api.LocationApiClient
import com.hubilon.positioning.internal.engine.FingerprintBuilder
import com.hubilon.positioning.model.LocationResult
import com.hubilon.positioning.model.ScanData

internal class ServerTrackerEstimator(
    private val apiClient: LocationApiClient,
    private val fingerprintBuilder: FingerprintBuilder,
    private val getPdrLocation: () -> LocationResult?
) : LocationEstimator {

    override suspend fun estimate(scanData: ScanData): LocationResult? {
        val values = fingerprintBuilder.buildTrackerPayload(scanData)
        if (values.isEmpty()) return null
        val pdr = getPdrLocation()
        return apiClient.trackerPredict(values, pdr?.lat ?: -999.0, pdr?.lng ?: -999.0)
    }
}
