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
import com.hubilon.seoulstationpoc.domain.model.LocationResult
import com.hubilon.seoulstationpoc.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = AppLog.SENSOR

// 걸음 감지 임계값 (m/s²): 보행 시 가속도 크기 피크/저점
private const val STEP_HIGH_THRESHOLD = 11.5f
private const val STEP_LOW_THRESHOLD  = 9.5f
private const val MIN_STEP_INTERVAL_MS = 300L
private const val STEP_LENGTH_M = 0.7f

class PdrProcessor(context: Context) {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val pdrThread = HandlerThread("PdrThread").also { it.start() }
    private val pdrHandler = Handler(pdrThread.looper)

    // 앵커 좌표 (서버 측위 결과)
    @Volatile private var anchorLat = Double.NaN
    @Volatile private var anchorLng = Double.NaN

    // 앵커에서의 누적 변위 (미터)
    @Volatile private var displacementNorth = 0.0
    @Volatile private var displacementEast  = 0.0

    // 방위각 (라디안, 0=북, 시계 방향 양수)
    @Volatile private var azimuthRad = 0f

    // 걸음 감지 상태
    private var stepHigh = false
    private var lastStepTimeMs = 0L

    private val _pdrFlow = MutableStateFlow<LocationResult?>(null)
    val pdrFlow: StateFlow<LocationResult?> = _pdrFlow.asStateFlow()

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    val ax = event.values[0]
                    val ay = event.values[1]
                    val az = event.values[2]
                    detectStep(sqrt(ax * ax + ay * ay + az * az))
                }
                Sensor.TYPE_ROTATION_VECTOR -> {
                    val rm = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rm, event.values)
                    val orientation = FloatArray(3)
                    SensorManager.getOrientation(rm, orientation)
                    azimuthRad = orientation[0]  // 방위각 (라디안)
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
        if (anchorLat.isNaN()) return
        val heading = azimuthRad.toDouble()
        displacementNorth += STEP_LENGTH_M * cos(heading)
        displacementEast  += STEP_LENGTH_M * sin(heading)

        val deltaLat = displacementNorth / 111320.0
        val deltaLng = displacementEast / (111320.0 * cos(anchorLat * PI / 180.0))
        val newLat = anchorLat + deltaLat
        val newLng = anchorLng + deltaLng
        _pdrFlow.value = LocationResult(newLat, newLng)
        Log.d(TAG, "PDR 걸음 감지 — ΔN=%.2fm ΔE=%.2fm → lat=%.6f lng=%.6f".format(
            displacementNorth, displacementEast, newLat, newLng))
    }

    val hasAnchor: Boolean get() = !anchorLat.isNaN()

    /** 서버 측위 결과를 앵커로 설정하고 변위를 초기화한다. */
    fun setAnchor(lat: Double, lng: Double) {
        anchorLat = lat
        anchorLng = lng
        displacementNorth = 0.0
        displacementEast  = 0.0
        _pdrFlow.value = LocationResult(lat, lng)
        Log.d(TAG, "PDR 앵커 설정 — lat=$lat lng=$lng")
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
        anchorLat = Double.NaN
        anchorLng = Double.NaN
        displacementNorth = 0.0
        displacementEast  = 0.0
        stepHigh = false
        _pdrFlow.value = null
        Log.i(TAG, "PDR 중지")
    }
}
