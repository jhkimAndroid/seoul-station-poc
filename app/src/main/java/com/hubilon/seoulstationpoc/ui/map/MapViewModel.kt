package com.hubilon.seoulstationpoc.ui.map

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hubilon.seoulstationpoc.data.api.LocationApiClient
import com.hubilon.seoulstationpoc.data.ble.BleScanner
import com.hubilon.seoulstationpoc.data.fingerprint.FingerprintBuilder
import com.hubilon.seoulstationpoc.data.location.FusedLocationProvider
import com.hubilon.seoulstationpoc.data.pdr.PdrProcessor
import com.hubilon.seoulstationpoc.data.fingerprint.FingerprintEntry
import com.hubilon.seoulstationpoc.data.fingerprint.MISSING_RSSI
import com.hubilon.seoulstationpoc.data.sensor.SensorCollector
import com.hubilon.seoulstationpoc.data.wifi.WifiScanner
import com.hubilon.seoulstationpoc.domain.model.BleSignal
import com.hubilon.seoulstationpoc.domain.model.LocationResult
import com.hubilon.seoulstationpoc.domain.model.ScanData
import com.hubilon.seoulstationpoc.domain.model.WifiSignal
import com.hubilon.seoulstationpoc.util.AppLog
import com.hubilon.seoulstationpoc.util.ScanLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = AppLog.VM

enum class FloorSelection { HIDDEN, F2, F3 }

sealed class ApLoadState {
    object Loading : ApLoadState()
    data class Success(val bleCount: Int, val wifiCount: Int) : ApLoadState()
    data class Error(val message: String) : ApLoadState()
}

data class MapUiState(
    val scanData: ScanData = ScanData(),
    val isAutoScanning: Boolean = false,
    val isTracking: Boolean = false,
    val scanIntervalMs: Long = 1_000L,
    val location: LocationResult? = null,           // 서버측위 (빨간)
    val locationUpdateCount: Int = 0,
    val pdrServerLocation: LocationResult? = null,  // 서버측위 + PDR (주황)
    val fusedLocation: LocationResult? = null,      // GPS (녹색)
    val pdrFusedLocation: LocationResult? = null,   // GPS + PDR (연두)
    val errorMessage: String? = null,
    val selectedFloor: FloorSelection = FloorSelection.HIDDEN,
    val fingerprintEntries: List<FingerprintEntry>? = null,
    val apLoadState: ApLoadState = ApLoadState.Loading
)

class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val wifiScanner           = WifiScanner(application.applicationContext)
    private val bleScanner            = BleScanner(application.applicationContext)
    private val sensorCollector       = SensorCollector(application.applicationContext)
    private val fusedLocationProvider = FusedLocationProvider(application.applicationContext)
    private val pdrProcessor          = PdrProcessor(application.applicationContext)
    private val apiClient             = LocationApiClient()
    private val scanLogger           = ScanLogger(application.applicationContext)

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private var autoScanJob: Job? = null

    init {
        loadAps()
    }

    private fun loadAps() {
        viewModelScope.launch {
            try {
                val aps = withContext(Dispatchers.IO) { apiClient.fetchAps() }
                FingerprintBuilder.updateFromAps(aps)
                val bleCount = aps.count { it.type == "ble" }
                val wifiCount = aps.count { it.type == "wifi" }
                _uiState.update { it.copy(apLoadState = ApLoadState.Success(bleCount, wifiCount)) }
            } catch (e: Exception) {
                Log.e(TAG, "AP 목록 로드 실패 — 기본값 유지: ${e.message}")
                _uiState.update { it.copy(apLoadState = ApLoadState.Error(e.message ?: "알 수 없는 오류")) }
            }
        }
    }

    fun setFloor(floor: FloorSelection) {
        _uiState.update { it.copy(selectedFloor = floor) }
    }

    fun toggleTracking() {
        val newValue = !_uiState.value.isTracking
        Log.i(TAG, "추적 ${if (newValue) "ON" else "OFF"}")
        _uiState.update { it.copy(isTracking = newValue) }
    }

    fun setScanInterval(ms: Long) {
        if (_uiState.value.isAutoScanning) return
        Log.i(TAG, "수집주기 변경: ${ms}ms")
        _uiState.update { it.copy(scanIntervalMs = ms) }
    }

    // 자동측위 토글
    // ON  → BLE 상시 스캔, scanIntervalMs마다 누적 버퍼를 서버에 전송 후 버퍼 초기화
    // OFF → 코루틴 취소 → finally에서 BLE 중단 및 상태 초기화
    fun toggleAutoScan() {
        if (autoScanJob?.isActive == true) {
            Log.i(TAG, "자동측위 중지 요청")
            autoScanJob?.cancel()
        } else {
            // 시작 시점의 간격을 캡처 — 스캔 중에는 변경 불가
            val intervalMs = _uiState.value.scanIntervalMs
            Log.i(TAG, "자동측위 시작 — 간격=${intervalMs}ms")
            _uiState.update { it.copy(isAutoScanning = true, errorMessage = null) }
            autoScanJob = viewModelScope.launch {
                // MAC/BSSID 기준 누적 버퍼: 동일 주소는 최신 RSSI로 갱신
                val bleAccumulator = mutableMapOf<String, BleSignal>()
                val wifiAccumulator = mutableMapOf<String, WifiSignal>()
                var pendingApiJob: Job? = null
                var cycleCount = 0

                try {
                    withContext(Dispatchers.IO) { bleScanner.startScan() }
                    sensorCollector.start()
                    fusedLocationProvider.start(intervalMs)
                    pdrProcessor.start()

                    // GPS 위치 수신 → GPS 마커 + GPS+PDR 마커 동시 갱신
                    launch {
                        fusedLocationProvider.locationFlow
                            .filterNotNull()
                            .collect { loc ->
                                _uiState.update { it.copy(
                                    fusedLocation    = loc,
                                    pdrFusedLocation = pdrProcessor.applyPdr(loc.lat, loc.lng)
                                ) }
                            }
                    }

                    // PDR 걸음 감지 → 현재 서버/GPS 좌표에 최신 변위 재적용
                    launch {
                        pdrProcessor.stepCount.collect {
                            val state = _uiState.value
                            _uiState.update { it.copy(
                                pdrServerLocation = state.location?.let { loc -> pdrProcessor.applyPdr(loc.lat, loc.lng) },
                                pdrFusedLocation  = state.fusedLocation?.let { loc -> pdrProcessor.applyPdr(loc.lat, loc.lng) }
                            ) }
                        }
                    }

                    while (isActive) {
                        delay(intervalMs)
                        cycleCount++

                        // BLE 스냅샷 → 누적 버퍼 병합 (동일 MAC: 최신 RSSI 덮어쓰기)
                        val newBle = withContext(Dispatchers.IO) { bleScanner.getSnapshotResults() }
                        for (signal in newBle) bleAccumulator[signal.deviceAddress] = signal

                        // WiFi 조회 → 누적 버퍼 병합 (동일 BSSID: 최신 RSSI 덮어쓰기)
                        val newWifi = withContext(Dispatchers.IO) { wifiScanner.scan() }
                        for (signal in newWifi) wifiAccumulator[signal.bssid] = signal

                        val bleList    = bleAccumulator.values.toList()
                        val wifiList   = wifiAccumulator.values.toList()
                        val sensorSnap = sensorCollector.getSnapshot()
                        Log.d(TAG, "자동측위 #$cycleCount — BLE누적=${bleList.size}(+${newBle.size}), WiFi누적=${wifiList.size}(+${newWifi.size}), sensor=${sensorSnap}")

                        if (bleList.isEmpty() && wifiList.isEmpty() && FingerprintBuilder.sensorIdentifiers.isEmpty()) {
                            Log.d(TAG, "자동측위 #$cycleCount — 누적 데이터 없음, 전송 생략")
                            continue
                        }

                        val scanData = ScanData(wifiList, bleList, sensorSnap)
                        val entries = withContext(Dispatchers.Default) {
                            FingerprintBuilder.buildEntries(scanData)
                        }
                        val matchCount = entries.count { it.rssi != MISSING_RSSI }
                        Log.d(TAG, "핑거프린트 — 매칭=${matchCount}/${entries.size}")

                        // 서버 전송 시점에 누적 버퍼 초기화 및 CSV 로그 기록
                        bleAccumulator.clear()
                        wifiAccumulator.clear()
                        Log.d(TAG, "자동측위 #$cycleCount — 버퍼 초기화, 서버 전송")

                        scanLogger.logScan(bleList, wifiList, entries, sensorSnap)

                        _uiState.update { it.copy(scanData = scanData, fingerprintEntries = entries) }

                        // 이전 요청이 진행 중이면 취소하고 최신 데이터로 교체
                        if (pendingApiJob?.isActive == true) {
                            Log.d(TAG, "이전 API 요청 취소 — #$cycleCount 데이터로 교체")
                            pendingApiJob.cancel()
                        }
                        pendingApiJob = launch {
                            try {
                                val sensorValues = FingerprintBuilder.toSensorArray(sensorSnap)
                                val location = apiClient.predict(FingerprintBuilder.toIntArray(entries), sensorValues)
                                Log.i(TAG, "자동측위 #$cycleCount 위치 수신 — lat=${location.lat}, lng=${location.lng}")
                                _uiState.update { it.copy(
                                    location          = location,
                                    pdrServerLocation = pdrProcessor.applyPdr(location.lat, location.lng),
                                    locationUpdateCount = it.locationUpdateCount + 1
                                ) }
                            } catch (e: CancellationException) {
                                // 새 요청으로 교체됐을 때 — 정상 흐름
                            } catch (e: Exception) {
                                Log.w(TAG, "자동측위 #$cycleCount API 오류: ${e.message}")
                            }
                        }
                    }
                } finally {
                    withContext(NonCancellable) {
                        withContext(Dispatchers.IO) { bleScanner.stopScan() }
                        sensorCollector.stop()
                        fusedLocationProvider.stop()
                        pdrProcessor.stop()
                    }
                    Log.i(TAG, "자동측위 종료")
                    _uiState.update { it.copy(
                        isAutoScanning    = false,
                        location          = null,
                        pdrServerLocation = null,
                        fusedLocation     = null,
                        pdrFusedLocation  = null
                    ) }
                }
            }
        }
    }

    /**
     * 화면 종료 직전에 호출 — 코루틴 취소 + BLE 즉시 중단 + 로그 파일 닫기.
     * onCleared()와 달리 동기적으로 실행되므로 Process.killProcess() 직전에 사용한다.
     */
    fun shutDown() {
        Log.i(TAG, "shutDown — 자동스캔 및 BLE 즉시 정리")
        autoScanJob?.cancel()
        bleScanner.stopScan()
        scanLogger.close()
    }

    override fun onCleared() {
        super.onCleared()
        shutDown()
    }
}
