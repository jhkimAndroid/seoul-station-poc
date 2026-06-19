package com.hubilon.seoulstationpoc.data.location

import com.hubilon.seoulstationpoc.data.location.processor.LocationProcessor
import com.hubilon.seoulstationpoc.data.location.processor.ProcessContext
import com.hubilon.seoulstationpoc.model.GeoPos

class LocationPipeline(private val processors: List<LocationProcessor>) {

    fun process(input: GeoPos, context: ProcessContext): GeoPos =
        processors.fold(input) { current, processor -> processor.process(current, context) }
}
