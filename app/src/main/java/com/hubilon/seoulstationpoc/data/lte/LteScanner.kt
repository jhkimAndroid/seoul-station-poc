package com.hubilon.seoulstationpoc.data.lte

import android.content.Context
import android.telephony.CellInfoLte
import android.telephony.TelephonyManager
import android.util.Log
import com.hubilon.seoulstationpoc.model.LteSignal
import com.hubilon.seoulstationpoc.util.AppLog

private const val TAG = AppLog.LTE

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
                .filter { it.isRegistered }   // 등록된 서빙 셀만 사용
                .mapNotNull { info ->
                    val id  = info.cellIdentity
                    val sig = info.cellSignalStrength
                    val pci  = id.pci
                    val tac  = id.tac
                    val rsrp = sig.rsrp
                    val rsrq = sig.rsrq
                    if (!pci.isValidCell() || !rsrp.isValidCell()) {
                        Log.d(TAG, "유효하지 않은 셀 생략 — pci=$pci rsrp=$rsrp")
                        return@mapNotNull null
                    }
                    Log.i(TAG, "LTE 서빙셀 — pci=$pci tac=$tac rsrp=$rsrp rsrq=$rsrq")
                    LteSignal(
                        pci          = pci,
                        tac          = if (tac.isValidCell()) tac else -1,
                        rawTac       = tac,
                        rsrp         = rsrp,
                        rsrq         = if (rsrq.isValidCell()) rsrq else 0,
                        isRegistered = true
                    )
                }
            Log.i(TAG, "LTE 스캔 완료 — 서빙셀 ${signals.size}개")
            signals.forEach { s ->
                Log.d(TAG, "  [서빙] pci=${s.pci} tac=${s.tac} rsrp=${s.rsrp}dBm rsrq=${s.rsrq}dB")
            }
            signals
        } catch (e: SecurityException) {
            Log.w(TAG, "LTE 스캔 권한 없음 (READ_PHONE_STATE 또는 ACCESS_FINE_LOCATION 필요): ${e.message}")
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "LTE 스캔 실패: ${e.message}")
            emptyList()
        }
    }

    private fun Int.isValidCell() = this != Int.MAX_VALUE && this != Int.MIN_VALUE
}
