package com.hubilon.positioning.model

data class LinkData(
    val linkId: Long,
    val linkLen: Double,
    val startPos: GeoPos,
    val endPos: GeoPos
)
