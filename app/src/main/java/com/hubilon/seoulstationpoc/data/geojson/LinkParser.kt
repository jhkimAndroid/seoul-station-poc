package com.hubilon.seoulstationpoc.data.geojson

import android.content.Context
import android.util.Log
import com.hubilon.seoulstationpoc.model.GeoPos
import com.hubilon.seoulstationpoc.model.LinkData
import com.hubilon.seoulstationpoc.util.AppLog
import org.json.JSONObject

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
                val props = feature.getJSONObject("properties")
                val coords = feature.getJSONObject("geometry").getJSONArray("coordinates")

                val start = coords.getJSONArray(0)
                val end   = coords.getJSONArray(1)

                result.add(
                    LinkData(
                        linkId   = props.getLong("LNK_ID"),
                        linkLen  = props.getDouble("LNK_LEN"),
                        startPos = GeoPos(lat = start.getDouble(1), lng = start.getDouble(0)),
                        endPos   = GeoPos(lat = end.getDouble(1),   lng = end.getDouble(0))
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "LinkParser: feature[$i] 파싱 오류 — ${e.message}")
            }
        }

        val totalLen = result.sumOf { it.linkLen }
        Log.i(TAG, "LinkParser: ${result.size}개 링크 파싱 완료 — 총 길이 %.1fm".format(totalLen))

        result.forEachIndexed { i, link ->
            Log.d(TAG, "LinkParser: [$i] id=${link.linkId} len=${link.linkLen}m" +
                " start=(${link.startPos.lat}, ${link.startPos.lng})" +
                " end=(${link.endPos.lat}, ${link.endPos.lng})")
        }

        return result
    }
}
