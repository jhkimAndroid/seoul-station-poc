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
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI
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
private const val ANCHOR_INTERVAL_MS = 30_000L
private const val TRACKER_SMOOTH_THRESHOLD_M = 5.0   // 이 거리 이상이면 전처리 적용
private const val SCAN_INTERVAL_MS            = 1_000L

enum class FloorSelection { HIDDEN, F2, F3 }

sealed class ApLoadState {
    object Loading : ApLoadState()
    data class Success(val anchorCount: Int, val trackerCount: Int) : ApLoadState()
    data class Error(val message: String) : ApLoadState()
}

data class MapUiState(
    val scanData: ScanData = ScanData(),
    val isAutoScanning: Boolean = false,
    val isTracking: Boolean = false,
    val trackerSmoothStep: Double = 2.0,
    val showTrackerSmoothDialog: Boolean = false,
    val location: LocationResult? = null,           // tracker 원본 (빨간)
    val finalLocation: LocationResult? = null,      // smooth+칼만 최종 (검은)
    val locationUpdateCount: Int = 0,
    val isPdrEnabled: Boolean = true,
    val isKalmanEnabled: Boolean = false,
    val kalmanMeasurementNoise: Double = 5.0,
    val kalmanProcessNoise: Double = 0.5,
    val showKalmanMeasurementDialog: Boolean = false,
    val showKalmanProcessDialog: Boolean = false,
    val rttSignals: List<RttSignal> = emptyList(),
    val rttLocation: LocationResult? = null,          // RTT 측위 (보라)
    val pdrServerLocation: LocationResult? = null,  // 서버측위 + PDR (주황)
    val fusedLocation: LocationResult? = null,      // GPS (녹색)
    val errorMessage: String? = null,
    val selectedFloor: FloorSelection = FloorSelection.HIDDEN,
    val fingerprintEntries: List<FingerprintEntry>? = null,
    val apLoadState: ApLoadState = ApLoadState.Loading,
    val isLinkTestEnabled: Boolean = false,
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
    private val locationFilter        = LocationKalmanFilter()
    private val rttLocationFilter     = LocationKalmanFilter(measurementNoiseSigma = 3.0)
    private val rttScanner            = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        RttScanner(application.applicationContext)
    } else null

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private var autoScanJob: Job? = null
    private var rttJob: Job? = null
    private var pdrOrigin: LocationResult? = null   // PDR 기준점 — 최초 서버측위 결과로 고정

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

    fun toggleLinkTest() {
        val enabling = !_uiState.value.isLinkTestEnabled
        _uiState.update { it.copy(
            isLinkTestEnabled  = enabling,
            linkTouchPoint     = null,
            linkSnappedPoint   = null
        )}
    }

    fun onMapTouched(lat: Double, lng: Double) {
        if (!_uiState.value.isLinkTestEnabled) return
        val snapped = nearestPointOnLinks(lat, lng)
        Log.i(TAG, "맵 터치 — touch=(${lat}, ${lng}) snapped=(${snapped?.lat}, ${snapped?.lng})")
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
            pdrOrigin = _uiState.value.location   // 현재 서버 위치를 PDR 기준점으로 고정
            _uiState.update { state ->
                state.copy(
                    isPdrEnabled      = true,
                    pdrServerLocation = pdrOrigin   // 변위 0이므로 기준점과 동일
                )
            }
            Log.i(TAG, "PDR ON — 기준점=(${pdrOrigin?.lat}, ${pdrOrigin?.lng})")
        } else {
            pdrProcessor.reset()
            pdrOrigin = null
            _uiState.update { it.copy(isPdrEnabled = false, pdrServerLocation = null) }
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
            locationFilter.reset()
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
        locationFilter.measurementNoiseSigma = sigma
        rttLocationFilter.measurementNoiseSigma = sigma
        _uiState.update { it.copy(kalmanMeasurementNoise = sigma, showKalmanMeasurementDialog = false) }
        Log.i(TAG, "칼만 측정노이즈 변경: $sigma")
    }

    fun setKalmanProcessNoise(sigma: Double) {
        locationFilter.processNoiseSigma = sigma
        rttLocationFilter.processNoiseSigma = sigma
        _uiState.update { it.copy(kalmanProcessNoise = sigma, showKalmanProcessDialog = false) }
        Log.i(TAG, "칼만 프로세스노이즈 변경: $sigma")
    }

    fun resetPdr() {
        pdrProcessor.reset()
        pdrOrigin = _uiState.value.location   // 현재 서버 위치를 새 기준점으로
        _uiState.update { state ->
            state.copy(pdrServerLocation = pdrOrigin)   // 변위 0 → 기준점과 동일
        }
        Log.i(TAG, "PDR 초기화 — 새 기준점=(${pdrOrigin?.lat}, ${pdrOrigin?.lng})")
    }

    fun showTrackerSmoothDialog()  { _uiState.update { it.copy(showTrackerSmoothDialog = true) } }
    fun dismissTrackerSmoothDialog() { _uiState.update { it.copy(showTrackerSmoothDialog = false) } }
    fun setTrackerSmoothStep(step: Double) {
        Log.i(TAG, "트래커 smooth step 변경: ${step}m")
        _uiState.update { it.copy(trackerSmoothStep = step, showTrackerSmoothDialog = false) }
    }

    // 자동측위 토글
    // ON  → BLE 상시 스캔, 1s마다 tracker 위치추론 / 첫 실행 + 40s마다 anchor 위치추론
    // OFF → 코루틴 취소 → finally에서 BLE 중단 및 상태 초기화
    fun toggleAutoScan() {
        if (autoScanJob?.isActive == true) {
            Log.i(TAG, "자동측위 중지 요청")
            appLogger.i(TAG, "자동측위 중지 요청")
            autoScanJob?.cancel()
        } else {
            val intervalMs = SCAN_INTERVAL_MS
            Log.i(TAG, "자동측위 시작 — tracker간격=${intervalMs}ms anchor간격=${ANCHOR_INTERVAL_MS}ms")
            appLogger.i(TAG, "자동측위 시작 — tracker간격=${intervalMs}ms anchor간격=${ANCHOR_INTERVAL_MS}ms")
            _uiState.update { it.copy(isAutoScanning = true, errorMessage = null) }
            autoScanJob = viewModelScope.launch {
                val bleAccumulator  = mutableMapOf<String, BleSignal>()
                val wifiAccumulator = mutableMapOf<String, WifiSignal>()
                var pendingApiJob: Job? = null
                var cycleCount = 0
                // lastAnchorTimeMs를 현재 - ANCHOR_INTERVAL_MS 로 설정하면 첫 사이클에 앵커 실행
                var lastAnchorTimeMs = System.currentTimeMillis() - ANCHOR_INTERVAL_MS

                try {
                    withContext(Dispatchers.IO) { bleScanner.startScan() }
                    sensorCollector.start()
                    fusedLocationProvider.start(intervalMs)
                    pdrProcessor.start()

                    // RTT 스캔 — 자동측위와 동일한 intervalMs 주기로 독립 실행
                    if (rttScanner != null && rttScanner.isSupported) {
                        rttJob = launch {
                            while (isActive) {
                                val cycleStart = System.currentTimeMillis()
                                try {
                                    val signals = rttScanner.scan()
                                    _uiState.update { it.copy(rttSignals = signals) }
                                    if (signals.isNotEmpty()) {
                                        appLogger.d(TAG, "RTT AP ${signals.size}개 발견")
                                        try {
                                            val raw = apiClient.rttPredict(signals)
                                            val loc = if (_uiState.value.isKalmanEnabled)
                                                rttLocationFilter.update(raw.lat, raw.lng) else raw
                                            _uiState.update { it.copy(rttLocation = loc) }
                                            Log.i(TAG, "RTT 측위 — raw=(${raw.lat},${raw.lng}) filtered=(${loc.lat},${loc.lng})")
                                            appLogger.i(TAG, "RTT 측위 결과 — raw=(${raw.lat},${raw.lng}) filtered=(${loc.lat},${loc.lng})")
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            Log.w(TAG, "RTT predict 오류: ${e.message}")
                                            appLogger.w(TAG, "RTT predict 오류: ${e.message}")
                                        }
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.w(TAG, "RTT 스캔 오류: ${e.message}")
                                    appLogger.w(TAG, "RTT 스캔 오류: ${e.message}")
                                }
                                val remaining = intervalMs - (System.currentTimeMillis() - cycleStart)
                                if (remaining > 0) delay(remaining)
                            }
                        }
                        Log.i(TAG, "RTT 스캔 시작")
                        appLogger.i(TAG, "RTT 스캔 시작 (intervalMs=${intervalMs}ms)")
                    } else {
                        Log.d(TAG, "RTT 미지원 — 스캔 생략")
                    }

                    // GPS 위치 수신 → GPS 마커 갱신
                    launch {
                        fusedLocationProvider.locationFlow
                            .filterNotNull()
                            .collect { loc ->
                                _uiState.update { state -> state.copy(fusedLocation = loc) }
                            }
                    }

                    // PDR 걸음 감지 → 기준점(pdrOrigin)에 누적 변위 재적용
                    launch {
                        pdrProcessor.stepCount.collect {
                            _uiState.update { state ->
                                if (!state.isPdrEnabled) state
                                else state.copy(
                                    pdrServerLocation = pdrOrigin?.let { origin ->
                                        pdrProcessor.applyPdr(origin.lat, origin.lng)
                                    }
                                )
                            }
                        }
                    }

                    while (isActive) {
                        delay(intervalMs)
                        val cycle = ++cycleCount
                        val nowMs = System.currentTimeMillis()

                        // BLE/WiFi 스냅샷 → 누적 버퍼 병합
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
                            // 앵커 실행: 진행 중인 트래커 취소
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
//                                    val prev = _uiState.value.finalLocation ?: _uiState.value.location
//                                    val adjusted = smoothTrackerLocation(raw, prev)
//                                    val finalLoc = if (_uiState.value.isKalmanEnabled)
//                                        locationFilter.update(raw.lat, raw.lng) else raw
                                    val finalLoc = raw
                                    Log.i(TAG, "[자동측위] 앵커 #$cycle 수신 — raw=(${raw.lat},${raw.lng}) final=(${finalLoc.lat},${finalLoc.lng})")
                                    _uiState.update { state ->
                                        if (!state.isPdrEnabled) state.copy(
                                            location            = raw,
                                            finalLocation       = finalLoc,
                                            locationUpdateCount = state.locationUpdateCount + 1
                                        ) else {
                                            // 앵커 결과로 PDR 기준점 리셋
                                            pdrProcessor.reset()
                                            pdrOrigin = finalLoc
                                            Log.i(TAG, "PDR 기준점 리셋(앵커) — (${finalLoc.lat}, ${finalLoc.lng})")
                                            state.copy(
                                                location            = raw,
                                                finalLocation       = finalLoc,
                                                pdrServerLocation   = finalLoc,   // 변위 0 → 기준점과 동일
                                                locationUpdateCount = state.locationUpdateCount + 1
                                            )
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
                                    val pdrLoc = _uiState.value.pdrServerLocation
                                    val raw = if (pdrLoc != null)
                                        apiClient.trackerPredict(values, pdrLoc.lat, pdrLoc.lng)
                                    else
                                        apiClient.trackerPredict(values)
                                    val prev = _uiState.value.finalLocation ?: _uiState.value.location
                                    val adjusted = smoothTrackerLocation(raw, prev)
                                    val finalLoc = if (_uiState.value.isKalmanEnabled)
                                        locationFilter.update(adjusted.lat, adjusted.lng) else adjusted
                                    Log.i(TAG, "[자동측위] 트래커 #$cycle — raw=(${raw.lat},${raw.lng}) final=(${finalLoc.lat},${finalLoc.lng})")
                                    // 트래커: 원본→빨간 마커, smooth+칼만→검은 마커, PDR 무영향
                                    _uiState.update { state ->
                                        state.copy(
                                            location            = raw,
                                            finalLocation       = finalLoc,
                                            locationUpdateCount = state.locationUpdateCount + 1
                                        )
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
                    locationFilter.reset()
                    rttLocationFilter.reset()
                    pdrOrigin = null
                    rttJob?.cancel()
                    rttJob = null
                    Log.i(TAG, "[자동측위] 종료")
                    appLogger.i(TAG, "[자동측위] 종료")
                    _uiState.update {
                        it.copy(
                            isAutoScanning    = false,
                            rttSignals        = emptyList(),
                            rttLocation       = null,
                            location          = null,
                            finalLocation     = null,
                            pdrServerLocation = null,
                            fusedLocation     = null
                        )
                    }
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
        appLogger.i(TAG, "shutDown — 자동스캔 및 BLE 즉시 정리")
        autoScanJob?.cancel()
        rttJob?.cancel()
        bleScanner.stopScan()
        scanLogger.close()
        appLogger.close()
    }

    /**
     * 트래커 좌표 전처리 — 이전 마커와의 거리가 THRESHOLD_M 이상이면
     * 이전 위치에서 현재 방향으로 STEP_M 만큼 이동한 좌표를 반환한다.
     */
    private fun smoothTrackerLocation(
        raw: LocationResult,
        prev: LocationResult?
    ): LocationResult {
        if (prev == null) return raw

        val metersPerDegLat = 111320.0
        val metersPerDegLng = metersPerDegLat * cos(prev.lat * PI / 180.0)

        val dLatM = (raw.lat - prev.lat) * metersPerDegLat
        val dLngM = (raw.lng - prev.lng) * metersPerDegLng
        val dist  = sqrt(dLatM * dLatM + dLngM * dLngM)

        if (dist < TRACKER_SMOOTH_THRESHOLD_M) return raw

        val scale = _uiState.value.trackerSmoothStep / dist
        return LocationResult(
            lat = prev.lat + dLatM * scale / metersPerDegLat,
            lng = prev.lng + dLngM * scale / metersPerDegLng
        )
    }

    /**
     * 입력 좌표에서 각 링크(시작점–끝점 선분)에 내린 수선의 발 중 가장 가까운 점을 반환한다.
     * 수선의 발이 선분 범위 밖이면 지리 거리 기준으로 가까운 끝점을 사용한다.
     */
    fun nearestPointOnLinks(lat: Double, lng: Double): GeoPos? {
        if (linkData.isEmpty()) return null

        val current = GeoPos(lat, lng)
        var minDist = Double.MAX_VALUE
        var nearest: GeoPos? = null

        for (link in linkData) {
            val (footLat, footLng, dist) = linkCrossPoint(link.startPos, link.endPos, current)
            if (dist < minDist) {
                minDist = dist
                nearest = GeoPos(footLat, footLng)
            }
        }

        return nearest
    }

    // 선분(start→end) 위에서 current 에 가장 가까운 점과 그 거리를 반환한다.
    private fun linkCrossPoint(start: GeoPos, end: GeoPos, current: GeoPos): Triple<Double, Double, Double> {
        val factor = linkProjectionFactor(start, end, current)
        val distStart = geoDistanceM(start.lat, start.lng, current.lat, current.lng)
        val distEnd   = geoDistanceM(end.lat,   end.lng,   current.lat, current.lng)

        return if (factor > 0.0 && factor < 1.0) {
            when {
                distStart == 0.0 -> Triple(current.lat, current.lng, 0.0)
                distEnd   == 0.0 -> Triple(current.lat, current.lng, 0.0)
                else -> {
                    val footLat = start.lat + factor * (end.lat - start.lat)
                    val footLng = start.lng + factor * (end.lng - start.lng)
                    val dist = geoDistanceM(footLat, footLng, current.lat, current.lng)
                    Triple(footLat, footLng, dist)
                }
            }
        } else {
            if (distStart <= distEnd)
                Triple(start.lat, start.lng, distStart)
            else
                Triple(end.lat, end.lng, distEnd)
        }
    }

    // lat/lng 원좌표계에서 투영 계수를 계산한다 (0이면 start, 1이면 end)
    private fun linkProjectionFactor(start: GeoPos, end: GeoPos, current: GeoPos): Double {
        if (start.lng == current.lng && start.lat == current.lat) return 0.0
        if (end.lng == current.lng && end.lat == current.lat) return 1.0
        val dx = end.lng - start.lng
        val dy = end.lat - start.lat
        val lenSq = dx * dx + dy * dy
        if (lenSq <= 0.0) return 0.0
        return ((current.lng - start.lng) * dx + (current.lat - start.lat) * dy) / lenSq
    }

    // Haversine 공식으로 두 위경도 좌표 간 거리(m)를 반환한다
    private fun geoDistanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLng = (lng2 - lng1) * PI / 180.0
        val a = sin(dLat / 2).pow(2) +
                cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sin(dLng / 2).pow(2)
        return R * 2.0 * asin(sqrt(a))
    }

    override fun onCleared() {
        super.onCleared()
        shutDown()
    }
}
