package com.walaris.airscout.core

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.widget.ImageView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.atakmap.coremap.log.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Handles Axis PTZ command dispatch, optional event channel bridging, and video playback.
 */
class AxisCameraController(context: Context) {

    private companion object {
        const val MJPEG_MAX_FRAME_BYTES = 2 * 1024 * 1024
    }

    private val loggerTag = "AxisCameraController"
    private val appContext = context.applicationContext
    private val uiHandler = Handler(Looper.getMainLooper())
    private val networkExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val httpTimeoutMs = 5000
    private val httpUserAgent = "WalarisAirScout/1.0"

    private val httpClient = OkHttpClient()

    private var player: ExoPlayer? = null
    private var currentPlayerView: PlayerView? = null
    private var currentImageView: ImageView? = null
    private var currentCamera: AxisCamera? = null
    private val webSocketRef = AtomicReference<WebSocket?>()
    private var currentMode: StreamMode? = null
    private var mjpegRenderer: MjpegStreamRenderer? = null

    fun startStream(
        playerView: PlayerView,
        imageView: ImageView,
        camera: AxisCamera,
        onError: (Throwable) -> Unit
    ) {
        uiHandler.post {
            stopMjpegStream()
            currentPlayerView = playerView
            currentImageView = imageView
            currentCamera = camera
            closeEventChannel()
            if (shouldUseMjpeg(camera)) {
                currentMode = StreamMode.MJPEG
                releasePlayer()
                startMjpegStream(playerView, imageView, camera, onError)
            } else {
                currentMode = StreamMode.EXOPLAYER
                stopMjpegStream()
                initializePlayer(playerView, camera.rtspUrl, onError)
            }
        }
    }

    fun stopStream() {
        uiHandler.post {
            releasePlayer()
            stopMjpegStream()
            currentPlayerView?.visibility = View.GONE
            currentImageView?.visibility = View.GONE
            currentMode = null
        }
    }

    fun reconnectStream(onError: (Throwable) -> Unit) {
        val camera = currentCamera ?: return
        val playerView = currentPlayerView ?: return
        val imageView = currentImageView ?: return
        startStream(playerView, imageView, camera, onError)
    }

    fun detachPlayerView() {
        uiHandler.post {
            currentPlayerView?.player = null
            currentPlayerView = null
            currentImageView = null
        }
    }

    fun connectEventChannel(camera: AxisCamera, listener: (String) -> Unit) {
        val wsUrl = camera.eventWebSocketUrl ?: return
        networkExecutor.execute {
            try {
                val requestBuilder = Request.Builder().url(wsUrl)
                buildAuthHeader(camera)?.let { requestBuilder.addHeader("Authorization", it) }
                val socket = httpClient.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        listener.invoke(text)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.d(loggerTag, "Event channel failure", t)
                    }
                })
                webSocketRef.getAndSet(socket)?.close(1000, "replaced")
            } catch (ex: Exception) {
                Log.d(loggerTag, "Unable to open event channel", ex)
            }
        }
    }

    fun closeEventChannel() {
        webSocketRef.getAndSet(null)?.close(1000, null)
    }

    fun sendMoveCommand(camera: AxisCamera, panVelocity: Float, tiltVelocity: Float) {
        val pan = panVelocity.coerceIn(-1f, 1f)
        val tilt = tiltVelocity.coerceIn(-1f, 1f)
        dispatchControl(camera, mapOf("continuouspantiltmove" to "$pan,$tilt"))
    }

    fun sendZoomCommand(camera: AxisCamera, zoomVelocity: Float) {
        val zoom = zoomVelocity.coerceIn(-1f, 1f)
        dispatchControl(camera, mapOf("continuouszoommove" to zoom.toString()))
    }

    fun stopMotion(camera: AxisCamera) {
        dispatchControl(camera, mapOf(
            "continuouspantiltmove" to "0,0",
            "continuouszoommove" to "0"
        ))
    }

    fun gotoPreset(camera: AxisCamera, preset: String) {
        dispatchControl(camera, mapOf("gotopresetname" to preset))
    }

    private fun shouldUseMjpeg(camera: AxisCamera): Boolean {
        val url = camera.rtspUrl.orEmpty()
        val protocol = camera.protocol?.lowercase()
        if (url.startsWith("rtsp", ignoreCase = true) || protocol == "rtsp") {
            return false
        }
        if (url.startsWith("rtmp", ignoreCase = true) || protocol == "rtmp") {
            return false
        }
        val lower = url.lowercase()
        if (!lower.startsWith("http")) {
            return protocol == "mjpeg" || protocol == "mjpg"
        }
        if (protocol == "mjpeg" || protocol == "mjpg") {
            return true
        }
        return lower.endsWith(".mjpg") ||
            lower.endsWith(".mjpeg") ||
            lower.contains("mjpg") ||
            lower.contains("mjpeg")
    }

    private fun startMjpegStream(
        playerView: PlayerView,
        imageView: ImageView,
        camera: AxisCamera,
        onError: (Throwable) -> Unit
    ) {
        imageView.visibility = View.VISIBLE
        playerView.visibility = View.GONE
        val renderer = MjpegStreamRenderer(imageView, playerView, camera, onError)
        mjpegRenderer = renderer
        renderer.start(camera.rtspUrl)
    }

    private fun stopMjpegStream() {
        mjpegRenderer?.stop()
        mjpegRenderer = null
        currentImageView?.setImageDrawable(null)
        currentImageView?.visibility = View.GONE
    }

    private fun initializePlayer(targetView: PlayerView, source: String, onError: (Throwable) -> Unit) {
        releasePlayer()
        if (source.isBlank()) {
            onError.invoke(IllegalArgumentException("Empty stream URL"))
            return
        }
        try {
            val player = ExoPlayer.Builder(appContext).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        onError.invoke(error)
                    }
                })
            }
            this.player = player
            targetView.player = player
            targetView.visibility = View.VISIBLE
            currentImageView?.visibility = View.GONE
            targetView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            targetView.useController = false
            targetView.setDefaultArtwork(null)
            targetView.setShutterBackgroundColor(Color.TRANSPARENT)
            val mediaItem = runCatching { Uri.parse(source) }
                .map { MediaItem.fromUri(it) }
                .getOrDefault(MediaItem.fromUri(source))
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true
            player.play()
        } catch (ex: Exception) {
            releasePlayer()
            onError.invoke(ex)
        }
    }

    private fun releasePlayer() {
        player?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            } finally {
                it.release()
            }
        }
        player = null
        currentPlayerView?.player = null
    }

    private fun dispatchControl(camera: AxisCamera, params: Map<String, String>) {
        if (camera.controlUrl.isBlank()) return
        networkExecutor.execute {
            try {
                val uriBuilder = Uri.parse(camera.controlUrl).buildUpon()
                params.forEach { (key, value) -> uriBuilder.appendQueryParameter(key, value) }
                val url = uriBuilder.build().toString()
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = httpTimeoutMs
                    readTimeout = httpTimeoutMs
                    requestMethod = "GET"
                    addRequestProperty("User-Agent", httpUserAgent)
                    buildAuthHeader(camera)?.let { addRequestProperty("Authorization", it) }
                }
                connection.use {
                    if (connection.responseCode !in 200..299) {
                        Log.d(loggerTag, "Axis command failed (${connection.responseCode}) for $params")
                    }
                }
            } catch (ex: Exception) {
                Log.d(loggerTag, "Axis command failed", ex)
            }
        }
    }

    private fun HttpURLConnection.use(block: () -> Unit) {
        try {
            block()
        } finally {
            try {
                inputStream?.close()
            } catch (_: Exception) {
            }
            try {
                errorStream?.close()
            } catch (_: Exception) {
            }
            disconnect()
        }
    }

    private fun buildAuthHeader(camera: AxisCamera): String? {
        val user = camera.username
        val pass = camera.password
        if (user.isNullOrBlank() || pass.isNullOrBlank()) return null
        val credentials = "$user:$pass"
        val encoded = Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
        return "Basic $encoded"
    }

    fun shutdown() {
        closeEventChannel()
        stopStream()
        networkExecutor.shutdownNow()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private inner class MjpegStreamRenderer(
        private val imageView: ImageView,
        private val playerView: PlayerView,
        private val camera: AxisCamera,
        private val onError: (Throwable) -> Unit
    ) {
        private val active = AtomicBoolean(false)
        private var call: Call? = null

        fun start(url: String) {
            if (url.isBlank()) {
                onError.invoke(IllegalArgumentException("Empty stream URL"))
                return
            }
            active.set(true)
            val requestBuilder = Request.Builder().url(url)
            buildAuthHeader(camera)?.let { requestBuilder.addHeader("Authorization", it) }
            call = httpClient.newCall(requestBuilder.build()).also { call ->
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!active.get()) return
                        uiHandler.post {
                            if (!active.get()) return@post
                            onError.invoke(e)
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (!response.isSuccessful) {
                            if (!active.get()) return
                            uiHandler.post {
                                if (!active.get()) return@post
                                onError.invoke(IOException("HTTP ${response.code}"))
                            }
                            response.close()
                            return
                        }
                        val body = response.body
                        if (body == null) {
                            if (!active.get()) return
                            uiHandler.post {
                                if (!active.get()) return@post
                                onError.invoke(IllegalStateException("Empty MJPEG body"))
                            }
                            response.close()
                            return
                        }
                        val stream = BufferedInputStream(body.byteStream())
                        try {
                            readFrames(stream)
                        } catch (ex: Exception) {
                            if (active.get()) {
                                uiHandler.post { onError.invoke(ex) }
                            }
                        } finally {
                            body.close()
                            response.close()
                            stop()
                        }
                    }
                })
            }
        }

        fun stop() {
            if (!active.getAndSet(false)) return
            call?.cancel()
            call = null
            uiHandler.post {
                imageView.setImageDrawable(null)
                imageView.visibility = View.GONE
                playerView.visibility = View.GONE
            }
        }

        @Throws(IOException::class)
        private fun readFrames(stream: BufferedInputStream) {
            val buffer = ByteArrayOutputStream()
            var prev = -1
            var current: Int

            fun findStart(): Boolean {
                prev = -1
                while (active.get()) {
                    current = stream.read()
                    if (current == -1) return false
                    if (prev == 0xFF && current == 0xD8) {
                        buffer.reset()
                        buffer.write(0xFF)
                        buffer.write(0xD8)
                        return true
                    }
                    prev = current
                }
                return false
            }

            while (active.get()) {
                if (!findStart()) break
                prev = -1
                while (active.get()) {
                    current = stream.read()
                    if (current == -1) return
                    buffer.write(current)
                    if (buffer.size() > MJPEG_MAX_FRAME_BYTES) {
                        buffer.reset()
                        break
                    }
                    if (prev == 0xFF && current == 0xD9) {
                        val frameData = buffer.toByteArray()
                        buffer.reset()
                        deliverFrame(frameData)
                        break
                    }
                    prev = current
                }
            }
        }

        private fun deliverFrame(bytes: ByteArray) {
            if (!active.get()) return
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            uiHandler.post {
                if (!active.get()) {
                    return@post
                }
                imageView.setImageBitmap(bitmap)
                imageView.visibility = View.VISIBLE
                playerView.visibility = View.GONE
            }
        }
    }

    private enum class StreamMode { EXOPLAYER, MJPEG }
}
