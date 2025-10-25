package com.walaris.airscout.map

import android.content.Context
import android.graphics.Color
import com.atakmap.android.drawing.mapItems.DrawingShape
import com.atakmap.android.maps.MapItem
import com.atakmap.android.maps.MapView
import com.atakmap.android.maps.Marker
import com.atakmap.android.menu.MapMenuReceiver
import com.atakmap.coremap.maps.assets.Icon
import com.atakmap.coremap.maps.coords.GeoCalculations
import com.atakmap.coremap.maps.coords.GeoPoint
import com.atakmap.coremap.maps.coords.GeoPointMetaData
import com.atakmap.android.video.ConnectionEntry
import com.atakmap.android.video.manager.VideoManager
import com.walaris.airscout.R
import com.walaris.airscout.core.AxisCamera
import com.walaris.airscout.core.AxisCameraRepository
import com.atakmap.android.util.Circle
import com.atakmap.map.CameraController
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
        val horizontalFov =
            (camera.frustumHorizontalFovDeg ?: DEFAULT_HORIZONTAL_FOV).coerceIn(1.0, 179.0)
        val verticalFov =
            (camera.frustumVerticalFovDeg ?: DEFAULT_VERTICAL_FOV).coerceIn(1.0, 179.0)
        val segments = (horizontalFov / 4).roundToInt().coerceIn(6, 36)
        val bearing = camera.frustumBearingDeg ?: 0.0
        val center = marker.point
        val altitude = center.altitude
        val startAzimuth = bearing - horizontalFov / 2.0
        val endAzimuth = bearing + horizontalFov / 2.0
        marker.setTrack(bearing, 0.0)

        val points = ArrayList<GeoPoint>(segments + 3)
        points += GeoPoint(center.latitude, center.longitude, altitude)
        for (i in 0..segments) {
            val t = i.toDouble() / segments.toDouble()
            val azimuth = startAzimuth + (endAzimuth - startAzimuth) * t
            val target = GeoCalculations.pointAtDistance(center, azimuth, range)
            points += GeoPoint(target.latitude, target.longitude, altitude)
        }
        points += GeoPoint(center.latitude, center.longitude, altitude)

        val shapeId = "${camera.uid}-fovshape"
        val existingShape = mapView.getMapItem(shapeId) as? DrawingShape
        val shape = existingShape ?: DrawingShape(mapView, shapeId).apply {
            setMetaBoolean("archive", false)
            setMetaBoolean("editable", false)
            setClosed(true)
        }
        shape.setPoints(points.toTypedArray())
        shape.setStrokeColor(FOV_STROKE_COLOR)
        shape.setStrokeWeight(2.5)
        shape.setFillColor(FOV_FILL_COLOR)
        shape.setHeight(altitude)
        shape.setMetaString("parent_uid", camera.uid)
        shape.setMetaString("callsign", context.getString(R.string.overlay_camera_fov_title, camera.displayName))
        shape.setMetaDouble("bearing", bearing)
        shape.setMetaDouble("hfov", horizontalFov)
        shape.setMetaDouble("vfov", verticalFov)
        shape.setMetaDouble("range", range)
        shape.setTitle(context.getString(R.string.overlay_camera_fov_title, camera.displayName))
        if (existingShape == null) {
            mapView.getRootGroup().addItem(shape)
        } else {
            shape.refresh(mapView.getMapEventDispatcher(), null, javaClass)
        }

        val arrowId = "${camera.uid}-fovarrow"
        val existingArrow = mapView.getMapItem(arrowId) as? DrawingShape
        val arrowShape = existingArrow ?: DrawingShape(mapView, arrowId).apply {
            setMetaBoolean("archive", false)
            setMetaBoolean("editable", false)
            setClosed(false)
        }
        val arrowBase = GeoPoint(center.latitude, center.longitude, altitude)
        val arrowHead = GeoCalculations.pointAtDistance(center, bearing, range)
        val arrowLeft = GeoCalculations.pointAtDistance(arrowHead, bearing + 160.0, range * 0.05)
        val arrowRight = GeoCalculations.pointAtDistance(arrowHead, bearing - 160.0, range * 0.05)
        val midPoint = GeoCalculations.pointAtDistance(center, bearing, range * 0.5)
        arrowShape.setPoints(arrayOf(
            arrowBase,
            GeoPoint(arrowHead.latitude, arrowHead.longitude, altitude),
            GeoPoint(arrowLeft.latitude, arrowLeft.longitude, altitude),
            GeoPoint(arrowHead.latitude, arrowHead.longitude, altitude),
            GeoPoint(arrowRight.latitude, arrowRight.longitude, altitude)
        ))
        arrowShape.setStrokeColor(FOV_STROKE_COLOR)
        arrowShape.setStrokeWeight(2.5)
        arrowShape.setFillColor(Color.TRANSPARENT)
        arrowShape.setHeight(altitude)
        arrowShape.setMetaString("parent_uid", camera.uid)
        arrowShape.setMetaString("callsign", context.getString(R.string.overlay_camera_fov_title, camera.displayName))
        arrowShape.setTitle(context.getString(R.string.overlay_camera_fov_title, camera.displayName))
        if (existingArrow == null) {
            mapView.getRootGroup().addItem(arrowShape)
        } else {
            arrowShape.refresh(mapView.getMapEventDispatcher(), null, javaClass)
        }

        val pointId = "${camera.uid}-fovpoint"
        val existingPoint = mapView.getMapItem(pointId) as? Circle
        val pointRadius = (range * 0.03).coerceAtLeast(5.0)
        val pointCircle = existingPoint ?: Circle(GeoPointMetaData.wrap(midPoint), pointRadius, pointId).apply {
            setMetaBoolean("archive", false)
            setMetaBoolean("editable", false)
        }
        pointCircle.setCenterPoint(GeoPointMetaData.wrap(GeoPoint(midPoint.latitude, midPoint.longitude, altitude)))
        pointCircle.setRadius(pointRadius)
        pointCircle.setColor(FOV_STROKE_COLOR)
        pointCircle.setStrokeColor(FOV_STROKE_COLOR)
        pointCircle.setStrokeWeight(2.0)
        pointCircle.setFillColor(Color.TRANSPARENT)
        pointCircle.setMetaString("parent_uid", camera.uid)
        pointCircle.setMetaString("callsign", context.getString(R.string.overlay_camera_fov_title, camera.displayName))
        pointCircle.setTitle(context.getString(R.string.overlay_camera_fov_title, camera.displayName))
        if (existingPoint == null) {
            mapView.getRootGroup().addItem(pointCircle)
        } else {
            pointCircle.refresh(mapView.getMapEventDispatcher(), null, javaClass)
        }
    }

    private fun clearFrustumOverlay(cameraId: String) {
        mapView.getMapItem("$cameraId-ring")?.removeFromGroup()
        mapView.getMapItem("$cameraId-fovshape")?.removeFromGroup()
        mapView.getMapItem("$cameraId-fovarrow")?.removeFromGroup()
        mapView.getMapItem("$cameraId-fovpoint")?.removeFromGroup()
    }

    companion object {
        const val CAMERA_MARKER_TYPE = "b-walaris-airscout"
        private val CIRCLE_STROKE_COLOR = Color.parseColor("#FF4B6EA8")
        private val CIRCLE_FILL_COLOR = Color.parseColor("#334B6EA8")
        private val FOV_STROKE_COLOR = Color.parseColor("#FFC1D034")
        private val FOV_FILL_COLOR = Color.parseColor("#33C1D034")
        private const val DEFAULT_HORIZONTAL_FOV = 60.0
        private const val DEFAULT_VERTICAL_FOV = 45.0
    }
}
