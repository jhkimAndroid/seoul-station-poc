package com.hubilon.seoulstationpoc.data.pdr

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.hubilon.seoulstationpoc.model.LocationResult
import com.hubilon.seoulstationpoc.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = AppLog.SENSOR

private const val STEP_HIGH_THRESHOLD  = 11.5f
private const val STEP_LOW_THRESHOLD   = 9.5f
private const val MIN_STEP_INTERVAL_MS = 300L
private const val STEP_LENGTH_DEFAULT_M = 0.7f

/**
 * PDR 변위 트래커.
 * 걸음 수·방향을 내부에서 관리하며 어떤 좌표에든 현재 변위를 적용할 수 있다.
 *
 * - [applyPdr]: 입력 좌표에 현재 누적 변위를 적용해 보정 좌표를 반환한다.
 * - [reset]: 누적 변위를 초기화한다.
 * - [stepCount]: 걸음이 감지될 때마다 갱신되는 StateFlow — 수집하면 변위 적용 타이밍을 알 수 있다.
 */
class PdrProcessor(context: Context) {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val pdrThread = HandlerThread("PdrThread").also { it.start() }
    private val pdrHandler = Handler(pdrThread.looper)

    // 누적 변위 (미터, @Volatile: 센서 스레드 쓰기 / 메인 스레드 읽기)
    @Volatile private var displacementNorth = 0.0
    @Volatile private var displacementEast  = 0.0

    // 방위각 (라디안, 0=북, 시계 방향 양수) — 자북(Magnetic North) 기준
    @Volatile private var azimuthRad = 0f

    // 자북→진북 편각 보정값 (라디안, GPS 첫 수신 시 GeomagneticField로 설정)
    @Volatile private var declinationRad = 0f

    // 보폭 (미터, 앵커 방향 비교로 동적 조정)
    @Volatile private var stepLengthM = STEP_LENGTH_DEFAULT_M

    // 걸음 감지 상태 (pdrHandler 스레드 전용)
    private var stepHigh = false
    private var lastStepTimeMs = 0L
    private var totalSteps = 0

    private val _stepCount = MutableStateFlow(0)
    val stepCount: StateFlow<Int> = _stepCount.asStateFlow()

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    val ax = event.values[0]; val ay = event.values[1]; val az = event.values[2]
                    detectStep(sqrt(ax * ax + ay * ay + az * az))
                }
                Sensor.TYPE_ROTATION_VECTOR -> {
                    val rm = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rm, event.values)
                    val orientation = FloatArray(3)
                    SensorManager.getOrientation(rm, orientation)
                    azimuthRad = orientation[0]
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private fun detectStep(magnitude: Float) {
        if (!stepHigh && magnitude > STEP_HIGH_THRESHOLD) {
            stepHigh = true
        } else if (stepHigh && magnitude < STEP_LOW_THRESHOLD) {
            stepHigh = false
            val now = SystemClock.elapsedRealtime()
            if (now - lastStepTimeMs >= MIN_STEP_INTERVAL_MS) {
                lastStepTimeMs = now
                onStepDetected()
            }
        }
    }

    private fun onStepDetected() {
        val heading = (azimuthRad + declinationRad).toDouble()
        displacementNorth += stepLengthM * cos(heading)
        displacementEast  += stepLengthM * sin(heading)
        totalSteps++
        _stepCount.value = totalSteps
        Log.d(TAG, "PDR 걸음 #$totalSteps — ΔN=%.2fm ΔE=%.2fm".format(displacementNorth, displacementEast))
    }

    /**
     * 입력 좌표에 현재 누적 PDR 변위를 적용한 좌표를 반환한다.
     * 변위가 0이면 입력 좌표와 동일한 값이 반환된다.
     */
    fun applyPdr(lat: Double, lng: Double): LocationResult {
        val north = displacementNorth
        val east  = displacementEast
        val deltaLat = north / 111320.0
        val deltaLng = east  / (111320.0 * cos(lat * PI / 180.0))
        return LocationResult(lat + deltaLat, lng + deltaLng)
    }

    /**
     * 자북→진북 편각을 설정한다.
     * GPS 첫 수신 시 GeomagneticField.getDeclination()으로 계산한 값을 전달한다.
     * 서편각이면 음수(한국 약 -7~-8°), 동편각이면 양수.
     */
    fun setDeclination(degrees: Float) {
        declinationRad = (degrees * PI / 180.0).toFloat()
        Log.i(TAG, "PDR 편각 보정 설정 — ${degrees}° (${declinationRad}rad)")
    }

    /**
     * 방위각을 외부에서 직접 설정한다.
     * 앵커 이동 방향으로 초기 방향을 보정할 때 사용하며, reset() 이후에도 값이 유지된다.
     */
    fun setAzimuth(radians: Double) {
        azimuthRad = radians.toFloat()
        Log.i(TAG, "PDR 방위각 보정 — ${"%.1f".format(radians * 180.0 / PI)}°")
    }

    /**
     * 보폭을 외부에서 설정한다.
     * 앵커 방향이 진행 방향과 일치하면 0.8f, 반대이면 0.4f로 조정한다.
     */
    fun setStepLength(meters: Float) {
        stepLengthM = meters
        Log.i(TAG, "PDR 보폭 조정 — ${meters}m")
    }

    /** 누적 변위와 걸음 수를 초기화하고 stepCount를 emit한다. 방위각은 초기화하지 않는다. */
    fun reset() {
        displacementNorth = 0.0
        displacementEast  = 0.0
        totalSteps        = 0
        stepHigh          = false
        _stepCount.value  = 0
        Log.i(TAG, "PDR 초기화")
    }

    /**
     * 누적 변위와 걸음 수만 초기화한다. stepCount를 emit하지 않으므로
     * collector가 트리거되지 않아 UI 좌표가 변하지 않는다.
     * PDR 주기 리셋처럼 위치를 유지한 채 기준점만 갱신할 때 사용한다.
     */
    fun resetDisplacement() {
        displacementNorth = 0.0
        displacementEast  = 0.0
        totalSteps        = 0
        stepHigh          = false
        Log.i(TAG, "PDR 변위 초기화")
    }

    fun start() {
        val types = listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_ROTATION_VECTOR)
        var registered = 0
        types.forEach { type ->
            sensorManager.getDefaultSensor(type)?.let { sensor ->
                sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME, pdrHandler)
                registered++
            } ?: Log.w(TAG, "PDR: 센서 없음 type=$type")
        }
        Log.i(TAG, "PDR 시작 — $registered/${types.size}개 센서 등록")
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
        reset()
        Log.i(TAG, "PDR 중지")
    }
}
