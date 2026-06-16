package com.hubilon.seoulstationpoc.ui.map

import android.app.Activity
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hubilon.seoulstationpoc.R
import com.hubilon.seoulstationpoc.data.fingerprint.MISSING_RSSI
import com.hubilon.seoulstationpoc.util.AppLog
import com.kakao.vectormap.GestureType
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraUpdateFactory

private const val TAG = AppLog.MAP

private val SEOUL_STATION = LatLng.from(37.5550, 126.9707)
private const val DEFAULT_ZOOM = 17

@Composable
fun MapScreen(
    viewModel: MapViewModel = viewModel(),
    onNavigateToScanDetail: (section: String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var kakaoMap by remember { mutableStateOf<KakaoMap?>(null) }
    val context = LocalContext.current
    val activity = context as Activity

    var showExitDialog by remember { mutableStateOf(false) }

    // 뒤로가기: 팝업이 열려 있으면 닫고, 아니면 자동측위 중단 후 종료 팝업 표시
    BackHandler {
        if (showExitDialog) {
            showExitDialog = false
        } else {
            if (uiState.isAutoScanning) viewModel.toggleAutoScan()
            showExitDialog = true
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("앱 종료") },
            text  = { Text("종료하시겠습니까?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.shutDown()
                    activity.finishAndRemoveTask()
                    android.os.Process.killProcess(android.os.Process.myPid())
                }) { Text("종료") }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) { Text("취소") }
            }
        )
    }

    val f2Bitmap = remember {
        decodeSampledBitmap(context.resources, R.drawable.img_1105001001_2f, 2048, 2048)
            .also { Log.d(TAG, "f2Bitmap: ${if (it != null) "${it.width}x${it.height}" else "null — 리소스 로드 실패"}") }
    }
    val f3Bitmap = remember {
        decodeSampledBitmap(context.resources, R.drawable.img_1105001001_3f, 2048, 2048)
            .also { Log.d(TAG, "f3Bitmap: ${if (it != null) "${it.width}x${it.height}" else "null — 리소스 로드 실패"}") }
    }
    val floorBitmap = when (uiState.selectedFloor) {
        FloorSelection.F2     -> f2Bitmap
        FloorSelection.F3     -> f3Bitmap
        FloorSelection.HIDDEN -> null
    }

    val isTracking = uiState.isTracking
    val location = uiState.location
    val pdrServerLocation = uiState.pdrServerLocation
    val fusedLocation = uiState.fusedLocation
    val locationUpdateCount = uiState.locationUpdateCount

    // 네이티브 마커 홀더 (맵 인스턴스가 바뀌면 참조 무효화)
//    val nativeMarkerHolder = remember { object { var label: Label? = null; var forMap: KakaoMap? = null } }

    // locationUpdateCount: 좌표가 동일해도 매 응답마다 반드시 재실행되도록 카운터 사용
    // isTracking: 추적 ON 전환 시 현재 위치로 즉시 이동
    LaunchedEffect(locationUpdateCount, kakaoMap, isTracking) {
        val map = kakaoMap ?: return@LaunchedEffect
        val loc = location ?: return@LaunchedEffect
        val latLng = LatLng.from(loc.lat, loc.lng)

        if (isTracking) {
            map.moveCamera(CameraUpdateFactory.newCenterPosition(latLng))
        }

/*        // 네이티브 마커: 오버레이 마커와 동일 좌표에 표시해 비교 검증용
        if (nativeMarkerHolder.forMap !== map) {
            nativeMarkerHolder.label = null
            nativeMarkerHolder.forMap = map
        }

        val existingLabel = nativeMarkerHolder.label
        if (existingLabel != null) {
            existingLabel.moveTo(latLng)
        } else {
            try {
                val labelManager = map.labelManager
                val layer = labelManager?.layer
                if (labelManager != null && layer != null) {
                    val styles = labelManager.addLabelStyles(
                        LabelStyles.from(LabelStyle.from(createNativeMarkerBitmap()))
                    )
                    nativeMarkerHolder.label = layer.addLabel(
                        LabelOptions.from(latLng).setStyles(styles)
                    )
                    Log.i(TAG, "네이티브 마커 생성 — lat=${loc.lat}, lng=${loc.lng}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "네이티브 마커 생성 실패: ${e.message}", e)
            }
        }*/
    }

    Box(modifier = Modifier.fillMaxSize()) {
        KakaoMapComposable(
            modifier = Modifier.fillMaxSize(),
            onMapReady = { map -> kakaoMap = map }
        )

        // 층 평면도 오버레이 + 위치 마커 (마커는 평면도 위에 그림)
        val isAutoScanning = uiState.isAutoScanning
        val isPdrEnabled   = uiState.isPdrEnabled
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { FloorPlanOverlayView(it) },
            update = { view ->
                view.updateMap(kakaoMap)
                view.updateBitmap(floorBitmap)
                view.updateLocation(
                    if (isAutoScanning) location?.let { LatLng.from(it.lat, it.lng) } else null
                )
                view.updatePdrServerLocation(
                    if (isAutoScanning && isPdrEnabled) pdrServerLocation?.let { LatLng.from(it.lat, it.lng) } else null
                )
                view.updateFusedLocation(
                    if (isAutoScanning) fusedLocation?.let { LatLng.from(it.lat, it.lng) } else null
                )
            }
        )

        // 좌상단: 스캔결과 버튼 + PDR 토글 버튼
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = 8.dp, start = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MapIconButton(
                painter = painterResource(R.drawable.icon_log),
                contentDescription = "스캔결과",
                onClick = { onNavigateToScanDetail("wifi") }
            )
        }

        // 우측 중앙: 추적토글 / 1회스캔 / 자동스캔 (세로 정렬)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MapIconButton(
                painter = painterResource(R.drawable.icon_trace),
                contentDescription = "추적",
                onClick = { viewModel.toggleTracking() },
                isActive = uiState.isTracking
            )
            MapIconButton(
                painter = painterResource(R.drawable.icon_location),
                contentDescription = "서버측위",
                onClick = { viewModel.toggleAutoScan() },
                isActive = uiState.isAutoScanning
            )
            MapIconButton(
                painter = painterResource(R.drawable.icon_filter),
                contentDescription = "칼만필터 토글",
                onClick = { viewModel.toggleKalman() },
                isActive = uiState.isKalmanEnabled
            )
        }

        // 우상단: 수집주기 선택 + 층 선택
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IntervalSelectionButton(
                selectedIntervalMs = uiState.scanIntervalMs,
                enabled = !uiState.isAutoScanning,
                onIntervalSelected = { viewModel.setScanInterval(it) }
            )
            FloorSelectionDropdown(
                selectedFloor = uiState.selectedFloor,
                onFloorSelected = { viewModel.setFloor(it) }
            )
        }

        // 하단: 자동측위 ON일 때만 표시
        val errorMessage = uiState.errorMessage
        val fingerprintEntries = uiState.fingerprintEntries
        val matchCount = fingerprintEntries?.count { it.rssi != MISSING_RSSI } ?: 0
        val hasBottom = uiState.isAutoScanning &&
                (fingerprintEntries != null || errorMessage != null ||
                 location != null || pdrServerLocation != null ||
                 fusedLocation != null)

        if (hasBottom) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shadowElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 스캔 통계 칩 (스캔 이력 있을 때)
                    if (fingerprintEntries != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatChip(label = "BLE",  value = uiState.scanData.bleSignals.size.toString())
                            StatChip(label = "WiFi", value = uiState.scanData.wifiSignals.size.toString())
                            StatChip(label = "매칭", value = "$matchCount / ${fingerprintEntries.size}")
                        }
                    }
                    // 오류 또는 위치 정보
                    if (errorMessage != null) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        if (location != null) {
                            Text(
                                text = "● 서버: %.6f, %.6f".format(location.lat, location.lng),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFE93228)
                            )
                        }
                        if (pdrServerLocation != null) {
                            Text(
                                text = "● 서버+PDR: %.6f, %.6f".format(pdrServerLocation.lat, pdrServerLocation.lng),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFF57C00)
                            )
                        }
                        if (fusedLocation != null) {
                            Text(
                                text = "● GPS: %.6f, %.6f".format(fusedLocation.lat, fusedLocation.lng),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF388E3C)
                            )
                        }
                    }
                }
            }
        }
    }
}

// PNG/SVG 커스텀 이미지 버튼 — 원본 색상 그대로 표시, 배경색으로 활성 상태 표현
@Composable
private fun MapIconButton(
    painter: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    isActive: Boolean = false,
    enabled: Boolean = true
) {
    val bgColor = when {
        isLoading || isActive -> MaterialTheme.colorScheme.primaryContainer
        !enabled              -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        else                  -> MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = bgColor,
        shadowElevation = 4.dp,
        modifier = modifier
            .size(44.dp)
            .clickable(enabled = enabled && !isLoading, onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            } else {
                Icon(
                    painter = painter,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(28.dp),
                    tint = Color.Unspecified
                )
            }
        }
    }
}

@Composable
private fun MapIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    isActive: Boolean = false,
    enabled: Boolean = true
) {
    val bgColor = when {
        isLoading || isActive -> MaterialTheme.colorScheme.primaryContainer
        !enabled              -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        else                  -> MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
    }
    val iconTint = when {
        isLoading || isActive -> MaterialTheme.colorScheme.onPrimaryContainer
        !enabled              -> MaterialTheme.colorScheme.onSurfaceVariant
        else                  -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = bgColor,
        shadowElevation = 4.dp,
        modifier = modifier
            .size(44.dp)
            .clickable(enabled = enabled && !isLoading, onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(24.dp),
                    tint = iconTint
                )
            }
        }
    }
}

// 아이콘 + 텍스트 라벨이 세로로 조합된 버튼
@Composable
private fun MapLabeledIconButton(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    enabled: Boolean = true
) {
    val bgColor = when {
        isActive -> MaterialTheme.colorScheme.primaryContainer
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        else     -> MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
    }
    val contentColor = when {
        isActive -> MaterialTheme.colorScheme.onPrimaryContainer
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        else     -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = bgColor,
        shadowElevation = 4.dp,
        modifier = modifier
            .width(44.dp)
            .height(54.dp)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp),
                tint = contentColor
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        }
    }
}

@Composable
private fun KakaoMapComposable(
    modifier: Modifier = Modifier,
    onMapReady: (KakaoMap) -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnMapReady by rememberUpdatedState(onMapReady)
    val mapView = remember {
        Log.d(TAG, "MapView 생성")
        MapView(context)
    }

    AndroidView(
        modifier = modifier,
        factory = {
            Log.d(TAG, "AndroidView.factory: MapView를 뷰 계층에 추가")
            mapView
        }
    )

    DisposableEffect(Unit) {
        Log.d(TAG, "DisposableEffect: lifecycle.currentState=${lifecycle.currentState}")
        Log.d(TAG, "DisposableEffect: mapView.start() 호출")
        mapView.start(
            object : MapLifeCycleCallback() {
                override fun onMapDestroy() {
                    Log.d(TAG, "onMapDestroy")
                }
                override fun onMapError(e: Exception) {
                    Log.e(TAG, "onMapError: ${e.javaClass.simpleName} — ${e.message}", e)
                }
            },
            object : KakaoMapReadyCallback() {
                override fun getPosition(): LatLng = SEOUL_STATION
                override fun getZoomLevel(): Int = DEFAULT_ZOOM
                override fun onMapReady(kakaoMap: KakaoMap) {
                    Log.d(TAG, "onMapReady: 지도 준비 완료")
                    kakaoMap.setGestureEnable(GestureType.Rotate, false)
                    currentOnMapReady(kakaoMap)
                }
            }
        )

        val observer = LifecycleEventObserver { _, event ->
            Log.d(TAG, "LifecycleEvent: $event")
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    Log.d(TAG, "mapView.resume() 호출 (ON_RESUME)")
                    mapView.resume()
                }
                Lifecycle.Event.ON_PAUSE  -> {
                    Log.d(TAG, "mapView.pause() 호출 (ON_PAUSE)")
                    mapView.pause()
                }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)

        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            Log.d(TAG, "mapView.resume() 호출 (이미 RESUMED 상태)")
            mapView.resume()
        } else {
            Log.d(TAG, "현재 상태가 RESUMED 미만 — resume() 보류: ${lifecycle.currentState}")
        }

        onDispose {
            Log.d(TAG, "onDispose: lifecycle observer 제거")
            lifecycle.removeObserver(observer)
        }
    }
}

/**
 * 네이티브 마커용 비트맵 — 빨간 원으로 오버레이 마커(파란 원)와 시각적으로 구분
 * 두 마커가 겹치면 좌표가 올바름을 확인할 수 있다.
 */
private fun createNativeMarkerBitmap(): Bitmap {
    val size = 60
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val cx = size / 2f; val cy = size / 2f; val r = size / 2f - 2f
    paint.color = android.graphics.Color.argb(255, 229, 57, 53)   // 빨간 외곽
    canvas.drawCircle(cx, cy, r, paint)
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(cx, cy, r * 0.5f, paint)
    paint.color = android.graphics.Color.argb(255, 229, 57, 53)   // 빨간 중심점
    canvas.drawCircle(cx, cy, r * 0.25f, paint)
    return bmp
}

/**
 * 대용량 이미지를 reqWidth×reqHeight 이하로 축소해 디코딩한다.
 * inJustDecodeBounds로 원본 크기를 먼저 읽고, 2의 거듭제곱 inSampleSize를 계산해 메모리를 절약한다.
 */
private fun decodeSampledBitmap(
    resources: Resources,
    resId: Int,
    maxWidth: Int,
    maxHeight: Int
): android.graphics.Bitmap? {
    // inScaled=false: 기기 밀도 보정 없이 파일 원본 크기를 그대로 조회
    val opts = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        inScaled = false
    }
    BitmapFactory.decodeResource(resources, resId, opts)
    val rawW = opts.outWidth
    val rawH = opts.outHeight

    // 가로 OR 세로 중 하나라도 기준 초과이면 계속 축소 (2의 거듭제곱)
    var sampleSize = 1
    while (rawW / sampleSize > maxWidth || rawH / sampleSize > maxHeight) {
        sampleSize *= 2
    }

    Log.d(TAG, "decodeSampledBitmap: 원본=${rawW}x${rawH}  inSampleSize=$sampleSize  결과≈${rawW / sampleSize}x${rawH / sampleSize}")

    opts.inJustDecodeBounds = false
    opts.inSampleSize = sampleSize
    return BitmapFactory.decodeResource(resources, resId, opts)
}

@Composable
private fun StatChip(label: String, value: String) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun IntervalSelectionButton(
    selectedIntervalMs: Long,
    enabled: Boolean,
    onIntervalSelected: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    val label = when (selectedIntervalMs) {
        1_000L -> "1초"
        2_000L -> "2초"
        3_000L -> "3초"
        else   -> "${selectedIntervalMs / 1000}초"
    }
    val surfaceColor = if (enabled)
        MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
    else
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    val textColor = if (enabled)
        MaterialTheme.colorScheme.onSurface
    else
        MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier = modifier) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = surfaceColor,
            shadowElevation = 4.dp,
            modifier = Modifier.clickable(enabled = enabled) { expanded = true }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(text = label, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium, color = textColor)
                Text(text = "▾", style = MaterialTheme.typography.bodyMedium, color = textColor)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(1_000L to "1초", 2_000L to "2초", 3_000L to "3초").forEach { (ms, lbl) ->
                DropdownMenuItem(
                    text = { Text(lbl) },
                    onClick = { onIntervalSelected(ms); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun FloorSelectionDropdown(
    selectedFloor: FloorSelection,
    onFloorSelected: (FloorSelection) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    val floorLabel = when (selectedFloor) {
        FloorSelection.HIDDEN -> "숨김"
        FloorSelection.F2     -> "2층"
        FloorSelection.F3     -> "3층"
    }

    Box(modifier = modifier) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            shadowElevation = 4.dp,
            modifier = Modifier.clickable { expanded = true }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(text = floorLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(text = "▾", style = MaterialTheme.typography.bodyMedium)
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            listOf(FloorSelection.HIDDEN to "숨김", FloorSelection.F2 to "2층", FloorSelection.F3 to "3층")
                .forEach { (floor, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onFloorSelected(floor)
                            expanded = false
                        }
                    )
                }
        }
    }
}
