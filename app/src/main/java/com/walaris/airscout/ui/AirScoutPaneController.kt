package com.walaris.airscout.ui

import android.app.AlertDialog
import android.content.Context
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.atakmap.android.maps.MapView
import com.atakmap.android.missionpackage.api.MissionPackageApi
import com.atakmap.android.missionpackage.file.MissionPackageBuilder
import com.atakmap.android.missionpackage.file.MissionPackageManifest
import com.atakmap.android.importfiles.sort.ImportMissionPackageSort.ImportMissionV1PackageSort
import com.walaris.airscout.core.AxisCameraRepository
import com.walaris.airscout.core.PluginStorage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
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
    private val cameraController: AxisCameraController,
    private val repository: AxisCameraRepository
) : DualJoystickView.JoystickListener,
    AirScoutMapController.Listener {

    private enum class ContentMode { LIST, CONTROL }

    private var binding: AirscoutPaneBinding? = null
    private var contentMode: ContentMode = ContentMode.LIST
    private var currentCamera: AxisCamera? = null
    private var pendingSelectUid: String? = null
    private var pendingEditCamera: AxisCamera? = null
    private var currentInfoDetails: List<ControlInfo> = emptyList()

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

        val editTarget = pendingEditCamera
        if (editTarget == null) {
            showList()
            refreshResourceList()
        } else {
            pendingEditCamera = null
            pendingSelectUid = editTarget.uid
            showList(editTarget.uid)
            refreshResourceList(editTarget.uid)
            promptResourceDialog(editTarget, null)
        }
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
        ui.infoButton.setOnClickListener { showControlInfoDialog() }
        ui.infoButton.visibility = View.GONE
    }

    private fun showList(selectUid: String? = null) {
        val ui = binding ?: return
        if (contentMode == ContentMode.CONTROL) {
            stopStream()
        }
        contentMode = ContentMode.LIST
        ui.contentSwitcher.displayedChild = ContentMode.LIST.ordinal
        ui.statusMessage.text = context.getString(R.string.status_no_cameras)
        currentCamera = null
        pendingSelectUid = selectUid
        resourceAdapter.setSelected(null)
        currentInfoDetails = emptyList()
        ui.infoButton.visibility = View.GONE
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
        ui.infoButton.visibility = View.VISIBLE
        updateControlInfo(camera)
        updateStatus("")
        startStream(camera)
    }

    private fun updateControlInfo(camera: AxisCamera) {
        val stream = camera.rtspUrl.takeIf { it.isNotBlank() }
        val streamText = stream?.let {
            context.getString(R.string.control_info_stream, it)
        } ?: context.getString(R.string.control_info_stream_placeholder)

        val controlEndpoint = camera.controlUrl.takeIf { it.isNotBlank() }
        val controlText = controlEndpoint?.let {
            context.getString(R.string.control_info_control, it)
        } ?: context.getString(R.string.control_info_control_placeholder)

        val locationText = if (camera.latitude != 0.0 || camera.longitude != 0.0) {
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

        val frustumText = when (camera.frustumMode) {
            AxisCamera.FrustumMode.CIRCLE -> {
                val radius = (camera.frustumRadiusMeters ?: camera.frustumRangeMeters)?.takeIf { it > 0 }
                radius?.let {
                    val zoomLevel = camera.frustumZoomLevel?.takeIf { z -> z > 0.0 }
                    if (zoomLevel != null) {
                        context.getString(
                            R.string.control_info_frustum_circle_zoom,
                            it,
                            (zoomLevel * 100).coerceAtMost(100.0)
                        )
                    } else {
                        context.getString(R.string.control_info_frustum_circle, it)
                    }
                } ?: context.getString(R.string.control_info_frustum_placeholder)
            }
            AxisCamera.FrustumMode.CONE -> {
                val range = camera.frustumRangeMeters
                val h = camera.frustumHorizontalFovDeg
                val v = camera.frustumVerticalFovDeg
                val bearing = camera.frustumBearingDeg
                if (range != null && h != null && v != null && bearing != null) {
                    val zoomLevel = camera.frustumZoomLevel?.takeIf { z -> z > 0.0 }
                    if (zoomLevel != null) {
                        context.getString(
                            R.string.control_info_frustum_cone_zoom,
                            range,
                            h,
                            v,
                            bearing,
                            (zoomLevel * 100).coerceAtMost(100.0)
                        )
                    } else {
                        context.getString(
                            R.string.control_info_frustum_cone,
                            range,
                            h,
                            v,
                            bearing
                        )
                    }
                } else {
                    context.getString(R.string.control_info_frustum_placeholder)
                }
            }
        }

        currentInfoDetails = listOf(
            ControlInfo(R.drawable.ic_stream, streamText),
            ControlInfo(R.drawable.ic_control, controlText),
            ControlInfo(R.drawable.ic_location, locationText),
            ControlInfo(R.drawable.ic_frustum, frustumText)
        )
    }

    private fun getExportsDirectory(): File =
        PluginStorage.resolveDirectory(context, EXPORT_SUBDIR)

    private fun exportResources(showToast: Boolean): File? {
        val cameras = mapController.listCameras()
        if (cameras.isEmpty()) {
            if (showToast) {
                Toast.makeText(context, R.string.resource_export_none, Toast.LENGTH_SHORT).show()
            }
            return null
        }
        val exportDir = getExportsDirectory()
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val snapshot = snapshotResources(exportDir, timestamp)
        if (snapshot == null) {
            if (showToast) {
                Toast.makeText(context, R.string.resource_export_failure, Toast.LENGTH_SHORT).show()
            }
            return null
        }

        val packageFile = buildMissionPackage(snapshot, timestamp, cameras)
        if (showToast) {
            if (packageFile != null) {
                Toast.makeText(
                    context,
                    context.getString(R.string.resource_export_package_success, packageFile.absolutePath),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.resource_export_partial, snapshot.absolutePath),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        return snapshot
    }

    private fun snapshotResources(targetDir: File, timestamp: String): File? {
        val snapshot = File(targetDir, "airscout_resources_$timestamp.airscout")
        return if (repository.exportTo(snapshot)) snapshot else null
    }

    private fun buildMissionPackage(
        snapshot: File,
        timestamp: String,
        cameras: List<AxisCamera>
    ): File? {
        if (cameras.isEmpty()) return null
        val mapView = runCatching { MapView.getMapView() }.getOrNull() ?: return null
        val packageFile = File(getExportsDirectory(), "airscout_package_$timestamp.zip")
        val manifest = MissionPackageManifest().apply {
            setName(context.getString(R.string.resource_share_package_name, timestamp))
            setPath(packageFile.absolutePath)
            cameras.forEach { addMapItem(it.uid) }
        }
        if (!manifest.addFile(snapshot, null)) {
            return null
        }
        val builtPath = runCatching {
            MissionPackageBuilder(null, manifest, mapView.rootGroup).build()
        }.getOrNull() ?: return null
        return File(builtPath)
    }

    private fun promptImportResources() {
        val exportDir = getExportsDirectory()
        val candidates = exportDir.listFiles { file ->
            if (!file.isFile) return@listFiles false
            when (file.extension.lowercase(Locale.US)) {
                "airscout", "json", "zip", "mpk", "mpkg" -> true
                else -> false
            }
        }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        if (candidates.isEmpty()) {
            Toast.makeText(context, R.string.resource_import_none, Toast.LENGTH_SHORT).show()
            return
        }
        val names = candidates.map { it.name }.toTypedArray()
        val dialogContext = ContextThemeWrapper(
            binding?.root?.context ?: runCatching { MapView.getMapView().context }.getOrNull() ?: context,
            R.style.ATAKPluginTheme
        )
        AlertDialog.Builder(dialogContext)
            .setTitle(R.string.resource_import_title)
            .setItems(names) { dialog, index ->
                dialog.dismiss()
                val file = candidates[index]
                AlertDialog.Builder(dialogContext)
                    .setTitle(R.string.resource_import_title)
                    .setMessage(R.string.resource_import_mode_message)
                    .setPositiveButton(R.string.resource_import_replace) { _, _ ->
                        importResources(file, replaceExisting = true)
                    }
                    .setNegativeButton(R.string.resource_import_merge) { _, _ ->
                        importResources(file, replaceExisting = false)
                    }
                    .setNeutralButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importResources(source: File, replaceExisting: Boolean) {
        when (source.extension.lowercase(Locale.US)) {
            "airscout", "json" -> importResourceSnapshot(source, replaceExisting)
            "zip", "mpk", "mpkg" -> importMissionPackage(source, replaceExisting)
            else -> Toast.makeText(context, R.string.resource_import_unsupported, Toast.LENGTH_SHORT).show()
        }
    }

    private fun importResourceSnapshot(source: File, replaceExisting: Boolean) {
        val result = repository.importFrom(source, replaceExisting)
        if (result.total == 0) {
            Toast.makeText(context, R.string.resource_import_failure, Toast.LENGTH_SHORT).show()
            return
        }
        mapController.reloadFromRepository()
        Toast.makeText(
            context,
            context.getString(R.string.resource_import_success, result.total),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun importMissionPackage(source: File, replaceExisting: Boolean) {
        val mapView = runCatching { MapView.getMapView() }.getOrNull()
        val hostContext = mapView?.context ?: binding?.root?.context ?: context
        val importer = ImportMissionV1PackageSort(hostContext, true, true, true)
        Thread {
            if (replaceExisting) {
                repository.replaceAll(emptyList())
                mapController.reloadFromRepository()
            }
            val success = runCatching { importer.beginImport(source) }.getOrDefault(false)
            runOnUiThread {
                if (success) {
                    Toast.makeText(hostContext, R.string.resource_import_package_success, Toast.LENGTH_SHORT).show()
                    mapController.reloadFromRepository()
                } else {
                    Toast.makeText(hostContext, R.string.resource_import_package_failure, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun shareResources() {
        val cameras = mapController.listCameras()
        if (cameras.isEmpty()) {
            Toast.makeText(context, R.string.resource_export_none, Toast.LENGTH_SHORT).show()
            return
        }
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val snapshot = snapshotResources(getExportsDirectory(), timestamp) ?: run {
            Toast.makeText(context, R.string.resource_share_failure, Toast.LENGTH_SHORT).show()
            return
        }
        val mapView = runCatching { MapView.getMapView() }.getOrNull()
        val shareContext = mapView?.context ?: binding?.root?.context ?: context
        val manifest = MissionPackageApi.CreateTempManifest("airscout-share-$timestamp", true, true, null)
            ?: run {
                Toast.makeText(context, R.string.resource_share_failure, Toast.LENGTH_SHORT).show()
                return
            }
        manifest.setName(shareContext.getString(R.string.resource_share_package_name, timestamp))
        if (!manifest.addFile(snapshot, null)) {
            Toast.makeText(shareContext, R.string.resource_share_failure, Toast.LENGTH_SHORT).show()
            return
        }
        cameras.forEach { manifest.addMapItem(it.uid) }

        val dispatchShare: () -> Unit = {
            MissionPackageApi.SendUIDs(shareContext, manifest, null as String?, null, true)
            Toast.makeText(shareContext, R.string.resource_share_success, Toast.LENGTH_SHORT).show()
        }

        if (mapView != null) {
            mapView.post {
                runCatching { dispatchShare() }.onFailure {
                    Toast.makeText(shareContext, R.string.resource_share_failure, Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            val result = runCatching { dispatchShare() }
            if (result.isFailure) {
                Toast.makeText(shareContext, R.string.resource_share_failure, Toast.LENGTH_SHORT).show()
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
        val zoomCircleLevelInput = dialogView.findViewById<EditText>(R.id.inputZoomLevelCircle)
        val rangeInput = dialogView.findViewById<EditText>(R.id.inputRange)
        val horizontalInput = dialogView.findViewById<EditText>(R.id.inputHorizontalFov)
        val verticalInput = dialogView.findViewById<EditText>(R.id.inputVerticalFov)
        val bearingInput = dialogView.findViewById<EditText>(R.id.inputBearing)
        val zoomConeLevelInput = dialogView.findViewById<EditText>(R.id.inputZoomLevelCone)

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
                    zoomCircleLevelInput.setText(camera.frustumZoomLevel?.toString() ?: "")
                    circleFields.visibility = View.VISIBLE
                    coneFields.visibility = View.GONE
                }
                AxisCamera.FrustumMode.CONE -> {
                    frustumGroup.check(R.id.radioFrustumCone)
                    rangeInput.setText(camera.frustumRangeMeters?.toString() ?: "")
                    horizontalInput.setText(camera.frustumHorizontalFovDeg?.toString() ?: "")
                    verticalInput.setText(camera.frustumVerticalFovDeg?.toString() ?: "")
                    bearingInput.setText(camera.frustumBearingDeg?.toString() ?: "")
                    zoomConeLevelInput.setText(camera.frustumZoomLevel?.toString() ?: "")
                    circleFields.visibility = View.GONE
                    coneFields.visibility = View.VISIBLE
                }
            }
        } ?: run {
            frustumGroup.check(R.id.radioFrustumCircle)
            circleFields.visibility = View.VISIBLE
            coneFields.visibility = View.GONE
        }

        frustumGroup.setOnCheckedChangeListener { _: RadioGroup, checkedId: Int ->
            if (checkedId == R.id.radioFrustumCircle) {
                circleFields.visibility = View.VISIBLE
                coneFields.visibility = View.GONE
                zoomConeLevelInput.setText("")
            } else {
                circleFields.visibility = View.GONE
                coneFields.visibility = View.VISIBLE
                zoomCircleLevelInput.setText("")
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
                        val radiusValue = radiusInput.text.toString().trim().toDoubleOrNull()
                        val radius = radiusValue?.takeIf { it > 0.0 }
                        if (radius == null) {
                            Toast.makeText(context, R.string.resource_dialog_error_frustum, Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        val zoomLevel = zoomCircleLevelInput.text.toString().trim().toDoubleOrNull()
                        if (zoomLevel != null && (zoomLevel < 0.0 || zoomLevel > 1.0)) {
                            Toast.makeText(context, R.string.resource_dialog_error_frustum, Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        camera.frustumMode = AxisCamera.FrustumMode.CIRCLE
                        camera.frustumRadiusMeters = radius
                        camera.frustumRangeMeters = radius
                        camera.frustumHorizontalFovDeg = null
                        camera.frustumVerticalFovDeg = null
                        camera.frustumBearingDeg = null
                        camera.frustumZoomLevel = zoomLevel
                    }
                    AxisCamera.FrustumMode.CONE -> {
                        val rangeValue = rangeInput.text.toString().trim().toDoubleOrNull()
                        val h = horizontalInput.text.toString().trim().toDoubleOrNull()
                        val v = verticalInput.text.toString().trim().toDoubleOrNull()
                        val bearing = bearingInput.text.toString().trim().toDoubleOrNull()
                        val zoomLevel = zoomConeLevelInput.text.toString().trim().toDoubleOrNull()
                        val range = rangeValue?.takeIf { it > 0.0 }
                        if (range == null || h == null || v == null || bearing == null) {
                            Toast.makeText(context, R.string.resource_dialog_error_frustum, Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        if (zoomLevel != null && (zoomLevel < 0.0 || zoomLevel > 1.0)) {
                            Toast.makeText(context, R.string.resource_dialog_error_frustum, Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        camera.frustumMode = AxisCamera.FrustumMode.CONE
                        camera.frustumRangeMeters = range
                        camera.frustumHorizontalFovDeg = h
                        camera.frustumVerticalFovDeg = v
                        camera.frustumBearingDeg = bearing
                        camera.frustumRadiusMeters = null
                        camera.frustumZoomLevel = zoomLevel
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

    private fun showControlInfoDialog() {
        val baseContext = runCatching { MapView.getMapView().context }.getOrNull()
            ?: binding?.root?.context
            ?: context
        val themedContext = ContextThemeWrapper(baseContext, R.style.ATAKPluginTheme)
        if (currentInfoDetails.isEmpty()) {
            AlertDialog.Builder(themedContext)
                .setTitle(context.getString(R.string.control_info_dialog_title))
                .setMessage(context.getString(R.string.control_info_dialog_empty))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val density = themedContext.resources.displayMetrics.density
        val listContainer = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }
        currentInfoDetails.forEach { entry ->
            val row = LinearLayout(themedContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val pad = (6 * density).toInt()
                setPadding(0, pad, 0, pad)
            }
            val iconDrawable = ContextCompat.getDrawable(context, entry.iconRes)?.mutate()
            if (iconDrawable != null) {
                DrawableCompat.setTint(iconDrawable, ContextCompat.getColor(themedContext, android.R.color.white))
                val iconView = ImageView(themedContext).apply {
                    setImageDrawable(iconDrawable)
                }
                val iconParams = LinearLayout.LayoutParams((20 * density).toInt(), (20 * density).toInt()).apply {
                    marginEnd = (12 * density).toInt()
                }
                row.addView(iconView, iconParams)
            }

            val textView = TextView(themedContext).apply {
                text = entry.value
                setTextColor(ContextCompat.getColor(themedContext, android.R.color.white))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
            row.addView(textView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            listContainer.addView(row)
        }

        val scrollView = ScrollView(themedContext).apply {
            val padH = (16 * density).toInt()
            val padV = (12 * density).toInt()
            setPadding(padH, padV, padH, padV)
            addView(listContainer)
        }

        AlertDialog.Builder(themedContext)
            .setTitle(context.getString(R.string.control_info_dialog_title))
            .setView(scrollView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private data class ControlInfo(val iconRes: Int, val value: String)

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
        // handled via plugin
    }

    override fun onCameraEditRequested(camera: AxisCamera) {
        // handled via plugin
    }

    fun showResourceCatalog(target: AxisCamera?) {
        binding?.root?.post {
            val targetUid = target?.uid
            showList(targetUid)
            refreshResourceList(targetUid)
        }
    }

    fun editCamera(camera: AxisCamera) {
        binding?.root?.post {
            val bound = binding != null
            if (!bound) {
                pendingEditCamera = camera
            } else {
                pendingSelectUid = camera.uid
                promptResourceDialog(camera, null)
            }
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
                            val zoomLevel = camera.frustumZoomLevel?.takeIf { z -> z > 0.0 }
                            if (zoomLevel != null) {
                                val zoomPercent = (zoomLevel * 100).roundToInt().coerceIn(0, 100)
                                ctx.getString(R.string.resource_frustum_circle_summary_zoom, radius, zoomPercent)
                            } else {
                                ctx.getString(R.string.resource_frustum_circle_summary, radius)
                            }
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
                            val zoomLevel = camera.frustumZoomLevel?.takeIf { z -> z > 0.0 }
                            if (zoomLevel != null) {
                                val zoomPercent = (zoomLevel * 100).roundToInt().coerceIn(0, 100)
                                ctx.getString(R.string.resource_frustum_cone_summary_zoom, range, h, v, b, zoomPercent)
                            } else {
                                ctx.getString(R.string.resource_frustum_cone_summary, range, h, v, b)
                            }
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

    private fun runOnUiThread(action: () -> Unit) {
        val rootView = binding?.root
        when {
            rootView != null -> rootView.post(action)
            else -> {
                val mapView = runCatching { MapView.getMapView() }.getOrNull()
                if (mapView != null) {
                    mapView.post(action)
                } else {
                    Handler(Looper.getMainLooper()).post(action)
                }
            }
        }
    }

    companion object {
        private const val EXPORT_SUBDIR = "exports"
    }
}
