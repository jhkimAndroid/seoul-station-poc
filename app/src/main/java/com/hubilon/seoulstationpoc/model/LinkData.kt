package com.hubilon.seoulstationpoc.model

data class GeoPos(val lat: Double, val lng: Double)

data class LinkData(
    val linkId: Long,
    val linkLen: Double,
    val startPos: GeoPos,
    val endPos: GeoPos
)
