package com.hubilon.positioning.scan

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import com.hubilon.positioning.model.BleSignal

private const val TAG = "SSP_BLE"

class BleScanner(context: Context) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val collectedResults = mutableListOf<BleSignal>()
    private var activeScanCallback: ScanCallback? = null

    fun isAvailable(): Boolean = bluetoothAdapter?.isEnabled == true

    fun startScan() {
        stopScan()
        synchronized(collectedResults) { collectedResults.clear() }

        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            Log.w(TAG, "스캐너 없음 — Bluetooth 비활성화 상태")
            return
        }

        activeScanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val signal = BleSignal(result.device.address, result.rssi)
                synchronized(collectedResults) {
                    val idx = collectedResults.indexOfFirst { it.deviceAddress == signal.deviceAddress }
                    if (idx >= 0) collectedResults[idx] = signal
                    else {
                        collectedResults.add(signal)
                        Log.d(TAG, "발견 [${collectedResults.size}] addr=${signal.deviceAddress} rssi=${signal.rssi}dBm")
                    }
                }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "스캔 실패 — errorCode=$errorCode")
                activeScanCallback = null
            }
        }

        try {
            scanner.startScan(activeScanCallback)
            Log.i(TAG, "스캔 시작")
        } catch (e: SecurityException) {
            Log.e(TAG, "권한 없음: ${e.message}")
            activeScanCallback = null
        }
    }

    /** 현재까지 수집된 결과를 반환하고 버퍼를 비운다. */
    fun getSnapshotResults(): List<BleSignal> {
        val snapshot = synchronized(collectedResults) {
            val copy = collectedResults.toList()
            collectedResults.clear()
            copy
        }
        Log.d(TAG, "스냅샷 — ${snapshot.size}개 (버퍼 초기화)")
        return snapshot
    }

    fun stopScan() {
        activeScanCallback?.let { cb ->
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(cb)
                Log.i(TAG, "스캔 중단")
            } catch (_: SecurityException) {
                Log.e(TAG, "스캔 중단 실패 — 권한 없음")
            }
        }
        activeScanCallback = null
    }
}
