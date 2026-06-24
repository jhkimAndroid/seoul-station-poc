package com.hubilon.positioning.scan

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.hubilon.positioning.model.SensorSignal

private const val TAG = "SSP_SENSOR"

class SensorCollector(context: Context) {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val sensorThread = HandlerThread("SensorCollectorThread").also { it.start() }
    private val sensorHandler = Handler(sensorThread.looper)

    @Volatile private var accel = FloatArray(3)
    @Volatile private var gyro = FloatArray(3)
    @Volatile private var mag = FloatArray(3)
    @Volatile private var rotationMatrix: FloatArray? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER  -> accel = event.values.copyOf()
                Sensor.TYPE_GYROSCOPE      -> gyro  = event.values.copyOf()
                Sensor.TYPE_MAGNETIC_FIELD -> mag   = event.values.copyOf()
                Sensor.TYPE_ROTATION_VECTOR -> {
                    val rm = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rm, event.values)
                    rotationMatrix = rm
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    fun start() {
        val types = listOf(
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_ROTATION_VECTOR,
        )
        var registered = 0
        types.forEach { type ->
            sensorManager.getDefaultSensor(type)?.let { sensor ->
                sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler)
                registered++
            } ?: Log.w(TAG, "센서 없음: type=$type")
        }
        Log.i(TAG, "센서 수집 시작 — $registered/${types.size}개 등록")
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
        Log.i(TAG, "센서 수집 중지")
    }

    fun getSnapshot(): SensorSignal {
        val a = accel; val g = gyro; val m = mag
        val rm = rotationMatrix
        return if (rm != null) {
            SensorSignal(
                accelX = a[0], accelY = a[1], accelZ = a[2],
                accelWX = rm[0]*a[0] + rm[1]*a[1] + rm[2]*a[2],
                accelWY = rm[3]*a[0] + rm[4]*a[1] + rm[5]*a[2],
                accelWZ = rm[6]*a[0] + rm[7]*a[1] + rm[8]*a[2],
                gyroX  = g[0], gyroY  = g[1], gyroZ  = g[2],
                gyroWX = rm[0]*g[0] + rm[1]*g[1] + rm[2]*g[2],
                gyroWY = rm[3]*g[0] + rm[4]*g[1] + rm[5]*g[2],
                gyroWZ = rm[6]*g[0] + rm[7]*g[1] + rm[8]*g[2],
                magX   = m[0], magY   = m[1], magZ   = m[2],
                magWX  = rm[0]*m[0] + rm[1]*m[1] + rm[2]*m[2],
                magWY  = rm[3]*m[0] + rm[4]*m[1] + rm[5]*m[2],
                magWZ  = rm[6]*m[0] + rm[7]*m[1] + rm[8]*m[2],
            )
        } else {
            Log.w(TAG, "회전 행렬 미획득 — body frame 반환")
            SensorSignal(
                accelX = a[0], accelY = a[1], accelZ = a[2],
                accelWX = a[0], accelWY = a[1], accelWZ = a[2],
                gyroX  = g[0], gyroY  = g[1], gyroZ  = g[2],
                gyroWX = g[0], gyroWY = g[1], gyroWZ = g[2],
                magX   = m[0], magY   = m[1], magZ   = m[2],
                magWX  = m[0], magWY  = m[1], magWZ  = m[2],
            )
        }
    }
}
