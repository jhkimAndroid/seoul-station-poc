package com.hubilon.seoulstationpoc.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.hubilon.seoulstationpoc.data.fingerprint.FingerprintBuilder
import com.hubilon.seoulstationpoc.data.fingerprint.FingerprintEntry
import com.hubilon.seoulstationpoc.data.fingerprint.MISSING_RSSI
import com.hubilon.seoulstationpoc.model.BleSignal
import com.hubilon.seoulstationpoc.model.LteSignal
import com.hubilon.seoulstationpoc.model.SensorSignal
import com.hubilon.seoulstationpoc.model.WifiSignal
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = AppLog.APP

class ScanLogger(context: Context) {

    private var rawStream: OutputStream? = null      // 파일 1: 스캔 원본 데이터
    private var predictStream: OutputStream? = null  // 파일 2: 서버 전송 데이터
    private var rawRowCount = 0
    private var predictRowCount = 0

    private val cycleTimeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    init {
        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            rawStream     = openStream(context, "SSP_${stamp}_RAW.csv")
            predictStream = openStream(context, "SSP_${stamp}_PRED.csv")

            // 파일 1 헤더: 실제 스캔된 신호
            writeLine(rawStream, "카운트", "타임스탬프", "타입", "이름", "맥주소", "RSSI", "매칭")
            // 파일 2 헤더: 서버 전송 피처 (856개)
            writeLine(predictStream, "카운트", "타임스탬프", "타입", "이름", "맥주소", "값", "매칭")

            Log.i(TAG, "스캔 로그 생성: SSP_${stamp}_RAW.csv / SSP_${stamp}_PRED.csv")
        } catch (e: Exception) {
            Log.e(TAG, "스캔 로그 파일 생성 실패: ${e.message}", e)
        }
    }

    /**
     * 한 사이클의 스캔 결과를 두 파일에 동일한 타임스탬프로 기록한다.
     *
     * 파일 1 (RAW): 실제 감지된 BLE/WiFi 신호만 저장.
     * 파일 2 (PRED): 서버 전송과 동일한 856개 피처 저장.
     *   - entries (미감지 -110 포함, feature_idx 순서)
     *   - sensor 최대 18행 (acc/gyro/mag 각 body(x/y/z) + world(wx/wy/wz))
     */
    fun logScan(
        bleSignals: List<BleSignal>,
        wifiSignals: List<WifiSignal>,
        entries: List<FingerprintEntry>,
        sensor: SensorSignal? = null,
        lteSignals: List<LteSignal> = emptyList()
    ) {
        val ts = cycleTimeFmt.format(Date())
        val ssidMap      = wifiSignals.associate { it.bssid.lowercase() to it.ssid }
        val matchedMacs  = entries.filter { it.rssi != MISSING_RSSI }.mapTo(HashSet()) { it.mac }

        // ── 파일 1: 스캔 원본 ──────────────────────────────────────────
        for (signal in bleSignals) {
            val matched = if (matchedMacs.contains(signal.deviceAddress.lowercase())) "Y" else "N"
            writeLine(
                rawStream,
                (++rawRowCount).toString(), ts,
                "BLE", "", signal.deviceAddress, signal.rssi.toString(), matched
            )
        }
        for (signal in wifiSignals) {
            val matched = if (matchedMacs.contains(signal.bssid.lowercase())) "Y" else "N"
            writeLine(
                rawStream,
                (++rawRowCount).toString(), ts,
                "WIFI", signal.ssid, signal.bssid, signal.rssi.toString(), matched
            )
        }
        for (signal in lteSignals) {
            val tag = if (signal.isRegistered) "서빙" else "주변"
            writeLine(
                rawStream,
                (++rawRowCount).toString(), ts,
                "LTE", tag, "${signal.pci}:${signal.tac}",
                "rsrp=${signal.rsrp} rsrq=${signal.rsrq}", ""
            )
        }

        // ── 파일 2: 서버 전송 피처 ───────────────────────────
        for (entry in entries) {
            val type    = if (entry.isBle) "BLE" else "WIFI"
            val name    = if (entry.isBle) "" else ssidMap[entry.mac] ?: ""
            val matched = if (entry.rssi != MISSING_RSSI) "Y" else "N"
            writeLine(
                predictStream,
                (++predictRowCount).toString(), ts,
                type, name, entry.mac, entry.rssi.toString(), matched
            )
        }
        for (signal in lteSignals) {
            val id = "${signal.pci}:${signal.tac}"
            writeLine(predictStream, (++predictRowCount).toString(), ts, "LTE_RSRP", "", id, signal.rsrp.toString(), "Y")
            writeLine(predictStream, (++predictRowCount).toString(), ts, "LTE_RSRQ", "", id, signal.rsrq.toString(), "Y")
        }
        if (sensor != null) {
            val fmt = "%.4f"
            val sensorValues = FingerprintBuilder.toSensorArray(sensor)
            FingerprintBuilder.sensorIdentifiers.forEachIndexed { i, name ->
                val value = fmt.format(sensorValues.getOrElse(i) { 0f })
                writeLine(predictStream, (++predictRowCount).toString(), ts, "SENSOR", name, "", value, "")
            }
        }
    }

    fun close() {
        try {
            rawStream?.close()
            rawStream = null
            Log.i(TAG, "RAW 로그 닫힘 (총 ${rawRowCount}행)")
        } catch (e: Exception) {
            Log.e(TAG, "RAW 로그 닫기 실패: ${e.message}")
        }
        try {
            predictStream?.close()
            predictStream = null
            Log.i(TAG, "PRED 로그 닫힘 (총 ${predictRowCount}행)")
        } catch (e: Exception) {
            Log.e(TAG, "PRED 로그 닫기 실패: ${e.message}")
        }
    }

    private fun openStream(context: Context, fileName: String): OutputStream? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/SSP")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            )
            uri?.let { context.contentResolver.openOutputStream(it) }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "SSP"
            )
            dir.mkdirs()
            File(dir, fileName).outputStream()
        }
    }

    private fun writeLine(stream: OutputStream?, vararg fields: String) {
        try {
            val line = fields.joinToString(",") { field ->
                if (field.contains(',') || field.contains('"') || field.contains('\n'))
                    "\"${field.replace("\"", "\"\"")}\""
                else field
            } + "\n"
            stream?.write(line.toByteArray(Charsets.UTF_8))
            stream?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "CSV 쓰기 실패: ${e.message}")
        }
    }
}
