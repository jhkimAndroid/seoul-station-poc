package com.hubilon.positioning.scan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.hubilon.positioning.model.WifiSignal
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "SSP_WIFI"

class WifiScanner(context: Context) {

    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun isEnabled(): Boolean = wifiManager.isWifiEnabled

    /**
     * 시스템 WiFi 스캔이 완료될 때마다 최신 결과를 emit하는 Flow.
     * Flow가 활성화되는 순간 첫 스캔을 시작한다.
     */
    fun resultsFlow(): Flow<List<WifiSignal>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                val results = readResults()
                Log.i(TAG, "브로드캐스트 수신 — fresh=$updated AP=${results.size}개")
                trySend(results)
            }
        }
        appContext.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        wifiManager.startScan()
        awaitClose { appContext.unregisterReceiver(receiver) }
    }

    private fun readResults(): List<WifiSignal> {
        if (!wifiManager.isWifiEnabled) return emptyList()
        return try {
            wifiManager.scanResults.map { result ->
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
        } catch (e: SecurityException) {
            Log.e(TAG, "권한 없음: ${e.message}")
            emptyList()
        }
    }
}
