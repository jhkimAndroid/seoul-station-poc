package com.hubilon.positioning.internal.estimator

import com.hubilon.positioning.estimator.LocationEstimator
import com.hubilon.positioning.internal.api.LocationApiClient
import com.hubilon.positioning.internal.engine.FingerprintBuilder
import com.hubilon.positioning.model.LocationResult
import com.hubilon.positioning.model.ScanData

internal class ServerAnchorEstimator(
    private val apiClient: LocationApiClient,
    private val fingerprintBuilder: FingerprintBuilder
) : LocationEstimator {

    override suspend fun estimate(scanData: ScanData): LocationResult? {
        val values = fingerprintBuilder.buildAnchorPayload(scanData)
        if (values.isEmpty()) return null
        return apiClient.anchorPredict(values)
    }
}
