package com.walaris.airscout.core

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.atakmap.coremap.log.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Handles Axis PTZ command dispatch, optional event channel bridging, and video playback.
 */
class AxisCameraController(context: Context) {

    private val loggerTag = "AxisCameraController"
    private val appContext = context.applicationContext
    private val uiHandler = Handler(Looper.getMainLooper())
    private val networkExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val httpTimeoutMs = 5000
    private val httpUserAgent = "WalarisAirScout/1.0"

    private val httpClient = OkHttpClient()

    private var player: ExoPlayer? = null
    private var currentPlayerView: PlayerView? = null
    private var currentCamera: AxisCamera? = null
    private val webSocketRef = AtomicReference<WebSocket?>()

    fun startStream(playerView: PlayerView, camera: AxisCamera, onError: (Throwable) -> Unit) {
        uiHandler.post {
            currentPlayerView = playerView
            currentCamera = camera
            closeEventChannel()
            initializePlayer(playerView, camera.rtspUrl, onError)
        }
    }

    fun stopStream() {
        uiHandler.post { releasePlayer() }
    }

    fun reconnectStream(onError: (Throwable) -> Unit) {
        val camera = currentCamera ?: return
        val playerView = currentPlayerView ?: return
        startStream(playerView, camera, onError)
    }

    fun detachPlayerView() {
        uiHandler.post {
            currentPlayerView?.player = null
            currentPlayerView = null
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
}
