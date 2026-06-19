package com.hubilon.seoulstationpoc.data.geojson

import android.content.Context
import android.util.Log
import com.hubilon.seoulstationpoc.model.GeoPos
import com.hubilon.seoulstationpoc.model.LinkData
import com.hubilon.seoulstationpoc.util.AppLog
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = AppLog.APP

object LinkParser {

    fun parse(context: Context, assetFileName: String): List<LinkData> {
        return try {
            val json = context.assets.open(assetFileName).bufferedReader().use { it.readText() }
            parseJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "LinkParser: $assetFileName 파싱 실패 — ${e.message}", e)
            emptyList()
        }
    }

    private fun parseJson(json: String): List<LinkData> {
        val root = JSONObject(json)
        val features = root.getJSONArray("features")
        val result = mutableListOf<LinkData>()

        for (i in 0 until features.length()) {
            try {
                val feature = features.getJSONObject(i)
                val props   = feature.getJSONObject("properties")
                val coords  = feature.getJSONObject("geometry").getJSONArray("coordinates")

                val start = coords.getJSONArray(0)
                val end   = coords.getJSONArray(1)

                val startLat = start.getDouble(1); val startLng = start.getDouble(0)
                val endLat   = end.getDouble(1);   val endLng   = end.getDouble(0)

                val linkId  = if (props.has("LNK_ID"))  props.getLong("LNK_ID")    else i.toLong()
                val linkLen = if (props.has("LNK_LEN")) props.getDouble("LNK_LEN") else
                    haversineM(startLat, startLng, endLat, endLng)

                result.add(
                    LinkData(
                        linkId   = linkId,
                        linkLen  = linkLen,
                        startPos = GeoPos(lat = startLat, lng = startLng),
                        endPos   = GeoPos(lat = endLat,   lng = endLng)
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "LinkParser: feature[$i] 파싱 오류 — ${e.message}")
            }
        }

        val totalLen = result.sumOf { it.linkLen }
        Log.i(TAG, "LinkParser: ${result.size}개 링크 파싱 완료 — 총 길이 %.1fm".format(totalLen))

        result.forEachIndexed { i, link ->
            Log.d(TAG, "LinkParser: [$i] id=${link.linkId} len=%.1fm".format(link.linkLen) +
                " start=(${link.startPos.lat}, ${link.startPos.lng})" +
                " end=(${link.endPos.lat}, ${link.endPos.lng})")
        }

        return result
    }

    private fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLng = (lng2 - lng1) * PI / 180.0
        val a = sin(dLat / 2).pow(2) +
                cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sin(dLng / 2).pow(2)
        return r * 2.0 * asin(sqrt(a))
    }
}
