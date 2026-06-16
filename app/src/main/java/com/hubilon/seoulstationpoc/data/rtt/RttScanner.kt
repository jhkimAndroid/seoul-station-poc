package com.hubilon.seoulstationpoc.data.rtt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.util.Log
import androidx.annotation.RequiresApi
import com.hubilon.seoulstationpoc.model.RttSignal
import com.hubilon.seoulstationpoc.util.AppLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = AppLog.RTT

private val TARGET_BSSIDS = setOf(
    "00:09:b4:75:55:9c",
    "06:09:b4:75:01:24",
    "06:09:b4:75:55:9c",
    "0a:09:b4:75:01:24",
    "0a:29:d5:5c:59:b0",
    "0a:29:d5:5c:59:b1",
    "0a:29:d5:5c:59:d2",
    "0a:29:d5:5c:59:e1",
    "0a:29:d5:5c:59:f0",
    "0a:29:d5:5c:59:f1",
    "0a:29:d5:5c:59:f2",
    "0a:29:d5:5c:59:f3",
    "0a:29:d5:5c:5a:01",
    "0a:29:d5:5c:5a:02",
    "0a:29:d5:5c:5a:03",
    "0a:29:d5:5c:e7:70",
    "0a:29:d5:5c:e7:71",
    "0a:29:d5:5c:e7:72",
    "0a:29:d5:5c:e7:73",
    "0a:29:d5:5c:e7:80",
    "0a:29:d5:5c:e7:82",
    "0e:09:b4:75:01:24",
    "0e:09:b4:75:55:9c",
    "16:09:b4:75:55:9c",
    "1c:ec:72:5f:9d:89",
    "22:ec:72:5f:9d:89",
    "66:29:d5:50:16:a4",
    // 휴빌론
//    "24:e5:0f:21:ff:2f",
//    "24:e5:0f:21:88:3d",
//    "24:e5:0f:21:cd:25"
)

@RequiresApi(Build.VERSION_CODES.P)
class RttScanner(context: Context) {
    private val appContext = context.applicationContext
    private val rttManager = appContext.getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? WifiRttManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    val isSupported: Boolean get() =
        rttManager != null &&
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)

    suspend fun scan(): List<RttSignal> {
        val rtt = rttManager
        if (rtt == null) {
            Log.w(TAG, "WifiRttManager 없음")
            return emptyList()
        }
        if (!isSupported) {
            Log.w(TAG, "WiFi RTT 미지원 기기")
            return emptyList()
        }

        val scanResults = try {
            @Suppress("DEPRECATION")
            wifiManager.scanResults
        } catch (e: SecurityException) {
            Log.w(TAG, "scanResults 접근 권한 없음: ${e.message}")
            return emptyList()
        }

        Log.d(TAG, "전체 스캔 결과 ${scanResults.size}개:")
        scanResults.forEach { Log.d(TAG, "  BSSID=${it.BSSID}  SSID=${it.SSID}  level=${it.level}") }

        val targets = scanResults.filter { it.BSSID.lowercase() in TARGET_BSSIDS }
        if (targets.isEmpty()) {
            Log.d(TAG, "대상 AP 없음 — 캐시된 스캔 결과 ${scanResults.size}개 중 타겟 BSSID 없음")
            return emptyList()
        }
        Log.d(TAG, "RTT 대상 AP ${targets.size}개 발견")

        // Android 12+는 NEARBY_WIFI_DEVICES, 그 이하는 ACCESS_FINE_LOCATION 필요
        val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.NEARBY_WIFI_DEVICES
        else
            Manifest.permission.ACCESS_FINE_LOCATION

        if (appContext.checkSelfPermission(requiredPermission) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RTT 권한 없음: $requiredPermission")
            return emptyList()
        }

        val request = RangingRequest.Builder().apply {
            targets.forEach { addAccessPoint(it) }
        }.build()

        return suspendCancellableCoroutine { cont ->
            try {
                rtt.startRanging(request, appContext.mainExecutor, object : RangingResultCallback() {
                    override fun onRangingResults(results: MutableList<RangingResult>) {
                        val signals = results.mapNotNull { result ->
                            if (result.status == RangingResult.STATUS_SUCCESS) {
                                RttSignal(
                                    bssid            = result.macAddress.toString(),
                                    distanceMm       = result.distanceMm,
                                    distanceStdDevMm = result.distanceStdDevMm,
                                    rssi             = result.rssi,
                                    successCount     = result.numSuccessfulMeasurements,
                                    attemptCount     = result.numAttemptedMeasurements
                                )
                            } else {
                                Log.d(TAG, "RTT 실패 — bssid=${result.macAddress} status=${result.status}")
                                null
                            }
                        }
                        Log.i(TAG, "RTT 결과: ${signals.size}/${results.size}개 성공")
                        signals.forEach { s ->
                            val distM = s.distanceMm / 1000.0
                            Log.d(TAG, "  ${s.bssid}: %.2fm ±${s.distanceStdDevMm}mm RSSI=${s.rssi}dBm (${s.successCount}/${s.attemptCount})".format(distM))
                        }
                        cont.resume(signals)
                    }

                    override fun onRangingFailure(code: Int) {
                        Log.w(TAG, "RTT ranging 실패 — code=$code")
                        cont.resume(emptyList())
                    }
                })
            } catch (e: SecurityException) {
                Log.w(TAG, "startRanging SecurityException: ${e.message}")
                cont.resume(emptyList())
            }
        }
    }
}
