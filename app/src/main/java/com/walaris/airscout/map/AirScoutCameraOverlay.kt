package com.walaris.airscout.map

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.BaseAdapter
import android.widget.Button
import com.atakmap.android.hierarchy.HierarchyListFilter
import com.atakmap.android.hierarchy.HierarchyListItem
import com.atakmap.android.hierarchy.items.AbstractHierarchyListItem2
import com.atakmap.android.hierarchy.items.MapItemUser
import com.atakmap.android.hierarchy.action.GoTo
import com.atakmap.map.CameraController
import com.atakmap.android.maps.DeepMapItemQuery
import com.atakmap.android.maps.DefaultMapGroup
import com.atakmap.android.maps.MapGroup
import com.atakmap.android.maps.MapItem
import com.atakmap.android.maps.MapView
import com.atakmap.android.maps.Marker
import com.atakmap.android.menu.MapMenuReceiver
import com.atakmap.coremap.maps.coords.GeoBounds
import com.atakmap.coremap.maps.coords.GeoPoint
import com.walaris.airscout.R
import java.util.SortedSet
import java.util.TreeSet

class AirScoutCameraOverlay(
    private val context: Context,
    private val mapView: MapView,
    private val callbacks: Callbacks
) : com.atakmap.android.overlay.AbstractMapOverlay2() {

    interface Callbacks {
        fun onAddCameraRequested()
        fun onPreviewCameraRequested(cameraId: String)
        fun onRemoveCameraRequested(cameraId: String)
    }

    private val group = DefaultMapGroup(context.getString(R.string.overlay_group_airscout)).apply {
        setMetaBoolean("addToObjList", false)
    }

    private val deepQuery = CameraDeepQuery()
    private var overlayListItem: AirScoutOverlayListItem? = null

    private val entries: MutableList<CameraMapEntry> = mutableListOf()

    override fun getIdentifier(): String = "com.walaris.airscout.overlay.cameras"

    override fun getName(): String = context.getString(R.string.overlay_group_airscout)

    override fun getRootGroup(): MapGroup = group

    override fun getQueryFunction(): DeepMapItemQuery = deepQuery

    override fun getListModel(
        adapter: BaseAdapter,
        capabilities: Long,
        prefFilter: HierarchyListFilter?
    ): HierarchyListItem {
        val item = overlayListItem ?: AirScoutOverlayListItem(context, mapView, callbacks)
        overlayListItem = item
        item.update(entries)
        return item
    }

    fun onMarkerAdded(entry: CameraMapEntry) {
        entries.removeAll { it.camera.uid == entry.camera.uid }
        entries.add(entry)
        group.addItem(entry.marker)
        overlayListItem?.update(entries)
    }

    fun onMarkerRemoved(cameraId: String, marker: Marker) {
        entries.removeAll { it.camera.uid == cameraId }
        group.removeItem(marker)
        overlayListItem?.update(entries)
    }

    private inner class CameraDeepQuery : DeepMapItemQuery {
        override fun deepFindItem(metadata: Map<String, String>): MapItem? {
            val uid = metadata["uid"] ?: return null
            return entries.firstOrNull { it.camera.uid == uid }?.marker
        }

        override fun deepFindItems(metadata: Map<String, String>): List<MapItem> {
            val uid = metadata["uid"]
            return if (uid != null) {
                entries.firstOrNull { it.camera.uid == uid }?.marker?.let { listOf(it) }
                    ?: emptyList()
            } else {
                entries.map { it.marker }
            }
        }

        override fun deepFindItems(geobounds: GeoBounds, metadata: Map<String, String>): Collection<MapItem> {
            return entries.filter {
                geobounds.contains(it.marker.point)
            }.map { it.marker }
        }

        override fun deepFindItems(
            location: GeoPoint,
            radius: Double,
            metadata: Map<String, String>
        ): Collection<MapItem> {
            return entries.filter {
                it.marker.point.distanceTo(location) <= radius
            }.map { it.marker }
        }

        override fun deepFindClosestItem(
            location: GeoPoint,
            threshold: Double,
            metadata: Map<String, String>
        ): MapItem? {
            var candidate: MapItem? = null
            var distance = Double.MAX_VALUE
            for (entry in entries) {
                val d = entry.marker.point.distanceTo(location)
                if (d < distance && d <= threshold) {
                    distance = d
                    candidate = entry.marker
                }
            }
            return candidate
        }

        override fun deepHitTest(
            xpos: Int,
            ypos: Int,
            point: GeoPoint,
            view: MapView
        ): MapItem? {
            return entries.firstOrNull { it.marker.point == point }?.marker
        }

        override fun deepHitTestItems(
            xpos: Int,
            ypos: Int,
            point: GeoPoint,
            view: MapView
        ): SortedSet<MapItem> {
            val results = TreeSet<MapItem>(MapItem.ZORDER_HITTEST_COMPARATOR)
            entries.filter { it.marker.point == point }
                .forEach { results.add(it.marker) }
            return results
        }
    }

    private class AirScoutOverlayListItem(
        private val context: Context,
        private val mapView: MapView,
        private val callbacks: Callbacks
    ) : AbstractHierarchyListItem2(), View.OnClickListener {

        private var headerView: View? = null
        private var footerView: View? = null
        private var cachedEntries: List<CameraMapEntry> = emptyList()

        init {
            asyncRefresh = false
        }

        fun update(entries: Collection<CameraMapEntry>) {
            cachedEntries = entries.sortedBy { it.camera.displayName }
            applyFilter()
        }

        override fun getTitle(): String = context.getString(R.string.overlay_group_airscout)

        override fun getIconUri(): String =
            "android.resource://${context.packageName}/${R.drawable.ic_plugin_badge}"

        override fun getUserObject(): Any = this

        override fun isChildSupported(): Boolean = true

        override fun getDescendantCount(): Int = cachedEntries.size

        override fun refreshImpl() {
            applyFilter()
        }

        override fun dispose() {
            disposeChildren()
        }

        override fun hideIfEmpty(): Boolean = true

        override fun isMultiSelectSupported(): Boolean = false

        override fun getHeaderView(): View {
            val header = headerView ?: LayoutInflater.from(context)
                .inflate(R.layout.overlay_airscout_header, mapView, false)
                .also {
                    it.findViewById<Button>(R.id.overlayAddCamera).setOnClickListener(this)
                    headerView = it
                }
            return header
        }

        override fun getFooterView(): View? {
            if (footerView == null) {
                footerView = LayoutInflater.from(context)
                    .inflate(R.layout.overlay_airscout_footer, mapView, false)
            }
            return footerView
        }

        override fun onClick(v: View) {
            if (v.id == R.id.overlayAddCamera) {
                callbacks.onAddCameraRequested()
            }
        }

        private fun applyFilter() {
            val activeFilter = filter
            val items = cachedEntries.map { CameraHierarchyItem(context, mapView, callbacks, it) }
            val filteredItems = if (activeFilter != null) {
                items.filter { activeFilter.accept(it) }
            } else {
                items
            }
            updateChildren(filteredItems)
        }
    }

    private class CameraHierarchyItem(
        private val context: Context,
        private val mapView: MapView,
        private val callbacks: Callbacks,
        private val entry: CameraMapEntry
    ) : AbstractHierarchyListItem2(), MapItemUser, GoTo, View.OnClickListener {

        private var extraView: View? = null

        init {
            asyncRefresh = false
        }

        override fun getTitle(): String = entry.camera.displayName

        override fun getDescription(): String =
            context.getString(R.string.overlay_camera_description, entry.camera.rtspUrl)

        override fun getIconUri(): String =
            "android.resource://${context.packageName}/${R.drawable.ic_plugin_badge_camera}"

        override fun getUserObject(): Any = entry.camera

        override fun isChildSupported(): Boolean = false

        override fun getDescendantCount(): Int = 0

        override fun refreshImpl() {
            // no children to refresh
        }

        override fun hideIfEmpty(): Boolean = false

        override fun getMapItem(): MapItem = entry.marker

        override fun getExtraView(): View {
            val view = extraView ?: LayoutInflater.from(context)
                .inflate(R.layout.overlay_airscout_camera_actions, mapView, false)
                .also {
                    it.findViewById<Button>(R.id.overlayPreview).setOnClickListener(this)
                    it.findViewById<Button>(R.id.overlayRemove).setOnClickListener(this)
                    extraView = it
                }
            return view
        }

        override fun onClick(v: View) {
            when (v.id) {
                R.id.overlayPreview -> callbacks.onPreviewCameraRequested(entry.camera.uid)
                R.id.overlayRemove -> callbacks.onRemoveCameraRequested(entry.camera.uid)
            }
        }

        override fun goTo(select: Boolean): Boolean {
            CameraController.Programmatic.panTo(
                mapView.renderer3,
                entry.marker.point,
                true
            )
            if (select) {
                MapMenuReceiver.getMenuWidget()?.openMenuOnItem(entry.marker)
            }
            return true
        }
    }
}
