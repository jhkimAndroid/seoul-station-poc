package com.hubilon.positioning.internal.api

import android.util.Log
import com.hubilon.positioning.internal.log.AppLogger
import com.hubilon.positioning.model.LocationResult
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "SSP_API"

internal data class ApEntry(
    val apId: String,
    val featureIdx: Int,
    val identifier: String,
    val type: String
)

internal class LocationApiClient(
    private val baseUrl: String,
    private val buildingId: Long,
    private val appLogger: AppLogger? = null
) {
    private val anchorFeaturesUrl  get() = "$baseUrl/api/v1/anchor/features"
    private val trackerFeaturesUrl get() = "$baseUrl/api/v1/tracker/features"
    private val anchorPredictUrl   get() = "$baseUrl/api/v1/anchor/predict"
    private val trackerPredictUrl  get() = "$baseUrl/api/v1/tracker/predict"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun fetchAnchorFeatures(): List<ApEntry>  = fetchFeatures(anchorFeaturesUrl)
    suspend fun fetchTrackerFeatures(): List<ApEntry> = fetchFeatures(trackerFeaturesUrl)

    suspend fun anchorPredict(values: FloatArray): LocationResult {
        Log.d(TAG, "[anchor] features=${values.size}개")
        appLogger?.d(TAG, "[anchor] features=${values.size}개")
        return predict(anchorPredictUrl, values)
    }

    suspend fun trackerPredict(values: FloatArray, pdrLat: Double = -999.0, pdrLon: Double = -999.0): LocationResult {
        Log.d(TAG, "[tracker] features=${values.size}개 pdr=($pdrLat,$pdrLon)")
        return predict(trackerPredictUrl, values, pdrLat, pdrLon)
    }

    private suspend fun predict(
        endpoint: String,
        values: FloatArray,
        pdrLat: Double = -999.0,
        pdrLon: Double = -999.0
    ): LocationResult = suspendCancellableCoroutine { cont ->
        val startMs = System.currentTimeMillis()
        val jsonBody = JSONObject().apply {
            put("values", JSONArray().also { arr -> values.forEach { arr.put(it.toDouble()) } })
            put("pdr_lat", pdrLat)
            put("pdr_lon", pdrLon)
        }.toString()

        val request = Request.Builder()
            .url(endpoint)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val elapsed = System.currentTimeMillis() - startMs
                Log.e(TAG, "[$endpoint] 네트워크 오류 (${elapsed}ms): ${e.message}")
                appLogger?.e(TAG, "[$endpoint] 네트워크 오류 (${elapsed}ms): ${e.message}")
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                val elapsed = System.currentTimeMillis() - startMs
                try {
                    if (!response.isSuccessful) {
                        appLogger?.e(TAG, "[$endpoint] HTTP ${response.code} (${elapsed}ms)")
                        cont.resumeWithException(IOException("서버 오류: HTTP ${response.code}"))
                        return
                    }
                    val body = response.body?.string() ?: throw IOException("응답 본문 없음")
                    val json = JSONObject(body)
                    val result = LocationResult(json.getDouble("lat"), json.getDouble("lon"))
                    Log.i(TAG, "[$endpoint] lat=${result.lat}, lng=${result.lng} (${elapsed}ms)")
                    appLogger?.i(TAG, "[$endpoint] HTTP ${response.code} (${elapsed}ms) — lat=${result.lat}, lng=${result.lng}")
                    cont.resume(result)
                } catch (e: Exception) {
                    Log.e(TAG, "[$endpoint] 파싱 오류 (${elapsed}ms): ${e.message}")
                    appLogger?.e(TAG, "[$endpoint] 파싱 오류 (${elapsed}ms): ${e.message}")
                    if (!cont.isCancelled) cont.resumeWithException(e)
                }
            }
        })
    }

    private suspend fun fetchFeatures(endpoint: String): List<ApEntry> =
        suspendCancellableCoroutine { cont ->
            val request = Request.Builder().url(endpoint).get().build()
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "[fetchFeatures] 네트워크 오류: ${e.message}")
                    if (!cont.isCancelled) cont.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        if (!response.isSuccessful) {
                            cont.resumeWithException(IOException("서버 오류: HTTP ${response.code}"))
                            return
                        }
                        val body = response.body?.string() ?: throw IOException("응답 본문 없음")
                        val featuresArray = JSONObject(body).getJSONArray("features")
                        val entries = ArrayList<ApEntry>(featuresArray.length())
                        for (i in 0 until featuresArray.length()) {
                            val obj = featuresArray.getJSONObject(i)
                            entries.add(ApEntry(
                                apId       = obj.getString("name"),
                                featureIdx = obj.getInt("feature_idx"),
                                identifier = obj.getString("identifier"),
                                type       = obj.getString("type")
                            ))
                        }
                        Log.i(TAG, "[fetchFeatures] $endpoint — ${entries.size}개")
                        cont.resume(entries)
                    } catch (e: Exception) {
                        Log.e(TAG, "[fetchFeatures] 파싱 오류: ${e.message}")
                        if (!cont.isCancelled) cont.resumeWithException(e)
                    }
                }
            })
        }
}
