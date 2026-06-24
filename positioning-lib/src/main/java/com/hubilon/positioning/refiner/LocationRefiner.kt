package com.hubilon.positioning.refiner

import com.hubilon.positioning.model.LocationResult

/** 위치 추정 결과를 정제하는 인터페이스. 커스텀 정제기를 구현할 때 사용한다. */
interface LocationRefiner {
    fun refine(location: LocationResult): LocationResult
    fun reset()
}
