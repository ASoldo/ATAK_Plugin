package com.walaris.airscout

import android.content.Context
import android.view.View
import com.atak.plugins.impl.PluginContextProvider
import com.atak.plugins.impl.PluginLayoutInflater
import com.atakmap.coremap.maps.coords.GeoPoint
import com.walaris.airscout.core.AxisCamera
import com.walaris.airscout.core.AxisCameraController
import com.walaris.airscout.core.AxisCameraRepository
import com.walaris.airscout.map.AirScoutMapController
import com.walaris.airscout.ui.AirScoutPaneController
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

    private val repository: AxisCameraRepository
    private val mapController: AirScoutMapController
    private val cameraController: AxisCameraController
    private val paneController: AirScoutPaneController

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
        paneController = AirScoutPaneController(pluginContext, mapController, cameraController)

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
    }

    override fun onStop() {
        uiService?.removeToolbarItem(toolbarItem)
        mapController.unregisterListener(this)
        mapController.stop()
        paneView?.let { paneController.unbind() }
        cameraController.shutdown()
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
        showPane()
        paneController.editCamera(camera)
    }
}
