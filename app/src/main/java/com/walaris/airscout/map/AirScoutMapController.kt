package com.walaris.airscout.map

import android.content.Context
import android.graphics.Color
import com.atakmap.android.cot.detail.SensorDetailHandler
import com.atakmap.android.maps.MapItem
import com.atakmap.android.maps.MapView
import com.atakmap.android.maps.Marker
import com.atakmap.android.menu.MapMenuReceiver
import com.atakmap.map.CameraController
import com.atakmap.coremap.maps.assets.Icon
import com.atakmap.coremap.maps.coords.GeoPoint
import com.atakmap.coremap.maps.coords.GeoPointMetaData
import com.atakmap.android.video.ConnectionEntry
import com.atakmap.android.video.manager.VideoManager
import com.walaris.airscout.R
import com.walaris.airscout.core.AxisCamera
import com.walaris.airscout.core.AxisCameraRepository
import com.atakmap.android.util.Circle
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.roundToInt

class AirScoutMapController(
    private val context: Context,
    private val repository: AxisCameraRepository
) : AirScoutCameraOverlay.Callbacks, AirScoutMenuFactory.Callback {

    interface Listener {
        fun onCameraSelected(camera: AxisCamera)
        fun onCameraPreviewRequested(camera: AxisCamera)
        fun onAddCameraRequested(initialLocation: GeoPoint?)
        fun onCameraRemoved(camera: AxisCamera)
        fun onCameraInventoryChanged()
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val mapView: MapView = MapView.getMapView()
    private val overlay = AirScoutCameraOverlay(context, mapView, this)
    private val menuFactory = AirScoutMenuFactory(context, this)
    private val cameras = LinkedHashMap<String, AxisCamera>()
    private val markers = LinkedHashMap<String, Marker>()

    fun start() {
        mapView.post {
            mapView.getMapOverlayManager().addOverlay(overlay)
            MapMenuReceiver.getInstance().registerMapMenuFactory(menuFactory)
            repository.getAll().forEach { addOrUpdateCameraInternal(it, persist = false) }
            syncWithAvailableVideoSources()
        }
    }

    fun stop() {
        mapView.post {
            MapMenuReceiver.getInstance().unregisterMapMenuFactory(menuFactory)
            cameras.keys.toList().forEach { clearFrustumOverlay(it) }
            markers.values.forEach { marker ->
                mapView.getRootGroup().removeItem(marker)
            }
            markers.clear()
            cameras.clear()
            mapView.getMapOverlayManager().removeOverlay(overlay)
        }
    }

    fun registerListener(listener: Listener) {
        listeners += listener
    }

    fun unregisterListener(listener: Listener) {
        listeners -= listener
    }

    fun getCamera(uid: String): AxisCamera? = cameras[uid]?.copy()

    fun listCameras(): List<AxisCamera> = cameras.values.map { it.copy() }

    fun addOrUpdateCamera(camera: AxisCamera) {
        mapView.post { addOrUpdateCameraInternal(camera, persist = true) }
    }

    fun removeCamera(uid: String) {
        val camera = cameras[uid] ?: return
        mapView.post {
            clearFrustumOverlay(uid)
            val marker = markers.remove(uid)
            if (marker != null) {
                mapView.getRootGroup().removeItem(marker)
                overlay.onMarkerRemoved(uid, marker)
            }
            cameras.remove(uid)
            repository.delete(uid)
            listeners.forEach { it.onCameraRemoved(camera) }
            listeners.forEach { it.onCameraInventoryChanged() }
        }
    }

    fun selectCamera(uid: String, centerOnMap: Boolean = false) {
        val camera = cameras[uid] ?: return
        if (centerOnMap) {
            markers[uid]?.let { marker ->
                mapView.post {
                    CameraController.Programmatic.panTo(
                        mapView.renderer3,
                        marker.point,
                        true
                    )
                }
            }
        }
        listeners.forEach { it.onCameraSelected(camera) }
    }

    private fun addOrUpdateCameraInternal(camera: AxisCamera, persist: Boolean) {
        val existing = cameras[camera.uid]
        if (existing == null) {
            val copy = camera.copy()
            cameras[copy.uid] = copy
            if (persist) {
                repository.upsert(copy)
            }
            val marker = createMarker(copy)
            markers[copy.uid] = marker
            mapView.getRootGroup().addItem(marker)
            refreshFrustumOverlay(copy, marker)
            overlay.onMarkerAdded(CameraMapEntry(copy, marker))
            listeners.forEach { it.onCameraInventoryChanged() }
        } else {
            existing.updateFrom(camera)
            if (persist) {
                repository.upsert(existing)
            }
            val marker = markers[existing.uid]
            if (marker != null) {
                marker.setTitle(existing.displayName)
                marker.setMetaString("callsign", existing.displayName)
                marker.setMetaString("uid", existing.uid)
                existing.protocol?.let { marker.setMetaString("protocol", it) }
                marker.setPoint(GeoPoint(existing.latitude, existing.longitude, existing.altitude))
                marker.refresh(mapView.getMapEventDispatcher(), null, javaClass)
                refreshFrustumOverlay(existing, marker)
                overlay.onMarkerAdded(CameraMapEntry(existing.copy(), marker))
            }
            listeners.forEach { it.onCameraInventoryChanged() }
        }
    }

    private fun createMarker(camera: AxisCamera): Marker {
        val point = GeoPoint(camera.latitude, camera.longitude, camera.altitude)
        val marker = Marker(point, camera.uid)
        marker.setTitle(camera.displayName)
        marker.setType(CAMERA_MARKER_TYPE)
        marker.setClickable(true)
        marker.setMetaString("callsign", camera.displayName)
        marker.setMetaString("uid", camera.uid)
        camera.protocol?.let { marker.setMetaString("protocol", it) }
        marker.setAlwaysShowText(true)
        val resourceUri = "android.resource://${context.packageName}/${R.drawable.ic_camera_marker}"
        val icon = Icon.Builder()
            .setImageUri(0, resourceUri)
            .build()
        marker.setIcon(icon)
        return marker
    }

    override fun onAddCameraRequested() {
        mapView.post {
            val center = try {
                mapView.centerPoint.get()
            } catch (_: Exception) {
                null
            }
            listeners.forEach { it.onAddCameraRequested(center) }
        }
    }

    override fun onPreviewCameraRequested(cameraId: String) {
        val camera = cameras[cameraId] ?: return
        selectCamera(cameraId, centerOnMap = true)
        listeners.forEach { it.onCameraPreviewRequested(camera) }
    }

    override fun onRemoveCameraRequested(cameraId: String) {
        removeCamera(cameraId)
    }

    override fun onMenuAction(item: com.atakmap.android.maps.MapItem, action: AirScoutMenuFactory.MenuAction) {
        val marker = item as? Marker ?: return
        val camera = cameras[marker.uid] ?: return
        when (action) {
            AirScoutMenuFactory.MenuAction.CONTROL -> {
                listeners.forEach { it.onCameraSelected(camera) }
            }
            AirScoutMenuFactory.MenuAction.CENTER -> {
                mapView.post {
                    CameraController.Programmatic.panTo(mapView.renderer3, marker.point, true)
                }
            }
        }
    }

    fun registerVideoSources(entries: List<ConnectionEntry>, fallbackLocation: GeoPoint?): List<String> {
        if (entries.isEmpty()) return emptyList()
        val added = mutableListOf<String>()
        val effectiveFallback = fallbackLocation ?: runCatching { mapView.centerPoint.get() }.getOrNull()
        entries.forEach { entry ->
            val sourceId = deriveSourceId(entry)
            val existing = cameras[sourceId]
            val entryLocation = extractGeoPoint(entry)
            val latLonAlt = existing?.let { Triple(it.latitude, it.longitude, it.altitude) }
                ?: geoPointToTriple(entryLocation)
                ?: geoPointToTriple(effectiveFallback)
                ?: Triple(0.0, 0.0, 0.0)
            val (latitude, longitude, altitude) = latLonAlt
            val alias = entry.alias ?: existing?.displayName ?: context.getString(R.string.app_name)
            val streamUrl = ConnectionEntry.getURL(entry, false) ?: existing?.rtspUrl ?: ""
            val camera = AxisCamera(
                uid = sourceId,
                displayName = alias,
                description = existing?.description ?: "",
                latitude = latitude,
                longitude = longitude,
                altitude = altitude,
                rtspUrl = streamUrl,
                controlUrl = existing?.controlUrl ?: "",
                eventWebSocketUrl = existing?.eventWebSocketUrl,
                username = existing?.username,
                password = existing?.password,
                protocol = entry.protocol?.name ?: existing?.protocol,
                frustumMode = existing?.frustumMode ?: AxisCamera.FrustumMode.CIRCLE,
                frustumRangeMeters = existing?.frustumRangeMeters,
                frustumHorizontalFovDeg = existing?.frustumHorizontalFovDeg,
                frustumVerticalFovDeg = existing?.frustumVerticalFovDeg,
                frustumRadiusMeters = existing?.frustumRadiusMeters,
                frustumBearingDeg = existing?.frustumBearingDeg
            )
            addOrUpdateCameraInternal(camera, persist = true)
            added += sourceId
        }
        return added
    }

    private fun syncWithAvailableVideoSources() {
        val entries = runCatching { VideoManager.getInstance().entries }.getOrNull() ?: return
        mapView.post {
            entries.forEach { entry ->
                val id = deriveSourceId(entry)
                val existing = cameras[id] ?: return@forEach
                val alias = entry.alias ?: existing.displayName
                val url = ConnectionEntry.getURL(entry, false) ?: existing.rtspUrl
                val protocolName = entry.protocol?.name ?: existing.protocol
                if (alias != existing.displayName || url != existing.rtspUrl || protocolName != existing.protocol) {
                    val updated = existing.copy(
                        displayName = alias,
                        rtspUrl = url,
                        protocol = protocolName
                    )
                    addOrUpdateCameraInternal(updated, persist = true)
                }
            }
        }
    }

    private fun deriveSourceId(entry: ConnectionEntry): String {
        val explicitId = runCatching {
            val method = entry.javaClass.getMethod("getUid")
            (method.invoke(entry) as? String)?.takeIf { it.isNotBlank() }
        }.getOrNull()
        if (!explicitId.isNullOrBlank()) {
            return explicitId
        }
        val url = ConnectionEntry.getURL(entry, false) ?: ""
        val alias = entry.alias ?: "video"
        return "$alias|$url"
    }

    private fun extractGeoPoint(entry: ConnectionEntry): GeoPoint? {
        val lat = invokeDouble(entry, "getLatitude")
        val lon = invokeDouble(entry, "getLongitude")
        if (lat == null || lon == null) return null
        val alt = invokeDouble(entry, "getAltitude") ?: 0.0
        return GeoPoint(lat, lon, alt)
    }

    private fun invokeDouble(target: Any, methodName: String): Double? {
        return runCatching {
            val method = target.javaClass.getMethod(methodName)
            (method.invoke(target) as? Number)?.toDouble()
        }.getOrNull()
    }

    private fun geoPointToTriple(point: GeoPoint?): Triple<Double, Double, Double>? {
        point ?: return null
        return Triple(point.latitude, point.longitude, point.altitude)
    }

    private fun refreshFrustumOverlay(camera: AxisCamera, marker: Marker) {
        clearFrustumOverlay(camera.uid)
        when (camera.frustumMode) {
            AxisCamera.FrustumMode.CIRCLE -> createCoverageRing(camera, marker)
            AxisCamera.FrustumMode.CONE -> createSensorFov(camera, marker)
        }
    }

    private fun createCoverageRing(camera: AxisCamera, marker: Marker) {
        val radius = (camera.frustumRadiusMeters ?: camera.frustumRangeMeters)?.takeIf { it > 0 } ?: return
        val circleId = "${camera.uid}-ring"
        mapView.getMapItem(circleId)?.removeFromGroup()
        val circle = Circle(GeoPointMetaData.wrap(marker.point), radius, circleId)
        circle.setTitle(context.getString(R.string.overlay_camera_ring_title, camera.displayName))
        circle.setMetaString("parent_uid", camera.uid)
        circle.setMetaString("callsign", circle.title)
        circle.setColor(CIRCLE_STROKE_COLOR)
        circle.setStrokeColor(CIRCLE_STROKE_COLOR)
        circle.setStrokeWeight(2.5)
        circle.setFillColor(CIRCLE_FILL_COLOR)
        circle.setMetaBoolean("labels_on", false)
        circle.setHeight(marker.point.altitude)
        mapView.getRootGroup().addItem(circle)
    }

    private fun createSensorFov(camera: AxisCamera, marker: Marker) {
        val range = camera.frustumRangeMeters?.takeIf { it > 0 } ?: return
        val horizontalFov = (camera.frustumHorizontalFovDeg ?: DEFAULT_HORIZONTAL_FOV)
            .roundToInt().coerceIn(1, 179)
        val verticalFov = (camera.frustumVerticalFovDeg ?: DEFAULT_VERTICAL_FOV)
            .roundToInt().coerceIn(1, 179)
        val bearing = camera.frustumBearingDeg ?: 0.0
        mapView.getMapItem("${marker.uid}-fov")?.removeFromGroup()
        marker.setTrack(bearing, 0.0)
        val color = floatArrayOf(
            Color.red(FOV_COLOR) / 255f,
            Color.green(FOV_COLOR) / 255f,
            Color.blue(FOV_COLOR) / 255f,
            FOV_OPACITY_DEG
        )
        SensorDetailHandler.addFovToMap(
            marker,
            horizontalFov.toDouble(),
            verticalFov.toDouble(),
            range.coerceAtLeast(1.0),
            color,
            true
        )
        (mapView.getMapItem("${marker.uid}-fov") as? MapItem)?.let { fov ->
            fov.setMetaString("parent_uid", camera.uid)
            fov.setMetaString("callsign", context.getString(R.string.overlay_camera_fov_title, camera.displayName))
        }
    }

    private fun clearFrustumOverlay(cameraId: String) {
        mapView.getMapItem("$cameraId-ring")?.removeFromGroup()
        mapView.getMapItem("$cameraId-fov")?.removeFromGroup()
    }

    companion object {
        const val CAMERA_MARKER_TYPE = "b-walaris-airscout"
        private val CIRCLE_STROKE_COLOR = Color.parseColor("#FF4B6EA8")
        private val CIRCLE_FILL_COLOR = Color.parseColor("#334B6EA8")
        private val FOV_COLOR = Color.parseColor("#FFC1D034")
        private const val FOV_OPACITY_DEG = 90f
        private const val DEFAULT_HORIZONTAL_FOV = 60.0
        private const val DEFAULT_VERTICAL_FOV = 45.0
    }
}
