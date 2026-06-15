package com.hubilon.seoulstationpoc.util

/**
 * 앱 전체 로그 태그 상수
 *
 * ── logcat 필터 ──────────────────────────────────────────────────────
 *  전체 앱 로그       tag:SSP
 *  BLE 스캔          tag:SSP_BLE
 *  WiFi 스캔         tag:SSP_WIFI
 *  센서 수집          tag:SSP_SENSOR
 *  서버 API 통신      tag:SSP_API
 *  ViewModel 흐름     tag:SSP_VM
 *  지도·오버레이       tag:SSP_MAP
 *  앱 일반(초기화 등)  tag:SSP_APP
 * ─────────────────────────────────────────────────────────────────────
 *
 * 로그 레벨 사용 기준:
 *  Log.d → 정상 흐름 상세 정보 (디버그 전용)
 *  Log.i → 중요 상태 변경, 성공 결과
 *  Log.w → 예상 가능한 경고 (비활성화, 빈 결과 등)
 *  Log.e → 복구 불가한 오류 (예외, API 실패 등)
 */
object AppLog {
    const val BLE    = "SSP_BLE"    // BLE 스캔 관련
    const val WIFI   = "SSP_WIFI"   // WiFi 스캔 관련
    const val SENSOR = "SSP_SENSOR" // 센서 수집 관련
    const val API    = "SSP_API"    // 서버 API 통신
    const val VM     = "SSP_VM"     // ViewModel — 스캔/API 흐름 조율
    const val MAP    = "SSP_MAP"    // 지도 화면 및 오버레이
    const val APP    = "SSP_APP"    // 앱 일반 (초기화, 권한 등)
}
