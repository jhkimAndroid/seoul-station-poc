package com.hubilon.seoulstationpoc.ui.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.hubilon.seoulstationpoc.model.GeoPos
import com.hubilon.seoulstationpoc.util.AppLog
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.LatLng
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

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
    private var floorBitmap: android.graphics.Bitmap? = null
    private var fusedLocationLatLng: LatLng? = null          // GPS (초록)
    private var serverLocationLatLng: LatLng? = null         // 서버측위 원본 (파란)
    private var kalmanFilteredLocationLatLng: LatLng? = null // 칼만필터 (보라)
    private var pdrLocationLatLng: LatLng? = null            // PDR 위치 (노란)
    private var finalLocationLatLng: LatLng? = null          // 최종 위치 (빨간)
    private var locationHistory: List<GeoPos> = emptyList()  // 이동경로 좌표 이력

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = 180
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerRadius = 15f * context.resources.displayMetrics.density
    private val dp = context.resources.displayMetrics.density
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(200, 255, 140, 0)  // 주황
        strokeWidth = 3f * dp
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(220, 255, 140, 0)  // 주황
        style = Paint.Style.FILL
    }
    private val arrowPath = Path()
    private val arrowSize = 12f * dp

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

    fun updateBitmap(bitmap: android.graphics.Bitmap?) {
        floorBitmap = bitmap
        invalidate()
    }

    fun updateServerLocation(latLng: LatLng?) {
        if (serverLocationLatLng == latLng) return
        serverLocationLatLng = latLng
        invalidate()
    }

    fun updateFinalLocation(latLng: LatLng?) {
        if (finalLocationLatLng == latLng) return
        finalLocationLatLng = latLng
        invalidate()
    }

    fun updateKalmanFilteredLocation(latLng: LatLng?) {
        if (kalmanFilteredLocationLatLng == latLng) return
        kalmanFilteredLocationLatLng = latLng
        invalidate()
    }

    fun updatePdrLocation(latLng: LatLng?) {
        if (pdrLocationLatLng == latLng) return
        pdrLocationLatLng = latLng
        invalidate()
    }

    fun updateFusedLocation(latLng: LatLng?) {
        if (fusedLocationLatLng == latLng) return
        fusedLocationLatLng = latLng
        invalidate()
    }

    fun updateLocationHistory(history: List<GeoPos>) {
        locationHistory = history
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

        // 이동경로 화살표 — 마커 아래 레이어
        if (locationHistory.size >= 2) {
            try {
                val pts = locationHistory.mapNotNull { geo ->
                    map.toScreenPoint(LatLng.from(geo.lat, geo.lng))
                }
                if (pts.size >= 2) {
                    for (i in 0 until pts.size - 1) {
                        val x1 = pts[i].x.toFloat()
                        val y1 = pts[i].y.toFloat()
                        val x2 = pts[i + 1].x.toFloat()
                        val y2 = pts[i + 1].y.toFloat()
                        canvas.drawLine(x1, y1, x2, y2, pathPaint)
                        drawArrowHead(canvas, x1, y1, x2, y2)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "FloorOverlay 경로 화살표 오류: ${e.message}", e)
            }
        }

        // GPS 마커 (초록) — 1레이어
        fusedLocationLatLng?.let {
            try {
                map.toScreenPoint(it)?.let { pt -> drawGpsMarker(canvas, pt.x.toFloat(), pt.y.toFloat()) }
            } catch (e: Exception) { Log.e(TAG, "FloorOverlay GPS 마커 오류: ${e.message}", e) }
        }

        // 서버 마커 (파란) — 2레이어
        serverLocationLatLng?.let {
            try {
                map.toScreenPoint(it)?.let { pt -> drawServerMarker(canvas, pt.x.toFloat(), pt.y.toFloat()) }
            } catch (e: Exception) { Log.e(TAG, "FloorOverlay 서버 마커 오류: ${e.message}", e) }
        }

        // 칼만필터 마커 (보라) — 3레이어
        kalmanFilteredLocationLatLng?.let {
            try {
                map.toScreenPoint(it)?.let { pt -> drawKalmanMarker(canvas, pt.x.toFloat(), pt.y.toFloat()) }
            } catch (e: Exception) { Log.e(TAG, "FloorOverlay 칼만 마커 오류: ${e.message}", e) }
        }

        // PDR 마커 (노란) — 5레이어
        pdrLocationLatLng?.let {
            try {
                map.toScreenPoint(it)?.let { pt -> drawPdrMarker(canvas, pt.x.toFloat(), pt.y.toFloat()) }
            } catch (e: Exception) { Log.e(TAG, "FloorOverlay PDR 마커 오류: ${e.message}", e) }
        }

        // 최종 마커 (빨간) — 최상위 레이어
        finalLocationLatLng?.let {
            try {
                map.toScreenPoint(it)?.let { pt -> drawFinalMarker(canvas, pt.x.toFloat(), pt.y.toFloat()) }
            } catch (e: Exception) { Log.e(TAG, "FloorOverlay 최종 마커 오류: ${e.message}", e) }
        }
    }

    private fun drawGpsMarker(canvas: Canvas, cx: Float, cy: Float) {
        val r = markerRadius
        markerPaint.color = android.graphics.Color.argb(180, 56, 142, 60)   // 초록
        canvas.drawCircle(cx, cy, r, markerPaint)
        markerPaint.color = android.graphics.Color.argb(180, 255, 255, 255)
        canvas.drawCircle(cx, cy, r * 0.5f, markerPaint)
        markerPaint.color = android.graphics.Color.argb(180, 56, 142, 60)
        canvas.drawCircle(cx, cy, r * 0.25f, markerPaint)
    }

    private fun drawServerMarker(canvas: Canvas, cx: Float, cy: Float) {
        val r = markerRadius
        markerPaint.color = android.graphics.Color.argb(180, 33, 150, 243)  // 파란
        canvas.drawCircle(cx, cy, r, markerPaint)
        markerPaint.color = android.graphics.Color.argb(180, 255, 255, 255)
        canvas.drawCircle(cx, cy, r * 0.5f, markerPaint)
        markerPaint.color = android.graphics.Color.argb(180, 33, 150, 243)
        canvas.drawCircle(cx, cy, r * 0.25f, markerPaint)
    }

    private fun drawKalmanMarker(canvas: Canvas, cx: Float, cy: Float) {
        val r = markerRadius
        markerPaint.color = android.graphics.Color.argb(200, 103, 58, 183)  // 보라
        canvas.drawCircle(cx, cy, r, markerPaint)
        markerPaint.color = android.graphics.Color.argb(200, 255, 255, 255)
        canvas.drawCircle(cx, cy, r * 0.5f, markerPaint)
        markerPaint.color = android.graphics.Color.argb(200, 103, 58, 183)
        canvas.drawCircle(cx, cy, r * 0.25f, markerPaint)
    }

    private fun drawPdrMarker(canvas: Canvas, cx: Float, cy: Float) {
        val r = markerRadius
        markerPaint.color = android.graphics.Color.argb(200, 249, 168, 37)  // 노란
        canvas.drawCircle(cx, cy, r, markerPaint)
        markerPaint.color = android.graphics.Color.argb(200, 255, 255, 255)
        canvas.drawCircle(cx, cy, r * 0.5f, markerPaint)
        markerPaint.color = android.graphics.Color.argb(200, 249, 168, 37)
        canvas.drawCircle(cx, cy, r * 0.25f, markerPaint)
    }

    private fun drawFinalMarker(canvas: Canvas, cx: Float, cy: Float) {
        val r = markerRadius
        markerPaint.color = android.graphics.Color.argb(220, 233, 50, 43)   // 빨간
        canvas.drawCircle(cx, cy, r, markerPaint)
        markerPaint.color = android.graphics.Color.argb(220, 255, 255, 255)
        canvas.drawCircle(cx, cy, r * 0.5f, markerPaint)
        markerPaint.color = android.graphics.Color.argb(220, 233, 50, 43)
        canvas.drawCircle(cx, cy, r * 0.25f, markerPaint)
    }

    private fun drawArrowHead(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val ax = arrowSize * cos(angle - Math.PI / 6).toFloat()
        val ay = arrowSize * sin(angle - Math.PI / 6).toFloat()
        val bx = arrowSize * cos(angle + Math.PI / 6).toFloat()
        val by = arrowSize * sin(angle + Math.PI / 6).toFloat()
        arrowPath.reset()
        arrowPath.moveTo(x2, y2)
        arrowPath.lineTo(x2 - ax, y2 - ay)
        arrowPath.lineTo(x2 - bx, y2 - by)
        arrowPath.close()
        canvas.drawPath(arrowPath, arrowPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cameraMoving = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onTouchEvent(event: MotionEvent?) = false
}
