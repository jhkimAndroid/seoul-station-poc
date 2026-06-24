package com.hubilon.positioning.scan

import android.content.Context
import android.telephony.CellInfoLte
import android.telephony.TelephonyManager
import android.util.Log
import com.hubilon.positioning.model.LteSignal

private const val TAG = "SSP_LTE"

class LteScanner(context: Context) {

    private val telephonyManager = context.applicationContext
        .getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    fun scan(): List<LteSignal> {
        return try {
            val cellInfoList = telephonyManager.allCellInfo
            if (cellInfoList == null) {
                Log.w(TAG, "allCellInfo null — SIM 없음 또는 비행기 모드")
                return emptyList()
            }
            val signals = cellInfoList
                .filterIsInstance<CellInfoLte>()
                .filter { it.isRegistered }
                .mapNotNull { info ->
                    val id = info.cellIdentity
                    val sig = info.cellSignalStrength
                    val pci = id.pci
                    val tac = id.tac
                    val rsrp = sig.rsrp
                    val rsrq = sig.rsrq
                    if (!pci.isValidCell() || !rsrp.isValidCell()) {
                        Log.d(TAG, "유효하지 않은 셀 생략 — pci=$pci rsrp=$rsrp")
                        return@mapNotNull null
                    }
                    LteSignal(
                        pci = pci,
                        tac = if (tac.isValidCell()) tac else -1,
                        rawTac = tac,
                        rsrp = rsrp,
                        rsrq = if (rsrq.isValidCell()) rsrq else 0,
                        isRegistered = true
                    )
                }
            Log.i(TAG, "LTE 스캔 완료 — 서빙셀 ${signals.size}개")
            signals
        } catch (e: SecurityException) {
            Log.w(TAG, "LTE 스캔 권한 없음: ${e.message}")
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "LTE 스캔 실패: ${e.message}")
            emptyList()
        }
    }

    private fun Int.isValidCell() = this != Int.MAX_VALUE && this != Int.MIN_VALUE
}
