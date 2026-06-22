package com.hubilon.seoulstationpoc.ui.map

import android.app.Application
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hubilon.seoulstationpoc.SeoulStationPocApplication
import com.hubilon.seoulstationpoc.data.api.LocationApiClient
import com.hubilon.seoulstationpoc.data.ble.BleScanner
import com.hubilon.seoulstationpoc.data.filter.LocationKalmanFilter
import com.hubilon.seoulstationpoc.data.geojson.LinkParser
import com.hubilon.seoulstationpoc.data.lte.LteScanner
import com.hubilon.seoulstationpoc.data.fingerprint.FingerprintBuilder
import com.hubilon.seoulstationpoc.data.location.FusedLocationProvider
import com.hubilon.seoulstationpoc.data.location.processor.KalmanProcessor
import com.hubilon.seoulstationpoc.data.location.processor.LinkMatchProcessor
import com.hubilon.seoulstationpoc.data.location.processor.LocationSourceType
import com.hubilon.seoulstationpoc.data.location.processor.ProcessContext
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
private const val PDR_RESET_INTERVAL_DEFAULT_S  = 10        // PDR 주기 리셋 기본값(초)
private const val SCAN_INTERVAL_MS            = 1_000L
private const val LOCATION_HISTORY_MIN_DIST_M = 1.0
private const val LOCATION_HISTORY_MAX_SIZE   = 20

private const val MBR_MIN_LAT = 37.5533828
private const val MBR_MAX_LAT = 37.55571757
private const val MBR_MIN_LNG = 126.96928985
private const val MBR_MAX_LNG = 126.97222419
//private const val MBR_MIN_LAT = 37.553826
//private const val MBR_MAX_LAT = 37.554289
//private const val MBR_MIN_LNG = 126.970123
//private const val MBR_MAX_LNG = 126.971101

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
    val isTestMarkerEnabled: Boolean = SeoulStationPocApplication.IS_TEST, // 테스트 마커 (GPS·서버·칼만·PDR)
    val isLinkEnabled: Boolean = SeoulStationPocApplication.IS_TEST,  // 링크 폴리라인 표시
    val isLinkMatchingEnabled: Boolean = false,  // 터치 스냅 마커
    val linkTouchPoint: GeoPos? = null,
    val linkSnappedPoint: GeoPos? = null,
    val pdrResetIntervalSec: Int = PDR_RESET_INTERVAL_DEFAULT_S,  // PDR 갱신 주기 (초)
    val showPdrResetIntervalDialog: Boolean = false,
    val anchorDirectionLabel: String? = null,  // "정방향" | "역방향" | null
    val stepLengthM: Float = 0.6f,             // 현재 설정된 보폭 (m)
    val isOutsideMbr: Boolean = false          // GPS가 MBR 구역 밖에 있음
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
//    private val rttLocationFilter     = LocationKalmanFilter()
//    private val rttScanner            = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
//        RttScanner(application.applicationContext)
//    } else null

    private val linkMatchProcessor = LinkMatchProcessor()
    private val kalmanProcessor    = KalmanProcessor()

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private var autoScanJob: Job? = null
//    private var rttJob: Job? = null
    private var pdrOrigin: LocationResult? = null
    private var mapScanJob: Job? = null
    private var consecutiveForwardCount = 0
    private var mbrChecked = false

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

    fun resetMapToggles() {
        _uiState.update { it.copy(
            isTracking          = false,
            isTestMarkerEnabled = false,
            isLinkMatchingEnabled = false,
            linkTouchPoint      = null,
            linkSnappedPoint    = null
        )}
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
//            rttLocationFilter.reset()
            _uiState.update { it.copy(isKalmanEnabled = false) }
            Log.i(TAG, "칼만필터 OFF — 필터 초기화")
            appLogger.i(TAG, "칼만필터 OFF — 필터 초기화")
        }
    }

    fun showKalmanMeasurementDialog()  { _uiState.update { it.copy(showKalmanMeasurementDialog = true) } }
    fun showKalmanProcessDialog()      { _uiState.update { it.copy(showKalmanProcessDialog = true) } }
    fun dismissKalmanDialogs()         { _uiState.update { it.copy(showKalmanMeasurementDialog = false, showKalmanProcessDialog = false) } }
    fun showPdrResetIntervalDialog()     { _uiState.update { it.copy(showPdrResetIntervalDialog = true) } }
    fun dismissPdrResetIntervalDialog()  { _uiState.update { it.copy(showPdrResetIntervalDialog = false) } }
    fun setPdrResetIntervalSec(sec: Int) {
        val clamped = sec.coerceIn(5, 20)
        _uiState.update { it.copy(pdrResetIntervalSec = clamped, showPdrResetIntervalDialog = false) }
        Log.i(TAG, "PDR 갱신 주기 변경: ${clamped}s")
    }

    fun setKalmanMeasurementNoise(sigma: Double) {
        kalmanProcessor.filter.measurementNoiseSigma = sigma
//        rttLocationFilter.measurementNoiseSigma = sigma
        _uiState.update { it.copy(kalmanMeasurementNoise = sigma, showKalmanMeasurementDialog = false) }
        Log.i(TAG, "칼만 측정노이즈 변경: $sigma")
    }

    fun setKalmanProcessNoise(sigma: Double) {
        kalmanProcessor.filter.processNoiseSigma = sigma
//        rttLocationFilter.processNoiseSigma = sigma
        _uiState.update { it.copy(kalmanProcessNoise = sigma, showKalmanProcessDialog = false) }
        Log.i(TAG, "칼만 프로세스노이즈 변경: $sigma")
    }

    fun resetPdr() {
        pdrProcessor.reset()
        pdrOrigin = _uiState.value.serverLocation
        _uiState.update { state -> state.copy(pdrLocation = pdrOrigin) }
        Log.i(TAG, "PDR 초기화 — 새 기준점=(${pdrOrigin?.lat}, ${pdrOrigin?.lng})")
    }

    // 자동측위 토글
    // ON  → BLE 상시 스캔, 초기 1회 anchor 측위 → PDR 실시간 측위 → 5s마다 PDR 주기 리셋
    // OFF → 코루틴 취소 → finally에서 BLE 중단 및 상태 초기화
    fun toggleAutoScan() {
        if (autoScanJob?.isActive == true) {
            Log.i(TAG, "자동측위 중지 요청")
            appLogger.i(TAG, "자동측위 중지 요청")
            autoScanJob?.cancel()
        } else {
            Log.i(TAG, "자동측위 시작 — 초기앵커 1회 + PDR 주기리셋 ${_uiState.value.pdrResetIntervalSec}s")
            appLogger.i(TAG, "자동측위 시작")
            _uiState.update { it.copy(isAutoPositioning = true, errorMessage = null) }
            autoScanJob = viewModelScope.launch {
                val bleAccumulator = mutableMapOf<String, BleSignal>()
                var cycleCount = 0

                try {
                    // RTT 스캔 — 독립 주기 실행 (비활성화)
//                    if (rttScanner != null && rttScanner.isSupported) {
//                        rttJob = launch {
//                            while (isActive) {
//                                val cycleStart = System.currentTimeMillis()
//                                try {
//                                    val signals = rttScanner.scan()
//                                    _uiState.update { it.copy(rttSignals = signals) }
//                                    if (signals.isNotEmpty()) {
//                                        try {
//                                            val raw = apiClient.rttPredict(signals)
//                                            val loc = if (_uiState.value.isKalmanEnabled)
//                                                rttLocationFilter.update(raw.lat, raw.lng) else raw
//                                            _uiState.update { it.copy(rttLocation = loc) }
//                                        } catch (e: CancellationException) { throw e
//                                        } catch (e: Exception) {
//                                            Log.w(TAG, "RTT predict 오류: ${e.message}")
//                                        }
//                                    }
//                                } catch (e: CancellationException) { throw e
//                                } catch (e: Exception) { Log.w(TAG, "RTT 스캔 오류: ${e.message}") }
//                                val remaining = SCAN_INTERVAL_MS - (System.currentTimeMillis() - cycleStart)
//                                if (remaining > 0) delay(remaining)
//                            }
//                        }
//                        Log.i(TAG, "RTT 스캔 시작")
//                    } else {
//                        Log.d(TAG, "RTT 미지원 — 스캔 생략")
//                    }

                    // PDR 걸음 감지 → pdrLocation + finalLocation 갱신
                    launch {
                        pdrProcessor.stepCount.collect {
                            val state = _uiState.value
                            if (!state.isPdrEnabled) return@collect
                            val origin = pdrOrigin ?: return@collect
                            val newPdrLoc = pdrProcessor.applyPdr(origin.lat, origin.lng)
                            val pdrGeo = GeoPos(newPdrLoc.lat, newPdrLoc.lng)
                            val ctx = ProcessContext(
                                previousFinal   = state.finalLocation?.let { GeoPos(it.lat, it.lng) } ?: pdrGeo,
                                sourceType      = LocationSourceType.ANCHOR,
                                isKalmanEnabled = false,
                                smoothStep      = 0.0,
                                linkData        = linkData
                            )
                            val finalGeo = linkMatchProcessor.process(pdrGeo, ctx)
                            val finalLoc = LocationResult(finalGeo.lat, finalGeo.lng)
                            _uiState.update { s ->
                                val history = updateLocationHistory(s.locationHistory, finalGeo)
                                s.copy(
                                    pdrLocation         = newPdrLoc,
                                    finalLocation       = finalLoc,
                                    locationHistory     = history,
                                    locationUpdateCount = s.locationUpdateCount + 1
                                )
                            }
                        }
                    }

                    // WiFi 브로드캐스트 수신 → 앵커 측위 (resultsFlow 내부에서 wifiManager.startScan() 호출)
                    launch {
                        wifiScanner.resultsFlow().collect { wifiList ->
                            if (wifiList.isEmpty()) return@collect
                            applyAnchor(wifiList, bleAccumulator.values.toList())
                        }
                    }
                    Log.i(TAG, "WiFi 브로드캐스트 수신 대기 시작")

                    // BLE 누적 루프
                    while (isActive) {
                        delay(SCAN_INTERVAL_MS)
                        val cycle = ++cycleCount
                        val newBle = withContext(Dispatchers.IO) { bleScanner.getSnapshotResults() }
                        for (signal in newBle) bleAccumulator[signal.deviceAddress] = signal
                        Log.d(TAG, "[자동측위] #$cycle — BLE누적=${bleAccumulator.size}(+${newBle.size})")
                    }
                } finally {
                    // WiFi 브로드캐스트 수신은 resultsFlow() Flow 취소로 자동 해제됨
                    kalmanProcessor.reset()
//                    rttLocationFilter.reset()
                    pdrOrigin = null
                    consecutiveForwardCount = 0
//                    rttJob?.cancel()
//                    rttJob = null
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
                            locationHistory        = emptyList(),
                            anchorDirectionLabel   = null
                        )
                    }
                }
            }
        }
    }

    /** 맵 화면 진입 시 호출 — GPS/BLE/Sensor 스캔 시작 및 PDR 초기화. */
    fun startMapScanning() {
        if (mapScanJob?.isActive == true) return
        mbrChecked = false
        pdrProcessor.start()
        mapScanJob = viewModelScope.launch {
            withContext(Dispatchers.IO) { bleScanner.startScan() }
            sensorCollector.start()
            fusedLocationProvider.start(SCAN_INTERVAL_MS)
            fusedLocationProvider.locationFlow
                .filterNotNull()
                .collect { loc ->
                    if (!mbrChecked) {
                        mbrChecked = true
                        val outside = loc.lat < MBR_MIN_LAT || loc.lat > MBR_MAX_LAT ||
                                      loc.lng < MBR_MIN_LNG || loc.lng > MBR_MAX_LNG
                        if (outside) {
                            Log.i(TAG, "[MBR] 첫 GPS 구역 이탈 — lat=${loc.lat}, lng=${loc.lng}")
                            _uiState.update { state -> state.copy(fusedLocation = loc, isOutsideMbr = true) }
                            return@collect
                        }
                    }
                    _uiState.update { state -> state.copy(fusedLocation = loc) }
                }
        }
        Log.i(TAG, "맵 스캔 시작 — GPS/BLE/LTE/Sensor 활성화")
    }

    fun shutDown() {
        Log.i(TAG, "shutDown — 자동스캔 및 BLE 즉시 정리")
        appLogger.i(TAG, "shutDown — 자동스캔 및 BLE 즉시 정리")
        mapScanJob?.cancel()
        autoScanJob?.cancel()
//        rttJob?.cancel()
        pdrProcessor.stop()
        bleScanner.stopScan()
        sensorCollector.stop()
        fusedLocationProvider.stop()
        scanLogger.close()
        appLogger.close()
    }

    /**
     * WiFi/BLE 데이터로 앵커 측위를 수행한다.
     *
     * 첫 앵커 (pdrOrigin == null):
     *   - 즉시 스냅 + PDR 기준점(pdrOrigin) 설정
     *
     * 2번째 이후 앵커:
     *   - PDR 좌표 → 앵커 방향 vs 이동 히스토리 방향 비교 → 보폭만 조정
     *     · 정방향(±45° 이내) → 0.8f  /  역방향 → 0.4f
     *   - finalLocation / PDR 기준점은 변경하지 않음
     */
    private suspend fun applyAnchor(
        wifiList: List<WifiSignal>,
        bleList: List<BleSignal>
    ) {
        val lteList    = withContext(Dispatchers.IO) { lteScanner.scan() }
        val sensorSnap = sensorCollector.getSnapshot()
        val scanData   = ScanData(wifiList, bleList, sensorSnap, lteList)

        val entries = withContext(Dispatchers.Default) { FingerprintBuilder.buildEntries(scanData) }
        Log.d(TAG, "[앵커] WiFi=${wifiList.size} BLE=${bleList.size} 매칭=${entries.count { it.rssi != MISSING_RSSI }}/${entries.size}")
        scanLogger.logScan(bleList, wifiList, entries, sensorSnap, lteList)
        _uiState.update { it.copy(scanData = scanData, fingerprintEntries = entries) }

        try {
            val values = FingerprintBuilder.buildAnchorPayload(scanData)
            if (values.isEmpty()) {
                Log.d(TAG, "[앵커] 피처 없음 — 전송 생략")
                return
            }
            val raw = apiClient.anchorPredict(values)
            val anchorGeo = GeoPos(raw.lat, raw.lng)
            Log.i(TAG, "[앵커] 측위 완료 — (${raw.lat}, ${raw.lng})")

            _uiState.update { it.copy(serverLocation = raw) }

            val prevFinalGeo = _uiState.value.finalLocation?.let { GeoPos(it.lat, it.lng) }

            if (prevFinalGeo == null) {
                // ── 첫 앵커: 즉시 스냅 + PDR 기준점 설정 ──
                if(SeoulStationPocApplication.IS_TEST)
                    Toast.makeText(getApplication(), "앵커 첫 측위 완료", Toast.LENGTH_SHORT).show()

                val ctx = ProcessContext(
                    previousFinal   = anchorGeo,
                    sourceType      = LocationSourceType.ANCHOR,
                    isKalmanEnabled = false,
                    smoothStep      = 0.0,
                    linkData        = linkData
                )
                val linkedFinalGeo = linkMatchProcessor.process(anchorGeo, ctx)
                val linkedFinalLoc = LocationResult(linkedFinalGeo.lat, linkedFinalGeo.lng)
                pdrProcessor.reset()
                pdrOrigin = raw
                _uiState.update { state ->
                    val history = updateLocationHistory(state.locationHistory, linkedFinalGeo)
                    state.copy(
                        finalLocation        = linkedFinalLoc,
                        pdrLocation          = LocationResult(raw.lat, raw.lng),
                        locationHistory      = history,
                        locationUpdateCount  = state.locationUpdateCount + 1,
                        anchorDirectionLabel = null
                    )
                }
                Log.i(TAG, "[앵커] 첫 스냅 — pdrOrigin=(${raw.lat}, ${raw.lng})")
            } else {
                // ── 2번째 이후 앵커: 방향 판단 → 보폭 조정 → (정방향 3회↑) PDR 기준점 갱신 ──
                val curPdrGeo = _uiState.value.pdrLocation?.let { GeoPos(it.lat, it.lng) } ?: prevFinalGeo

                // historyAngle: 마지막 4개 좌표의 평균 진행 방향 (circular mean)
                val histPoints = _uiState.value.locationHistory.takeLast(4)
                val historyAngleRad: Double? = if (histPoints.size >= 2) {
                    val bearings = (0 until histPoints.size - 1).map { i ->
                        geoBearingRad(histPoints[i].lat, histPoints[i].lng, histPoints[i+1].lat, histPoints[i+1].lng)
                    }
                    val meanSin = bearings.sumOf { sin(it) } / bearings.size
                    val meanCos = bearings.sumOf { cos(it) } / bearings.size
                    atan2(meanSin, meanCos)
                } else null

                // anchorAngle: 현재 PDR 좌표 → anchor 측위 좌표 방향
                val anchorAngleRad = geoBearingRad(curPdrGeo.lat, curPdrGeo.lng, anchorGeo.lat, anchorGeo.lng)

                val diffDeg = if (historyAngleRad != null) angleDiffDeg(historyAngleRad, anchorAngleRad) else 0.0
                val isForward = historyAngleRad == null || diffDeg <= 80.0

                val directionLabel: String
                val stepLen: Float
                if (isForward) {
                    stepLen = 0.75f
                    pdrProcessor.setStepLength(stepLen)
                    directionLabel = "정방향"
                    Log.i(TAG, "[앵커] 정방향 — diff=${"%.1f".format(diffDeg)}° 연속 ${consecutiveForwardCount}회 보폭=0.75m")
                } else {
                    consecutiveForwardCount = 0
                    stepLen = 0.45f
                    pdrProcessor.setStepLength(stepLen)
                    directionLabel = "역방향"
                    Log.i(TAG, "[앵커] 역방향 — diff=${"%.1f".format(diffDeg)}° 보폭=0.45m")
                }

                consecutiveForwardCount++
                if (consecutiveForwardCount >= 2) {
                    pdrOrigin = raw
                    pdrProcessor.resetDisplacement()
                    Log.i(TAG, "[앵커] PDR 기준점 갱신 — (${raw.lat}, ${raw.lng})")
                }

                val toastMsg = if (directionLabel != null) "앵커 측위 : $directionLabel, 카운트 : $consecutiveForwardCount" else "앵커 측위 완료, 카운트 : $consecutiveForwardCount"
                if(SeoulStationPocApplication.IS_TEST)
                    Toast.makeText(getApplication(), toastMsg, Toast.LENGTH_SHORT).show()

                _uiState.update { it.copy(anchorDirectionLabel = directionLabel, stepLengthM = stepLen) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "[앵커] API 오류: ${e.message}")
        }
    }

    // Step 6: 최종좌표 이력 갱신 — 이전 좌표와 1.0m 이상 차이날 때만 추가, 최대 10개 유지
    private fun updateLocationHistory(history: List<GeoPos>, newPos: GeoPos): List<GeoPos> {
        val last = history.lastOrNull()
        if (last != null && geoDistanceM(last.lat, last.lng, newPos.lat, newPos.lng) < LOCATION_HISTORY_MIN_DIST_M) {
            return history
        }
        return (history + newPos).takeLast(LOCATION_HISTORY_MAX_SIZE)
    }

    private fun geoDistanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R    = 6371000.0
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLng = (lng2 - lng1) * PI / 180.0
        val a    = sin(dLat / 2).pow(2) +
                   cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sin(dLng / 2).pow(2)
        return R * 2.0 * asin(sqrt(a))
    }

    /** 두 좌표 간 방위각(라디안). 0=북, 양수=시계 방향 — PdrProcessor.azimuthRad 와 동일 규약. */
    private fun geoBearingRad(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val φ1   = lat1 * PI / 180.0
        val φ2   = lat2 * PI / 180.0
        val dLng = (lng2 - lng1) * PI / 180.0
        return atan2(sin(dLng) * cos(φ2), cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(dLng))
    }

    /** 두 방위각(라디안) 간 최소 각도 차이(도, 0~180). */
    private fun angleDiffDeg(a: Double, b: Double): Double {
        val diff = ((b - a) * 180.0 / PI % 360.0 + 360.0) % 360.0
        return if (diff <= 180.0) diff else 360.0 - diff
    }

    override fun onCleared() {
        super.onCleared()
        shutDown()
    }
}
