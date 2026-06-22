package com.hubilon.seoulstationpoc.ui.map

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.hubilon.seoulstationpoc.SeoulStationPocApplication
import com.hubilon.seoulstationpoc.data.fingerprint.MISSING_RSSI
import com.hubilon.seoulstationpoc.util.AppLog
import com.kakao.vectormap.GestureType
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.Label
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.label.LabelStyles
import com.kakao.vectormap.shape.MapPoints
import com.kakao.vectormap.shape.Polygon
import com.kakao.vectormap.shape.PolygonOptions
import com.kakao.vectormap.shape.PolygonStyle
import com.kakao.vectormap.shape.Polyline
import com.kakao.vectormap.shape.PolylineOptions
import com.kakao.vectormap.shape.PolylineStyle

private const val TAG = AppLog.MAP

private val SEOUL_STATION = LatLng.from(37.55407996178773, 126.97066291608478)
private const val DEFAULT_ZOOM = 19

@Composable
fun MapScreen(
    viewModel: MapViewModel = viewModel(),
    onNavigateToScanDetail: (section: String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var kakaoMap by remember { mutableStateOf<KakaoMap?>(null) }
    val context = LocalContext.current
    val activity = context as Activity
    val lifecycleOwner = LocalLifecycleOwner.current

    var showExitDialog by remember { mutableStateOf(false) }
    var showWifiDialog by remember { mutableStateOf(false) }
    var showBluetoothDialog by remember { mutableStateOf(false) }
//    var showRttPanel by remember { mutableStateOf(false) }
    val wifiManager = remember { context.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    val bluetoothAdapter = remember {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    // 블루투스 자동 켜기 — 시스템 팝업으로 요청, 거부 시 설정 팝업으로 대체
    val bluetoothEnableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (bluetoothAdapter?.isEnabled == false) showBluetoothDialog = true
    }

    // ON_RESUME마다 WiFi/블루투스 상태 재확인 (설정에서 돌아왔을 때 포함)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                when {
                    !wifiManager.isWifiEnabled ->
                        showWifiDialog = true
                    bluetoothAdapter?.isEnabled == false && !showBluetoothDialog ->
                        bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 맵 진입 시 GPS/BLE/Sensor 스캔 시작
    LaunchedEffect(Unit) {
        viewModel.startMapScanning()
    }

    // 자동측위 중 화면 자동잠김 방지
    DisposableEffect(uiState.isAutoPositioning) {
        if (uiState.isAutoPositioning) {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 뒤로가기: 팝업이 열려 있으면 닫고, 아니면 자동측위 중단 후 종료 팝업 표시
    BackHandler {
        if (showExitDialog) {
            showExitDialog = false
        } else {
            if (uiState.isAutoPositioning) viewModel.toggleAutoScan()
            viewModel.resetMapToggles()
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

    if (showWifiDialog) {
        AlertDialog(
            onDismissRequest = {
                showWifiDialog = false
                if (bluetoothAdapter?.isEnabled == false) showBluetoothDialog = true
            },
            title = { Text("WiFi 꺼짐") },
            text  = { Text("WiFi가 꺼져있습니다.\n위치 측위를 위해 WiFi를 켜주세요.") },
            confirmButton = {
                TextButton(onClick = {
                    showWifiDialog = false
                    if (bluetoothAdapter?.isEnabled == false) showBluetoothDialog = true
                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                }) { Text("설정") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showWifiDialog = false
                    if (bluetoothAdapter?.isEnabled == false) showBluetoothDialog = true
                }) { Text("취소") }
            }
        )
    }

    if (showBluetoothDialog) {
        AlertDialog(
            onDismissRequest = { showBluetoothDialog = false },
            title = { Text("블루투스 꺼짐") },
            text  = { Text("블루투스가 꺼져있습니다.\n위치 측위를 위해 블루투스를 켜주세요.") },
            confirmButton = {
                TextButton(onClick = {
                    showBluetoothDialog = false
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }) { Text("설정") }
            },
            dismissButton = {
                TextButton(onClick = { showBluetoothDialog = false }) { Text("취소") }
            }
        )
    }

    if (uiState.isOutsideMbr) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("구역 이탈") },
            text  = { Text("현재 위치가 서비스 구역을 벗어났습니다.\n앱을 종료합니다.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.shutDown()
                    activity.finishAndRemoveTask()
                    android.os.Process.killProcess(android.os.Process.myPid())
                }) { Text("종료") }
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
    val serverLocation = uiState.serverLocation
    val kalmanFilteredLocation = uiState.kalmanFilteredLocation
    val finalLocation = uiState.finalLocation
    val pdrLocation = uiState.pdrLocation
    val fusedLocation = uiState.fusedLocation
    val locationUpdateCount = uiState.locationUpdateCount

    // 네이티브 마커 홀더 (맵 인스턴스가 바뀌면 참조 무효화)
//    val nativeMarkerHolder = remember { object { var label: Label? = null; var forMap: KakaoMap? = null } }

    // locationUpdateCount: 좌표가 동일해도 매 응답마다 반드시 재실행되도록 카운터 사용
    // isTracking: 추적 ON 전환 시 현재 위치로 즉시 이동
    LaunchedEffect(locationUpdateCount, kakaoMap, isTracking) {
        val map = kakaoMap ?: return@LaunchedEffect
        val loc = finalLocation ?: serverLocation ?: return@LaunchedEffect
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

    // 링크 폴리라인 표시/숨김
    val isLinkTestEnabled = uiState.isLinkEnabled
    val linkPolylines = remember { mutableListOf<Polyline>() }
    DisposableEffect(kakaoMap, isLinkTestEnabled) {
        val map = kakaoMap
        if (map != null && isLinkTestEnabled) {
            val shapeLayer = map.getShapeManager()?.getLayer()
            val linkStyle  = PolylineStyle.from(4f, 0x990000FF.toInt())
            viewModel.linkData.forEach { link ->
                try {
                    val pts = MapPoints.fromLatLng(listOf(
                        LatLng.from(link.startPos.lat, link.startPos.lng),
                        LatLng.from(link.endPos.lat, link.endPos.lng)
                    ))
                    shapeLayer?.addPolyline(PolylineOptions.from(pts, linkStyle))
                        ?.let { linkPolylines.add(it) }
                } catch (e: Exception) {
                    Log.w(TAG, "링크 폴리라인 추가 실패: ${e.message}")
                }
            }
            Log.i(TAG, "링크 폴리라인 ON — ${linkPolylines.size}개 생성")
        }
        onDispose {
            val shapeLayer = map?.getShapeManager()?.getLayer()
            linkPolylines.forEach { runCatching { shapeLayer?.remove(it) } }
            linkPolylines.clear()
            Log.i(TAG, "링크 폴리라인 제거")
        }
    }

    // 서울역 구역 폴리곤 — 맵 준비 후 1회 추가, 반투명 녹색
    var areaPolygon by remember { mutableStateOf<Polygon?>(null) }
    DisposableEffect(kakaoMap) {
        val map = kakaoMap
        if (map != null) {
            val coords = listOf(
                LatLng.from(37.554289, 126.970212),
                LatLng.from(37.554091, 126.970123),
                LatLng.from(37.554028, 126.970544),
                LatLng.from(37.553858, 126.970533),
                LatLng.from(37.553853, 126.970606),
                LatLng.from(37.553826, 126.970603),
                LatLng.from(37.553826, 126.970755),
                LatLng.from(37.554027, 126.970756),
                LatLng.from(37.554088, 126.971101),
                LatLng.from(37.554284, 126.971007),
                LatLng.from(37.554218, 126.970654)
            )
            try {
                val pts   = MapPoints.fromLatLng(coords)
                val style = PolygonStyle.from(
                    0x6600C853.toInt(),  // 40% alpha, 녹색 채우기
                    2f,
                    0xFF00C853.toInt()   // 불투명 녹색 외곽선
                )
                val opts = PolygonOptions.from(pts, style)
                areaPolygon = map.getShapeManager()?.getLayer()?.addPolygon(opts)
                Log.i(TAG, "구역 폴리곤 추가 완료 — ${coords.size}개 좌표")
            } catch (e: Exception) {
                Log.w(TAG, "구역 폴리곤 추가 실패: ${e.message}")
            }
        }
        onDispose {
            areaPolygon?.let { polygon ->
                runCatching { kakaoMap?.getShapeManager()?.getLayer()?.remove(polygon) }
                areaPolygon = null
            }
        }
    }

    // 링크 스냅: 터치 클릭 리스너 관리
    val isLinkSnapEnabled = uiState.isLinkMatchingEnabled
    DisposableEffect(kakaoMap, isLinkSnapEnabled) {
        val map = kakaoMap
        if (map != null && isLinkSnapEnabled) {
            map.setOnMapClickListener { _, latLng, _, _ ->
                viewModel.onMapTouched(latLng.latitude, latLng.longitude)
            }
            Log.i(TAG, "링크 스냅 ON")
        }
        onDispose {
            if (!isLinkSnapEnabled) {
                map?.setOnMapClickListener(null)
                Log.i(TAG, "링크 스냅 OFF")
            }
        }
    }

    // 링크 테스트: 터치 마커 + 스냅 마커 관리
    val linkTouchPoint   = uiState.linkTouchPoint
    val linkSnappedPoint = uiState.linkSnappedPoint
    val touchLabelHolder   = remember { arrayOfNulls<Label>(1) }
    val snappedLabelHolder = remember { arrayOfNulls<Label>(1) }
    LaunchedEffect(kakaoMap, isLinkSnapEnabled, linkTouchPoint, linkSnappedPoint) {
        val map = kakaoMap ?: return@LaunchedEffect
        val labelManager = map.getLabelManager() ?: return@LaunchedEffect
        val layer = labelManager.getLayer() ?: return@LaunchedEffect

        if (!isLinkSnapEnabled || linkTouchPoint == null) {
            touchLabelHolder[0]?.let { runCatching { layer.remove(it) } }
            touchLabelHolder[0] = null
            snappedLabelHolder[0]?.let { runCatching { layer.remove(it) } }
            snappedLabelHolder[0] = null
            return@LaunchedEffect
        }

        val touchLatLng   = LatLng.from(linkTouchPoint.lat, linkTouchPoint.lng)
        val existingTouch = touchLabelHolder[0]
        if (existingTouch != null) {
            existingTouch.moveTo(touchLatLng)
            existingTouch.show(true)
        } else {
            try {
                val styles = labelManager.addLabelStyles(
                    LabelStyles.from(LabelStyle.from(createCircleBitmap(0xFFE91E63.toInt(), 32))
                        .setAnchorPoint(0.5f, 0.5f))
                )
                touchLabelHolder[0] = layer.addLabel(LabelOptions.from(touchLatLng).setStyles(styles))
            } catch (e: Exception) { Log.e(TAG, "터치 마커 생성 실패: ${e.message}") }
        }

        val snapped = linkSnappedPoint ?: return@LaunchedEffect
        val snappedLatLng    = LatLng.from(snapped.lat, snapped.lng)
        val existingSnapped = snappedLabelHolder[0]
        if (existingSnapped != null) {
            existingSnapped.moveTo(snappedLatLng)
            existingSnapped.show(true)
        } else {
            try {
                val styles = labelManager.addLabelStyles(
                    LabelStyles.from(LabelStyle.from(createCircleBitmap(0xFF4CAF50.toInt(), 32))
                        .setAnchorPoint(0.5f, 0.5f))
                )
                snappedLabelHolder[0] = layer.addLabel(LabelOptions.from(snappedLatLng).setStyles(styles))
            } catch (e: Exception) { Log.e(TAG, "스냅 마커 생성 실패: ${e.message}") }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        KakaoMapComposable(
            modifier = Modifier.fillMaxSize(),
            onMapReady = { map -> kakaoMap = map }
        )

        // 층 평면도 오버레이 + 위치 마커 (마커는 평면도 위에 그림)
        val isAutoScanning      = uiState.isAutoPositioning
        val isTestMarkerEnabled = uiState.isTestMarkerEnabled
        val isPdrEnabled        = uiState.isPdrEnabled
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { FloorPlanOverlayView(it) },
            update = { view ->
                view.updateMap(kakaoMap)
                view.updateBitmap(floorBitmap)
                // 테스트 마커: GPS·서버·칼만·PDR — 테스트 마커 버튼 ON일 때만 표시
                view.updateServerLocation(
                    if (isAutoScanning && isTestMarkerEnabled) serverLocation?.let { LatLng.from(it.lat, it.lng) } else null
                )
                view.updateKalmanFilteredLocation(
                    if (isAutoScanning && isTestMarkerEnabled) kalmanFilteredLocation?.let { LatLng.from(it.lat, it.lng) } else null
                )
                view.updatePdrLocation(
                    if (isAutoScanning && isTestMarkerEnabled && isPdrEnabled) pdrLocation?.let { LatLng.from(it.lat, it.lng) } else null
                )
                view.updateFusedLocation(
                    if (isTestMarkerEnabled) fusedLocation?.let { LatLng.from(it.lat, it.lng) } else null
                )
                // 최종 마커: 자동측위 ON/OFF에만 연동
                view.updateFinalLocation(
                    if (isAutoScanning) finalLocation?.let { LatLng.from(it.lat, it.lng) } else null
                )
                // 이동경로 화살표: 테스트 마커 ON일 때만 표시
                view.updateLocationHistory(
                    if (isAutoScanning && isTestMarkerEnabled) uiState.locationHistory else emptyList()
                )
            }
        )

        // 좌상단: 스캔결과 버튼
        if(SeoulStationPocApplication.IS_TEST) {
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
                // RTT 버튼 (비활성화)
//                Surface(
//                    shape = MaterialTheme.shapes.small,
//                    color = if (showRttPanel) MaterialTheme.colorScheme.primaryContainer
//                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
//                    shadowElevation = 4.dp,
//                    modifier = Modifier
//                        .size(44.dp)
//                        .clickable { showRttPanel = !showRttPanel }
//                ) {
//                    Box(contentAlignment = Alignment.Center) {
//                        Text(
//                            text = "RTT",
//                            style = MaterialTheme.typography.labelSmall,
//                            fontWeight = FontWeight.Bold,
//                            color = if (showRttPanel) MaterialTheme.colorScheme.onPrimaryContainer
//                                    else MaterialTheme.colorScheme.onSurface
//                        )
//                    }
//                }
            }

            // RTT 스캔 결과 패널 (비활성화)
//            if (showRttPanel) {
//                val rttSignals = uiState.rttSignals
//                Surface(
//                    modifier = Modifier
//                        .align(Alignment.TopStart)
//                        .statusBarsPadding()
//                        .padding(start = 8.dp, top = 60.dp)
//                        .width(230.dp),
//                    shape = MaterialTheme.shapes.medium,
//                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.93f),
//                    shadowElevation = 4.dp
//                ) {
//                    Column(
//                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
//                        verticalArrangement = Arrangement.spacedBy(6.dp)
//                    ) {
//                        Text(
//                            text = "RTT 결과 (${rttSignals.size}개)",
//                            style = MaterialTheme.typography.labelMedium,
//                            fontWeight = FontWeight.Bold
//                        )
//                        if (rttSignals.isEmpty()) {
//                            Text(
//                                text = "측정 결과 없음",
//                                style = MaterialTheme.typography.bodySmall,
//                                color = MaterialTheme.colorScheme.onSurfaceVariant
//                            )
//                        } else {
//                            rttSignals.forEach { s ->
//                                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
//                                    Text(
//                                        text = s.bssid,
//                                        style = MaterialTheme.typography.labelSmall,
//                                        fontWeight = FontWeight.Bold
//                                    )
//                                    Text(
//                                        text = "${"%.2f".format(s.distanceMm / 1000.0)}m  ±${s.distanceStdDevMm}mm",
//                                        style = MaterialTheme.typography.bodySmall
//                                    )
//                                    Text(
//                                        text = "RSSI ${s.rssi}dBm  성공 ${s.successCount}/${s.attemptCount}",
//                                        style = MaterialTheme.typography.bodySmall,
//                                        color = MaterialTheme.colorScheme.onSurfaceVariant
//                                    )
//                                }
//                            }
//                        }
//                    }
//                }
//            }
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
                isActive = uiState.isAutoPositioning
            )
            if(SeoulStationPocApplication.IS_TEST) {
                MapIconButton(
                    painter = painterResource(R.drawable.icon_test_marker),
                    contentDescription = "테스트 마커",
                    onClick = { viewModel.toggleTestMarker() },
                    isActive = uiState.isTestMarkerEnabled
                )
                MapIconButton(
                    painter = painterResource(R.drawable.icon_link),
                    contentDescription = "링크 표시",
                    onClick = { viewModel.toggleLink() },
                    isActive = uiState.isLinkEnabled
                )
                MapIconButton(
                    painter = painterResource(R.drawable.icon_link_matching),
                    contentDescription = "링크 매칭",
                    onClick = { viewModel.toggleLinkMatching() },
                    isActive = uiState.isLinkMatchingEnabled
                )
            }
        }

        // 우상단: 칼만 파라미터 설정 + 앵커 주기 + 층 선택
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if(SeoulStationPocApplication.IS_TEST) {
                KalmanParamButton(
                    label = "M",
                    value = "%.0f".format(uiState.kalmanMeasurementNoise),
                    onClick = { viewModel.showKalmanMeasurementDialog() }
                )
                KalmanParamButton(
                    label = "P",
                    value = "%.1f".format(uiState.kalmanProcessNoise),
                    onClick = { viewModel.showKalmanProcessDialog() }
                )
                KalmanParamButton(
                    label = "R",
                    value = "${uiState.pdrResetIntervalSec}s",
                    onClick = { viewModel.showPdrResetIntervalDialog() }
                )
            }
            FloorSelectionDropdown(
                selectedFloor = uiState.selectedFloor,
                onFloorSelected = { viewModel.setFloor(it) }
            )
        }

        // 칼만 측정노이즈 설정 다이얼로그
        if (uiState.showKalmanMeasurementDialog) {
            KalmanParamDialog(
                title = "측정 노이즈 (M)",
                description = "measurementNoiseSigma",
                hint = "▲ 올리면: 측위를 덜 신뢰 → 스무딩 강화\n▼ 내리면: 측위를 더 신뢰 → 즉각 반응",
                currentValue = uiState.kalmanMeasurementNoise.toFloat(),
                valueRange = 1f..20f,
                steps = 18,
                formatValue = { "%.0f".format(it) },
                onConfirm = { viewModel.setKalmanMeasurementNoise(it.toDouble()) },
                onDismiss = { viewModel.dismissKalmanDialogs() }
            )
        }

        // 칼만 프로세스노이즈 설정 다이얼로그
        if (uiState.showKalmanProcessDialog) {
            KalmanParamDialog(
                title = "프로세스 노이즈 (P)",
                description = "processNoiseSigma",
                hint = "▲ 올리면: 빠른 움직임 가정 → 즉각 반응\n▼ 내리면: 느린 움직임 가정 → 스무딩 강화",
                currentValue = (uiState.kalmanProcessNoise * 10).toFloat(),
                valueRange = 1f..20f,
                steps = 18,
                formatValue = { "%.1f".format(it / 10f) },
                onConfirm = { viewModel.setKalmanProcessNoise((it / 10.0)) },
                onDismiss = { viewModel.dismissKalmanDialogs() }
            )
        }

        // PDR 갱신 주기 설정 다이얼로그 (5~20초, 1초 단위)
        if (uiState.showPdrResetIntervalDialog) {
            KalmanParamDialog(
                title = "PDR 갱신 주기 (R)",
                description = "pdrResetIntervalSec",
                hint = "▲ 올리면: PDR 드리프트 누적 증가\n▼ 내리면: 기준점 갱신 빈도 증가",
                currentValue = uiState.pdrResetIntervalSec.toFloat(),
                valueRange = 5f..30f,
                steps = 14,
                formatValue = { "${it.toInt()}s" },
                onConfirm = { viewModel.setPdrResetIntervalSec(it.toInt()) },
                onDismiss = { viewModel.dismissPdrResetIntervalDialog() }
            )
        }

        // 우측 하단: 마커 범례 — 테스트 마커 ON일 때만 표시
        if (isTestMarkerEnabled) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 8.dp, bottom = 12.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                shadowElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    MarkerLegendItem(color = Color(0xFF388E3C), label = "GPS")
                    MarkerLegendItem(color = Color(0xFF2196F3), label = "서버")
                    MarkerLegendItem(color = Color(0xFFF9A825), label = "PDR")
                    MarkerLegendItem(color = Color(0xFFE93228), label = "최종")
                }
            }
        }

        // 하단 중앙: 자동측위 ON일 때만 표시
        val errorMessage = uiState.errorMessage
        val fingerprintEntries = uiState.fingerprintEntries
        val anchorDirectionLabel = uiState.anchorDirectionLabel
        val matchCount = fingerprintEntries?.count { it.rssi != MISSING_RSSI } ?: 0
        val hasBottom = SeoulStationPocApplication.IS_TEST &&
                (fingerprintEntries != null || errorMessage != null || finalLocation != null ||
                 anchorDirectionLabel != null ||
                 (isTestMarkerEnabled && (serverLocation != null || kalmanFilteredLocation != null ||
                  pdrLocation != null || fusedLocation != null)))

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
                    if (fingerprintEntries != null && isAutoScanning) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatChip(label = "BLE",  value = uiState.scanData.bleSignals.size.toString())
                            StatChip(label = "WiFi", value = uiState.scanData.wifiSignals.size.toString())
                            StatChip(label = "매칭", value = "$matchCount / ${fingerprintEntries.size}")
                        }
                    }
                    if (anchorDirectionLabel != null) {
                        val dirColor = if (anchorDirectionLabel == "정방향") Color(0xFF4CAF50) else Color(0xFFF44336)
                        val dirText  = if (anchorDirectionLabel == "정방향") "▲ 정방향 (보폭 ${uiState.stepLengthM}m)" else "▼ 역방향 (보폭 ${uiState.stepLengthM}m)"
                        Text(
                            text = dirText,
                            style = MaterialTheme.typography.bodySmall,
                            color = dirColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (errorMessage != null) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        if (isTestMarkerEnabled && serverLocation != null) {
                            Text(
                                text = "● 서버: %.6f, %.6f".format(serverLocation.lat, serverLocation.lng),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF2196F3)
                            )
                        }
                        if (isTestMarkerEnabled && kalmanFilteredLocation != null) {
                            Text(
                                text = "● 칼만: %.6f, %.6f".format(kalmanFilteredLocation.lat, kalmanFilteredLocation.lng),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF673AB7)
                            )
                        }
                        if (isTestMarkerEnabled && pdrLocation != null) {
                            Text(
                                text = "● PDR: %.6f, %.6f".format(pdrLocation.lat, pdrLocation.lng),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFF9A825)
                            )
                        }
                        if (finalLocation != null) {
                            Text(
                                text = "● 최종: %.6f, %.6f".format(finalLocation.lat, finalLocation.lng),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFE93228)
                            )
                        }
                        if (isTestMarkerEnabled && fusedLocation != null) {
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

@Composable
private fun MarkerLegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
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
                    kakaoMap.cameraMinLevel = 17
                    kakaoMap.cameraMaxLevel = 20
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

@Composable
private fun KalmanParamButton(
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .height(36.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(value, style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

private fun createCircleBitmap(color: Int, sizeDp: Int): Bitmap {
    val size = (sizeDp * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    val r = size / 2f
    canvas.drawCircle(r, r, r, paint)
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(r, r, r * 0.45f, paint)
    return bmp
}

@Composable
private fun KalmanParamDialog(
    title: String,
    description: String,
    hint: String = "",
    currentValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    formatValue: (Float) -> String,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var sliderValue by remember { mutableFloatStateOf(currentValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    formatValue(sliderValue),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth()
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = valueRange,
                    steps = steps,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatValue(valueRange.start), style = MaterialTheme.typography.labelSmall)
                    Text(formatValue(valueRange.endInclusive), style = MaterialTheme.typography.labelSmall)
                }
                if (hint.isNotEmpty()) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.4f
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(sliderValue) }) { Text("적용") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}
