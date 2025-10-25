package com.walaris.airscout.map

import android.content.Context
import android.graphics.Color
import com.atakmap.android.maps.MapItem
import com.atakmap.android.maps.MapView
import com.atakmap.android.menu.MapMenuButtonWidget
import com.atakmap.android.menu.MapMenuFactory
import com.atakmap.android.menu.MapMenuWidget
import com.atakmap.android.widgets.AbstractButtonWidget
import com.atakmap.android.widgets.WidgetBackground
import com.atakmap.android.widgets.WidgetIcon
import com.atakmap.android.widgets.MapWidget
import com.atakmap.android.menu.PluginMenuParser
import com.atakmap.android.maps.MapDataRef
import gov.tak.api.widgets.IMapMenuButtonWidget

class AirScoutMenuFactory(
    private val context: Context,
    private val callbacks: Callback
) : MapMenuFactory {

    interface Callback {
        fun onMenuAction(item: MapItem, action: MenuAction)
    }

    enum class MenuAction {
        CONTROL,
        CENTER
    }

    private val mapView: MapView = MapView.getMapView()
    private val appContext = mapView.context
    private val buttonBackground: WidgetBackground = WidgetBackground.Builder()
        .setColor(0, Color.parseColor("#FF2D2D2D"))
        .setColor(
            AbstractButtonWidget.STATE_PRESSED,
            Color.parseColor("#FF4B6EA8")
        )
        .setColor(
            AbstractButtonWidget.STATE_SELECTED,
            Color.parseColor("#FF4B6EA8")
        )
        .build()

    override fun create(mapItem: MapItem): MapMenuWidget? {
        if (mapItem.type != AirScoutMapController.CAMERA_MARKER_TYPE) {
            return null
        }

        val menu = MapMenuWidget()
        menu.addWidget(createButton(MenuAction.CONTROL, "icons/video.png"))
        menu.addWidget(createButton(MenuAction.CENTER, "icons/center.png"))
        return menu
    }

    private fun createButton(action: MenuAction, iconPath: String): MapWidget {
        val widget = MapMenuButtonWidget(appContext)
        widget.setIcon(buildIcon(iconPath))
        widget.setBackground(buttonBackground)
        widget.setButtonSize(BUTTON_SPAN, BUTTON_WIDTH)
        widget.setLayoutWeight(BUTTON_SPAN)
        widget.setOnButtonClickHandler(object : IMapMenuButtonWidget.OnButtonClickHandler {
            override fun isSupported(o: Any?): Boolean =
                o is MapItem && o.type == AirScoutMapController.CAMERA_MARKER_TYPE

            override fun performAction(o: Any?) {
                val item = o as? MapItem ?: return
                callbacks.onMenuAction(item, action)
            }
        })
        return widget
    }

    private fun buildIcon(path: String): WidgetIcon {
        val pluginAsset = PluginMenuParser.getItem(context, path)
        val uri = if (pluginAsset.isNotEmpty()) pluginAsset else "asset:///$path"
        val mapDataRef = MapDataRef.parseUri(uri)
        return WidgetIcon.Builder()
            .setImageRef(0, mapDataRef)
            .setAnchor(32, 32)
            .setSize(64, 64)
            .build()
    }

    companion object {
        private const val BUTTON_SPAN = 36f
        private const val BUTTON_WIDTH = 90f
    }
}
