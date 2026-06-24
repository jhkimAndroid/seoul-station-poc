package com.hubilon.positioning.internal.engine

import com.hubilon.positioning.model.LocationResult
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal object GeoUtils {

    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R    = 6371000.0
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLng = (lng2 - lng1) * PI / 180.0
        val a    = sin(dLat / 2).pow(2) +
                   cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sin(dLng / 2).pow(2)
        return R * 2.0 * asin(sqrt(a))
    }

    /** 두 좌표 간 방위각(라디안). 0=북, 양수=시계 방향. */
    fun bearingRad(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val φ1   = lat1 * PI / 180.0
        val φ2   = lat2 * PI / 180.0
        val dLng = (lng2 - lng1) * PI / 180.0
        return atan2(sin(dLng) * cos(φ2), cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(dLng))
    }

    /** 시작 좌표에서 방위각(라디안) 방향으로 distM 미터 이동한 좌표를 반환한다. */
    fun moveToward(lat: Double, lng: Double, bearingRad: Double, distM: Double): LocationResult {
        val R  = 6371000.0
        val δ  = distM / R
        val φ1 = lat * PI / 180.0
        val λ1 = lng * PI / 180.0
        val φ2 = asin(sin(φ1) * cos(δ) + cos(φ1) * sin(δ) * cos(bearingRad))
        val λ2 = λ1 + atan2(sin(bearingRad) * sin(δ) * cos(φ1), cos(δ) - sin(φ1) * sin(φ2))
        return LocationResult(φ2 * 180.0 / PI, λ2 * 180.0 / PI)
    }

    /** 두 방위각(라디안) 간 최소 각도 차이(도, 0~180). */
    fun angleDiffDeg(a: Double, b: Double): Double {
        val diff = ((b - a) * 180.0 / PI % 360.0 + 360.0) % 360.0
        return if (diff <= 180.0) diff else 360.0 - diff
    }
}
