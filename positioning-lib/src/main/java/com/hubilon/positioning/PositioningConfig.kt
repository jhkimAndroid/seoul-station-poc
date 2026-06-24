package com.hubilon.positioning

data class MbrBounds(
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double
)

data class PositioningConfig(
    val baseUrl: String,
    val buildingId: Long,
    val mbrBounds: MbrBounds? = null,
    val linkAssetFile: String? = null,
    val scanIntervalMs: Long = 1_000L,
    val historyMinDistM: Double = 2.0,
    val historyMaxSize: Int = 20,
    val pdrCorrectionMinDistM: Double = 5.0,
    val pdrCorrectionConsecutiveSteps: Int = 3,
    val pdrCorrectionForwardDistM: Double = 3.0,
    val pdrCorrectionReverseDistM: Double = 2.0,
    val fileLogEnabled: Boolean = false,
    val fileLogPrefix: String = "SSP",
    val isDebugMode: Boolean = false
)
