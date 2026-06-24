package com.hubilon.positioning.internal.engine

import android.util.Log
import com.hubilon.positioning.internal.api.ApEntry
import com.hubilon.positioning.model.FingerprintEntry
import com.hubilon.positioning.model.MISSING_RSSI
import com.hubilon.positioning.model.ScanData
import com.hubilon.positioning.model.SensorSignal

private const val TAG = "FingerprintBuilder"
private val LTE_TYPES = setOf("lte_rsrp", "lte_rsrq")
private val RF_TYPES  = setOf("wifi", "ble") + LTE_TYPES

internal class FingerprintBuilder {

    private var anchorFeatures: List<ApEntry>  = emptyList()
    private var trackerFeatures: List<ApEntry> = emptyList()
    private var anchorBleList: List<ApEntry>   = emptyList()
    private var anchorWifiList: List<ApEntry>  = emptyList()

    val anchorFeatureCount:  Int get() = anchorFeatures.size
    val trackerFeatureCount: Int get() = trackerFeatures.size

    val sensorIdentifiers: List<String> get() =
        anchorFeatures.filter { it.type !in RF_TYPES }.map { it.identifier }

    fun updateFromAnchorAps(aps: List<ApEntry>) {
        val sorted = aps.sortedBy { it.featureIdx }
        anchorFeatures  = sorted
        anchorBleList   = sorted.filter { it.type == "ble" }
        anchorWifiList  = sorted.filter { it.type == "wifi" }
        val lteCount    = sorted.count { it.type in LTE_TYPES }
        val sensorCount = sorted.count { it.type !in RF_TYPES }
        Log.i(TAG, "앵커 피처 갱신 — 총 ${sorted.size}개: BLE=${anchorBleList.size} WiFi=${anchorWifiList.size} LTE=$lteCount Sensor=$sensorCount")
    }

    fun updateFromTrackerAps(aps: List<ApEntry>) {
        val sorted = aps.sortedBy { it.featureIdx }
        trackerFeatures = sorted
        Log.i(TAG, "트래커 피처 갱신 — 총 ${sorted.size}개")
    }

    fun buildAnchorPayload(scanData: ScanData): FloatArray = buildPayload(anchorFeatures, scanData)

    fun buildTrackerPayload(scanData: ScanData): FloatArray = buildPayload(trackerFeatures, scanData)

    fun buildEntries(scanData: ScanData): List<FingerprintEntry> {
        val bleRssiMap  = scanData.bleSignals.associate  { it.deviceAddress.lowercase() to it.rssi }
        val wifiRssiMap = scanData.wifiSignals.associate { it.bssid.lowercase()         to it.rssi }
        val entries = ArrayList<FingerprintEntry>(anchorBleList.size + anchorWifiList.size)
        anchorBleList.forEach  { f -> entries.add(FingerprintEntry(f.identifier, bleRssiMap[f.identifier.lowercase()]  ?: MISSING_RSSI, isBle = true))  }
        anchorWifiList.forEach { f -> entries.add(FingerprintEntry(f.identifier, wifiRssiMap[f.identifier.lowercase()] ?: MISSING_RSSI, isBle = false)) }
        return entries
    }

    fun toSensorArray(signal: SensorSignal?): FloatArray {
        val ids = sensorIdentifiers
        if (ids.isEmpty()) return FloatArray(0)
        val s = signal ?: return FloatArray(ids.size)
        return FloatArray(ids.size) { i -> sensorValue(ids[i], s) }
    }

    private fun buildPayload(features: List<ApEntry>, scanData: ScanData): FloatArray {
        if (features.isEmpty()) return FloatArray(0)
        val bleRssiMap  = scanData.bleSignals.associate  { it.deviceAddress.lowercase() to it.rssi }
        val wifiRssiMap = scanData.wifiSignals.associate { it.bssid.lowercase()         to it.rssi }
        val registeredLte = scanData.lteSignals.firstOrNull()
        return FloatArray(features.size) { i ->
            val f = features[i]
            when (f.type) {
                "wifi"     -> (wifiRssiMap[f.identifier.lowercase()] ?: MISSING_RSSI).toFloat()
                "ble"      -> (bleRssiMap[f.identifier.lowercase()]  ?: MISSING_RSSI).toFloat()
                "lte_rsrp" -> registeredLte?.rsrp?.toFloat() ?: -140f
                "lte_rsrq" -> registeredLte?.rsrq?.toFloat() ?: -20f
                else       -> sensorValue(f.identifier, scanData.sensorSignal)
            }
        }
    }

    private fun sensorValue(identifier: String, sensor: SensorSignal?): Float {
        val s = sensor ?: return 0f
        return when (identifier) {
            "acc_x"  -> s.accelX; "acc_y"  -> s.accelY; "acc_z"  -> s.accelZ
            "acc_wx" -> s.accelWX; "acc_wy" -> s.accelWY; "acc_wz" -> s.accelWZ
            "gyro_x" -> s.gyroX; "gyro_y" -> s.gyroY; "gyro_z" -> s.gyroZ
            "gyro_wx"-> s.gyroWX; "gyro_wy"-> s.gyroWY; "gyro_wz"-> s.gyroWZ
            "mag_x"  -> s.magX; "mag_y"  -> s.magY; "mag_z"  -> s.magZ
            "mag_wx" -> s.magWX; "mag_wy" -> s.magWY; "mag_wz" -> s.magWZ
            else     -> { Log.w(TAG, "알 수 없는 센서 식별자: $identifier"); 0f }
        }
    }
}
