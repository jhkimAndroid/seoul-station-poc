package com.hubilon.seoulstationpoc

import android.app.Application
import android.util.Log
import com.hubilon.seoulstationpoc.util.AppLog
import com.kakao.vectormap.KakaoMapSdk

class SeoulStationPocApplication : Application() {
    companion object {
        const val IS_TEST = true
        const val BASE_URL = "http://121.134.167.198:5050"
        const val BUILDING_ID = 1105001001L
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(AppLog.APP, "앱 초기화 시작")
        KakaoMapSdk.init(this, BuildConfig.KAKAO_MAP_KEY)
        Log.i(AppLog.APP, "KakaoMapSdk 초기화 완료")
    }
}