package com.hubilon.seoulstationpoc.model

data class SensorSignal(
    val accelX: Float = 0f,
    val accelY: Float = 0f,
    val accelZ: Float = 0f,
    val accelWX: Float = 0f,
    val accelWY: Float = 0f,
    val accelWZ: Float = 0f,
    val gyroX: Float = 0f,
    val gyroY: Float = 0f,
    val gyroZ: Float = 0f,
    val gyroWX: Float = 0f,
    val gyroWY: Float = 0f,
    val gyroWZ: Float = 0f,
    val magX: Float = 0f,
    val magY: Float = 0f,
    val magZ: Float = 0f,
    val magWX: Float = 0f,
    val magWY: Float = 0f,
    val magWZ: Float = 0f,
) {
    /** 서버 전송용 순서 배열: accel(6) + gyro(6) + mag(6) = 18개 */
    fun toFloatArray(): FloatArray = floatArrayOf(
        accelX, accelY, accelZ,
        accelWX, accelWY, accelWZ,
        gyroX,  gyroY,  gyroZ,
        gyroWX,  gyroWY,  gyroWZ,
        magX,   magY,   magZ,
        magWX,   magWY,   magWZ,
    )
}
