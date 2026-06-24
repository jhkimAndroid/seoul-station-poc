package com.hubilon.positioning.internal.log

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.hubilon.positioning.internal.engine.FingerprintBuilder
import com.hubilon.positioning.model.BleSignal
import com.hubilon.positioning.model.FingerprintEntry
import com.hubilon.positioning.model.LteSignal
import com.hubilon.positioning.model.MISSING_RSSI
import com.hubilon.positioning.model.SensorSignal
import com.hubilon.positioning.model.WifiSignal
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "SSP_APP"

internal class ScanLogger(context: Context, private val fingerprintBuilder: FingerprintBuilder, filePrefix: String = "SSP") {

    private var rawStream: OutputStream?     = null
    private var predictStream: OutputStream? = null
    private var rawRowCount     = 0
    private var predictRowCount = 0
    private val cycleTimeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    init {
        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            rawStream     = openStream(context, "${filePrefix}_${stamp}_RAW.csv")
            predictStream = openStream(context, "${filePrefix}_${stamp}_PRED.csv")
            writeLine(rawStream,     "카운트", "타임스탬프", "타입", "이름", "맥주소", "RSSI", "매칭")
            writeLine(predictStream, "카운트", "타임스탬프", "타입", "이름", "맥주소", "값",   "매칭")
            Log.i(TAG, "스캔 로그 생성: ${filePrefix}_${stamp}_RAW/PRED.csv")
        } catch (e: Exception) {
            Log.e(TAG, "스캔 로그 파일 생성 실패: ${e.message}", e)
        }
    }

    fun logScan(
        bleSignals: List<BleSignal>,
        wifiSignals: List<WifiSignal>,
        entries: List<FingerprintEntry>,
        sensor: SensorSignal? = null,
        lteSignals: List<LteSignal> = emptyList()
    ) {
        val ts = cycleTimeFmt.format(Date())
        val ssidMap     = wifiSignals.associate { it.bssid.lowercase() to it.ssid }
        val matchedMacs = entries.filter { it.rssi != MISSING_RSSI }.mapTo(HashSet()) { it.mac }

        for (signal in bleSignals) {
            val matched = if (matchedMacs.contains(signal.deviceAddress.lowercase())) "Y" else "N"
            writeLine(rawStream, (++rawRowCount).toString(), ts, "BLE", "", signal.deviceAddress, signal.rssi.toString(), matched)
        }
        for (signal in wifiSignals) {
            val matched = if (matchedMacs.contains(signal.bssid.lowercase())) "Y" else "N"
            writeLine(rawStream, (++rawRowCount).toString(), ts, "WIFI", signal.ssid, signal.bssid, signal.rssi.toString(), matched)
        }
        for (signal in lteSignals) {
            val tag = if (signal.isRegistered) "서빙" else "주변"
            writeLine(rawStream, (++rawRowCount).toString(), ts, "LTE", tag, "${signal.pci}:${signal.tac}", "rsrp=${signal.rsrp} rsrq=${signal.rsrq}", "")
        }
        for (entry in entries) {
            val type    = if (entry.isBle) "BLE" else "WIFI"
            val name    = if (entry.isBle) "" else ssidMap[entry.mac] ?: ""
            val matched = if (entry.rssi != MISSING_RSSI) "Y" else "N"
            writeLine(predictStream, (++predictRowCount).toString(), ts, type, name, entry.mac, entry.rssi.toString(), matched)
        }
        for (signal in lteSignals) {
            val id = "${signal.pci}:${signal.tac}"
            writeLine(predictStream, (++predictRowCount).toString(), ts, "LTE_RSRP", "", id, signal.rsrp.toString(), "Y")
            writeLine(predictStream, (++predictRowCount).toString(), ts, "LTE_RSRQ", "", id, signal.rsrq.toString(), "Y")
        }
        if (sensor != null) {
            val fmt = "%.4f"
            val sensorValues = fingerprintBuilder.toSensorArray(sensor)
            fingerprintBuilder.sensorIdentifiers.forEachIndexed { i, name ->
                val value = fmt.format(sensorValues.getOrElse(i) { 0f })
                writeLine(predictStream, (++predictRowCount).toString(), ts, "SENSOR", name, "", value, "")
            }
        }
    }

    fun close() {
        try { rawStream?.close();     rawStream = null;     Log.i(TAG, "RAW 로그 닫힘 (총 ${rawRowCount}행)") }
        catch (e: Exception) { Log.e(TAG, "RAW 로그 닫기 실패: ${e.message}") }
        try { predictStream?.close(); predictStream = null; Log.i(TAG, "PRED 로그 닫힘 (총 ${predictRowCount}행)") }
        catch (e: Exception) { Log.e(TAG, "PRED 로그 닫기 실패: ${e.message}") }
    }

    private fun openStream(context: Context, fileName: String): OutputStream? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/SSP")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let { context.contentResolver.openOutputStream(it) }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SSP")
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
