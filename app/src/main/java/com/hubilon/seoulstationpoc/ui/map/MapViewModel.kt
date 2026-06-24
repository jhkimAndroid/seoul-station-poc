package com.hubilon.seoulstationpoc.ui.map

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hubilon.positioning.MbrBounds
import com.hubilon.positioning.PositioningConfig
import com.hubilon.positioning.PositioningEvent
import com.hubilon.positioning.PositioningManager
import com.hubilon.positioning.model.FingerprintEntry
import com.hubilon.positioning.model.GeoPos
import com.hubilon.positioning.model.LocationResult
import com.hubilon.positioning.model.RttSignal
import com.hubilon.positioning.model.ScanData
import com.hubilon.seoulstationpoc.SeoulStationPocApplication
import com.hubilon.seoulstationpoc.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = AppLog.MAP

private const val MBR_MIN_LAT = 37.5533828
private const val MBR_MAX_LAT = 37.55571757
private const val MBR_MIN_LNG = 126.96928985
private const val MBR_MAX_LNG = 126.97222419

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
    val serverLocation: LocationResult? = null,
    val kalmanFilteredLocation: LocationResult? = null,
    val finalLocation: LocationResult? = null,
    val pdrLocation: LocationResult? = null,
    val fusedLocation: LocationResult? = null,
    val rttLocation: LocationResult? = null,
    val locationUpdateCount: Int = 0,
    val locationHistory: List<GeoPos> = emptyList(),
    val rttSignals: List<RttSignal> = emptyList(),
    val errorMessage: String? = null,
    val selectedFloor: FloorSelection = FloorSelection.F3,
    val fingerprintEntries: List<FingerprintEntry>? = null,
    val apLoadState: ApLoadState = ApLoadState.Loading,
    val isTestMarkerEnabled: Boolean = SeoulStationPocApplication.IS_TEST,
    val isLinkEnabled: Boolean = SeoulStationPocApplication.IS_TEST,
    val isLinkMatchingEnabled: Boolean = false,
    val linkTouchPoint: GeoPos? = null,
    val linkSnappedPoint: GeoPos? = null,
    val pdrResetIntervalSec: Int = 10,
    val showPdrResetIntervalDialog: Boolean = false,
    val anchorDirectionLabel: String? = null,
    val stepLengthM: Float = 0.6f,
    val isOutsideMbr: Boolean = false
)

class MapViewModel(application: Application) : AndroidViewModel(application) {

    val positioning = PositioningManager(
        application.applicationContext,
        PositioningConfig(
            baseUrl       = SeoulStationPocApplication.BASE_URL,
            buildingId    = SeoulStationPocApplication.BUILDING_ID,
            mbrBounds     = MbrBounds(MBR_MIN_LAT, MBR_MAX_LAT, MBR_MIN_LNG, MBR_MAX_LNG),
            linkAssetFile = "link_3f.geojson",
            isDebugMode   = SeoulStationPocApplication.IS_TEST,
            fileLogEnabled = true,
            fileLogPrefix  = "SSP"
        )
    )

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    val linkData get() = positioning.getLinkData()

    init {
        positioning.initialize()

        viewModelScope.launch {
            positioning.update.collect { pos ->
                _uiState.update { state ->
                    state.copy(
                        finalLocation       = pos.finalLocation       ?: state.finalLocation,
                        serverLocation      = pos.serverLocation      ?: state.serverLocation,
                        pdrLocation         = pos.pdrLocation         ?: state.pdrLocation,
                        fusedLocation       = pos.gpsLocation         ?: state.fusedLocation,
                        locationHistory     = pos.locationHistory.ifEmpty { state.locationHistory },
                        locationUpdateCount = if (pos.locationUpdateCount > 0) pos.locationUpdateCount else state.locationUpdateCount,
                        fingerprintEntries  = pos.fingerprintEntries  ?: state.fingerprintEntries,
                        scanData            = pos.scanData            ?: state.scanData
                    )
                }
            }
        }

        viewModelScope.launch {
            positioning.events.collect { event ->
                when (event) {
                    is PositioningEvent.FeaturesLoaded ->
                        _uiState.update { it.copy(apLoadState = ApLoadState.Success(event.anchorCount, event.trackerCount)) }
                    is PositioningEvent.FeatureLoadError ->
                        _uiState.update { it.copy(apLoadState = ApLoadState.Error(event.message)) }
                    is PositioningEvent.OutsideMbr ->
                        _uiState.update { it.copy(isOutsideMbr = true) }
                    is PositioningEvent.GpsUpdate ->
                        _uiState.update { it.copy(fusedLocation = event.location) }
                    is PositioningEvent.AutoPositioningStarted ->
                        _uiState.update { it.copy(isAutoPositioning = true, errorMessage = null) }
                    is PositioningEvent.AutoPositioningStopped ->
                        _uiState.update { state ->
                            state.copy(
                                isAutoPositioning      = false,
                                serverLocation         = null,
                                kalmanFilteredLocation = null,
                                finalLocation          = null,
                                pdrLocation            = null,
                                locationHistory        = emptyList(),
                                anchorDirectionLabel   = null
                            )
                        }
                    is PositioningEvent.DebugInfo ->
                        if (SeoulStationPocApplication.IS_TEST)
                            Toast.makeText(application.applicationContext, event.message, Toast.LENGTH_SHORT).show()
                    else -> {}
                }
            }
        }
    }

    // ── Scanning ──────────────────────────────────────────────────────────────

    fun startMapScanning() {
        positioning.startScanning()
        Log.i(TAG, "맵 스캔 시작")
    }

    fun shutDown() {
        Log.i(TAG, "shutDown — 모든 리소스 해제")
        positioning.release()
    }

    // ── Auto positioning ──────────────────────────────────────────────────────

    fun toggleAutoScan() {
        if (_uiState.value.isAutoPositioning) {
            Log.i(TAG, "자동측위 중지 요청")
            positioning.stopAutoPositioning()
        } else {
            Log.i(TAG, "자동측위 시작 요청")
            positioning.startAutoPositioning()
        }
    }

    // ── Map interaction ───────────────────────────────────────────────────────

    fun onMapTouched(lat: Double, lng: Double) {
        if (!_uiState.value.isLinkMatchingEnabled) return
        val snapped = positioning.findNearestLink(GeoPos(lat, lng))
        Log.i(TAG, "맵 터치 — touch=($lat, $lng) snapped=(${snapped?.lat}, ${snapped?.lng})")
        _uiState.update { it.copy(
            linkTouchPoint   = GeoPos(lat, lng),
            linkSnappedPoint = snapped
        )}
    }

    // ── UI-only state mutations ────────────────────────────────────────────────

    fun toggleTestMarker() {
        _uiState.update { it.copy(isTestMarkerEnabled = !it.isTestMarkerEnabled) }
    }

    fun resetMapToggles() {
        _uiState.update { it.copy(
            isTracking            = false,
            isTestMarkerEnabled   = false,
            isLinkMatchingEnabled = false,
            linkTouchPoint        = null,
            linkSnappedPoint      = null
        )}
    }

    fun toggleLink() {
        _uiState.update { it.copy(isLinkEnabled = !it.isLinkEnabled) }
    }

    fun toggleLinkMatching() {
        val enabling = !_uiState.value.isLinkMatchingEnabled
        _uiState.update { it.copy(
            isLinkMatchingEnabled = enabling,
            linkTouchPoint        = null,
            linkSnappedPoint      = null
        )}
    }

    fun setFloor(floor: FloorSelection) {
        _uiState.update { it.copy(selectedFloor = floor) }
    }

    fun toggleTracking() {
        val newValue = !_uiState.value.isTracking
        Log.i(TAG, "추적 ${if (newValue) "ON" else "OFF"}")
        _uiState.update { it.copy(isTracking = newValue) }
    }

    fun showPdrResetIntervalDialog()     { _uiState.update { it.copy(showPdrResetIntervalDialog = true) } }
    fun dismissPdrResetIntervalDialog()  { _uiState.update { it.copy(showPdrResetIntervalDialog = false) } }

    fun setPdrResetIntervalSec(sec: Int) {
        val clamped = sec.coerceIn(5, 20)
        _uiState.update { it.copy(pdrResetIntervalSec = clamped, showPdrResetIntervalDialog = false) }
        Log.i(TAG, "PDR 갱신 주기 변경: ${clamped}s")
    }

    override fun onCleared() {
        super.onCleared()
        shutDown()
    }
}
