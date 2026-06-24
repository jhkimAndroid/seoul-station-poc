package com.hubilon.positioning.internal.refiner

import com.hubilon.positioning.model.LocationResult
import com.hubilon.positioning.refiner.LocationRefiner
import kotlin.math.cos

internal class KalmanRefiner(
    measurementNoiseSigma: Double = 10.0,
    processNoiseSigma: Double = 0.7
) : LocationRefiner {

    var measurementNoise: Double = measurementNoiseSigma
        set(value) { field = value; filter.measurementNoiseSigma = value }

    var processNoise: Double = processNoiseSigma
        set(value) { field = value; filter.processNoiseSigma = value }

    private val filter = LocationKalmanFilter(measurementNoiseSigma, processNoiseSigma)

    override fun refine(location: LocationResult): LocationResult =
        filter.update(location.lat, location.lng)

    override fun reset() = filter.reset()
}

private class LocationKalmanFilter(
    var measurementNoiseSigma: Double = 10.0,
    var processNoiseSigma: Double = 0.7
) {
    private var x = DoubleArray(4)
    private var P = identity4()
    private var origin: DoubleArray? = null
    private var lastTimeMs = 0L
    private var initialized = false

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
        val dt = ((nowMs - lastTimeMs) / 1000.0).coerceIn(0.05, 5.0)
        lastTimeMs = nowMs
        val (zx, zy) = toLocalMeters(measLat, measLng)

        val xp = doubleArrayOf(x[0] + x[2]*dt, x[1] + x[3]*dt, x[2], x[3])
        val FP = Array(4) { i -> DoubleArray(4) { j -> P[i][j] + if (i < 2) dt * P[i+2][j] else 0.0 } }
        val q = processNoiseSigma * processNoiseSigma
        val dt2 = dt*dt; val dt3 = dt2*dt; val dt4 = dt3*dt
        val Pp = Array(4) { i -> DoubleArray(4) { j ->
            val fpft = FP[i][j] + if (j < 2) dt * FP[i][j+2] else 0.0
            fpft + when {
                i == 0 && j == 0 -> q * dt4 / 4
                i == 1 && j == 1 -> q * dt4 / 4
                i == 2 && j == 2 -> q * dt2
                i == 3 && j == 3 -> q * dt2
                (i == 0 && j == 2) || (i == 2 && j == 0) -> q * dt3 / 2
                (i == 1 && j == 3) || (i == 3 && j == 1) -> q * dt3 / 2
                else -> 0.0
            }
        }}

        val r = measurementNoiseSigma * measurementNoiseSigma
        val s00 = Pp[0][0] + r; val s01 = Pp[0][1]
        val s10 = Pp[1][0];      val s11 = Pp[1][1] + r
        val det = s00 * s11 - s01 * s10
        val si00 = s11/det; val si01 = -s01/det
        val si10 = -s10/det; val si11 = s00/det
        val K = Array(4) { i -> doubleArrayOf(
            Pp[i][0]*si00 + Pp[i][1]*si10,
            Pp[i][0]*si01 + Pp[i][1]*si11
        )}
        val innov0 = zx - xp[0]; val innov1 = zy - xp[1]
        for (i in 0 until 4) x[i] = xp[i] + K[i][0]*innov0 + K[i][1]*innov1
        P = Array(4) { i -> DoubleArray(4) { j -> Pp[i][j] - K[i][0]*Pp[0][j] - K[i][1]*Pp[1][j] }}
        val (outLat, outLng) = fromLocalMeters(x[0], x[1])
        return LocationResult(outLat, outLng)
    }

    fun reset() { initialized = false; origin = null }

    private fun toLocalMeters(lat: Double, lng: Double): Pair<Double, Double> {
        val o = origin!!
        return Pair((lng - o[1]) * metersPerDegLng(o[0]), (lat - o[0]) * METERS_PER_DEG_LAT)
    }

    private fun fromLocalMeters(xm: Double, ym: Double): Pair<Double, Double> {
        val o = origin!!
        return Pair(o[0] + ym / METERS_PER_DEG_LAT, o[1] + xm / metersPerDegLng(o[0]))
    }

    companion object {
        private const val METERS_PER_DEG_LAT = 111320.0
        private fun metersPerDegLng(lat: Double) = 111320.0 * cos(Math.toRadians(lat))
        private fun identity4() = Array(4) { i -> DoubleArray(4) { j -> if (i == j) 1.0 else 0.0 } }
        private fun diagMatrix(d: DoubleArray) = Array(4) { i -> DoubleArray(4) { j -> if (i == j) d[i] else 0.0 } }
    }
}
