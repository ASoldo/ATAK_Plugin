package com.walaris.airscout.core

import java.util.UUID
import org.json.JSONObject

/**
 * Representation of a controllable Axis camera that can publish an RTSP stream and
 * exposes a PTZ control endpoint. Instances are persisted as JSON and restored on launch.
 */
data class AxisCamera(
    val uid: String = UUID.randomUUID().toString(),
    var displayName: String,
    var description: String = "",
    var latitude: Double,
    var longitude: Double,
    var altitude: Double = 0.0,
    var rtspUrl: String,
    var controlUrl: String,
    var eventWebSocketUrl: String? = null,
    var username: String? = null,
    var password: String? = null,
    var protocol: String? = null,
    var frustumMode: FrustumMode = FrustumMode.CIRCLE,
    var frustumRangeMeters: Double? = null,
    var frustumHorizontalFovDeg: Double? = null,
    var frustumVerticalFovDeg: Double? = null,
    var frustumRadiusMeters: Double? = null,
    var frustumBearingDeg: Double? = null,
    var frustumZoomRangeMeters: Double? = null,
    var frustumZoomRadiusMeters: Double? = null
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_UID, uid)
        put(KEY_NAME, displayName)
        put(KEY_DESCRIPTION, description)
        put(KEY_LAT, latitude)
        put(KEY_LON, longitude)
        put(KEY_ALT, altitude)
        put(KEY_RTSP, rtspUrl)
        put(KEY_CONTROL, controlUrl)
        eventWebSocketUrl?.let { put(KEY_WS, it) }
        username?.let { put(KEY_USER, it) }
        password?.let { put(KEY_PASS, it) }
        protocol?.let { put(KEY_PROTOCOL, it) }
        put(KEY_FRUSTUM_MODE, frustumMode.name)
        frustumRangeMeters?.let { put(KEY_FRUSTUM_RANGE, it) }
        frustumHorizontalFovDeg?.let { put(KEY_FRUSTUM_HFOV, it) }
        frustumVerticalFovDeg?.let { put(KEY_FRUSTUM_VFOV, it) }
        frustumRadiusMeters?.let { put(KEY_FRUSTUM_RADIUS, it) }
        frustumBearingDeg?.let { put(KEY_FRUSTUM_BEARING, it) }
        frustumZoomRangeMeters?.let { put(KEY_FRUSTUM_ZOOM_RANGE, it) }
        frustumZoomRadiusMeters?.let { put(KEY_FRUSTUM_ZOOM_RADIUS, it) }
    }

    fun updateFrom(other: AxisCamera) {
        displayName = other.displayName
        description = other.description
        latitude = other.latitude
        longitude = other.longitude
        altitude = other.altitude
        rtspUrl = other.rtspUrl
        controlUrl = other.controlUrl
        eventWebSocketUrl = other.eventWebSocketUrl
        username = other.username
        password = other.password
        protocol = other.protocol
        frustumMode = other.frustumMode
        frustumRangeMeters = other.frustumRangeMeters
        frustumHorizontalFovDeg = other.frustumHorizontalFovDeg
        frustumVerticalFovDeg = other.frustumVerticalFovDeg
        frustumRadiusMeters = other.frustumRadiusMeters
        frustumBearingDeg = other.frustumBearingDeg
        frustumZoomRangeMeters = other.frustumZoomRangeMeters
        frustumZoomRadiusMeters = other.frustumZoomRadiusMeters
    }

    companion object {
        private const val KEY_UID = "uid"
        private const val KEY_NAME = "displayName"
        private const val KEY_DESCRIPTION = "description"
        private const val KEY_LAT = "latitude"
        private const val KEY_LON = "longitude"
        private const val KEY_ALT = "altitude"
        private const val KEY_RTSP = "rtsp"
        private const val KEY_CONTROL = "control"
        private const val KEY_WS = "ws"
        private const val KEY_USER = "user"
        private const val KEY_PASS = "pass"
        private const val KEY_PROTOCOL = "protocol"
        private const val KEY_FRUSTUM_MODE = "frustumMode"
        private const val KEY_FRUSTUM_RANGE = "frustumRange"
        private const val KEY_FRUSTUM_HFOV = "frustumHorizontalFov"
        private const val KEY_FRUSTUM_VFOV = "frustumVerticalFov"
        private const val KEY_FRUSTUM_RADIUS = "frustumRadius"
        private const val KEY_FRUSTUM_BEARING = "frustumBearing"
        private const val KEY_FRUSTUM_ZOOM_RANGE = "frustumZoomRange"
        private const val KEY_FRUSTUM_ZOOM_RADIUS = "frustumZoomRadius"

        fun fromJson(payload: JSONObject): AxisCamera = AxisCamera(
            uid = payload.optString(KEY_UID, UUID.randomUUID().toString()),
            displayName = payload.optString(KEY_NAME, "Camera"),
            description = payload.optString(KEY_DESCRIPTION, ""),
            latitude = payload.optDouble(KEY_LAT, 0.0),
            longitude = payload.optDouble(KEY_LON, 0.0),
            altitude = payload.optDouble(KEY_ALT, 0.0),
            rtspUrl = payload.optString(KEY_RTSP, ""),
            controlUrl = payload.optString(KEY_CONTROL, ""),
            eventWebSocketUrl = payload.optString(KEY_WS, null),
            username = payload.optString(KEY_USER, null),
            password = payload.optString(KEY_PASS, null),
            protocol = payload.optString(KEY_PROTOCOL, null),
            frustumMode = payload.optString(KEY_FRUSTUM_MODE, null)
                ?.let { runCatching { FrustumMode.valueOf(it) }.getOrNull() }
                ?: FrustumMode.CIRCLE,
            frustumRangeMeters = payload.optDouble(KEY_FRUSTUM_RANGE, Double.NaN).takeIf { !it.isNaN() },
            frustumHorizontalFovDeg = payload.optDouble(KEY_FRUSTUM_HFOV, Double.NaN).takeIf { !it.isNaN() },
            frustumVerticalFovDeg = payload.optDouble(KEY_FRUSTUM_VFOV, Double.NaN).takeIf { !it.isNaN() },
            frustumRadiusMeters = payload.optDouble(KEY_FRUSTUM_RADIUS, Double.NaN).takeIf { !it.isNaN() },
            frustumBearingDeg = payload.optDouble(KEY_FRUSTUM_BEARING, Double.NaN).takeIf { !it.isNaN() },
            frustumZoomRangeMeters = payload.optDouble(KEY_FRUSTUM_ZOOM_RANGE, Double.NaN).takeIf { !it.isNaN() },
            frustumZoomRadiusMeters = payload.optDouble(KEY_FRUSTUM_ZOOM_RADIUS, Double.NaN).takeIf { !it.isNaN() }
        )
    }

    enum class FrustumMode {
        CIRCLE,
        CONE
    }
}
