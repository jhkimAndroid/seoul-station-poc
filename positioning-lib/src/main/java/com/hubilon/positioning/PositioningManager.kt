package com.hubilon.positioning

import android.content.Context
import com.hubilon.positioning.internal.engine.PositioningEngine
import com.hubilon.positioning.model.GeoPos
import com.hubilon.positioning.model.LocationResult
import com.hubilon.positioning.scan.BleScanner
import com.hubilon.positioning.scan.GpsScanner
import com.hubilon.positioning.scan.LteScanner
import com.hubilon.positioning.scan.PdrProcessor
import com.hubilon.positioning.scan.SensorCollector
import com.hubilon.positioning.scan.WifiScanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Entry point for the positioning library.
 *
 * Typical lifecycle:
 * 1. `initialize()` — load server features and link data.
 * 2. `startScanning()` — begin GPS / BLE / Sensor collection.
 * 3. `startAutoPositioning()` — begin WiFi-triggered anchor + PDR positioning loop.
 * 4. Collect [events] or observe [update] for results.
 * 5. `stopAutoPositioning()` → `stopScanning()` → `release()` on teardown.
 */
class PositioningManager(context: Context, config: PositioningConfig) {

    private val engine = PositioningEngine(context.applicationContext, config)

    /** Stream of [PositioningEvent] — LocationUpdate, GpsUpdate, OutsideMbr, etc. */
    val events: Flow<PositioningEvent> = engine.events

    /** Latest snapshot of positioning state. */
    val update: StateFlow<PositioningUpdate> = engine.update

    // ── Scanners (direct access for app UI or custom logic) ───────────────────
    val gpsScanner: GpsScanner           get() = engine.gpsScanner
    val bleScanner: BleScanner           get() = engine.bleScanner
    val wifiScanner: WifiScanner         get() = engine.wifiScanner
    val lteScanner: LteScanner           get() = engine.lteScanner
    val sensorCollector: SensorCollector get() = engine.sensorCollector
    val pdrProcessor: PdrProcessor       get() = engine.pdrProcessor

    /**
     * Load server AP features and link graph data.
     * Should be called once after creation, before [startScanning].
     */
    fun initialize() = engine.initialize()

    /**
     * Start GPS / BLE / Sensor collection.
     * MBR check and magnetic declination calibration happen automatically on first GPS fix.
     */
    fun startScanning() = engine.startScanning()

    /** Stop GPS / BLE / Sensor collection. */
    fun stopScanning() = engine.stopScanning()

    /**
     * Start the automatic positioning loop.
     * WiFi scan results trigger anchor positioning; PDR provides real-time updates between anchors.
     */
    fun startAutoPositioning() = engine.startAutoPositioning()

    /** Stop the automatic positioning loop. */
    fun stopAutoPositioning() = engine.stopAutoPositioning()

    /** Snap [pos] to the nearest point on the loaded link graph. Returns null if no link data. */
    fun findNearestLink(pos: com.hubilon.positioning.model.GeoPos): com.hubilon.positioning.model.GeoPos? =
        engine.findNearestLink(pos)

    /** Return the loaded link graph data (for drawing polylines in the map UI). */
    fun getLinkData(): List<com.hubilon.positioning.model.LinkData> = engine.getLinkData()

    /** Reset PDR displacement and set the origin to the latest server location. */
    fun resetPdr() = engine.resetPdr()

    /**
     * Stop everything and release all resources.
     * The instance should not be used after calling this.
     */
    fun release() = engine.release()
}
