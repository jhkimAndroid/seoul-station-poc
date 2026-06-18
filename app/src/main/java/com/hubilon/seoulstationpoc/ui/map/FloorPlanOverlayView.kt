package com.hubilon.seoulstationpoc.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.hubilon.seoulstationpoc.util.AppLog
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.LatLng

private const val TAG = AppLog.MAP

// coord.txt 기반 서울역 실내 지도 영역 좌표
// 순서: LeftTop, RightTop, RightBottom, LeftBottom
private val FLOOR_CORNERS = listOf(
    LatLng.from(37.55571757, 126.96928985),
    LatLng.from(37.55571757, 126.97222419),
    LatLng.from(37.5533828,  126.97222419),
    LatLng.from(37.5533828,  126.96928985)
)

class FloorPlanOverlayView(context: Context) : View(context) {
    private var kakaoMap: KakaoMap? = null
    private var floorBitmap: Bitmap? = null
    private var locationLatLng: LatLng? = null          // tracker 원본 (빨간)
    private var finalLocationLatLng: LatLng? = null     // smooth+칼만 최종 (검은)
    private var pdrServerLocationLatLng: LatLng? = null // 서버+PDR (주황)
    private var fusedLocationLatLng: LatLng? = null     // GPS (녹색)
    private var rttLocationLatLng: LatLng? = null       // RTT 측위 (보라)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = 200
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerRadius = 15f * context.resources.displayMetrics.density

    private var cameraMoving = false
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            invalidate()
            if (cameraMoving) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val cameraMoveStartListener = KakaoMap.OnCameraMoveStartListener { _, _ ->
        cameraMoving = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private val cameraMoveEndListener = KakaoMap.OnCameraMoveEndListener { _, _, _ ->
        cameraMoving = false
        invalidate()
    }

    fun updateMap(map: KakaoMap?) {
        if (map === kakaoMap) return
        kakaoMap?.setOnCameraMoveStartListener(null)
        kakaoMap?.setOnCameraMoveEndListener(null)
        kakaoMap = map
        Log.d(TAG, "FloorOverlay updateMap: ${if (map != null) "설정됨" else "null"}")
        map?.setOnCameraMoveStartListener(cameraMoveStartListener)
        map?.setOnCameraMoveEndListener(cameraMoveEndListener)
        invalidate()
    }

    fun updateBitmap(bitmap: Bitmap?) {
        floorBitmap = bitmap
        Log.d(TAG, "FloorOverlay updateBitmap: ${if (bitmap != null) "${bitmap.width}x${bitmap.height}" else "null (숨김)"}")
        invalidate()
    }

    fun updateLocation(latLng: LatLng?) {
        if (locationLatLng == latLng) return
        locationLatLng = latLng
        invalidate()
    }

    fun updateFinalLocation(latLng: LatLng?) {
        if (finalLocationLatLng == latLng) return
        finalLocationLatLng = latLng
        invalidate()
    }

    fun updatePdrServerLocation(latLng: LatLng?) {
        if (pdrServerLocationLatLng == latLng) return
        pdrServerLocationLatLng = latLng
        invalidate()
    }

    fun updateFusedLocation(latLng: LatLng?) {
        if (fusedLocationLatLng == latLng) return
        fusedLocationLatLng = latLng
        invalidate()
    }

    fun updateRttLocation(latLng: LatLng?) {
        if (rttLocationLatLng == latLng) return
        rttLocationLatLng = latLng
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val map = kakaoMap ?: return

        // 층 평면도
        val bmp = floorBitmap
        if (bmp != null) {
            try {
                val lt = map.toScreenPoint(FLOOR_CORNERS[0])
                val rt = map.toScreenPoint(FLOOR_CORNERS[1])
                val rb = map.toScreenPoint(FLOOR_CORNERS[2])
                val lb = map.toScreenPoint(FLOOR_CORNERS[3])

                if (lt == null || rt == null || rb == null || lb == null) {
                    postInvalidateOnAnimation()
                } else {
                    val left   = minOf(lt.x, lb.x).toFloat()
                    val top    = minOf(lt.y, rt.y).toFloat()
                    val right  = maxOf(rt.x, rb.x).toFloat()
                    val bottom = maxOf(lb.y, rb.y).toFloat()
                    if (right > left && bottom > top) {
                        canvas.drawBitmap(bmp, null, RectF(left, top, right, bottom), paint)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "FloorOverlay 평면도 오류: ${e.message}", e)
            }
        }

        // GPS 마커 (녹색) — 1레이어
        fusedLocationLatLng?.let {
            try {
                map.toScreenPoint(it)?.let { pt -> drawFusedLocationMarker(canvas, pt.x.toFloat(), pt.y.toFloat()) }
            } catch (e: Exception) { Log.e(TAG, "FloorOverlay GPS 마커 오류: ${e.message}", e) }
        }

        // RTT 마커 (보라) — 2레이어
        rttLocationLatLng?.let {
            try {
                map.toScreenPoint(it)?.let { pt -> drawRttLocationMarker(canvas, pt.x.toFloat(), pt.y.toFloat()) }
            } catch (e: Exception) { Log.e(TAG, "FloorOverlay RTT 마커 오류: ${e.message}", e) }
        }

        // 서버+PDR 마커 (주황) — 3레이어
        pdrServerLocationLatLng?.let {
            try {
                map.toScreenPoint(it)?.let { pt -> drawPdrServerLocationMarker(canvas, pt.x.toFloat(), pt.y.toFloat()) }
            } catch (e: Exception) { Log.e(TAG, "FloorOverlay 서버+PDR 마커 오류: ${e.message}", e) }
        }

        // 서버 마커 (빨간) — tracker 원본
        locationLatLng?.let {
            try {
                map.toScreenPoint(it)?.let { pt -> drawLocationMarker(canvas, pt.x.toFloat(), pt.y.toFloat()) }
            } catch (e: Exception) { Log.e(TAG, "FloorOverlay 서버 마커 오류: ${e.message}", e) }
        }

        // 최종 마커 (검은) — smooth+칼만 적용, 최상위 레이어
        finalLocationLatLng?.let {
            try {
                map.toScreenPoint(it)?.let { pt -> drawFinalLocationMarker(canvas, pt.x.toFloat(), pt.y.toFloat()) }
            } catch (e: Exception) { Log.e(TAG, "FloorOverlay 최종 마커 오류: ${e.message}", e) }
        }
    }

    private fun drawLocationMarker(canvas: Canvas, cx: Float, cy: Float) {
        val r = markerRadius
        markerPaint.color = android.graphics.Color.argb(180, 233, 50, 43)
        canvas.drawCircle(cx, cy, r, markerPaint)
        markerPaint.color = android.graphics.Color.argb(180, 255, 255, 255)
        canvas.drawCircle(cx, cy, r * 0.5f, markerPaint)
        markerPaint.color = android.graphics.Color.argb(180, 233, 50, 43)
        canvas.drawCircle(cx, cy, r * 0.25f, markerPaint)
    }

    private fun drawPdrServerLocationMarker(canvas: Canvas, cx: Float, cy: Float) {
        val r = markerRadius
        markerPaint.color = android.graphics.Color.argb(180, 245, 124, 0)
        canvas.drawCircle(cx, cy, r, markerPaint)
        markerPaint.color = android.graphics.Color.argb(180, 255, 255, 255)
        canvas.drawCircle(cx, cy, r * 0.5f, markerPaint)
        markerPaint.color = android.graphics.Color.argb(180, 245, 124, 0)
        canvas.drawCircle(cx, cy, r * 0.25f, markerPaint)
    }

    private fun drawRttLocationMarker(canvas: Canvas, cx: Float, cy: Float) {
        val r = markerRadius
        markerPaint.color = android.graphics.Color.argb(180, 156, 39, 176)  // 보라
        canvas.drawCircle(cx, cy, r, markerPaint)
        markerPaint.color = android.graphics.Color.argb(180, 255, 255, 255)
        canvas.drawCircle(cx, cy, r * 0.5f, markerPaint)
        markerPaint.color = android.graphics.Color.argb(180, 156, 39, 176)
        canvas.drawCircle(cx, cy, r * 0.25f, markerPaint)
    }

    private fun drawFinalLocationMarker(canvas: Canvas, cx: Float, cy: Float) {
        val r = markerRadius
        markerPaint.color = android.graphics.Color.argb(200, 30, 30, 30)   // 검은 반투명
        canvas.drawCircle(cx, cy, r, markerPaint)
        markerPaint.color = android.graphics.Color.argb(200, 255, 255, 255)
        canvas.drawCircle(cx, cy, r * 0.5f, markerPaint)
        markerPaint.color = android.graphics.Color.argb(200, 30, 30, 30)
        canvas.drawCircle(cx, cy, r * 0.25f, markerPaint)
    }

    private fun drawFusedLocationMarker(canvas: Canvas, cx: Float, cy: Float) {
        val r = markerRadius
        markerPaint.color = android.graphics.Color.argb(180, 56, 142, 60)
        canvas.drawCircle(cx, cy, r, markerPaint)
        markerPaint.color = android.graphics.Color.argb(180, 255, 255, 255)
        canvas.drawCircle(cx, cy, r * 0.5f, markerPaint)
        markerPaint.color = android.graphics.Color.argb(180, 56, 142, 60)
        canvas.drawCircle(cx, cy, r * 0.25f, markerPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cameraMoving = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onTouchEvent(event: MotionEvent?) = false
}
