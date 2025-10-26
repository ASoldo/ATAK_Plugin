package com.walaris.airscout.cot

import com.atakmap.android.cot.MarkerDetailHandler
import com.atakmap.android.maps.Marker
import com.atakmap.coremap.cot.event.CotDetail
import com.atakmap.coremap.cot.event.CotEvent
import com.walaris.airscout.core.AxisCamera
import com.walaris.airscout.core.AxisCameraRepository
import com.walaris.airscout.map.AirScoutMapController

/**
 * Persists AirScout camera metadata inside CoT detail blocks so mission packages
 * and broadcasts retain the full configuration.
 */
class AirScoutDetailHandler(
    private val repository: AxisCameraRepository,
    private val mapController: AirScoutMapController
) : MarkerDetailHandler {

    override fun toCotDetail(marker: Marker, detail: CotDetail) {
        val uid = marker.uid ?: return
        val camera = repository.get(uid) ?: axisCameraFromMarker(marker) ?: return
        detail.addChild(buildDetail(camera))
    }

    override fun toMarkerMetadata(marker: Marker, event: CotEvent, detail: CotDetail) {
        val camera = cameraFromDetail(detail, marker) ?: return
        repository.upsert(camera)
        mapController.addOrUpdateCamera(camera)

        if (!marker.getMetaBoolean(META_MANAGED, false)) {
            marker.removeFromGroup()
        }
    }

    private fun buildDetail(camera: AxisCamera): CotDetail {
        val detail = CotDetail(DETAIL_TAG)
        detail.setAttribute("uid", camera.uid)
        detail.setAttribute("name", camera.displayName)
        if (camera.description.isNotBlank()) detail.setAttribute("description", camera.description)
        detail.setAttribute("lat", camera.latitude.toString())
        detail.setAttribute("lon", camera.longitude.toString())
        detail.setAttribute("alt", camera.altitude.toString())
        detail.setAttribute("rtsp", camera.rtspUrl)
        detail.setAttribute("control", camera.controlUrl)
        camera.eventWebSocketUrl?.let { detail.setAttribute("eventWs", it) }
        camera.protocol?.let { detail.setAttribute("protocol", it) }
        camera.username?.let { detail.setAttribute("username", it) }
        camera.password?.let { detail.setAttribute("password", it) }
        detail.setAttribute("frustumMode", camera.frustumMode.name)
        camera.frustumRangeMeters?.let { detail.setAttribute("frustumRange", it.toString()) }
        camera.frustumHorizontalFovDeg?.let { detail.setAttribute("frustumHfov", it.toString()) }
        camera.frustumVerticalFovDeg?.let { detail.setAttribute("frustumVfov", it.toString()) }
        camera.frustumRadiusMeters?.let { detail.setAttribute("frustumRadius", it.toString()) }
        camera.frustumBearingDeg?.let { detail.setAttribute("frustumBearing", it.toString()) }
        camera.frustumZoomLevel?.let { detail.setAttribute("frustumZoom", it.toString()) }
        return detail
    }

    private fun cameraFromDetail(detail: CotDetail, marker: Marker): AxisCamera? {
        val uid = detail.getAttribute("uid") ?: marker.uid ?: return null
        val name = detail.getAttribute("name") ?: marker.title ?: DEFAULT_NAME
        val description = detail.getAttribute("description")
            ?: marker.metaString("airscout.description").orEmpty()
        val latitude = detail.getAttribute("lat")?.toDoubleOrNull()
            ?: marker.point?.latitude ?: return null
        val longitude = detail.getAttribute("lon")?.toDoubleOrNull()
            ?: marker.point?.longitude ?: return null
        val altitude = detail.getAttribute("alt")?.toDoubleOrNull()
            ?: marker.point?.altitude ?: 0.0
        val frustumModeName = detail.getAttribute("frustumMode")
        val frustumMode = frustumModeName?.let {
            runCatching { AxisCamera.FrustumMode.valueOf(it) }.getOrNull()
        } ?: AxisCamera.FrustumMode.CIRCLE

        return AxisCamera(
            uid = uid,
            displayName = name,
            description = description,
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            rtspUrl = detail.getAttribute("rtsp") ?: marker.metaString("airscout.rtsp").orEmpty(),
            controlUrl = detail.getAttribute("control") ?: marker.metaString("airscout.control").orEmpty(),
            eventWebSocketUrl = detail.getAttribute("eventWs") ?: marker.metaString("airscout.eventWs"),
            username = detail.getAttribute("username") ?: marker.metaString("airscout.username"),
            password = detail.getAttribute("password") ?: marker.metaString("airscout.password"),
            protocol = detail.getAttribute("protocol") ?: marker.metaString("protocol"),
            frustumMode = frustumMode,
            frustumRangeMeters = parseDouble(detail.getAttribute("frustumRange"))
                ?: marker.metaDouble("airscout.frustumRange"),
            frustumHorizontalFovDeg = parseDouble(detail.getAttribute("frustumHfov"))
                ?: marker.metaDouble("airscout.frustumHfov"),
            frustumVerticalFovDeg = parseDouble(detail.getAttribute("frustumVfov"))
                ?: marker.metaDouble("airscout.frustumVfov"),
            frustumRadiusMeters = parseDouble(detail.getAttribute("frustumRadius"))
                ?: marker.metaDouble("airscout.frustumRadius"),
            frustumBearingDeg = parseDouble(detail.getAttribute("frustumBearing"))
                ?: marker.metaDouble("airscout.frustumBearing"),
            frustumZoomLevel = parseDouble(detail.getAttribute("frustumZoom"))
                ?: marker.metaDouble("airscout.frustumZoom")
        )
    }

    private fun axisCameraFromMarker(marker: Marker): AxisCamera? {
        val uid = marker.uid ?: return null
        val point = marker.point ?: return null
        return AxisCamera(
            uid = uid,
            displayName = marker.title ?: DEFAULT_NAME,
            description = marker.metaString("airscout.description").orEmpty(),
            latitude = point.latitude,
            longitude = point.longitude,
            altitude = point.altitude,
            rtspUrl = marker.metaString("airscout.rtsp").orEmpty(),
            controlUrl = marker.metaString("airscout.control").orEmpty(),
            eventWebSocketUrl = marker.metaString("airscout.eventWs"),
            username = marker.metaString("airscout.username"),
            password = marker.metaString("airscout.password"),
            protocol = marker.metaString("protocol"),
            frustumMode = marker.metaString("airscout.frustumMode")?.let {
                runCatching { AxisCamera.FrustumMode.valueOf(it) }.getOrNull()
            } ?: AxisCamera.FrustumMode.CIRCLE,
            frustumRangeMeters = marker.metaDouble("airscout.frustumRange"),
            frustumHorizontalFovDeg = marker.metaDouble("airscout.frustumHfov"),
            frustumVerticalFovDeg = marker.metaDouble("airscout.frustumVfov"),
            frustumRadiusMeters = marker.metaDouble("airscout.frustumRadius"),
            frustumBearingDeg = marker.metaDouble("airscout.frustumBearing"),
            frustumZoomLevel = marker.metaDouble("airscout.frustumZoom")
        )
    }

    private fun parseDouble(value: String?): Double? = value?.toDoubleOrNull()

    private fun Marker.metaString(key: String): String? =
        runCatching { getMetaString(key, null as String?) }.getOrNull()

    private fun Marker.metaDouble(key: String): Double? =
        runCatching { getMetaDouble(key, Double.NaN) }.getOrNull()?.takeUnless { it.isNaN() }

    companion object {
        const val DETAIL_TAG = "airscout"
        private const val DEFAULT_NAME = "AirScout Camera"
        private const val META_MANAGED = "airscout.managed"
    }
}
