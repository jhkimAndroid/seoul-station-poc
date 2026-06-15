package com.hubilon.seoulstationpoc.data.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import com.hubilon.seoulstationpoc.domain.model.BleSignal
import com.hubilon.seoulstationpoc.util.AppLog

private const val TAG = AppLog.BLE

class BleScanner(context: Context) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    // 콜백 스레드(메인)와 IO 스레드에서 동시 접근 가능 → synchronized 보호
    private val collectedResults = mutableListOf<BleSignal>()
    private var activeScanCallback: ScanCallback? = null

    fun isAvailable(): Boolean = bluetoothAdapter?.isEnabled == true

    fun startScan() {
        // 이전 스캔이 남아 있으면 먼저 정리 (중복 시작 방지)
        stopScan()
        synchronized(collectedResults) { collectedResults.clear() }

        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            Log.w(TAG, "스캐너 없음 — Bluetooth 비활성화 상태")
            return
        }

        activeScanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
//                if(checkBle(result)) {
                    val signal = BleSignal(result.device.address, result.rssi)
                    synchronized(collectedResults) {
                        val idx =
                            collectedResults.indexOfFirst { it.deviceAddress == signal.deviceAddress }
                        if (idx >= 0) {
                            collectedResults[idx] = signal  // 동일 MAC: 최신 RSSI로 갱신
                        } else {
                            collectedResults.add(signal)
                            Log.d(
                                TAG,
                                "발견 [${collectedResults.size}] addr=${signal.deviceAddress} rssi=${signal.rssi}dBm"
                            )
                        }
                    }
//                }
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

    fun checkBle(result: ScanResult): Boolean {
        Log.d("checkBle", "ADDRESS : ${result.device.address}, RSSI : ${result.rssi}")

        val scanRecord = result.scanRecord
        // 1. Manufacturer Specific Data 가져오기
        val manufacturerData = scanRecord?.getManufacturerSpecificData(0x004C)
        if (manufacturerData != null && manufacturerData.size >= 23) {
            // 2. 여기서 데이터 구조를 파싱하여 iBeacon 인지 확인
            // Byte 0-1: Beacon Type/Length
            // Byte 2-17: Proximity UUID
            // Byte 18-19: Major
            // Byte 20-21: Minor
            // Byte 22: Measured Power (RSSI)
            Log.i("checkBle", "비콘 감지됨: ${result.device.address}, RSSI ${result.rssi}")
            return true
        }

        return false
    }

    /** 현재까지 수집된 결과를 가져오고 버퍼를 비움 (자동스캔 인터벌마다 새 데이터만 반환) */
    fun getSnapshotResults(): List<BleSignal> {
        val snapshot = synchronized(collectedResults) {
            val copy = collectedResults.toList()
            collectedResults.clear()
            copy
        }
        Log.d(TAG, "스냅샷 — ${snapshot.size}개 (버퍼 초기화)")
        return snapshot
    }

    /** 결과 없이 스캔만 중단 */
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
