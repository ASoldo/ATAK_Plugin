package com.walaris.airscout.map

import com.atakmap.android.maps.Marker
import com.walaris.airscout.core.AxisCamera

data class CameraMapEntry(
    val camera: AxisCamera,
    val marker: Marker
)
