package com.hubilon.seoulstationpoc.data.fingerprint

import android.util.Log
import com.hubilon.seoulstationpoc.data.api.ApEntry
import com.hubilon.seoulstationpoc.domain.model.ScanData
import com.hubilon.seoulstationpoc.domain.model.SensorSignal

private const val TAG = "FingerprintBuilder"
const val MISSING_RSSI = -110

data class FingerprintEntry(
    val mac: String,
    val rssi: Int,
    val isBle: Boolean
)

// 기본값: final_ap_ble.csv 순서 (BLE_01 ~ BLE_12). fetchAps() 성공 시 교체됨.
private var BLE_MACS = arrayOf(
    "28:87:61:d8:e0:d3",
    "34:51:aa:06:88:72",
    "4c:15:0f:00:00:05",
    "5f:f4:10:3b:f4:73",
    "64:1c:ae:ec:f1:7f",
    "70:b1:3d:b7:fe:ef",
    "a0:d7:f3:28:52:31",
    "a0:d7:f3:55:61:93",
    "a4:14:0f:00:00:05",
    "c9:07:fb:29:e7:63",
    "e2:70:ea:5e:d1:c8",
    "f8:b3:00:00:00:41"
)

// 기본값: final_ap_wifi.csv 순서 (WIFI_001 ~ WIFI_266). fetchAps() 성공 시 교체됨.
private var WIFI_MACS = arrayOf(
    "00:09:b4:74:39:0c", "00:09:b4:74:43:0b", "00:09:b4:74:43:0c", "00:09:b4:74:43:2b",
    "00:09:b4:74:43:2c", "00:09:b4:74:4f:5c", "00:09:b4:74:55:93", "00:09:b4:74:55:94",
    "00:09:b4:75:00:f4", "00:09:b4:75:00:fc", "00:09:b4:75:01:04", "00:09:b4:75:01:0b",
    "00:09:b4:75:01:0c", "00:09:b4:75:01:23", "00:09:b4:75:01:24", "00:09:b4:75:01:2b",
    "00:09:b4:75:01:2c", "00:09:b4:75:55:9c", "00:19:3b:0d:b4:ad", "00:30:0d:83:6a:d0",
    "00:30:0d:83:6e:c0", "00:30:0d:83:6e:d0", "00:40:5a:cd:70:9e", "06:09:b4:74:39:0c",
    "06:09:b4:74:43:0b", "06:09:b4:74:43:0c", "06:09:b4:74:43:2b", "06:09:b4:74:43:2c",
    "06:09:b4:74:4f:5c", "06:09:b4:74:55:93", "06:09:b4:74:55:94", "06:09:b4:75:00:f4",
    "06:09:b4:75:00:fc", "06:09:b4:75:01:04", "06:09:b4:75:01:0b", "06:09:b4:75:01:0c",
    "06:09:b4:75:01:23", "06:09:b4:75:01:24", "06:09:b4:75:01:2b", "06:09:b4:75:01:2c",
    "06:09:b4:75:55:9c", "06:29:d5:5c:59:a0", "06:29:d5:5c:59:a1", "06:29:d5:5c:59:a2",
    "06:29:d5:5c:59:a3", "06:29:d5:5c:59:b0", "06:29:d5:5c:59:b1", "06:29:d5:5c:59:b2",
    "06:29:d5:5c:59:b3", "06:29:d5:5c:59:c0", "06:29:d5:5c:59:c1", "06:29:d5:5c:59:c2",
    "06:29:d5:5c:59:c3", "06:29:d5:5c:59:d0", "06:29:d5:5c:59:d1", "06:29:d5:5c:59:d2",
    "06:29:d5:5c:59:d3", "06:29:d5:5c:59:e0", "06:29:d5:5c:59:e1", "06:29:d5:5c:59:e2",
    "06:29:d5:5c:59:e3", "06:29:d5:5c:59:f0", "06:29:d5:5c:59:f1", "06:29:d5:5c:59:f2",
    "06:29:d5:5c:59:f3", "06:29:d5:5c:5a:00", "06:29:d5:5c:5a:01", "06:29:d5:5c:5a:02",
    "06:29:d5:5c:5a:03", "06:29:d5:5c:e7:50", "06:29:d5:5c:e7:51", "06:29:d5:5c:e7:52",
    "06:29:d5:5c:e7:53", "06:29:d5:5c:e7:60", "06:29:d5:5c:e7:61", "06:29:d5:5c:e7:62",
    "06:29:d5:5c:e7:63", "06:29:d5:5c:e7:70", "06:29:d5:5c:e7:71", "08:5d:dd:d0:ca:21",
    "0a:09:b4:74:39:0c", "0a:09:b4:74:43:0b", "0a:09:b4:74:43:0c", "0a:09:b4:74:43:2b",
    "0a:09:b4:74:43:2c", "0a:09:b4:74:4f:5c", "0a:09:b4:74:55:93", "0a:09:b4:74:55:94",
    "0a:09:b4:75:00:f4", "0a:09:b4:75:00:fc", "0a:09:b4:75:01:04", "0a:09:b4:75:01:0b",
    "0a:09:b4:75:01:0c", "0a:09:b4:75:01:23", "0a:09:b4:75:01:2c", "0a:09:b4:75:55:9c",
    "0a:29:d5:5c:59:90", "0a:29:d5:5c:59:91", "0a:29:d5:5c:59:92", "0a:29:d5:5c:59:93",
    "0a:29:d5:5c:59:a0", "0a:29:d5:5c:59:a1", "0a:29:d5:5c:59:a2", "0a:29:d5:5c:59:a3",
    "0a:29:d5:5c:59:b0", "0a:29:d5:5c:59:b1", "0a:29:d5:5c:59:b2", "0a:29:d5:5c:59:b3",
    "0a:29:d5:5c:59:c0", "0a:29:d5:5c:59:c1", "0a:29:d5:5c:59:c2", "0a:29:d5:5c:59:c3",
    "0a:29:d5:5c:59:d0", "0a:29:d5:5c:59:d1", "0a:29:d5:5c:59:d2", "0a:29:d5:5c:59:d3",
    "0a:29:d5:5c:59:e0", "0a:29:d5:5c:59:e1", "0a:29:d5:5c:59:e2", "0a:29:d5:5c:59:e3",
    "0a:29:d5:5c:59:f0", "0a:29:d5:5c:59:f1", "0a:29:d5:5c:59:f2", "0a:29:d5:5c:59:f3",
    "0a:29:d5:5c:5a:00", "0a:29:d5:5c:5a:01", "0a:29:d5:5c:5a:02", "0a:29:d5:5c:5a:03",
    "0a:29:d5:5c:e7:50", "0a:29:d5:5c:e7:51", "0a:29:d5:5c:e7:52", "0a:29:d5:5c:e7:53",
    "0a:29:d5:5c:e7:60", "0a:29:d5:5c:e7:61", "0a:29:d5:5c:e7:62", "0a:29:d5:5c:e7:63",
    "0a:29:d5:5c:e7:70", "0a:29:d5:5c:e7:71", "0a:29:d5:5c:e7:72", "0a:29:d5:5c:e7:73",
    "0a:29:d5:5c:e7:80", "0a:29:d5:5c:e7:81", "0a:29:d5:5c:e7:82", "0a:29:d5:5c:e7:83",
    "0a:29:d5:5d:f8:20", "0a:29:d5:5d:f8:21", "0a:29:d5:5d:f8:22", "0a:29:d5:5d:f8:23",
    "0a:29:d5:5d:f8:40", "0a:29:d5:5d:f8:41", "0a:29:d5:5d:f8:42", "0a:29:d5:5d:f8:43",
    "0a:30:0d:83:6a:d1", "0a:30:0d:83:6a:d2", "0a:30:0d:83:6e:d2", "0a:30:0d:93:ce:b1",
    "0a:30:0d:93:ce:b6", "0a:30:0d:93:ce:b7", "0a:30:0d:93:ce:b8", "0a:30:0d:93:ce:b9",
    "0c:96:cd:79:96:72", "0c:96:cd:79:96:73", "0e:09:b4:74:39:0c", "0e:09:b4:74:43:0b",
    "0e:09:b4:74:43:0c", "0e:09:b4:74:43:2b", "0e:09:b4:74:43:2c", "0e:09:b4:74:4f:5c",
    "0e:09:b4:74:55:93", "0e:09:b4:74:55:94", "0e:09:b4:75:00:f4", "0e:09:b4:75:00:fc",
    "0e:09:b4:75:01:04", "0e:09:b4:75:01:0b", "0e:09:b4:75:01:0c", "0e:09:b4:75:01:23",
    "0e:09:b4:75:01:24", "0e:09:b4:75:01:2c", "0e:09:b4:75:55:9c", "10:e6:6b:0c:17:51",
    "12:09:b4:75:00:f4", "12:09:b4:75:00:fc", "12:96:cd:79:96:72", "12:96:cd:79:96:73",
    "16:09:b4:75:00:f4", "16:09:b4:75:00:fc", "16:09:b4:75:55:9c", "16:e6:6b:0c:17:51",
    "1a:e6:6b:0c:17:51", "1c:ec:72:04:04:84", "1c:ec:72:1d:86:de", "1c:ec:72:5f:9d:88",
    "1c:ec:72:5f:9d:89", "1e:96:cd:79:96:72", "22:ec:72:04:04:84", "22:ec:72:1d:86:de",
    "22:ec:72:5f:9d:89", "24:d1:3f:16:e5:cd", "28:4e:e9:08:c8:ac", "28:4e:e9:24:23:10",
    "28:4e:e9:93:48:c3", "28:4e:e9:93:48:c4", "2a:87:61:d8:e0:d2", "32:4e:e9:93:48:c3",
    "32:7b:d8:aa:4f:9b", "38:f4:5e:05:c7:22", "38:f4:5e:5a:a5:f4", "38:f4:5e:5a:a5:f5",
    "38:f4:5e:67:b8:98", "38:f4:5e:67:b8:99", "3a:4e:e9:08:c8:ac", "3a:f4:5e:47:b8:98",
    "3a:f4:5e:4a:a5:f4", "3c:67:fc:73:a9:d1", "3c:67:fd:50:26:3d", "3c:6a:d2:00:5d:c5",
    "40:18:b1:25:bf:54", "40:18:b1:25:bf:55", "40:18:b1:25:cd:e8", "40:18:b1:25:cd:e9",
    "40:18:b1:2b:30:68", "40:18:b1:2b:30:69", "40:18:b1:67:ef:54", "40:18:b1:67:ef:55",
    "40:18:b1:7a:a8:54", "40:18:b1:7a:a8:55", "42:f4:5e:05:c7:22", "50:91:e3:c6:21:73",
    "58:86:94:68:47:b8", "58:86:94:7c:00:04", "58:86:94:cf:36:00", "5a:86:94:49:b7:f8",
    "5a:86:94:cf:36:00", "60:29:d5:4f:d0:90", "60:29:d5:50:16:a4", "66:29:d5:4f:d0:90",
    "66:29:d5:50:16:a4", "70:5d:cc:31:f0:30", "70:5d:cc:34:7d:dc", "78:11:9d:e2:fd:ce",
    "78:54:2e:ab:f4:58", "80:03:84:3a:4b:1d", "80:03:84:fa:4b:1c", "80:ca:4b:f0:c8:67",
    "86:ca:4b:f0:c8:67", "8c:86:dd:80:0c:20", "90:9f:33:10:10:80", "9c:65:ee:1b:eb:b0",
    "a6:17:d8:90:94:c0", "a8:ca:b9:13:5a:11", "a8:ca:b9:13:5a:12", "a8:ca:b9:13:5a:13",
    "a8:ca:b9:13:5a:14", "a8:ca:b9:13:76:31", "a8:ca:b9:13:76:32", "a8:ca:b9:13:76:33",
    "ac:f1:df:5c:63:c3", "b0:38:6c:40:65:fc", "b2:38:6c:49:51:70", "b2:38:6c:49:aa:9c",
    "b2:38:6c:4e:c4:58", "b4:a9:4f:39:31:df", "b4:a9:4f:39:31:e0", "c6:a9:4f:39:31:df",
    "d2:8f:89:40:67:c5", "f6:ab:5c:8a:06:8d"
)

object FingerprintBuilder {

    // fetchAps 성공 전 기본값: acc/gyro/mag 각 3축 = 9개
    private var SENSOR_IDENTIFIERS: List<String> = listOf(
        "acc_wx", "acc_wy", "acc_wz",
        "gyro_wx", "gyro_wy", "gyro_wz",
        "mag_wx",  "mag_wy",  "mag_wz",
    )

    /** ScanLogger 등 외부에서 현재 센서 식별자 목록을 읽을 때 사용 */
    val sensorIdentifiers: List<String> get() = SENSOR_IDENTIFIERS

    /**
     * /api/v1/model/aps 응답으로 BLE_MACS / WIFI_MACS / SENSOR_IDENTIFIERS를 교체한다.
     * feature_idx 오름차순 정렬 → BLE 먼저, WiFi 이후, 센서 마지막 순서를 유지한다.
     * identifier는 소문자로 정규화한다 (BLE 응답이 대문자로 올 수 있음).
     */
    fun updateFromAps(aps: List<ApEntry>) {
        val sorted = aps.sortedBy { it.featureIdx }
        BLE_MACS           = sorted.filter { it.type == "ble"  }.map { it.identifier.lowercase() }.toTypedArray()
        WIFI_MACS          = sorted.filter { it.type == "wifi" }.map { it.identifier.lowercase() }.toTypedArray()
        SENSOR_IDENTIFIERS = sorted.filter { it.type != "ble" && it.type != "wifi" }.map { it.identifier.lowercase() }

        Log.i(TAG, "AP 목록 갱신 — BLE=${BLE_MACS.size} WiFi=${WIFI_MACS.size} Sensor=${SENSOR_IDENTIFIERS.size}${
            if (SENSOR_IDENTIFIERS.isNotEmpty()) " [${SENSOR_IDENTIFIERS.joinToString()}]" else ""
        }")
    }

    /**
     * fetchAps 의 센서 식별자 순서에 맞춰 FloatArray를 반환한다.
     * 알 수 없는 식별자는 0f로 채운다.
     */
    fun toSensorArray(signal: SensorSignal?): FloatArray {
        if (SENSOR_IDENTIFIERS.isEmpty()) return FloatArray(0)
        val s = signal ?: return FloatArray(SENSOR_IDENTIFIERS.size)
        return FloatArray(SENSOR_IDENTIFIERS.size) { i ->
            when (SENSOR_IDENTIFIERS[i]) {
                "acc_wx"  -> s.accelX
                "acc_wy"  -> s.accelY
                "acc_wz"  -> s.accelZ
                "gyro_wx" -> s.gyroX
                "gyro_wy" -> s.gyroY
                "gyro_wz" -> s.gyroZ
                "mag_wx"  -> s.magX
                "mag_wy"  -> s.magY
                "mag_wz"  -> s.magZ
                else      -> { Log.w(TAG, "알 수 없는 센서 식별자: ${SENSOR_IDENTIFIERS[i]}"); 0f }
            }
        }
    }

    /**
     * 스캔 결과를 고정 MAC 순서(BLE → WiFi)에 맞춰
     * MAC + RSSI 엔트리 목록으로 변환한다.
     */
    fun buildEntries(scanData: ScanData): List<FingerprintEntry> {
        val bleRssiMap  = scanData.bleSignals.associate  { it.deviceAddress.lowercase() to it.rssi }
        val wifiRssiMap = scanData.wifiSignals.associate { it.bssid.lowercase()         to it.rssi }

        val entries = ArrayList<FingerprintEntry>(BLE_MACS.size + WIFI_MACS.size)
        BLE_MACS.forEach  { mac -> entries.add(FingerprintEntry(mac, bleRssiMap[mac]  ?: MISSING_RSSI, isBle = true))  }
        WIFI_MACS.forEach { mac -> entries.add(FingerprintEntry(mac, wifiRssiMap[mac] ?: MISSING_RSSI, isBle = false)) }

        val matchCount = entries.count { it.rssi != MISSING_RSSI }
        Log.d(TAG, "핑거프린트 매칭 생성 완료")
        Log.d(TAG, "  크기=${entries.size}  매칭=$matchCount  미매칭=${entries.size - matchCount}")
        Log.d(TAG, "  BLE  [0..${BLE_MACS.size - 1}]: ${entries.take(BLE_MACS.size).map { it.rssi }.joinToString()}")
        Log.d(TAG, "  WiFi [${BLE_MACS.size}..${entries.size - 1}]: ${entries.drop(BLE_MACS.size).map { it.rssi }.joinToString()}")

        return entries
    }

    /** 서버 전송용 IntArray — buildEntries() 결과에서 RSSI만 추출 */
    fun toIntArray(entries: List<FingerprintEntry>): IntArray = IntArray(entries.size) { entries[it].rssi }
}
