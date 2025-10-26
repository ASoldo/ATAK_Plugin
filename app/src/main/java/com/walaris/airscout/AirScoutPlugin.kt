package com.walaris.airscout

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.ImageButton
import androidx.core.graphics.drawable.DrawableCompat
import com.atakmap.app.ATAKActivity
import com.atakmap.android.widgets.LayoutHelper
import com.atakmap.android.widgets.RootLayoutWidget
import com.atakmap.android.cot.detail.CotDetailManager
import com.atakmap.android.importexport.ImporterManager
import com.atakmap.android.importexport.MarshalManager as LegacyMarshalManager
import com.atakmap.android.importfiles.task.ImportFilesTask
import com.atak.plugins.impl.PluginContextProvider
import com.atak.plugins.impl.PluginLayoutInflater
import com.atakmap.android.maps.MapView
import com.atakmap.coremap.maps.coords.GeoPoint
import com.walaris.airscout.core.AxisCamera
import com.walaris.airscout.core.AxisCameraController
import com.walaris.airscout.core.AxisCameraRepository
import com.walaris.airscout.cot.AirScoutDetailHandler
import com.walaris.airscout.map.AirScoutMapController
import com.walaris.airscout.ui.AirScoutPaneController
import com.walaris.airscout.packaging.AirScoutResourceImporter
import com.walaris.airscout.packaging.AirScoutResourceMarshal
import gov.tak.api.plugin.IPlugin
import gov.tak.api.plugin.IServiceController
import gov.tak.api.ui.IHostUIService
import gov.tak.api.ui.Pane
import gov.tak.api.ui.PaneBuilder
import gov.tak.api.ui.ToolbarItem
import gov.tak.api.ui.ToolbarItemAdapter
import gov.tak.platform.marshal.MarshalManager

class AirScoutPlugin(
    private val serviceController: IServiceController
) : IPlugin, AirScoutMapController.Listener {

    private val pluginContext: Context
    private val uiService: IHostUIService?
    private val toolbarItem: ToolbarItem
    private var pane: Pane? = null
    private var paneView: View? = null
    private var statusButtonView: View? = null
    private var rootLayoutWidget: RootLayoutWidget? = null
    private val layoutListener = RootLayoutWidget.OnLayoutChangedListener {
        updateStatusButtonPlacement()
    }

    private val repository: AxisCameraRepository
    private val mapController: AirScoutMapController
    private val cameraController: AxisCameraController
    private val paneController: AirScoutPaneController
    private val detailHandler: AirScoutDetailHandler
    private var importRegistered = false

    init {
        val ctxProvider = serviceController.getService(PluginContextProvider::class.java)
            ?: throw IllegalStateException("PluginContextProvider unavailable")
        pluginContext = ctxProvider.pluginContext.apply {
            setTheme(R.style.ATAKPluginTheme)
        }

        uiService = serviceController.getService(IHostUIService::class.java)
        repository = AxisCameraRepository(pluginContext)
        mapController = AirScoutMapController(pluginContext, repository)
        cameraController = AxisCameraController(pluginContext)
        paneController = AirScoutPaneController(pluginContext, mapController, cameraController, repository)
        detailHandler = AirScoutDetailHandler(repository, mapController)
        registerImporters()
        AirScoutResourceImporter.bind(pluginContext, repository, mapController)

        toolbarItem = ToolbarItem.Builder(
            pluginContext.getString(R.string.app_name),
            MarshalManager.marshal(
                pluginContext.resources.getDrawable(R.drawable.ic_toolbar, pluginContext.theme),
                android.graphics.drawable.Drawable::class.java,
                gov.tak.api.commons.graphics.Bitmap::class.java
            )
        ).setListener(object : ToolbarItemAdapter() {
            override fun onClick(item: ToolbarItem) {
                showPane()
            }
        }).build()
    }

    override fun onStart() {
        uiService?.addToolbarItem(toolbarItem)
        mapController.registerListener(this)
        mapController.start()
        mapController.reloadFromRepository()
        CotDetailManager.getInstance().registerHandler(AirScoutDetailHandler.DETAIL_TAG, detailHandler)
        attachStatusButton()
    }

    override fun onStop() {
        uiService?.removeToolbarItem(toolbarItem)
        mapController.unregisterListener(this)
        mapController.stop()
        paneView?.let { paneController.unbind() }
        cameraController.shutdown()
        CotDetailManager.getInstance().unregisterHandler(detailHandler)
        detachStatusButton()
        pane = null
        paneView = null
    }

    private fun showPane() {
        val host = uiService ?: return
        if (pane == null) {
            val view = PluginLayoutInflater.inflate(pluginContext, R.layout.airscout_pane, null)
            val builtPane = PaneBuilder(view)
                .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.45)
                .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.6)
                .build()
            pane = builtPane
            paneView = MarshalManager.marshal(builtPane, Pane::class.java, View::class.java)
            paneView?.let { paneController.bind(it) }
        } else if (paneView == null) {
            pane?.let {
                paneView = MarshalManager.marshal(it, Pane::class.java, View::class.java)
                paneView?.let { view -> paneController.bind(view) }
            }
        }

        pane?.let {
            if (!host.isPaneVisible(it)) {
                host.showPane(it, null)
            }
        }
    }

    override fun onCameraSelected(camera: AxisCamera) {
        showPane()
        paneController.focusCamera(camera)
    }

    override fun onCameraPreviewRequested(camera: AxisCamera) {
        onCameraSelected(camera)
    }

    override fun onAddCameraRequested(initialLocation: GeoPoint?) {
        showPane()
        paneController.promptNewCamera(initialLocation)
    }

    override fun onCameraRemoved(camera: AxisCamera) {
        // no-op - pane controller reacts to map callback
    }

    override fun onCameraInventoryChanged() {
        // no-op
    }

    override fun onResourceListRequested(targetCamera: AxisCamera?) {
        showPane()
        paneController.showResourceCatalog(targetCamera)
    }

    override fun onCameraEditRequested(camera: AxisCamera) {
        paneController.editCamera(camera)
        showPane()
    }

    private fun registerImporters() {
        if (importRegistered) return
        ImporterManager.registerImporter(AirScoutResourceImporter)
        LegacyMarshalManager.registerMarshal(AirScoutResourceMarshal)
        val mapView = runCatching { MapView.getMapView() }.getOrNull()
        val registerTask = Runnable {
            try {
                ImportFilesTask.registerExtension(".airscout")
            } catch (_: Exception) {
                // ignore registration errors; extension may already be registered
            }
        }
        if (mapView != null) {
            mapView.post(registerTask)
        } else {
            registerTask.run()
        }
        importRegistered = true
    }

    private fun attachStatusButton() {
        val mapView = runCatching { MapView.getMapView() }.getOrNull() ?: return
        mapView.post {
            val activity = mapView.context as? ATAKActivity ?: return@post
            val parent = activity.findViewById<ViewGroup>(com.atakmap.app.R.id.map_parent) ?: return@post
            var container = statusButtonView
            if (container == null || container.parent !== parent) {
                container?.let { (it.parent as? ViewGroup)?.removeView(it) }
                val size = pluginContext.resources.getDimensionPixelSize(R.dimen.airscout_status_button_size)
                container = PluginLayoutInflater.inflate(pluginContext, R.layout.airscout_status_button, parent, false).also {
                    val params = RelativeLayout.LayoutParams(size, size)
                    parent.addView(it, params)
                    statusButtonView = it
                }
            }
            container?.findViewById<View>(R.id.airscoutStatusButton)?.setOnClickListener { showPane() }
            rootLayoutWidget = mapView.getComponentExtra("rootLayoutWidget") as? RootLayoutWidget
            rootLayoutWidget?.addOnLayoutChangedListener(layoutListener)
            updateStatusButtonPlacement()
            updateStatusButtonState(true)
        }
    }

    private fun detachStatusButton() {
        val view = statusButtonView ?: return
        view.post {
            val parent = view.parent as? ViewGroup
            parent?.removeView(view)
            rootLayoutWidget?.removeOnLayoutChangedListener(layoutListener)
            rootLayoutWidget = null
            statusButtonView = null
        }
    }

    private fun updateStatusButtonState(active: Boolean) {
        val buttonView = statusButtonView?.findViewById<ImageButton>(R.id.airscoutStatusButton) ?: return
        val color = pluginContext.getColor(if (active) R.color.airscout_status_active else R.color.airscout_status_inactive)
        buttonView.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
    }

    private fun updateStatusButtonPlacement() {
        val mapView = runCatching { MapView.getMapView() }.getOrNull() ?: return
        val container = statusButtonView ?: return
        val params = container.layoutParams as? RelativeLayout.LayoutParams ?: return
        val layoutWidget = rootLayoutWidget ?: return

        val mapRect = Rect(0, 0, mapView.width, mapView.height)
        if (mapRect.width() <= 0 || mapRect.height() <= 0) return

        val occupied = layoutWidget.getOccupiedBounds(true)
        val helper = LayoutHelper(mapRect, occupied)
        var bounds = LayoutHelper.getBounds(container)
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            val size = pluginContext.resources.getDimensionPixelSize(R.dimen.airscout_status_button_size)
            bounds = Rect(0, 0, size, size)
        }
        bounds = helper.findBestPosition(bounds, RootLayoutWidget.BOTTOM_RIGHT)

        params.leftMargin = bounds.left
        params.topMargin = bounds.top
        container.layoutParams = params
        container.requestLayout()
    }
}
