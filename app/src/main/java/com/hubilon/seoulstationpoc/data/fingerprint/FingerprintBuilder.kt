package com.hubilon.seoulstationpoc.data.fingerprint

import android.util.Log
import com.hubilon.seoulstationpoc.data.api.ApEntry
import com.hubilon.seoulstationpoc.model.ScanData
import com.hubilon.seoulstationpoc.model.SensorSignal

private const val TAG = "FingerprintBuilder"
const val MISSING_RSSI = -110

private val LTE_TYPES = setOf("lte_rsrp", "lte_rsrq")
private val RF_TYPES  = setOf("wifi", "ble") + LTE_TYPES

data class FingerprintEntry(
    val mac: String,
    val rssi: Int,
    val isBle: Boolean
)

object FingerprintBuilder {

    private var anchorFeatures: List<ApEntry> = emptyList()
    private var trackerFeatures: List<ApEntry> = emptyList()

    // UI 표시용 (매칭 탭): anchor WiFi+BLE 피처만 분리 보관
    private var anchorBleList:  List<ApEntry> = emptyList()
    private var anchorWifiList: List<ApEntry> = emptyList()

    val anchorFeatureCount:  Int get() = anchorFeatures.size
    val trackerFeatureCount: Int get() = trackerFeatures.size

    /** ScanLogger 등 외부에서 anchor 센서 식별자 목록을 읽을 때 사용 */
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
        val bleCount    = sorted.count { it.type == "ble" }
        val lteCount    = sorted.count { it.type in LTE_TYPES }
        val sensorCount = sorted.count { it.type !in RF_TYPES }
        Log.i(TAG, "트래커 피처 갱신 — 총 ${sorted.size}개: BLE=$bleCount LTE=$lteCount Sensor=$sensorCount")
    }

    /** anchor 모델 전송 페이로드 (wifi + ble + lte + sensor, feature_idx 순서) */
    fun buildAnchorPayload(scanData: ScanData): FloatArray = buildPayload(anchorFeatures, scanData)

    /** tracker 모델 전송 페이로드 (ble + lte + sensor, feature_idx 순서) */
    fun buildTrackerPayload(scanData: ScanData): FloatArray = buildPayload(trackerFeatures, scanData)

    /** anchor WiFi+BLE 피처 기반 매칭 엔트리 — ScanDetailScreen 매칭 탭 표시용 */
    fun buildEntries(scanData: ScanData): List<FingerprintEntry> {
        val bleRssiMap  = scanData.bleSignals.associate  { it.deviceAddress.lowercase() to it.rssi }
        val wifiRssiMap = scanData.wifiSignals.associate { it.bssid.lowercase()         to it.rssi }

        val entries = ArrayList<FingerprintEntry>(anchorBleList.size + anchorWifiList.size)
        anchorBleList.forEach  { f -> entries.add(FingerprintEntry(f.identifier, bleRssiMap[f.identifier.lowercase()]  ?: MISSING_RSSI, isBle = true))  }
        anchorWifiList.forEach { f -> entries.add(FingerprintEntry(f.identifier, wifiRssiMap[f.identifier.lowercase()] ?: MISSING_RSSI, isBle = false)) }

        val matchCount = entries.count { it.rssi != MISSING_RSSI }
        Log.d(TAG, "핑거프린트 매칭 생성 완료 — 크기=${entries.size} 매칭=$matchCount 미매칭=${entries.size - matchCount}")
        return entries
    }

    /** ScanLogger용 센서 값 배열 — anchor sensorIdentifiers 순서 */
    fun toSensorArray(signal: SensorSignal?): FloatArray {
        val ids = sensorIdentifiers
        if (ids.isEmpty()) return FloatArray(0)
        val s = signal ?: return FloatArray(ids.size)
        return FloatArray(ids.size) { i -> sensorValue(ids[i], s) }
    }

    // ── 내부 구현 ─────────────────────────────────────────────────────────────

    private fun buildPayload(features: List<ApEntry>, scanData: ScanData): FloatArray {
        if (features.isEmpty()) return FloatArray(0)

        val bleRssiMap  = scanData.bleSignals.associate  { it.deviceAddress.lowercase() to it.rssi }
        val wifiRssiMap = scanData.wifiSignals.associate { it.bssid.lowercase()         to it.rssi }
        val lteMap      = scanData.lteSignals.associate  { "${it.pci}:${it.rawTac}"     to it     }

        return FloatArray(features.size) { i ->
            val f = features[i]
            when (f.type) {
                "wifi"     -> (wifiRssiMap[f.identifier.lowercase()] ?: MISSING_RSSI).toFloat()
                "ble"      -> (bleRssiMap[f.identifier.lowercase()]  ?: MISSING_RSSI).toFloat()
                "lte_rsrp" -> lteMap[f.identifier]?.rsrp?.toFloat() ?: -140f
                "lte_rsrq" -> lteMap[f.identifier]?.rsrq?.toFloat() ?: -20f
                else       -> sensorValue(f.identifier, scanData.sensorSignal)
            }
        }
    }

    private fun sensorValue(identifier: String, sensor: SensorSignal?): Float {
        val s = sensor ?: return 0f
        return when (identifier) {
            "acc_x"   -> s.accelX
            "acc_y"   -> s.accelY
            "acc_z"   -> s.accelZ
            "acc_wx"  -> s.accelWX
            "acc_wy"  -> s.accelWY
            "acc_wz"  -> s.accelWZ
            "gyro_x"  -> s.gyroX
            "gyro_y"  -> s.gyroY
            "gyro_z"  -> s.gyroZ
            "gyro_wx" -> s.gyroWX
            "gyro_wy" -> s.gyroWY
            "gyro_wz" -> s.gyroWZ
            "mag_x"   -> s.magX
            "mag_y"   -> s.magY
            "mag_z"   -> s.magZ
            "mag_wx"  -> s.magWX
            "mag_wy"  -> s.magWY
            "mag_wz"  -> s.magWZ
            else      -> { Log.w(TAG, "알 수 없는 센서 식별자: $identifier"); 0f }
        }
    }
}
