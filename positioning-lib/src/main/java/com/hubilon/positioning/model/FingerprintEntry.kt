package com.hubilon.positioning.model

const val MISSING_RSSI = -110

data class FingerprintEntry(
    val mac: String,
    val rssi: Int,
    val isBle: Boolean
)
