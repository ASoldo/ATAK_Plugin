package com.walaris.airscout.core

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.SurfaceHolder
import com.atakmap.coremap.log.Log
import java.io.IOException
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
 * Handles Axis PTZ command dispatch, optional event channel bridging, and RTSP playback.
 */
class AxisCameraController(context: Context) {

    private val loggerTag = "AxisCameraController"
    private val appContext = context.applicationContext
    private val uiHandler = Handler(Looper.getMainLooper())
    private val networkExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val httpTimeoutMs = 5000
    private val httpUserAgent = "WalarisAirScout/1.0"

    private val httpClient = OkHttpClient()

    private var mediaPlayer: MediaPlayer? = null
    private var currentSurface: SurfaceHolder? = null
    private var currentCamera: AxisCamera? = null
    private val webSocketRef = AtomicReference<WebSocket?>()

    fun startStream(surface: SurfaceHolder, camera: AxisCamera, onError: (Throwable) -> Unit) {
        uiHandler.post {
            currentSurface = surface
            currentCamera = camera
            closeEventChannel()
            initializePlayer(camera.rtspUrl, onError)
        }
    }

    fun stopStream() {
        uiHandler.post { releasePlayer() }
    }

    fun reconnectStream(onError: (Throwable) -> Unit) {
        val camera = currentCamera ?: return
        val surface = currentSurface ?: return
        startStream(surface, camera, onError)
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

    private fun initializePlayer(source: String, onError: (Throwable) -> Unit) {
        releasePlayer()
        val surface = currentSurface ?: return
        val player = MediaPlayer()
        mediaPlayer = player
        try {
            player.setSurface(surface.surface)
            val uri = runCatching { Uri.parse(source) }.getOrNull()
            if (uri != null && !uri.scheme.isNullOrBlank()) {
                player.setDataSource(appContext, uri)
            } else {
                player.setDataSource(source)
            }
            player.setOnPreparedListener { it.start() }
            player.setOnErrorListener { _, what, extra ->
                onError.invoke(IOException("Media error what=$what extra=$extra"))
                true
            }
            player.prepareAsync()
        } catch (ex: Exception) {
            releasePlayer()
            onError.invoke(ex)
        }
    }

    private fun releasePlayer() {
        mediaPlayer?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            } finally {
                it.reset()
                it.release()
            }
        }
        mediaPlayer = null
        currentSurface = null
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
