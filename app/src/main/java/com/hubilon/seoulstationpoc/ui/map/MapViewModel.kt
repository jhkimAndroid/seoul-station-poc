package com.hubilon.seoulstationpoc.ui.map

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hubilon.seoulstationpoc.data.api.LocationApiClient
import com.hubilon.seoulstationpoc.data.ble.BleScanner
import com.hubilon.seoulstationpoc.data.filter.LocationKalmanFilter
import com.hubilon.seoulstationpoc.data.geojson.LinkParser
import com.hubilon.seoulstationpoc.data.lte.LteScanner
import com.hubilon.seoulstationpoc.data.fingerprint.FingerprintBuilder
import com.hubilon.seoulstationpoc.data.location.FusedLocationProvider
import com.hubilon.seoulstationpoc.data.location.LocationPipeline
import com.hubilon.seoulstationpoc.data.location.processor.KalmanProcessor
import com.hubilon.seoulstationpoc.data.location.processor.LinkMatchProcessor
import com.hubilon.seoulstationpoc.data.location.processor.LocationSourceType
import com.hubilon.seoulstationpoc.data.location.processor.ProcessContext
import com.hubilon.seoulstationpoc.data.location.processor.SmoothStepProcessor
import com.hubilon.seoulstationpoc.data.pdr.PdrProcessor
import com.hubilon.seoulstationpoc.data.fingerprint.FingerprintEntry
import com.hubilon.seoulstationpoc.data.fingerprint.MISSING_RSSI
import com.hubilon.seoulstationpoc.data.rtt.RttScanner
import com.hubilon.seoulstationpoc.data.sensor.SensorCollector
import com.hubilon.seoulstationpoc.data.wifi.WifiScanner
import com.hubilon.seoulstationpoc.model.BleSignal
import com.hubilon.seoulstationpoc.model.GeoPos
import com.hubilon.seoulstationpoc.model.LinkData
import com.hubilon.seoulstationpoc.model.LocationResult
import com.hubilon.seoulstationpoc.model.RttSignal
import com.hubilon.seoulstationpoc.model.ScanData
import com.hubilon.seoulstationpoc.model.WifiSignal
import com.hubilon.seoulstationpoc.util.AppLog
import com.hubilon.seoulstationpoc.util.AppLogger
import com.hubilon.seoulstationpoc.util.ScanLogger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
private const val ANCHOR_INTERVAL_MS          = 30_000L
private const val SCAN_INTERVAL_MS            = 1_000L
private const val LOCATION_HISTORY_MIN_DIST_M = 1.0
private const val LOCATION_HISTORY_MAX_SIZE   = 10
private const val SMOOTH_THRESHOLD_M          = 10.0  // 스무딩 발동 거리 기준
private const val SMOOTH_STEP_M               = 2.0   // 1회 이동 거리
private const val SMOOTH_OVERRIDE_COUNT       = 3     // 초과 시 서버좌표 직접 사용
private const val SMOOTH_DIR_TOLERANCE_DEG    = 45.0  // 첫 방향 기준 허용 각도

enum class FloorSelection { HIDDEN, F2, F3 }

sealed class ApLoadState {
    object Loading : ApLoadState()
    data class Success(val anchorCount: Int, val trackerCount: Int) : ApLoadState()
    data class Error(val message: String) : ApLoadState()
}

data class MapUiState(
    val scanData: ScanData = ScanData(),
    val isAutoPositioning: Boolean = false,
    val isTracking: Boolean = false,
    val trackerSmoothStep: Double = 2.0,
    val showTrackerSmoothDialog: Boolean = false,
    val serverLocation: LocationResult? = null,       // 서버측위 원본 (파란 마커)
    val kalmanFilteredLocation: LocationResult? = null, // 칼만필터 적용 (보라 마커)
    val finalLocation: LocationResult? = null,        // 파이프라인 최종 (빨간 마커)
    val pdrLocation: LocationResult? = null,          // PDR 위치 (노란 마커)
    val fusedLocation: LocationResult? = null,        // GPS (초록 마커)
    val rttLocation: LocationResult? = null,       // RTT 측위 (마커 미표시)
    val locationUpdateCount: Int = 0,
    val locationHistory: List<GeoPos> = emptyList(),
    val isPdrEnabled: Boolean = true,
    val isKalmanEnabled: Boolean = true,
    val kalmanMeasurementNoise: Double = 10.0,
    val kalmanProcessNoise: Double = 0.7,
    val showKalmanMeasurementDialog: Boolean = false,
    val showKalmanProcessDialog: Boolean = false,
    val rttSignals: List<RttSignal> = emptyList(),
    val errorMessage: String? = null,
    val selectedFloor: FloorSelection = FloorSelection.F3,
    val fingerprintEntries: List<FingerprintEntry>? = null,
    val apLoadState: ApLoadState = ApLoadState.Loading,
    val isTestMarkerEnabled: Boolean = false, // 테스트 마커 (GPS·서버·칼만·PDR)
    val isLinkEnabled: Boolean = true,  // 링크 폴리라인 표시
    val isLinkMatchingEnabled: Boolean = false,  // 터치 스냅 마커
    val linkTouchPoint: GeoPos? = null,
    val linkSnappedPoint: GeoPos? = null
)

class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val wifiScanner           = WifiScanner(application.applicationContext)
    private val bleScanner            = BleScanner(application.applicationContext)
    private val lteScanner            = LteScanner(application.applicationContext)
    private val sensorCollector       = SensorCollector(application.applicationContext)
    private val fusedLocationProvider = FusedLocationProvider(application.applicationContext)
    private val pdrProcessor          = PdrProcessor(application.applicationContext)
    private val appLogger             = AppLogger(application.applicationContext)
    private val apiClient             = LocationApiClient(appLogger)
    private val scanLogger            = ScanLogger(application.applicationContext)
    private val rttLocationFilter     = LocationKalmanFilter()
    private val rttScanner            = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        RttScanner(application.applicationContext)
    } else null

    // 위치 처리 파이프라인: 링크매칭 → 스무딩 → 칼만
    private val linkMatchProcessor  = LinkMatchProcessor()
    private val smoothStepProcessor = SmoothStepProcessor()
    private val kalmanProcessor     = KalmanProcessor()
    private val pipeline = LocationPipeline(listOf(linkMatchProcessor, smoothStepProcessor, kalmanProcessor))

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private var autoScanJob: Job? = null
    private var rttJob: Job? = null
    private var pdrOrigin: LocationResult? = null
    private var smoothCount = 0          // 연속 스무딩 발동 횟수
    private var smoothInitBearing: Double? = null  // 첫 발동 시 기준 방향

    var linkData: List<LinkData> = emptyList()
        private set

    init {
        loadFeatures()
        loadLinkData()
    }

    private fun loadLinkData() {
        viewModelScope.launch(Dispatchers.IO) {
            linkData = LinkParser.parse(
                getApplication<Application>().applicationContext,
                "link_3f.geojson"
            )
        }
    }

    fun toggleTestMarker() {
        _uiState.update { it.copy(isTestMarkerEnabled = !it.isTestMarkerEnabled) }
    }

    fun toggleLink() {
        _uiState.update { it.copy(isLinkEnabled = !it.isLinkEnabled) }
    }

    fun toggleLinkMatching() {
        val enabling = !_uiState.value.isLinkMatchingEnabled
        _uiState.update { it.copy(
            isLinkMatchingEnabled = enabling,
            linkTouchPoint    = null,
            linkSnappedPoint  = null
        )}
    }

    fun onMapTouched(lat: Double, lng: Double) {
        if (!_uiState.value.isLinkMatchingEnabled) return
        val snapped = linkMatchProcessor.findNearest(GeoPos(lat, lng), linkData)
        Log.i(TAG, "맵 터치 — touch=($lat, $lng) snapped=(${snapped?.lat}, ${snapped?.lng})")
        _uiState.update { it.copy(
            linkTouchPoint   = GeoPos(lat, lng),
            linkSnappedPoint = snapped
        )}
    }

    private fun loadFeatures() {
        viewModelScope.launch {
            try {
                val (anchorAps, trackerAps) = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val anchorDeferred  = async { apiClient.fetchAnchorFeatures() }
                        val trackerDeferred = async { apiClient.fetchTrackerFeatures() }
                        Pair(anchorDeferred.await(), trackerDeferred.await())
                    }
                }
                FingerprintBuilder.updateFromAnchorAps(anchorAps)
                FingerprintBuilder.updateFromTrackerAps(trackerAps)
                _uiState.update {
                    it.copy(apLoadState = ApLoadState.Success(anchorAps.size, trackerAps.size))
                }
            } catch (e: Exception) {
                Log.e(TAG, "피처 목록 로드 실패 — 기본값 유지: ${e.message}")
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

    fun togglePdr() {
        val enabling = !_uiState.value.isPdrEnabled
        if (enabling) {
            pdrProcessor.reset()
            pdrOrigin = _uiState.value.serverLocation
            _uiState.update { state ->
                state.copy(isPdrEnabled = true, pdrLocation = pdrOrigin)
            }
            Log.i(TAG, "PDR ON — 기준점=(${pdrOrigin?.lat}, ${pdrOrigin?.lng})")
        } else {
            pdrProcessor.reset()
            pdrOrigin = null
            _uiState.update { it.copy(isPdrEnabled = false, pdrLocation = null) }
            Log.i(TAG, "PDR OFF — 상태 초기화")
        }
    }

    fun toggleKalman() {
        val enabling = !_uiState.value.isKalmanEnabled
        if (enabling) {
            _uiState.update { it.copy(isKalmanEnabled = true) }
            Log.i(TAG, "칼만필터 ON")
            appLogger.i(TAG, "칼만필터 ON")
        } else {
            kalmanProcessor.reset()
            rttLocationFilter.reset()
            _uiState.update { it.copy(isKalmanEnabled = false) }
            Log.i(TAG, "칼만필터 OFF — 필터 초기화")
            appLogger.i(TAG, "칼만필터 OFF — 필터 초기화")
        }
    }

    fun showKalmanMeasurementDialog() { _uiState.update { it.copy(showKalmanMeasurementDialog = true) } }
    fun showKalmanProcessDialog()     { _uiState.update { it.copy(showKalmanProcessDialog = true) } }
    fun dismissKalmanDialogs()        { _uiState.update { it.copy(showKalmanMeasurementDialog = false, showKalmanProcessDialog = false) } }

    fun setKalmanMeasurementNoise(sigma: Double) {
        kalmanProcessor.filter.measurementNoiseSigma = sigma
        rttLocationFilter.measurementNoiseSigma = sigma
        _uiState.update { it.copy(kalmanMeasurementNoise = sigma, showKalmanMeasurementDialog = false) }
        Log.i(TAG, "칼만 측정노이즈 변경: $sigma")
    }

    fun setKalmanProcessNoise(sigma: Double) {
        kalmanProcessor.filter.processNoiseSigma = sigma
        rttLocationFilter.processNoiseSigma = sigma
        _uiState.update { it.copy(kalmanProcessNoise = sigma, showKalmanProcessDialog = false) }
        Log.i(TAG, "칼만 프로세스노이즈 변경: $sigma")
    }

    fun resetPdr() {
        pdrProcessor.reset()
        pdrOrigin = _uiState.value.serverLocation
        _uiState.update { state -> state.copy(pdrLocation = pdrOrigin) }
        Log.i(TAG, "PDR 초기화 — 새 기준점=(${pdrOrigin?.lat}, ${pdrOrigin?.lng})")
    }

    fun showTrackerSmoothDialog()    { _uiState.update { it.copy(showTrackerSmoothDialog = true) } }
    fun dismissTrackerSmoothDialog() { _uiState.update { it.copy(showTrackerSmoothDialog = false) } }

    fun setTrackerSmoothStep(step: Double) {
        Log.i(TAG, "트래커 smooth step 변경: ${step}m")
        _uiState.update { it.copy(trackerSmoothStep = step, showTrackerSmoothDialog = false) }
    }

    // 자동측위 토글
    // ON  → BLE 상시 스캔, 1s마다 tracker 위치추론 / 첫 실행 + 30s마다 anchor 위치추론
    // OFF → 코루틴 취소 → finally에서 BLE 중단 및 상태 초기화
    fun toggleAutoScan() {
        if (autoScanJob?.isActive == true) {
            Log.i(TAG, "자동측위 중지 요청")
            appLogger.i(TAG, "자동측위 중지 요청")
            autoScanJob?.cancel()
        } else {
            Log.i(TAG, "자동측위 시작 — tracker간격=${SCAN_INTERVAL_MS}ms anchor간격=${ANCHOR_INTERVAL_MS}ms")
            appLogger.i(TAG, "자동측위 시작")
            _uiState.update { it.copy(isAutoPositioning = true, errorMessage = null) }
            autoScanJob = viewModelScope.launch {
                val bleAccumulator  = mutableMapOf<String, BleSignal>()
                val wifiAccumulator = mutableMapOf<String, WifiSignal>()
                var pendingApiJob: Job? = null
                var cycleCount = 0
                var lastAnchorTimeMs = System.currentTimeMillis() - ANCHOR_INTERVAL_MS

                try {
                    withContext(Dispatchers.IO) { bleScanner.startScan() }
                    sensorCollector.start()
                    fusedLocationProvider.start(SCAN_INTERVAL_MS)
                    pdrProcessor.start()

                    // RTT 스캔 — 독립 주기 실행
                    if (rttScanner != null && rttScanner.isSupported) {
                        rttJob = launch {
                            while (isActive) {
                                val cycleStart = System.currentTimeMillis()
                                try {
                                    val signals = rttScanner.scan()
                                    _uiState.update { it.copy(rttSignals = signals) }
                                    if (signals.isNotEmpty()) {
                                        try {
                                            val raw = apiClient.rttPredict(signals)
                                            val loc = if (_uiState.value.isKalmanEnabled)
                                                rttLocationFilter.update(raw.lat, raw.lng) else raw
                                            _uiState.update { it.copy(rttLocation = loc) }
                                        } catch (e: CancellationException) { throw e
                                        } catch (e: Exception) {
                                            Log.w(TAG, "RTT predict 오류: ${e.message}")
                                        }
                                    }
                                } catch (e: CancellationException) { throw e
                                } catch (e: Exception) { Log.w(TAG, "RTT 스캔 오류: ${e.message}") }
                                val remaining = SCAN_INTERVAL_MS - (System.currentTimeMillis() - cycleStart)
                                if (remaining > 0) delay(remaining)
                            }
                        }
                        Log.i(TAG, "RTT 스캔 시작")
                    } else {
                        Log.d(TAG, "RTT 미지원 — 스캔 생략")
                    }

                    // GPS 위치 수신 → fusedLocation 갱신
                    launch {
                        fusedLocationProvider.locationFlow
                            .filterNotNull()
                            .collect { loc ->
                                _uiState.update { state -> state.copy(fusedLocation = loc) }
                            }
                    }

                    // PDR 걸음 감지 → pdrLocation 갱신
                    launch {
                        pdrProcessor.stepCount.collect {
                            _uiState.update { state ->
                                if (!state.isPdrEnabled) state
                                else state.copy(
                                    pdrLocation = pdrOrigin?.let { origin ->
                                        pdrProcessor.applyPdr(origin.lat, origin.lng)
                                    }
                                )
                            }
                        }
                    }

                    while (isActive) {
                        delay(SCAN_INTERVAL_MS)
                        val cycle = ++cycleCount
                        val nowMs = System.currentTimeMillis()

                        val newBle = withContext(Dispatchers.IO) { bleScanner.getSnapshotResults() }
                        for (signal in newBle) bleAccumulator[signal.deviceAddress] = signal

                        val newWifi = withContext(Dispatchers.IO) { wifiScanner.scan() }
                        for (signal in newWifi) wifiAccumulator[signal.bssid] = signal

                        val bleList    = bleAccumulator.values.toList()
                        val wifiList   = wifiAccumulator.values.toList()
                        val lteList    = withContext(Dispatchers.IO) { lteScanner.scan() }
                        val sensorSnap = sensorCollector.getSnapshot()

                        val isAnchorTime = (nowMs - lastAnchorTimeMs) >= ANCHOR_INTERVAL_MS
                        Log.d(TAG, "[자동측위] #$cycle — BLE누적=${bleList.size}(+${newBle.size}) WiFi누적=${wifiList.size}(+${newWifi.size}) LTE=${lteList.size} anchor=$isAnchorTime")

                        if (isAnchorTime) {
                            if (pendingApiJob?.isActive == true) {
                                Log.d(TAG, "[자동측위] 앵커 실행 — 트래커 취소 (#$cycle)")
                                pendingApiJob!!.cancel()
                            }
                            lastAnchorTimeMs = nowMs

                            val anchorScanData = ScanData(wifiList, bleList, sensorSnap, lteList)
                            bleAccumulator.clear()
                            wifiAccumulator.clear()

                            val entries = withContext(Dispatchers.Default) {
                                FingerprintBuilder.buildEntries(anchorScanData)
                            }
                            Log.d(TAG, "[자동측위] 앵커 핑거프린트 — 매칭=${entries.count { it.rssi != MISSING_RSSI }}/${entries.size}")
                            scanLogger.logScan(bleList, wifiList, entries, sensorSnap, lteList)
                            _uiState.update { it.copy(scanData = anchorScanData, fingerprintEntries = entries) }

                            pendingApiJob = launch {
                                try {
                                    val values = FingerprintBuilder.buildAnchorPayload(anchorScanData)
                                    if (values.isEmpty()) {
                                        Log.d(TAG, "[자동측위] 앵커 피처 없음 — 전송 생략 (#$cycle)")
                                        return@launch
                                    }
                                    val raw = apiClient.anchorPredict(values)
                                    val serverGeo = GeoPos(raw.lat, raw.lng)
                                    val prevFinal = _uiState.value.finalLocation?.let { GeoPos(it.lat, it.lng) }

                                    // Step 2: 최초 수신 시 서버좌표 = 최종좌표 (파이프라인 없이 직접 적용)
                                    if (prevFinal == null) {
                                        Log.i(TAG, "[앵커] #$cycle 초기 — raw=(${raw.lat},${raw.lng})")
                                        _uiState.update { state ->
                                            if (!state.isPdrEnabled) state.copy(
                                                serverLocation      = raw,
                                                finalLocation       = raw,
                                                locationHistory     = listOf(serverGeo),
                                                locationUpdateCount = state.locationUpdateCount + 1
                                            ) else {
                                                pdrProcessor.reset()
                                                pdrOrigin = raw
                                                state.copy(
                                                    serverLocation      = raw,
                                                    finalLocation       = raw,
                                                    pdrLocation         = raw,
                                                    locationHistory     = listOf(serverGeo),
                                                    locationUpdateCount = state.locationUpdateCount + 1
                                                )
                                            }
                                        }
                                    } else {
                                        // Step 3: 거리 체크 스무딩 → Step 4: Kalman → Step 5: LinkMatch
                                        val smoothed = applyDistanceSmoothing(serverGeo, prevFinal)
                                        val ctx = ProcessContext(
                                            previousFinal   = prevFinal,
                                            sourceType      = LocationSourceType.ANCHOR,
                                            isKalmanEnabled = _uiState.value.isKalmanEnabled,
                                            smoothStep      = _uiState.value.trackerSmoothStep,
                                            linkData        = linkData
                                        )
                                        val kalmanGeo    = kalmanProcessor.process(smoothed, ctx)
                                        val finalGeo     = linkMatchProcessor.process(kalmanGeo, ctx)
                                        val kalmanLoc    = LocationResult(kalmanGeo.lat, kalmanGeo.lng)
                                        val finalLoc     = LocationResult(finalGeo.lat, finalGeo.lng)
                                        Log.i(TAG, "[앵커] #$cycle — raw=(${raw.lat},${raw.lng}) smoothed=(${smoothed.lat},${smoothed.lng}) kalman=(${kalmanLoc.lat},${kalmanLoc.lng}) final=(${finalLoc.lat},${finalLoc.lng})")
                                        _uiState.update { state ->
                                            val history = updateLocationHistory(state.locationHistory, finalGeo)
                                            if (!state.isPdrEnabled) state.copy(
                                                serverLocation         = raw,
                                                kalmanFilteredLocation = kalmanLoc,
                                                finalLocation          = finalLoc,
                                                locationHistory        = history,
                                                locationUpdateCount    = state.locationUpdateCount + 1
                                            ) else {
                                                pdrProcessor.reset()
                                                pdrOrigin = finalLoc
                                                state.copy(
                                                    serverLocation         = raw,
                                                    kalmanFilteredLocation = kalmanLoc,
                                                    finalLocation          = finalLoc,
                                                    pdrLocation            = finalLoc,
                                                    locationHistory        = history,
                                                    locationUpdateCount    = state.locationUpdateCount + 1
                                                )
                                            }
                                        }
                                    }
                                } catch (e: CancellationException) {
                                    // 정상 흐름 — 앵커 실행에 의한 취소
                                } catch (e: Exception) {
                                    Log.w(TAG, "[자동측위] 앵커 #$cycle API 오류: ${e.message}")
                                }
                            }
                        } else if (pendingApiJob?.isActive != true) {
                            // 트래커 실행: 진행 중인 요청 없을 때만
                            val trackerScanData = ScanData(wifiList, bleList, sensorSnap, lteList)

                            pendingApiJob = launch {
                                try {
                                    val values = FingerprintBuilder.buildTrackerPayload(trackerScanData)
                                    if (values.isEmpty()) {
                                        Log.d(TAG, "[자동측위] 트래커 피처 없음 — 전송 생략 (#$cycle)")
                                        return@launch
                                    }
                                    val pdrLoc = _uiState.value.pdrLocation
                                    val raw = if (pdrLoc != null)
                                        apiClient.trackerPredict(values, pdrLoc.lat, pdrLoc.lng)
                                    else
                                        apiClient.trackerPredict(values)

                                    val serverGeo = GeoPos(raw.lat, raw.lng)
                                    val prevFinal = _uiState.value.finalLocation?.let { GeoPos(it.lat, it.lng) }

                                    // Step 2: 최초 수신 시 서버좌표 = 최종좌표
                                    if (prevFinal == null) {
                                        Log.i(TAG, "[트래커] #$cycle 초기 — raw=(${raw.lat},${raw.lng})")
                                        _uiState.update { state ->
                                            state.copy(
                                                serverLocation      = raw,
                                                finalLocation       = raw,
                                                locationHistory     = listOf(serverGeo),
                                                locationUpdateCount = state.locationUpdateCount + 1
                                            )
                                        }
                                    } else {
                                        // Step 3: 거리 체크 스무딩 → Step 4: Kalman → Step 5: LinkMatch
                                        val smoothed = applyDistanceSmoothing(serverGeo, prevFinal)
                                        val ctx = ProcessContext(
                                            previousFinal   = prevFinal,
                                            sourceType      = LocationSourceType.TRACKER,
                                            isKalmanEnabled = _uiState.value.isKalmanEnabled,
                                            smoothStep      = _uiState.value.trackerSmoothStep,
                                            linkData        = linkData
                                        )
                                        val kalmanGeo    = kalmanProcessor.process(smoothed, ctx)
                                        val finalGeo     = linkMatchProcessor.process(kalmanGeo, ctx)
                                        val kalmanLoc    = LocationResult(kalmanGeo.lat, kalmanGeo.lng)
                                        val finalLoc     = LocationResult(finalGeo.lat, finalGeo.lng)
                                        Log.i(TAG, "[트래커] #$cycle — raw=(${raw.lat},${raw.lng}) smoothed=(${smoothed.lat},${smoothed.lng}) kalman=(${kalmanLoc.lat},${kalmanLoc.lng}) final=(${finalLoc.lat},${finalLoc.lng})")
                                        _uiState.update { state ->
                                            state.copy(
                                                serverLocation         = raw,
                                                kalmanFilteredLocation = kalmanLoc,
                                                finalLocation          = finalLoc,
                                                locationHistory        = updateLocationHistory(state.locationHistory, finalGeo),
                                                locationUpdateCount    = state.locationUpdateCount + 1
                                            )
                                        }
                                    }
                                } catch (e: CancellationException) {
                                    // 정상 흐름
                                } catch (e: Exception) {
                                    Log.w(TAG, "[자동측위] 트래커 #$cycle API 오류: ${e.message}")
                                }
                            }
                        } else {
                            Log.d(TAG, "[자동측위] 트래커 #$cycle 건너뜀 — 이전 요청 진행 중")
                        }
                    }
                } finally {
                    withContext(NonCancellable) {
                        withContext(Dispatchers.IO) { bleScanner.stopScan() }
                        sensorCollector.stop()
                        fusedLocationProvider.stop()
                        pdrProcessor.stop()
                    }
                    kalmanProcessor.reset()
                    rttLocationFilter.reset()
                    pdrOrigin = null
                    smoothCount = 0
                    smoothInitBearing = null
                    rttJob?.cancel()
                    rttJob = null
                    Log.i(TAG, "[자동측위] 종료")
                    appLogger.i(TAG, "[자동측위] 종료")
                    _uiState.update {
                        it.copy(
                            isAutoPositioning         = false,
                            rttSignals             = emptyList(),
                            rttLocation            = null,
                            serverLocation         = null,
                            kalmanFilteredLocation = null,
                            finalLocation          = null,
                            pdrLocation            = null,
                            fusedLocation          = null,
                            locationHistory        = emptyList()
                        )
                    }
                }
            }
        }
    }

    /**
     * 화면 종료 직전에 호출 — 코루틴 취소 + BLE 즉시 중단 + 로그 파일 닫기.
     */
    fun shutDown() {
        Log.i(TAG, "shutDown — 자동스캔 및 BLE 즉시 정리")
        appLogger.i(TAG, "shutDown — 자동스캔 및 BLE 즉시 정리")
        autoScanJob?.cancel()
        rttJob?.cancel()
        bleScanner.stopScan()
        scanLogger.close()
        appLogger.close()
    }

    // Step 6: 최종좌표 이력 갱신 — 이전 좌표와 0.1m 이상 차이날 때만 추가, 최대 10개 유지
    private fun updateLocationHistory(history: List<GeoPos>, newPos: GeoPos): List<GeoPos> {
        val last = history.lastOrNull()
        if (last != null && geoDistanceM(last.lat, last.lng, newPos.lat, newPos.lng) < LOCATION_HISTORY_MIN_DIST_M) {
            return history
        }
        return (history + newPos).takeLast(LOCATION_HISTORY_MAX_SIZE)
    }

    /**
     * 서버측위와 이전 최종좌표의 거리가 SMOOTH_THRESHOLD_M 이상이면 스무딩 적용.
     * 같은 방향(첫 발동 기준 ±SMOOTH_DIR_TOLERANCE_DEG)으로 SMOOTH_OVERRIDE_COUNT 초과 연속 발동 시
     * 서버좌표를 바로 반환 (빠른 이동으로 판단).
     */
    private fun applyDistanceSmoothing(serverGeo: GeoPos, prevFinal: GeoPos): GeoPos {
        val dist = geoDistanceM(prevFinal.lat, prevFinal.lng, serverGeo.lat, serverGeo.lng)
        if (dist < SMOOTH_THRESHOLD_M) {
            // 정상 범위: 카운터 리셋 후 서버좌표 그대로 사용
            smoothCount = 0
            smoothInitBearing = null
            return serverGeo
        }

        val bearing = bearingDeg(prevFinal.lat, prevFinal.lng, serverGeo.lat, serverGeo.lng)
        val initBearing = smoothInitBearing
        val isSameDir = initBearing == null ||
                angleDiffDeg(initBearing, bearing) <= SMOOTH_DIR_TOLERANCE_DEG

        if (isSameDir) {
            if (initBearing == null) smoothInitBearing = bearing  // 첫 발동: 기준 방향 고정
            smoothCount++
        } else {
            // 방향 이탈: 새로운 기준으로 리셋
            smoothCount = 1
            smoothInitBearing = bearing
        }

        return if (smoothCount > SMOOTH_OVERRIDE_COUNT) {
            // 같은 방향으로 3번 초과 연속 → 실제 이동으로 판단, 서버좌표 직접 사용
            Log.i(TAG, "[스무딩] override smoothCount=$smoothCount bearing=${"%.1f".format(bearing)}°")
            serverGeo
        } else {
            // 스무딩: prevFinal → serverGeo 방향으로 SMOOTH_STEP_M 이동
            val ratio = SMOOTH_STEP_M / dist
            Log.i(TAG, "[스무딩] step smoothCount=$smoothCount dist=${"%.1f".format(dist)}m bearing=${"%.1f".format(bearing)}°")
            GeoPos(
                prevFinal.lat + (serverGeo.lat - prevFinal.lat) * ratio,
                prevFinal.lng + (serverGeo.lng - prevFinal.lng) * ratio
            )
        }
    }

    private fun bearingDeg(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLng  = (lng2 - lng1) * PI / 180.0
        val lat1R = lat1 * PI / 180.0
        val lat2R = lat2 * PI / 180.0
        val y = sin(dLng) * cos(lat2R)
        val x = cos(lat1R) * sin(lat2R) - sin(lat1R) * cos(lat2R) * cos(dLng)
        return (atan2(y, x) * 180.0 / PI + 360.0) % 360.0
    }

    private fun angleDiffDeg(a: Double, b: Double): Double {
        var diff = ((b - a) % 360.0 + 360.0) % 360.0
        if (diff > 180.0) diff -= 360.0
        return abs(diff)
    }

    private fun geoDistanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R    = 6371000.0
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLng = (lng2 - lng1) * PI / 180.0
        val a    = sin(dLat / 2).pow(2) +
                   cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sin(dLng / 2).pow(2)
        return R * 2.0 * asin(sqrt(a))
    }

    override fun onCleared() {
        super.onCleared()
        shutDown()
    }
}
