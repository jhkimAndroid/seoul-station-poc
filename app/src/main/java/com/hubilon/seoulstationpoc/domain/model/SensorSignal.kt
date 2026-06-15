package com.hubilon.seoulstationpoc.domain.model

data class SensorSignal(
    val accelX: Float = 0f,
    val accelY: Float = 0f,
    val accelZ: Float = 0f,
    val gyroX: Float = 0f,
    val gyroY: Float = 0f,
    val gyroZ: Float = 0f,
    val magX: Float = 0f,
    val magY: Float = 0f,
    val magZ: Float = 0f,
) {
    /** 서버 전송용 순서 배열: accel(3) + gyro(3) + mag(3) = 9개 */
    fun toFloatArray(): FloatArray = floatArrayOf(
        accelX, accelY, accelZ,
        gyroX,  gyroY,  gyroZ,
        magX,   magY,   magZ,
    )
}
