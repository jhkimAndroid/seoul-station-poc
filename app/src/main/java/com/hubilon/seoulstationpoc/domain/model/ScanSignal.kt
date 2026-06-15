package com.hubilon.seoulstationpoc.domain.model

data class WifiSignal(
    val ssid: String,
    val bssid: String,
    val rssi: Int
)

data class BleSignal(
    val deviceAddress: String,
    val rssi: Int
)

data class ScanData(
    val wifiSignals: List<WifiSignal> = emptyList(),
    val bleSignals: List<BleSignal> = emptyList(),
    val sensorSignal: SensorSignal? = null
)

data class LocationResult(
    val lat: Double,
    val lng: Double,
    val confidence: Float = 0f
)
