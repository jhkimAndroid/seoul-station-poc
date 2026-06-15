package com.hubilon.seoulstationpoc.data.api

import android.util.Log
import com.hubilon.seoulstationpoc.domain.model.LocationResult
import com.hubilon.seoulstationpoc.domain.model.SensorSignal
import com.hubilon.seoulstationpoc.util.AppLog
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
private const val ENDPOINT = "$BASE_URL/api/v1/predict"
private const val APS_ENDPOINT = "$BASE_URL/api/v1/model/features"

data class ApEntry(
    val apId: String,
    val featureIdx: Int,
    val identifier: String,
    val type: String
)

class LocationApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * BLE+WiFi RSSI 배열과 센서 데이터를 서버에 전송하고 예측 좌표를 반환한다.
     * 전송 순서: BLE RSSI(20) → WiFi RSSI(827) → 센서 world frame(acc 3 + gyro 3 + mag 3 = 9)
     * 총 856개 — fetchAps feature_idx 순서와 동일해야 한다.
     * 코루틴 취소 시 OkHttp call 도 즉시 중단된다.
     */
    suspend fun predict(bleWifiValues: IntArray, sensor: SensorSignal?): LocationResult =
        suspendCancellableCoroutine { cont ->
        val startMs = System.currentTimeMillis()
        val sensorValues = sensor?.toFloatArray() ?: FloatArray(9)
        val totalSize = bleWifiValues.size + sensorValues.size
        val preview = bleWifiValues.slice(0 until minOf(5, bleWifiValues.size))
        Log.d(TAG, "[REQ] POST $ENDPOINT | BLE+WiFi=${bleWifiValues.size}개 sensor=${sensorValues.size}개 total=$totalSize | 앞5=$preview")
        Log.d(TAG, "  sensor=[accel=${sensorValues.slice(0..2)}, gyro=${sensorValues.slice(3..5)}, mag=${sensorValues.slice(6..8)}]")

        val jsonBody = JSONObject().apply {
            put("values", JSONArray().also { arr ->
                bleWifiValues.forEach { arr.put(it) }
                sensorValues.forEach { arr.put(it.toDouble()) }
            })
        }.toString()

        val request = Request.Builder()
            .url(ENDPOINT)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)
        cont.invokeOnCancellation {
            call.cancel()
            Log.d(TAG, "[CAN] predict 취소 — 새 요청으로 교체됨")
        }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val elapsed = System.currentTimeMillis() - startMs
                Log.e(TAG, "[ERR] 네트워크 오류 (${elapsed}ms) | ${e.javaClass.simpleName}: ${e.message}")
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val elapsed = System.currentTimeMillis() - startMs
                try {
                    Log.d(TAG, "[RES] HTTP ${response.code} (${elapsed}ms)")
                    if (!response.isSuccessful) {
                        val msg = "서버 오류: HTTP ${response.code}"
                        Log.e(TAG, "[ERR] $msg")
                        cont.resumeWithException(IOException(msg))
                        return
                    }
                    val body = response.body?.string()
                        ?: throw IOException("응답 본문 없음")
                    val json = JSONObject(body)
                    val result = LocationResult(
                        lat = json.getDouble("lat"),
                        lng = json.getDouble("lon")
                    )
                    Log.i(TAG, "[OK] lat=${result.lat}, lng=${result.lng} (${elapsed}ms)")
                    cont.resume(result)
                } catch (e: Exception) {
                    Log.e(TAG, "[ERR] 응답 파싱 오류 (${elapsed}ms) | ${e.message}")
                    if (!cont.isCancelled) cont.resumeWithException(e)
                }
            }
        })
    }

    suspend fun fetchAps(): List<ApEntry> = suspendCancellableCoroutine { cont ->
        Log.d(TAG, "[REQ] GET $APS_ENDPOINT")
        val request = Request.Builder().url(APS_ENDPOINT).get().build()
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "[ERR] fetchAps 네트워크 오류 | ${e.message}")
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!response.isSuccessful) {
                        cont.resumeWithException(IOException("서버 오류: HTTP ${response.code}"))
                        return
                    }
                    val body = response.body?.string() ?: throw IOException("응답 본문 없음")
                    val apsArray = JSONObject(body).getJSONArray("features")
                    val entries = ArrayList<ApEntry>(apsArray.length())
                    for (i in 0 until apsArray.length()) {
                        val obj = apsArray.getJSONObject(i)
                        entries.add(
                            ApEntry(
                                apId = obj.getString("name"),
                                featureIdx = obj.getInt("feature_idx"),
                                identifier = obj.getString("identifier"),
                                type = obj.getString("type")
                            )
                        )
                    }
                    val bleCount    = entries.count { it.type == "ble" }
                    val wifiCount   = entries.count { it.type == "wifi" }
                    val sensorCount = entries.count { it.type !in listOf("ble", "wifi") }
                    Log.i(TAG, "[OK] fetchAps: 총 ${entries.size}개 — BLE=$bleCount, WiFi=$wifiCount, Sensor=$sensorCount")
                    cont.resume(entries)
                } catch (e: Exception) {
                    Log.e(TAG, "[ERR] fetchAps 파싱 오류 | ${e.message}")
                    if (!cont.isCancelled) cont.resumeWithException(e)
                }
            }
        })
    }
}
