package com.hubilon.seoulstationpoc.model

data class WifiSignal(
    val ssid: String,
    val bssid: String,
    val rssi: Int
)

data class BleSignal(
    val deviceAddress: String,
    val rssi: Int
)

data class LteSignal(
    val pci: Int,             // Physical Cell ID (0-503)
    val tac: Int,             // Tracking Area Code (-1 = 미제공)
    val rawTac: Int,          // Android 원본 TAC 값 — 서버 identifier 매칭용 ({pci}:{rawTac})
    val rsrp: Int,            // Reference Signal Received Power [dBm]
    val rsrq: Int,            // Reference Signal Received Quality [dB]
    val isRegistered: Boolean // 연결된 셀 여부
)

data class ScanData(
    val wifiSignals: List<WifiSignal> = emptyList(),
    val bleSignals: List<BleSignal> = emptyList(),
    val sensorSignal: SensorSignal? = null,
    val lteSignals: List<LteSignal> = emptyList()
)

data class LocationResult(
    val lat: Double,
    val lng: Double,
    val confidence: Float = 0f
)
