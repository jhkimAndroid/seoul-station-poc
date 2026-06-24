package com.hubilon.positioning.internal.refiner

import com.hubilon.positioning.model.GeoPos
import com.hubilon.positioning.model.LinkData
import com.hubilon.positioning.model.LocationResult
import com.hubilon.positioning.refiner.LocationRefiner
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal class LinkSnapRefiner : LocationRefiner {

    private var linkData: List<LinkData> = emptyList()

    fun updateLinkData(data: List<LinkData>) {
        linkData = data
    }

    fun getLinkData(): List<LinkData> = linkData

    override fun refine(location: LocationResult): LocationResult {
        if (linkData.isEmpty()) return location
        return findNearest(GeoPos(location.lat, location.lng))
            ?.let { LocationResult(it.lat, it.lng) }
            ?: location
    }

    override fun reset() = Unit

    fun findNearest(input: GeoPos): GeoPos? {
        if (linkData.isEmpty()) return null
        var minDist = Double.MAX_VALUE
        var nearest: GeoPos? = null
        for (link in linkData) {
            val (footLat, footLng, dist) = linkCrossPoint(link.startPos, link.endPos, input)
            if (dist < minDist) { minDist = dist; nearest = GeoPos(footLat, footLng) }
        }
        return nearest
    }

    private fun linkCrossPoint(start: GeoPos, end: GeoPos, current: GeoPos): Triple<Double, Double, Double> {
        val factor = linkProjectionFactor(start, end, current)
        val distStart = geoDistanceM(start.lat, start.lng, current.lat, current.lng)
        val distEnd   = geoDistanceM(end.lat,   end.lng,   current.lat, current.lng)
        return if (factor > 0.0 && factor < 1.0) {
            when {
                distStart == 0.0 -> Triple(current.lat, current.lng, 0.0)
                distEnd   == 0.0 -> Triple(current.lat, current.lng, 0.0)
                else -> {
                    val footLat = start.lat + factor * (end.lat - start.lat)
                    val footLng = start.lng + factor * (end.lng - start.lng)
                    Triple(footLat, footLng, geoDistanceM(footLat, footLng, current.lat, current.lng))
                }
            }
        } else {
            if (distStart <= distEnd) Triple(start.lat, start.lng, distStart)
            else                      Triple(end.lat,   end.lng,   distEnd)
        }
    }

    private fun linkProjectionFactor(start: GeoPos, end: GeoPos, current: GeoPos): Double {
        if (start.lng == current.lng && start.lat == current.lat) return 0.0
        if (end.lng   == current.lng && end.lat   == current.lat) return 1.0
        val dx = end.lng - start.lng; val dy = end.lat - start.lat
        val lenSq = dx * dx + dy * dy
        if (lenSq <= 0.0) return 0.0
        return ((current.lng - start.lng) * dx + (current.lat - start.lat) * dy) / lenSq
    }

    private fun geoDistanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLng = (lng2 - lng1) * PI / 180.0
        val a = sin(dLat / 2).pow(2) +
                cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sin(dLng / 2).pow(2)
        return R * 2.0 * asin(sqrt(a))
    }
}
