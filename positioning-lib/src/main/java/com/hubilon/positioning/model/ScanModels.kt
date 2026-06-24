package com.hubilon.positioning.model

data class WifiSignal(val ssid: String, val bssid: String, val rssi: Int)

data class BleSignal(val deviceAddress: String, val rssi: Int)

data class LteSignal(
    val pci: Int,
    val tac: Int,
    val rawTac: Int,
    val rsrp: Int,
    val rsrq: Int,
    val isRegistered: Boolean
)

data class RttSignal(
    val bssid: String,
    val distanceMm: Int,
    val distanceStdDevMm: Int,
    val rssi: Int,
    val successCount: Int,
    val attemptCount: Int
)

data class ScanData(
    val wifiSignals: List<WifiSignal> = emptyList(),
    val bleSignals: List<BleSignal> = emptyList(),
    val sensorSignal: SensorSignal? = null,
    val lteSignals: List<LteSignal> = emptyList(),
    val rttSignals: List<RttSignal> = emptyList()
)
