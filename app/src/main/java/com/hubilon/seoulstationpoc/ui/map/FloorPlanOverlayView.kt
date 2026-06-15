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
    private var locationLatLng: LatLng? = null       // 서버측위 (빨간 마커)
    private var fusedLocationLatLng: LatLng? = null  // 구글 퓨즈드 (녹색 마커)
    private var pdrLocationLatLng: LatLng? = null    // PDR 융합 (파란 마커)

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

    fun updateFusedLocation(latLng: LatLng?) {
        if (fusedLocationLatLng == latLng) return
        fusedLocationLatLng = latLng
        invalidate()
    }

    fun updatePdrLocation(latLng: LatLng?) {
        if (pdrLocationLatLng == latLng) return
        pdrLocationLatLng = latLng
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

        // 퓨즈드 위치 마커 (녹색) — 가장 아래 레이어
        val fusedLoc = fusedLocationLatLng
        if (fusedLoc != null) {
            try {
                val pt = map.toScreenPoint(fusedLoc)
                if (pt != null) drawFusedLocationMarker(canvas, pt.x.toFloat(), pt.y.toFloat())
            } catch (e: Exception) {
                Log.e(TAG, "FloorOverlay 퓨즈드 마커 오류: ${e.message}", e)
            }
        }

        // PDR 위치 마커 (파란색) — 중간 레이어
        val pdrLoc = pdrLocationLatLng
        if (pdrLoc != null) {
            try {
                val pt = map.toScreenPoint(pdrLoc)
                if (pt != null) drawPdrLocationMarker(canvas, pt.x.toFloat(), pt.y.toFloat())
            } catch (e: Exception) {
                Log.e(TAG, "FloorOverlay PDR 마커 오류: ${e.message}", e)
            }
        }

        // 서버 위치 마커 (빨간색) — 최상위 레이어
        val loc = locationLatLng
        if (loc != null) {
            try {
                val pt = map.toScreenPoint(loc)
                if (pt != null) drawLocationMarker(canvas, pt.x.toFloat(), pt.y.toFloat())
            } catch (e: Exception) {
                Log.e(TAG, "FloorOverlay 서버 마커 오류: ${e.message}", e)
            }
        }
    }

    private fun drawLocationMarker(canvas: Canvas, cx: Float, cy: Float) {
        val r = markerRadius
        markerPaint.color = android.graphics.Color.argb(255, 233, 50, 43)   // 빨간 외곽
        canvas.drawCircle(cx, cy, r, markerPaint)
        markerPaint.color = android.graphics.Color.WHITE
        canvas.drawCircle(cx, cy, r * 0.5f, markerPaint)
        markerPaint.color = android.graphics.Color.argb(255, 233, 50, 43)   // 빨간 중심
        canvas.drawCircle(cx, cy, r * 0.25f, markerPaint)
    }

    private fun drawFusedLocationMarker(canvas: Canvas, cx: Float, cy: Float) {
        val r = markerRadius
        markerPaint.color = android.graphics.Color.argb(255, 56, 142, 60)   // 녹색 외곽
        canvas.drawCircle(cx, cy, r, markerPaint)
        markerPaint.color = android.graphics.Color.WHITE
        canvas.drawCircle(cx, cy, r * 0.5f, markerPaint)
        markerPaint.color = android.graphics.Color.argb(255, 56, 142, 60)   // 녹색 중심
        canvas.drawCircle(cx, cy, r * 0.25f, markerPaint)
    }

    private fun drawPdrLocationMarker(canvas: Canvas, cx: Float, cy: Float) {
        val r = markerRadius
        markerPaint.color = android.graphics.Color.argb(255, 25, 118, 210)   // 파란 외곽
        canvas.drawCircle(cx, cy, r, markerPaint)
        markerPaint.color = android.graphics.Color.WHITE
        canvas.drawCircle(cx, cy, r * 0.5f, markerPaint)
        markerPaint.color = android.graphics.Color.argb(255, 25, 118, 210)   // 파란 중심
        canvas.drawCircle(cx, cy, r * 0.25f, markerPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cameraMoving = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onTouchEvent(event: MotionEvent?) = false
}
