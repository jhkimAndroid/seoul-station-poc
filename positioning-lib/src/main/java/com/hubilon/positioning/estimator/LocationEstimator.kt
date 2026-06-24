package com.hubilon.positioning.estimator

import com.hubilon.positioning.model.LocationResult
import com.hubilon.positioning.model.ScanData

/** 스캔 데이터로부터 위치를 추정하는 인터페이스. 커스텀 추정기를 구현할 때 사용한다. */
interface LocationEstimator {
    suspend fun estimate(scanData: ScanData): LocationResult?
}
