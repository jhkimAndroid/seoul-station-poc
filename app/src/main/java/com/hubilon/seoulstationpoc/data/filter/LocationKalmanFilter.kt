package com.hubilon.seoulstationpoc.data.filter

import com.hubilon.seoulstationpoc.model.LocationResult
import kotlin.math.cos

/**
 * 서버측위 좌표를 스무딩하는 4-state 표준 칼만 필터.
 *
 * state  = [x_m, y_m, vx, vy]  (로컬 미터계, 최초 수신 좌표 원점)
 * 측정   = [x_m, y_m]           (위치만 관측, 속도는 latent)
 * 모델   = 등속 운동 + 가속도 프로세스 노이즈
 *
 * 튜닝 파라미터:
 *   measurementNoiseSigma — 서버 측위 오차 [미터]. 실내 WiFi/BLE 기준 3~7 권장.
 *   processNoiseSigma     — 가속도 불확실성 [m/s²]. 보행 환경 0.3~1.0 권장.
 */
class LocationKalmanFilter(
    measurementNoiseSigma: Double = 5.0,
    processNoiseSigma: Double = 0.5
) {
    var measurementNoiseSigma: Double = measurementNoiseSigma
    var processNoiseSigma: Double = processNoiseSigma
    private var x = DoubleArray(4)          // [x_m, y_m, vx, vy]
    private var P = identity4()             // 4×4 공분산
    private var origin: DoubleArray? = null // [lat0, lng0]
    private var lastTimeMs = 0L
    private var initialized = false

    /**
     * 새로운 서버측위 좌표를 입력해 필터링된 좌표를 반환한다.
     * 첫 호출 시 초기화 후 입력값을 그대로 반환한다.
     */
    fun update(measLat: Double, measLng: Double): LocationResult {
        val nowMs = System.currentTimeMillis()

        if (!initialized) {
            origin = doubleArrayOf(measLat, measLng)
            x = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
            P = diagMatrix(doubleArrayOf(25.0, 25.0, 4.0, 4.0))
            lastTimeMs = nowMs
            initialized = true
            return LocationResult(measLat, measLng)
        }

        // dt 상한: 5초 초과 시 공분산이 비정상적으로 커지는 것을 방지
        val dt = ((nowMs - lastTimeMs) / 1000.0).coerceIn(0.05, 5.0)
        lastTimeMs = nowMs

        val (zx, zy) = toLocalMeters(measLat, measLng)

        // ── Predict ─────────────────────────────────────────────────────────
        // F: 등속 운동 모델 (F는 희소하므로 행렬 곱 대신 직접 전개)
        val xp = doubleArrayOf(
            x[0] + x[2] * dt,
            x[1] + x[3] * dt,
            x[2],
            x[3]
        )
        // F*P (행 0,1에 dt × 행 2,3 추가)
        val FP = Array(4) { i ->
            DoubleArray(4) { j -> P[i][j] + if (i < 2) dt * P[i + 2][j] else 0.0 }
        }
        // F*P*Fᵀ (열 0,1에 dt × 열 2,3 추가) + Q
        val q = processNoiseSigma * processNoiseSigma
        val dt2 = dt * dt; val dt3 = dt2 * dt; val dt4 = dt3 * dt
        val Pp = Array(4) { i ->
            DoubleArray(4) { j ->
                val fpft = FP[i][j] + if (j < 2) dt * FP[i][j + 2] else 0.0
                fpft + when {
                    i == 0 && j == 0 -> q * dt4 / 4
                    i == 1 && j == 1 -> q * dt4 / 4
                    i == 2 && j == 2 -> q * dt2
                    i == 3 && j == 3 -> q * dt2
                    (i == 0 && j == 2) || (i == 2 && j == 0) -> q * dt3 / 2
                    (i == 1 && j == 3) || (i == 3 && j == 1) -> q * dt3 / 2
                    else -> 0.0
                }
            }
        }

        // ── Update ──────────────────────────────────────────────────────────
        // H = [[1,0,0,0],[0,1,0,0]]  →  S = Pp[0..1][0..1] + R
        val r = measurementNoiseSigma * measurementNoiseSigma
        val s00 = Pp[0][0] + r;  val s01 = Pp[0][1]
        val s10 = Pp[1][0];       val s11 = Pp[1][1] + r
        val det = s00 * s11 - s01 * s10

        // S⁻¹ (2×2 역행렬)
        val si00 = s11 / det;  val si01 = -s01 / det
        val si10 = -s10 / det; val si11 = s00 / det

        // K = Pp * Hᵀ * S⁻¹  (Pp의 앞 2열 × 2×2 행렬)
        val K = Array(4) { i ->
            doubleArrayOf(
                Pp[i][0] * si00 + Pp[i][1] * si10,
                Pp[i][0] * si01 + Pp[i][1] * si11
            )
        }

        // 상태 갱신
        val innov0 = zx - xp[0]
        val innov1 = zy - xp[1]
        for (i in 0 until 4) {
            x[i] = xp[i] + K[i][0] * innov0 + K[i][1] * innov1
        }

        // P = (I − K·H) · Pp
        P = Array(4) { i ->
            DoubleArray(4) { j ->
                Pp[i][j] - K[i][0] * Pp[0][j] - K[i][1] * Pp[1][j]
            }
        }

        val (outLat, outLng) = fromLocalMeters(x[0], x[1])
        return LocationResult(outLat, outLng)
    }

    fun reset() {
        initialized = false
        origin = null
    }

    // ── 좌표 변환 ──────────────────────────────────────────────────────────

    private fun toLocalMeters(lat: Double, lng: Double): Pair<Double, Double> {
        val o = origin!!
        return Pair(
            (lng - o[1]) * metersPerDegLng(o[0]),
            (lat - o[0]) * METERS_PER_DEG_LAT
        )
    }

    private fun fromLocalMeters(xm: Double, ym: Double): Pair<Double, Double> {
        val o = origin!!
        return Pair(
            o[0] + ym / METERS_PER_DEG_LAT,
            o[1] + xm / metersPerDegLng(o[0])
        )
    }

    // ── 행렬 유틸 ──────────────────────────────────────────────────────────

    companion object {
        private const val METERS_PER_DEG_LAT = 111320.0
        private fun metersPerDegLng(lat: Double) = 111320.0 * cos(Math.toRadians(lat))

        private fun identity4() =
            Array(4) { i -> DoubleArray(4) { j -> if (i == j) 1.0 else 0.0 } }

        private fun diagMatrix(d: DoubleArray) =
            Array(4) { i -> DoubleArray(4) { j -> if (i == j) d[i] else 0.0 } }
    }
}
