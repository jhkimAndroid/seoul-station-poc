package com.hubilon.seoulstationpoc.data.wifi

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.hubilon.seoulstationpoc.model.WifiSignal
import com.hubilon.seoulstationpoc.util.AppLog

private const val TAG = AppLog.WIFI

class WifiScanner(context: Context) {

    private val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun isEnabled(): Boolean = wifiManager.isWifiEnabled

    fun scan(): List<WifiSignal> {
        if (!wifiManager.isWifiEnabled) {
            Log.w(TAG, "WiFi 비활성화 상태 — 스캔 생략")
            return emptyList()
        }
        return try {
            val signals = wifiManager.scanResults.map { result ->
                WifiSignal(
                    ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        result.wifiSsid?.toString()?.trim('"') ?: ""
                    } else {
                        @Suppress("DEPRECATION")
                        result.SSID ?: ""
                    },
                    bssid = result.BSSID ?: "",
                    rssi = result.level
                )
            }
            Log.i(TAG, "캐시 조회 완료 — ${signals.size}개")
            if (signals.isNotEmpty()) {
                signals.forEachIndexed { i, s ->
                    Log.d(TAG, "  [$i] ssid=${s.ssid.ifEmpty { "(hidden)" }} bssid=${s.bssid} rssi=${s.rssi}dBm")
                }
            }
            signals
        } catch (e: SecurityException) {
            Log.e(TAG, "권한 없음: ${e.message}")
            emptyList()
        }
    }
}
