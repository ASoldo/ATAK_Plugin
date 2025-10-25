package com.walaris.airscout.ui

import android.content.Context
import android.view.View
import android.widget.Toast
import com.atakmap.android.maps.MapView
import com.atakmap.coremap.maps.coords.GeoPoint
import com.atakmap.android.video.ConnectionEntry
import com.atakmap.android.video.VideoListDialog
import com.atakmap.android.video.manager.VideoManager
import com.walaris.airscout.R
import com.walaris.airscout.core.AxisCamera
import com.walaris.airscout.core.AxisCameraController
import com.walaris.airscout.databinding.AirscoutPaneBinding
import com.walaris.airscout.map.AirScoutMapController

class AirScoutPaneController(
    private val context: Context,
    private val mapController: AirScoutMapController,
    private val cameraController: AxisCameraController
) : DualJoystickView.JoystickListener,
    AirScoutMapController.Listener {

    private var binding: AirscoutPaneBinding? = null
    private var currentCamera: AxisCamera? = null

    fun bind(root: View) {
        binding = AirscoutPaneBinding.bind(root)
        mapController.registerListener(this)
        setupUi()
        refreshCameraList()
    }

    fun unbind() {
        binding?.joystickOverlay?.listener = null
        cameraController.stopStream()
        cameraController.closeEventChannel()
        cameraController.detachPlayerView()
        binding = null
        mapController.unregisterListener(this)
    }

    private fun setupUi() {
        val ui = binding ?: return
        ui.joystickOverlay.listener = this

        ui.addCameraButton.setOnClickListener {
            openVideoPicker(currentMapCenter())
        }
        ui.removeCameraButton.setOnClickListener {
            currentCamera?.let { camera ->
                cameraController.stopStream()
                cameraController.closeEventChannel()
                mapController.removeCamera(camera.uid)
                Toast.makeText(context, context.getString(R.string.toast_camera_removed), Toast.LENGTH_SHORT).show()
            }
        }
        ui.removeCameraButton.isEnabled = false
    }

    private fun refreshCameraList(selectUid: String? = null) {
        val cameras = mapController.listCameras()
        binding?.removeCameraButton?.isEnabled = cameras.isNotEmpty()

        if (cameras.isEmpty()) {
            currentCamera = null
            binding?.selectedFeedLabel?.text = context.getString(R.string.status_no_cameras)
            binding?.statusMessage?.setText(R.string.status_no_cameras)
            cameraController.stopStream()
            cameraController.closeEventChannel()
            return
        }

        val selectedCamera = selectUid?.let { uid -> cameras.firstOrNull { it.uid == uid } }
            ?: currentCamera?.let { existing -> cameras.firstOrNull { it.uid == existing.uid } }
            ?: cameras.first()

        currentCamera = selectedCamera
        binding?.selectedFeedLabel?.text = selectedCamera.displayName
        updateStatus(context.getString(R.string.status_selected_camera, selectedCamera.displayName))
        startStreamIfReady()
    }

    private fun startStreamIfReady() {
        val camera = currentCamera ?: return
        val ui = binding ?: return
        cameraController.closeEventChannel()
        cameraController.startStream(ui.videoPlayerView, ui.videoImageView, camera) { error ->
            updateStatus(context.getString(R.string.status_stream_error, error.localizedMessage ?: ""))
        }
        cameraController.connectEventChannel(camera) { }
    }

    private fun updateStatus(message: String) {
        binding?.statusMessage?.text = message
        binding?.selectedFeedLabel?.text = currentCamera?.displayName ?: context.getString(R.string.status_no_cameras)
    }

    private fun openVideoPicker(initialLocation: GeoPoint?) {
        binding?.root?.post {
            val mapView = MapView.getMapView()
            val manager = runCatching { VideoManager.getInstance() }.getOrNull()
            val entries = manager?.entries
            if (entries == null || entries.isEmpty()) {
                Toast.makeText(context, R.string.error_no_video_sources, Toast.LENGTH_SHORT).show()
                return@post
            }
            VideoListDialog(mapView).show(null, entries, true, object : VideoListDialog.Callback {
                override fun onVideosSelected(list: List<ConnectionEntry>) {
                    if (list.isEmpty()) {
                        return
                    }
                    val added = mapController.registerVideoSources(list, initialLocation ?: currentMapCenter())
                    val target = added.lastOrNull()
                    if (target != null) {
                        mapController.selectCamera(target)
                    } else {
                        refreshCameraList()
                    }
                }
            })
        }
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
        focusCamera(camera)
    }

    fun focusCamera(camera: AxisCamera) {
        binding?.root?.post {
            currentCamera = camera
            refreshCameraList(camera.uid)
        }
    }

    override fun onCameraPreviewRequested(camera: AxisCamera) {
        onCameraSelected(camera)
    }

    override fun onAddCameraRequested(initialLocation: GeoPoint?) {
        openVideoPicker(initialLocation)
    }

    override fun onCameraRemoved(camera: AxisCamera) {
        binding?.root?.post {
            if (currentCamera?.uid == camera.uid) {
                cameraController.stopStream()
                cameraController.closeEventChannel()
                currentCamera = null
            }
            refreshCameraList()
        }
    }

    fun promptNewCamera(initialLocation: GeoPoint?) {
        openVideoPicker(initialLocation)
    }

    private fun currentMapCenter(): GeoPoint? = try {
        MapView.getMapView().centerPoint.get()
    } catch (_: Exception) {
        null
    }
}
