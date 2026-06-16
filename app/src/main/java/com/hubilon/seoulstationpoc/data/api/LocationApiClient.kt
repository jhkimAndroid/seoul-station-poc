package com.hubilon.seoulstationpoc.data.api

import android.util.Log
import com.hubilon.seoulstationpoc.model.LocationResult
import com.hubilon.seoulstationpoc.model.RttSignal
import com.hubilon.seoulstationpoc.util.AppLog
import com.hubilon.seoulstationpoc.util.AppLogger
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

private const val TAG = AppLog.API
const val BASE_URL = "http://121.134.167.198:5050"

private const val ANCHOR_FEATURES_URL  = "$BASE_URL/api/v1/anchor/features"
private const val TRACKER_FEATURES_URL = "$BASE_URL/api/v1/tracker/features"
private const val ANCHOR_PREDICT_URL   = "$BASE_URL/api/v1/anchor/predict"
private const val TRACKER_PREDICT_URL  = "$BASE_URL/api/v1/tracker/predict"
private const val RTT_PREDICT_URL      = "http://121.134.167.198:5057/api/ftm/location"

private const val BUILDING_ID = 1105001001L

data class ApEntry(
    val apId: String,
    val featureIdx: Int,
    val identifier: String,
    val type: String
)

class LocationApiClient(private val appLogger: AppLogger? = null) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun fetchAnchorFeatures(): List<ApEntry>  = fetchFeatures(ANCHOR_FEATURES_URL)
    suspend fun fetchTrackerFeatures(): List<ApEntry> = fetchFeatures(TRACKER_FEATURES_URL)

    suspend fun anchorPredict(values: FloatArray): LocationResult {
        Log.d(TAG, "[anchor] POST $ANCHOR_PREDICT_URL | features=${values.size}개")
        appLogger?.d(TAG, "[anchor] POST $ANCHOR_PREDICT_URL | features=${values.size}개")
        return predict(ANCHOR_PREDICT_URL, values)
    }

    suspend fun trackerPredict(values: FloatArray): LocationResult {
        Log.d(TAG, "[tracker] POST $TRACKER_PREDICT_URL | features=${values.size}개")
        appLogger?.d(TAG, "[tracker] POST $TRACKER_PREDICT_URL | features=${values.size}개")
        return predict(TRACKER_PREDICT_URL, values)
    }

    suspend fun rttPredict(signals: List<RttSignal>): LocationResult {
        if (RTT_PREDICT_URL.isEmpty()) throw IOException("RTT API URL 미설정")
        return suspendCancellableCoroutine { cont ->
            val startMs = System.currentTimeMillis()
            val ftmList = JSONArray().apply {
                signals.forEach { s ->
                    put(JSONObject().apply {
                        put("apmacaddr", s.bssid.uppercase())
                        put("distance", s.distanceMm / 1000.0)
                    })
                }
            }
            val jsonBody = JSONObject().apply {
                put("building_id", BUILDING_ID)
                put("timestamp", startMs)
                put("ftm_scanlist", ftmList)
            }.toString()
            Log.d(TAG, "[rtt] POST $RTT_PREDICT_URL | AP=${signals.size}개 | body=$jsonBody")
            appLogger?.d(TAG, "[rtt] POST $RTT_PREDICT_URL | AP=${signals.size}개 | body=$jsonBody")

            val request = Request.Builder()
                .url(RTT_PREDICT_URL)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val elapsed = System.currentTimeMillis() - startMs
                    Log.e(TAG, "[rtt] 네트워크 오류 (${elapsed}ms): ${e.message}")
                    appLogger?.e(TAG, "[rtt] 네트워크 오류 (${elapsed}ms): ${e.message}")
                    if (!cont.isCancelled) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val elapsed = System.currentTimeMillis() - startMs
                    try {
                        Log.d(TAG, "[rtt] HTTP ${response.code} (${elapsed}ms)")
                        if (!response.isSuccessful) {
                            val errBody = try { response.body?.string() } catch (e: Exception) { null }
                            Log.e(TAG, "[rtt] 서버 오류 HTTP ${response.code} — body=$errBody")
                            appLogger?.e(TAG, "[rtt] 서버 오류 HTTP ${response.code} — body=$errBody")
                            cont.resumeWithException(IOException("서버 오류: HTTP ${response.code}"))
                            return
                        }
                        val body = response.body?.string() ?: throw IOException("응답 본문 없음")
                        Log.i(TAG, "[rtt] body=$body")
                        val json = JSONObject(body)
                        if (json.optString("status") == "fail") {
                            appLogger?.w(TAG, "[rtt] 측위 실패 (status=fail) — body=$body")
                            throw IOException("RTT 측위 실패 (status=fail)")
                        }
                        val result = LocationResult(
                            lat = json.getDouble("latitude"),
                            lng = json.getDouble("longitude")
                        )
                        Log.i(TAG, "[rtt] lat=${result.lat}, lng=${result.lng} (${elapsed}ms)")
                        appLogger?.i(TAG, "[rtt] HTTP ${response.code} (${elapsed}ms) — lat=${result.lat}, lng=${result.lng}")
                        cont.resume(result)
                    } catch (e: Exception) {
                        Log.e(TAG, "[rtt] 파싱 오류 (${elapsed}ms): ${e.message}")
                        appLogger?.e(TAG, "[rtt] 파싱 오류 (${elapsed}ms): ${e.message}")
                        if (!cont.isCancelled) cont.resumeWithException(e)
                    }
                }
            })
        }
    }

    private suspend fun predict(endpoint: String, values: FloatArray): LocationResult =
        suspendCancellableCoroutine { cont ->
            val startMs = System.currentTimeMillis()
            val jsonBody = JSONObject().apply {
                put("values", JSONArray().also { arr -> values.forEach { arr.put(it.toDouble()) } })
            }.toString()

            val request = Request.Builder()
                .url(endpoint)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val call = client.newCall(request)
            cont.invokeOnCancellation {
                call.cancel()
                Log.d(TAG, "[$endpoint] 취소")
            }

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
                        Log.d(TAG, "[$endpoint] HTTP ${response.code} (${elapsed}ms)")
                        if (!response.isSuccessful) {
                            appLogger?.e(TAG, "[$endpoint] 서버 오류 HTTP ${response.code} (${elapsed}ms)")
                            cont.resumeWithException(IOException("서버 오류: HTTP ${response.code}"))
                            return
                        }
                        val body = response.body?.string() ?: throw IOException("응답 본문 없음")
                        val json = JSONObject(body)
                        val result = LocationResult(
                            lat = json.getDouble("lat"),
                            lng = json.getDouble("lon")
                        )
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
            Log.d(TAG, "[fetchFeatures] GET $endpoint")
            val request = Request.Builder().url(endpoint).get().build()
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "[fetchFeatures] 네트워크 오류 | ${e.message}")
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
                            entries.add(
                                ApEntry(
                                    apId       = obj.getString("name"),
                                    featureIdx = obj.getInt("feature_idx"),
                                    identifier = obj.getString("identifier"),
                                    type       = obj.getString("type")
                                )
                            )
                        }
                        val bleCount    = entries.count { it.type == "ble" }
                        val wifiCount   = entries.count { it.type == "wifi" }
                        val lteCount    = entries.count { it.type.startsWith("lte_") }
                        val sensorCount = entries.count { it.type != "ble" && it.type != "wifi" && !it.type.startsWith("lte_") }
                        Log.i(TAG, "[fetchFeatures] $endpoint — 총 ${entries.size}개: BLE=$bleCount WiFi=$wifiCount LTE=$lteCount Sensor=$sensorCount")
                        cont.resume(entries)
                    } catch (e: Exception) {
                        Log.e(TAG, "[fetchFeatures] 파싱 오류 | ${e.message}")
                        if (!cont.isCancelled) cont.resumeWithException(e)
                    }
                }
            })
        }
}
