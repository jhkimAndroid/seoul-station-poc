package com.hubilon.positioning.internal.engine

import android.content.Context
import android.hardware.GeomagneticField
import android.util.Log
import com.hubilon.positioning.PositioningConfig
import com.hubilon.positioning.PositioningEvent
import com.hubilon.positioning.PositioningUpdate
import com.hubilon.positioning.internal.api.LocationApiClient
import com.hubilon.positioning.internal.geojson.LinkParser
import com.hubilon.positioning.internal.log.AppLogger
import com.hubilon.positioning.internal.log.ScanLogger
import com.hubilon.positioning.internal.refiner.KalmanRefiner
import com.hubilon.positioning.internal.refiner.LinkSnapRefiner
import com.hubilon.positioning.model.BleSignal
import com.hubilon.positioning.model.GeoPos
import com.hubilon.positioning.model.LocationResult
import com.hubilon.positioning.model.MISSING_RSSI
import com.hubilon.positioning.model.ScanData
import com.hubilon.positioning.model.WifiSignal
import com.hubilon.positioning.scan.BleScanner
import com.hubilon.positioning.scan.GpsScanner
import com.hubilon.positioning.scan.LteScanner
import com.hubilon.positioning.scan.PdrProcessor
import com.hubilon.positioning.scan.SensorCollector
import com.hubilon.positioning.scan.WifiScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val TAG = "SSP_ENGINE"

internal class PositioningEngine(
    private val context: Context,
    private val config: PositioningConfig
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Scanners (exposed via PositioningManager for direct access)
    val gpsScanner      = GpsScanner(context)
    val bleScanner      = BleScanner(context)
    val wifiScanner     = WifiScanner(context)
    val lteScanner      = LteScanner(context)
    val sensorCollector = SensorCollector(context)
    val pdrProcessor    = PdrProcessor(context)

    // Logger first — apiClient depends on it
    private val appLogger: AppLogger?   = if (config.fileLogEnabled) AppLogger(context, config.fileLogPrefix)  else null
    private val fingerprintBuilder      = FingerprintBuilder()
    private val apiClient               = LocationApiClient(config.baseUrl, config.buildingId, appLogger)
    private val scanLogger: ScanLogger? = if (config.fileLogEnabled) ScanLogger(context, fingerprintBuilder, config.fileLogPrefix) else null
    private val kalmanRefiner           = KalmanRefiner()
    private val linkSnapRefiner         = LinkSnapRefiner()

    // Event flow for callers
    private val _events = MutableSharedFlow<PositioningEvent>(extraBufferCapacity = 64)
    val events: Flow<PositioningEvent> = _events

    // Latest positioning state (callers can also collect via events)
    private val _update = MutableStateFlow(PositioningUpdate())
    val update: StateFlow<PositioningUpdate> = _update.asStateFlow()

    // Internal mutable state
    private var pdrOrigin: LocationResult? = null
    private var latestAnchorResult: LocationResult? = null
    private var consecutiveForwardCount = 0
    private var mbrChecked = false

    private var scanJob: Job? = null
    private var autoPositioningJob: Job? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    fun initialize() {
        scope.launch { loadFeatures() }
        loadLinkData()
    }

    private suspend fun loadFeatures() {
        try {
            val (anchorAps, trackerAps) = withContext(Dispatchers.IO) {
                coroutineScope {
                    val a = async { apiClient.fetchAnchorFeatures() }
                    val t = async { apiClient.fetchTrackerFeatures() }
                    Pair(a.await(), t.await())
                }
            }
            fingerprintBuilder.updateFromAnchorAps(anchorAps)
            fingerprintBuilder.updateFromTrackerAps(trackerAps)
            Log.i(TAG, "피처 로드 완료 — anchor=${anchorAps.size} tracker=${trackerAps.size}")
            _events.emit(PositioningEvent.FeaturesLoaded(anchorAps.size, trackerAps.size))
        } catch (e: Exception) {
            Log.e(TAG, "피처 로드 실패: ${e.message}")
            _events.emit(PositioningEvent.FeatureLoadError(e.message ?: "알 수 없는 오류"))
        }
    }

    private fun loadLinkData() {
        val assetFile = config.linkAssetFile ?: return
        scope.launch(Dispatchers.IO) {
            val data = LinkParser.parse(context, assetFile)
            linkSnapRefiner.updateLinkData(data)
            Log.i(TAG, "링크 데이터 로드 완료 — ${data.size}개")
        }
    }

    // ── Scanning ───────────────────────────────────────────────────────────────

    fun startScanning() {
        if (scanJob?.isActive == true) return
        mbrChecked = false
        pdrProcessor.start()
        scanJob = scope.launch {
            withContext(Dispatchers.IO) { bleScanner.startScan() }
            sensorCollector.start()
            gpsScanner.start(config.scanIntervalMs)
            Log.i(TAG, "스캔 시작 — GPS/BLE/Sensor 활성화")

            gpsScanner.locationFlow.filterNotNull().collect { loc ->
                if (!mbrChecked) {
                    mbrChecked = true
                    val bounds = config.mbrBounds
                    if (bounds != null) {
                        val outside = loc.lat < bounds.minLat || loc.lat > bounds.maxLat ||
                                      loc.lng < bounds.minLng || loc.lng > bounds.maxLng
                        if (outside) {
                            Log.i(TAG, "[MBR] 첫 GPS 구역 이탈 — lat=${loc.lat}, lng=${loc.lng}")
                            _update.update { it.copy(gpsLocation = loc) }
                            _events.emit(PositioningEvent.OutsideMbr)
                            return@collect
                        }
                    }
                    val geo = GeomagneticField(
                        loc.lat.toFloat(), loc.lng.toFloat(), 0f,
                        System.currentTimeMillis()
                    )
                    pdrProcessor.setDeclination(geo.declination)
                }
                _update.update { it.copy(gpsLocation = loc) }
                _events.emit(PositioningEvent.GpsUpdate(loc))
            }
        }
    }

    fun stopScanning() {
        scanJob?.cancel()
        scanJob = null
        pdrProcessor.stop()
        bleScanner.stopScan()
        sensorCollector.stop()
        gpsScanner.stop()
        Log.i(TAG, "스캔 중지")
    }

    // ── Auto positioning ───────────────────────────────────────────────────────

    fun startAutoPositioning() {
        if (autoPositioningJob?.isActive == true) return
        Log.i(TAG, "자동측위 시작")
        appLogger?.i(TAG, "자동측위 시작")
        _events.tryEmit(PositioningEvent.AutoPositioningStarted)

        autoPositioningJob = scope.launch {
            val bleAccumulator = mutableMapOf<String, BleSignal>()

            try {
                // PDR step handler
                launch {
                    pdrProcessor.stepCount.collect { _ ->
                        val state = _update.value
                        val anchor = latestAnchorResult
                        val curPdrLoc = state.pdrLocation

                        val pdrAnchorDist = if (curPdrLoc != null && anchor != null)
                            GeoUtils.distanceM(curPdrLoc.lat, curPdrLoc.lng, anchor.lat, anchor.lng)
                        else Double.MAX_VALUE

                        if (anchor != null && curPdrLoc != null) {
                            val isForward = checkFront(state.locationHistory, curPdrLoc, anchor)
                            if (pdrAnchorDist >= config.pdrCorrectionMinDistM) {
                                consecutiveForwardCount++
                                if (consecutiveForwardCount >= config.pdrCorrectionConsecutiveSteps) {
                                    consecutiveForwardCount = 0
                                    val bearing = GeoUtils.bearingRad(
                                        curPdrLoc.lat, curPdrLoc.lng,
                                        anchor.lat, anchor.lng
                                    )
                                    if (config.isDebugMode) {
                                        val label = if (isForward) "정방향 ${config.pdrCorrectionForwardDistM}m" else "역방향 ${config.pdrCorrectionReverseDistM}m"
                                        _events.emit(PositioningEvent.DebugInfo("위치 보정($label)"))
                                    }
                                    val distM = if (isForward) config.pdrCorrectionForwardDistM else config.pdrCorrectionReverseDistM
                                    val newOrigin = GeoUtils.moveToward(curPdrLoc.lat, curPdrLoc.lng, bearing, distM)
                                    pdrOrigin = newOrigin
                                    pdrProcessor.resetDisplacement()
                                    Log.i(TAG, "[PDR 걸음] 기준점 갱신 — ${if (isForward) "정방향" else "역방향"} dist=${"%.1f".format(pdrAnchorDist)}m → (${newOrigin.lat}, ${newOrigin.lng})")
                                }
                            } else {
                                consecutiveForwardCount = 0
                            }
                        }

                        val origin = pdrOrigin ?: return@collect
                        val newPdrLoc = pdrProcessor.applyPdr(origin.lat, origin.lng)
                        val pdrGeo = GeoPos(newPdrLoc.lat, newPdrLoc.lng)
                        val finalLoc = linkSnapRefiner.refine(LocationResult(pdrGeo.lat, pdrGeo.lng))
                        val finalGeo = GeoPos(finalLoc.lat, finalLoc.lng)

                        _update.update { s ->
                            val history = appendHistory(s.locationHistory, finalGeo)
                            s.copy(
                                pdrLocation         = newPdrLoc,
                                finalLocation       = finalLoc,
                                locationHistory     = history,
                                locationUpdateCount = s.locationUpdateCount + 1
                            )
                        }
                        _events.emit(PositioningEvent.LocationUpdate(_update.value))
                    }
                }

                // WiFi scan → anchor positioning
                launch {
                    wifiScanner.resultsFlow().collect { wifiList ->
                        if (wifiList.isEmpty()) return@collect
                        applyAnchor(wifiList, bleAccumulator.values.toList())
                    }
                }
                Log.i(TAG, "WiFi 브로드캐스트 수신 대기 시작")

                // BLE accumulation loop
                while (isActive) {
                    delay(config.scanIntervalMs)
                    val newBle = withContext(Dispatchers.IO) { bleScanner.getSnapshotResults() }
                    for (signal in newBle) bleAccumulator[signal.deviceAddress] = signal
                    Log.d(TAG, "[자동측위] BLE누적=${bleAccumulator.size}(+${newBle.size})")
                }

            } finally {
                kalmanRefiner.reset()
                pdrOrigin = null
                latestAnchorResult = null
                consecutiveForwardCount = 0
                Log.i(TAG, "[자동측위] 종료")
                appLogger?.i(TAG, "[자동측위] 종료")
                _update.update { it.copy(
                    finalLocation   = null,
                    serverLocation  = null,
                    pdrLocation     = null,
                    locationHistory = emptyList()
                )}
                _events.tryEmit(PositioningEvent.AutoPositioningStopped)
            }
        }
    }

    fun stopAutoPositioning() {
        autoPositioningJob?.cancel()
        autoPositioningJob = null
    }

    fun findNearestLink(pos: GeoPos): GeoPos? = linkSnapRefiner.findNearest(pos)

    fun getLinkData(): List<com.hubilon.positioning.model.LinkData> = linkSnapRefiner.getLinkData()

    fun resetPdr() {
        pdrProcessor.reset()
        pdrOrigin = update.value.serverLocation
        Log.i(TAG, "PDR 초기화 — 새 기준점=(${pdrOrigin?.lat}, ${pdrOrigin?.lng})")
    }

    fun release() {
        Log.i(TAG, "release — 모든 리소스 해제")
        appLogger?.i(TAG, "release")
        stopScanning()
        stopAutoPositioning()
        scanLogger?.close()
        appLogger?.close()
        scope.cancel()
    }

    // ── Anchor positioning ─────────────────────────────────────────────────────

    private suspend fun applyAnchor(wifiList: List<WifiSignal>, bleList: List<BleSignal>) {
        val lteList    = withContext(Dispatchers.IO) { lteScanner.scan() }
        val sensorSnap = sensorCollector.getSnapshot()
        val scanData   = ScanData(wifiList, bleList, sensorSnap, lteList)

        val entries = withContext(Dispatchers.Default) { fingerprintBuilder.buildEntries(scanData) }
        Log.d(TAG, "[앵커] WiFi=${wifiList.size} BLE=${bleList.size} 매칭=${entries.count { it.rssi != MISSING_RSSI }}/${entries.size}")
        scanLogger?.logScan(bleList, wifiList, entries, sensorSnap, lteList)
        _update.update { it.copy(scanData = scanData, fingerprintEntries = entries) }

        try {
            val values = fingerprintBuilder.buildAnchorPayload(scanData)
            if (values.isEmpty()) {
                Log.d(TAG, "[앵커] 피처 없음 — 전송 생략")
                return
            }
            val raw = apiClient.anchorPredict(values)
            val anchorGeo = GeoPos(raw.lat, raw.lng)
            Log.i(TAG, "[앵커] 측위 완료 — (${raw.lat}, ${raw.lng})")
            _update.update { it.copy(serverLocation = raw) }

            val prevFinalLoc = _update.value.finalLocation

            if (prevFinalLoc == null) {
                // First anchor — snap and set PDR origin
                if (config.isDebugMode) {
                    _events.emit(PositioningEvent.DebugInfo("앵커 첫 측위 완료"))
                }
                val linkedFinalLoc = linkSnapRefiner.refine(LocationResult(anchorGeo.lat, anchorGeo.lng))
                val linkedFinalGeo = GeoPos(linkedFinalLoc.lat, linkedFinalLoc.lng)
                pdrProcessor.reset()
                pdrOrigin = raw
                _update.update { s ->
                    val history = appendHistory(s.locationHistory, linkedFinalGeo)
                    s.copy(
                        finalLocation       = linkedFinalLoc,
                        pdrLocation         = LocationResult(raw.lat, raw.lng),
                        locationHistory     = history,
                        locationUpdateCount = s.locationUpdateCount + 1
                    )
                }
                _events.emit(PositioningEvent.LocationUpdate(_update.value))
                Log.i(TAG, "[앵커] 첫 스냅 — pdrOrigin=(${raw.lat}, ${raw.lng})")
            } else {
                // Subsequent anchor — update reference for PDR correction
                latestAnchorResult = raw
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "[앵커] API 오류: ${e.message}")
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun checkFront(
        history: List<GeoPos>,
        curPdrLoc: LocationResult,
        anchor: LocationResult
    ): Boolean {
        val histPoints = history.takeLast(3)
        val historyAngleRad: Double? = if (histPoints.size >= 2) {
            val bearings = (0 until histPoints.size - 1).map { i ->
                GeoUtils.bearingRad(
                    histPoints[i].lat, histPoints[i].lng,
                    histPoints[i + 1].lat, histPoints[i + 1].lng
                )
            }
            val meanSin = bearings.sumOf { sin(it) } / bearings.size
            val meanCos = bearings.sumOf { cos(it) } / bearings.size
            atan2(meanSin, meanCos)
        } else null

        val anchorAngleRad = GeoUtils.bearingRad(
            curPdrLoc.lat, curPdrLoc.lng,
            anchor.lat, anchor.lng
        )
        val diffDeg = if (historyAngleRad != null) GeoUtils.angleDiffDeg(historyAngleRad, anchorAngleRad) else 0.0
        return historyAngleRad == null || diffDeg <= 90.0
    }

    private fun appendHistory(history: List<GeoPos>, newPos: GeoPos): List<GeoPos> {
        val last = history.lastOrNull()
        if (last != null && GeoUtils.distanceM(last.lat, last.lng, newPos.lat, newPos.lng) < config.historyMinDistM) {
            return history
        }
        return (history + newPos).takeLast(config.historyMaxSize)
    }
}
