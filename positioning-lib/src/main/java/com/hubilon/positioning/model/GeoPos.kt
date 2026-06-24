package com.hubilon.positioning.model

data class GeoPos(val lat: Double, val lng: Double)

data class LocationResult(
    val lat: Double,
    val lng: Double,
    val confidence: Float = 0f
)
