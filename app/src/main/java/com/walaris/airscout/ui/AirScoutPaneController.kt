package com.walaris.airscout.ui

import android.app.AlertDialog
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.atakmap.android.maps.MapView
import com.atakmap.coremap.maps.coords.GeoPoint
import com.walaris.airscout.R
import com.walaris.airscout.core.AxisCamera
import com.walaris.airscout.core.AxisCameraController
import com.walaris.airscout.databinding.AirscoutPaneBinding
import com.walaris.airscout.map.AirScoutMapController
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class AirScoutPaneController(
    private val context: Context,
    private val mapController: AirScoutMapController,
    private val cameraController: AxisCameraController
) : DualJoystickView.JoystickListener,
    AirScoutMapController.Listener {

    private enum class ContentMode { LIST, CONTROL }

    private var binding: AirscoutPaneBinding? = null
    private var contentMode: ContentMode = ContentMode.LIST
    private var currentCamera: AxisCamera? = null
    private var pendingSelectUid: String? = null

    private val resourceAdapter = ResourceAdapter(object : ResourceAdapter.Listener {
        override fun onPreview(camera: AxisCamera) {
            binding?.root?.post { mapController.selectCamera(camera.uid, centerOnMap = true) }
        }

        override fun onEdit(camera: AxisCamera) {
            binding?.root?.post { promptResourceDialog(camera, null) }
        }

        override fun onDelete(camera: AxisCamera) {
            binding?.root?.post { deleteResource(camera) }
        }
    })

    fun bind(root: View) {
        binding = AirscoutPaneBinding.bind(root)
        mapController.registerListener(this)
        setupListUi()
        setupControlUi()
        showList()
        refreshResourceList()
    }

    fun unbind() {
        stopStream()
        binding?.joystickOverlay?.listener = null
        binding = null
        mapController.unregisterListener(this)
    }

    private fun setupListUi() {
        val ui = binding ?: return
        ui.resourceRecyclerView.layoutManager = LinearLayoutManager(context)
        ui.resourceRecyclerView.adapter = resourceAdapter
        ui.addResourceButton.setOnClickListener {
            promptResourceDialog(null, currentMapCenter())
        }
    }

    private fun setupControlUi() {
        val ui = binding ?: return
        ui.joystickOverlay.listener = this
        ui.backButton.setOnClickListener { showList() }
    }

    private fun showList(selectUid: String? = null) {
        val ui = binding ?: return
        if (contentMode == ContentMode.CONTROL) {
            stopStream()
        }
        contentMode = ContentMode.LIST
        ui.contentSwitcher.displayedChild = ContentMode.LIST.ordinal
        ui.statusMessage.text = context.getString(R.string.status_no_cameras)
        val selection = selectUid ?: pendingSelectUid
        pendingSelectUid = selection
        currentCamera = selection?.let { mapController.getCamera(it) }
        resourceAdapter.setSelected(selection)
    }

    private fun showControl(camera: AxisCamera) {
        val ui = binding ?: return
        contentMode = ContentMode.CONTROL
        currentCamera = camera
        resourceAdapter.setSelected(camera.uid)
        ui.contentSwitcher.displayedChild = ContentMode.CONTROL.ordinal
        ui.controlTitle.text = camera.displayName
        if (camera.description.isBlank()) {
            ui.controlSubtitle.visibility = View.GONE
        } else {
            ui.controlSubtitle.visibility = View.VISIBLE
            ui.controlSubtitle.text = camera.description
        }
        updateControlInfo(camera)
        updateStatus(context.getString(R.string.status_selected_camera, camera.displayName))
        startStream(camera)
    }

    private fun updateControlInfo(camera: AxisCamera) {
        val ui = binding ?: return
        val stream = camera.rtspUrl.takeIf { it.isNotBlank() }
        ui.controlStreamInfo.text = stream?.let {
            context.getString(R.string.control_info_stream, it)
        } ?: context.getString(R.string.control_info_stream_placeholder)

        val controlEndpoint = camera.controlUrl.takeIf { it.isNotBlank() }
        ui.controlControlInfo.text = controlEndpoint?.let {
            context.getString(R.string.control_info_control, it)
        } ?: context.getString(R.string.control_info_control_placeholder)

        val location = if (camera.latitude != 0.0 || camera.longitude != 0.0) {
            val altitudeSuffix = camera.altitude.takeIf { abs(it) > 0.01 }
                ?.let { String.format(Locale.US, ", %.1f m", it) } ?: ""
            context.getString(
                R.string.control_info_location,
                camera.latitude,
                camera.longitude,
                altitudeSuffix
            )
        } else {
            context.getString(R.string.control_info_location_placeholder)
        }
        ui.controlLocationInfo.text = location

        ui.controlFrustumInfo.text = when (camera.frustumMode) {
            AxisCamera.FrustumMode.CIRCLE -> {
                val radius = (camera.frustumRadiusMeters ?: camera.frustumRangeMeters)?.takeIf { it > 0 }
                radius?.let {
                    context.getString(R.string.control_info_frustum_circle, it)
                } ?: context.getString(R.string.control_info_frustum_placeholder)
            }
            AxisCamera.FrustumMode.CONE -> {
                val range = camera.frustumRangeMeters
                val h = camera.frustumHorizontalFovDeg
                val v = camera.frustumVerticalFovDeg
                val bearing = camera.frustumBearingDeg
                if (range != null && h != null && v != null && bearing != null) {
                    context.getString(
                        R.string.control_info_frustum_cone,
                        range,
                        h,
                        v,
                        bearing
                    )
                } else {
                    context.getString(R.string.control_info_frustum_placeholder)
                }
            }
        }
    }

    private fun refreshResourceList(selectUid: String? = null) {
        val cameras = mapController.listCameras()
        resourceAdapter.update(cameras)
        updateEmptyState(cameras.isEmpty())
        if (cameras.isEmpty()) {
            if (contentMode == ContentMode.CONTROL) {
                showList()
            }
            return
        }
        val targetUid = selectUid ?: pendingSelectUid ?: currentCamera?.uid
        resourceAdapter.setSelected(targetUid)
        if (targetUid != null) {
            pendingSelectUid = null
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        val ui = binding ?: return
        ui.emptyMessage.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    private fun promptResourceDialog(existing: AxisCamera?, initialLocation: GeoPoint?) {
        val hostContext = runCatching { MapView.getMapView().context }.getOrNull()
        if (hostContext == null) {
            Toast.makeText(context, R.string.resource_dialog_error_context, Toast.LENGTH_SHORT).show()
            return
        }

        val pluginThemedContext = ContextThemeWrapper(context, R.style.ATAKPluginTheme)
        val dialogView = LayoutInflater.from(pluginThemedContext)
            .inflate(R.layout.dialog_airscout_resource, null, false)
        val alertContext = ContextThemeWrapper(hostContext, R.style.ATAKPluginTheme)

        val nameInput = dialogView.findViewById<EditText>(R.id.inputName)
        val descriptionInput = dialogView.findViewById<EditText>(R.id.inputDescription)
        val streamInput = dialogView.findViewById<EditText>(R.id.inputStreamUrl)
        val controlInput = dialogView.findViewById<EditText>(R.id.inputControlUrl)
        val latInput = dialogView.findViewById<EditText>(R.id.inputLatitude)
        val lonInput = dialogView.findViewById<EditText>(R.id.inputLongitude)
        val altInput = dialogView.findViewById<EditText>(R.id.inputAltitude)
        val useCenterButton = dialogView.findViewById<View>(R.id.useMapCenterButton)
        val frustumGroup = dialogView.findViewById<RadioGroup>(R.id.inputFrustumGroup)
        val circleFields = dialogView.findViewById<LinearLayout>(R.id.circleFields)
        val coneFields = dialogView.findViewById<LinearLayout>(R.id.coneFields)
        val radiusInput = dialogView.findViewById<EditText>(R.id.inputRadius)
        val rangeInput = dialogView.findViewById<EditText>(R.id.inputRange)
        val horizontalInput = dialogView.findViewById<EditText>(R.id.inputHorizontalFov)
        val verticalInput = dialogView.findViewById<EditText>(R.id.inputVerticalFov)
        val bearingInput = dialogView.findViewById<EditText>(R.id.inputBearing)

        val location = existing?.let { GeoPoint(it.latitude, it.longitude, it.altitude) } ?: initialLocation
        location?.let {
            latInput.setText(it.latitude.toString())
            lonInput.setText(it.longitude.toString())
            if (!it.altitude.isNaN()) {
                altInput.setText(it.altitude.toString())
            }
        }

        existing?.let { camera ->
            nameInput.setText(camera.displayName)
            descriptionInput.setText(camera.description)
            streamInput.setText(camera.rtspUrl)
            controlInput.setText(camera.controlUrl)
            if (camera.latitude != 0.0 || camera.longitude != 0.0) {
                latInput.setText(camera.latitude.toString())
                lonInput.setText(camera.longitude.toString())
            }
            if (camera.altitude != 0.0) {
                altInput.setText(camera.altitude.toString())
            }
            when (camera.frustumMode) {
                AxisCamera.FrustumMode.CIRCLE -> {
                    frustumGroup.check(R.id.radioFrustumCircle)
                    radiusInput.setText(
                        (camera.frustumRadiusMeters ?: camera.frustumRangeMeters)?.toString() ?: ""
                    )
                    circleFields.visibility = View.VISIBLE
                    coneFields.visibility = View.GONE
                }
                AxisCamera.FrustumMode.CONE -> {
                    frustumGroup.check(R.id.radioFrustumCone)
                    rangeInput.setText(camera.frustumRangeMeters?.toString() ?: "")
                    horizontalInput.setText(camera.frustumHorizontalFovDeg?.toString() ?: "")
                    verticalInput.setText(camera.frustumVerticalFovDeg?.toString() ?: "")
                    bearingInput.setText(camera.frustumBearingDeg?.toString() ?: "")
                    circleFields.visibility = View.GONE
                    coneFields.visibility = View.VISIBLE
                }
            }
        } ?: run {
            frustumGroup.check(R.id.radioFrustumCircle)
            circleFields.visibility = View.VISIBLE
            coneFields.visibility = View.GONE
        }

        frustumGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.radioFrustumCircle) {
                circleFields.visibility = View.VISIBLE
                coneFields.visibility = View.GONE
            } else {
                circleFields.visibility = View.GONE
                coneFields.visibility = View.VISIBLE
            }
        }

        useCenterButton.setOnClickListener {
            currentMapCenter()?.let { center ->
                latInput.setText(center.latitude.toString())
                lonInput.setText(center.longitude.toString())
                if (!center.altitude.isNaN()) {
                    altInput.setText(center.altitude.toString())
                }
            }
        }

        val dialogTitle = pluginThemedContext.getText(
            if (existing != null) R.string.resource_dialog_title_edit
            else R.string.resource_dialog_title_add
        )

        val dialog = AlertDialog.Builder(alertContext)
            .setTitle(dialogTitle)
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val streamUrl = streamInput.text.toString().trim()
                val controlUrl = controlInput.text.toString().trim()
                if (name.isEmpty() || streamUrl.isEmpty()) {
                    Toast.makeText(context, R.string.resource_dialog_error_required, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val latitude = latInput.text.toString().trim().toDoubleOrNull()
                val longitude = lonInput.text.toString().trim().toDoubleOrNull()
                if (latitude == null || longitude == null) {
                    Toast.makeText(context, R.string.resource_dialog_error_coordinates, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val altitude = altInput.text.toString().trim().toDoubleOrNull() ?: 0.0

                val mode = if (frustumGroup.checkedRadioButtonId == R.id.radioFrustumCircle) {
                    AxisCamera.FrustumMode.CIRCLE
                } else {
                    AxisCamera.FrustumMode.CONE
                }

                val camera = (existing?.copy() ?: AxisCamera(
                    displayName = name,
                    description = "",
                    latitude = latitude,
                    longitude = longitude,
                    altitude = altitude,
                    rtspUrl = streamUrl,
                    controlUrl = controlUrl
                ))

                camera.displayName = name
                camera.description = descriptionInput.text.toString().trim()
                camera.rtspUrl = streamUrl
                camera.controlUrl = controlUrl
                camera.latitude = latitude
                camera.longitude = longitude
                camera.altitude = altitude

                when (mode) {
                    AxisCamera.FrustumMode.CIRCLE -> {
                        val radius = radiusInput.text.toString().trim().toDoubleOrNull()
                        if (radius == null || radius <= 0.0) {
                            Toast.makeText(context, R.string.resource_dialog_error_frustum, Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        camera.frustumMode = AxisCamera.FrustumMode.CIRCLE
                        camera.frustumRadiusMeters = radius
                        camera.frustumRangeMeters = radius
                        camera.frustumHorizontalFovDeg = null
                        camera.frustumVerticalFovDeg = null
                        camera.frustumBearingDeg = null
                    }
                    AxisCamera.FrustumMode.CONE -> {
                        val range = rangeInput.text.toString().trim().toDoubleOrNull()
                        val h = horizontalInput.text.toString().trim().toDoubleOrNull()
                        val v = verticalInput.text.toString().trim().toDoubleOrNull()
                        val bearing = bearingInput.text.toString().trim().toDoubleOrNull()
                        if (range == null || range <= 0.0 || h == null || v == null || bearing == null) {
                            Toast.makeText(context, R.string.resource_dialog_error_frustum, Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        camera.frustumMode = AxisCamera.FrustumMode.CONE
                        camera.frustumRangeMeters = range
                        camera.frustumHorizontalFovDeg = h
                        camera.frustumVerticalFovDeg = v
                        camera.frustumBearingDeg = bearing
                        camera.frustumRadiusMeters = null
                    }
                }

                mapController.addOrUpdateCamera(camera)
                pendingSelectUid = camera.uid
                refreshResourceList(camera.uid)
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun deleteResource(camera: AxisCamera) {
        val hostContext = runCatching { MapView.getMapView().context }.getOrNull()
        val dialogContext = if (hostContext != null) ContextThemeWrapper(hostContext, R.style.ATAKPluginTheme) else ContextThemeWrapper(context, R.style.ATAKPluginTheme)
        val titleText = context.getText(R.string.resource_delete_confirm_title)
        val messageText = context.getString(R.string.resource_delete_confirm_message, camera.displayName)

        AlertDialog.Builder(dialogContext)
            .setTitle(titleText)
            .setMessage(messageText)
            .setNegativeButton(context.getText(R.string.resource_delete_confirm_negative), null)
            .setPositiveButton(context.getText(R.string.resource_delete_confirm_positive)) { _, _ ->
                cameraController.stopStream()
                cameraController.closeEventChannel()
                mapController.removeCamera(camera.uid)
            }
            .show()
    }

    private fun startStream(camera: AxisCamera) {
        val ui = binding ?: return
        cameraController.closeEventChannel()
        cameraController.startStream(ui.videoPlayerView, ui.videoImageView, camera) { error ->
            updateStatus(
                context.getString(
                    R.string.status_stream_error,
                    error.localizedMessage ?: ""
                )
            )
        }
        cameraController.connectEventChannel(camera) { }
    }

    private fun stopStream() {
        cameraController.stopStream()
        cameraController.closeEventChannel()
        cameraController.detachPlayerView()
    }

    private fun updateStatus(message: String) {
        binding?.statusMessage?.text = message
    }

    // Joystick listener
    override fun onLeftJoystickChanged(x: Float, y: Float, active: Boolean) {
        currentCamera?.let {
            if (active) {
                cameraController.sendMoveCommand(it, x, -y)
            } else {
                cameraController.stopMotion(it)
            }
        }
    }

    override fun onRightJoystickChanged(x: Float, y: Float, active: Boolean) {
        currentCamera?.let {
            if (active) {
                cameraController.sendZoomCommand(it, -y)
            } else {
                cameraController.stopMotion(it)
            }
        }
    }

    // MapController listener
    override fun onCameraSelected(camera: AxisCamera) {
        binding?.root?.post {
            showControl(camera)
            refreshResourceList(camera.uid)
        }
    }

    fun focusCamera(camera: AxisCamera) {
        binding?.root?.post {
            showControl(camera)
            refreshResourceList(camera.uid)
        }
    }

    override fun onCameraPreviewRequested(camera: AxisCamera) {
        // already handled via onCameraSelected when preview is requested
    }

    override fun onAddCameraRequested(initialLocation: GeoPoint?) {
        promptResourceDialog(null, initialLocation ?: currentMapCenter())
    }

    override fun onCameraRemoved(camera: AxisCamera) {
        binding?.root?.post {
            if (currentCamera?.uid == camera.uid) {
                showList()
            }
            refreshResourceList()
            Toast.makeText(context, context.getString(R.string.toast_camera_removed), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCameraInventoryChanged() {
        binding?.root?.post {
            refreshResourceList(currentCamera?.uid)
        }
    }

    override fun onResourceListRequested(targetCamera: AxisCamera?) {
        // handled through plugin controller
    }

    override fun onCameraEditRequested(camera: AxisCamera) {
        // handled through plugin controller
    }

    fun showResourceCatalog(target: AxisCamera?) {
        binding?.root?.post {
            val targetUid = target?.uid
            pendingSelectUid = targetUid
            showList(targetUid)
            refreshResourceList(targetUid)
        }
    }

    fun editCamera(camera: AxisCamera) {
        binding?.root?.post {
            pendingSelectUid = camera.uid
            showList(camera.uid)
            refreshResourceList(camera.uid)
            promptResourceDialog(camera, null)
        }
    }

    fun promptNewCamera(initialLocation: GeoPoint?) {
        promptResourceDialog(null, initialLocation ?: currentMapCenter())
    }

    private fun currentMapCenter(): GeoPoint? = try {
        MapView.getMapView().centerPoint.get()
    } catch (_: Exception) {
        null
    }

    private class ResourceAdapter(
        private val listener: Listener
    ) : RecyclerView.Adapter<ResourceAdapter.ResourceViewHolder>() {

        interface Listener {
            fun onPreview(camera: AxisCamera)
            fun onEdit(camera: AxisCamera)
            fun onDelete(camera: AxisCamera)
        }

        private val items: MutableList<AxisCamera> = mutableListOf()
        private var selectedUid: String? = null

        fun update(cameras: List<AxisCamera>) {
            items.clear()
            items.addAll(cameras.sortedBy { it.displayName.lowercase(Locale.US) })
            notifyDataSetChanged()
        }

        fun setSelected(uid: String?) {
            selectedUid = uid
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResourceViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_airscout_resource, parent, false)
            return ResourceViewHolder(view)
        }

        override fun onBindViewHolder(holder: ResourceViewHolder, position: Int) {
            val camera = items[position]
            holder.bind(camera, camera.uid == selectedUid, listener)
        }

        override fun getItemCount(): Int = items.size

        class ResourceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val nameView: TextView = itemView.findViewById(R.id.resourceName)
            private val descriptionView: TextView = itemView.findViewById(R.id.resourceDescription)
            private val streamView: TextView = itemView.findViewById(R.id.resourceStream)
            private val controlView: TextView = itemView.findViewById(R.id.resourceControl)
            private val locationView: TextView = itemView.findViewById(R.id.resourceLocation)
            private val frustumView: TextView = itemView.findViewById(R.id.resourceFrustum)
            private val previewButton: ImageButton = itemView.findViewById(R.id.previewButton)
            private val editButton: ImageButton = itemView.findViewById(R.id.editButton)
            private val deleteButton: ImageButton = itemView.findViewById(R.id.deleteButton)
            private val root: View = itemView.findViewById(R.id.resourceRoot)

            fun bind(
                camera: AxisCamera,
                selected: Boolean,
                listener: Listener
            ) {
                val ctx = itemView.context
                nameView.text = camera.displayName
                if (camera.description.isBlank()) {
                    descriptionView.visibility = View.GONE
                } else {
                    descriptionView.visibility = View.VISIBLE
                    descriptionView.text = camera.description
                }

                streamView.text = camera.rtspUrl.ifBlank { ctx.getString(R.string.control_info_stream_placeholder) }
                controlView.text = camera.controlUrl.ifBlank { ctx.getString(R.string.control_info_control_placeholder) }

                val altitudeSuffix = camera.altitude.takeIf { abs(it) > 0.01 }
                    ?.let { String.format(Locale.US, ", %.1f m", it) } ?: ""
                locationView.text = String.format(
                    Locale.US,
                    "Lat %.5f°, Lon %.5f°%s",
                    camera.latitude,
                    camera.longitude,
                    altitudeSuffix
                )

                frustumView.text = when (camera.frustumMode) {
                    AxisCamera.FrustumMode.CIRCLE -> {
                        val radius = (camera.frustumRadiusMeters ?: camera.frustumRangeMeters)?.roundToInt()
                        if (radius != null && radius > 0) {
                            ctx.getString(R.string.resource_frustum_circle_summary, radius)
                        } else {
                            ctx.getString(R.string.control_info_frustum_placeholder)
                        }
                    }
                    AxisCamera.FrustumMode.CONE -> {
                        val range = camera.frustumRangeMeters?.roundToInt()
                        val h = camera.frustumHorizontalFovDeg?.roundToInt()
                        val v = camera.frustumVerticalFovDeg?.roundToInt()
                        val b = camera.frustumBearingDeg?.roundToInt()
                        if (range != null && h != null && v != null && b != null) {
                            ctx.getString(R.string.resource_frustum_cone_summary, range, h, v, b)
                        } else {
                            ctx.getString(R.string.control_info_frustum_placeholder)
                        }
                    }
                }

                root.alpha = if (selected) 1f else 0.85f

                previewButton.setOnClickListener { listener.onPreview(camera) }
                editButton.setOnClickListener { listener.onEdit(camera) }
                deleteButton.setOnClickListener { listener.onDelete(camera) }
            }
        }
    }
}
