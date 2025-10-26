package com.walaris.airscout.packaging

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.atakmap.android.importexport.AbstractImporter
import com.atakmap.comms.CommsMapComponent
import com.walaris.airscout.core.AxisCameraRepository
import com.walaris.airscout.map.AirScoutMapController
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

private const val IMPORTER_CONTENT_TYPE = "AirScout Cameras"
private const val IMPORTER_MIME_TYPE = "application/json"

/**
 * Importer that allows AirScout camera inventories to be restored through
 * ATAK's standard import flows, including Mission Packages and the file browser.
 */
object AirScoutResourceImporter : AbstractImporter(IMPORTER_CONTENT_TYPE) {

    private val contextRef = AtomicReference<Context?>()
    private val repositoryRef = AtomicReference<AxisCameraRepository?>()
    private val mapControllerRef = AtomicReference<AirScoutMapController?>()

    fun bind(context: Context, repository: AxisCameraRepository, controller: AirScoutMapController) {
        contextRef.set(context.applicationContext)
        repositoryRef.set(repository)
        mapControllerRef.set(controller)
    }

    fun unbind() {
        contextRef.set(null)
        repositoryRef.set(null)
        mapControllerRef.set(null)
    }

    override fun getSupportedMIMETypes(): Set<String> = setOf(MIME_TYPE)

    @Throws(IOException::class)
    override fun importData(inputStream: InputStream, contentType: String?, bundle: Bundle?): CommsMapComponent.ImportResult {
        val context = contextRef.get() ?: return CommsMapComponent.ImportResult.FAILURE
        val repository = repositoryRef.get() ?: return CommsMapComponent.ImportResult.FAILURE
        val mapController = mapControllerRef.get()

        val tempFile = createTempStorage(context)
        return try {
            tempFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            val result = repository.importFrom(tempFile, replaceExisting = bundle?.getBoolean(KEY_REPLACE_EXISTING, false) == true)
            if (result.total == 0) {
                CommsMapComponent.ImportResult.FAILURE
            } else {
                mapController?.reloadFromRepository()
                CommsMapComponent.ImportResult.SUCCESS
            }
        } finally {
            tempFile.delete()
        }
    }

    @Throws(IOException::class)
    override fun importData(uri: Uri, contentType: String?, bundle: Bundle?): CommsMapComponent.ImportResult {
        return AbstractImporter.importUriAsStream(this, uri, contentType, bundle)
    }

    private fun createTempStorage(context: Context): File {
        val baseDir = context.cacheDir ?: context.filesDir
        return File.createTempFile("airscout-import-", ".json", baseDir)
    }

    const val MIME_TYPE = IMPORTER_MIME_TYPE
    const val CONTENT_TYPE = IMPORTER_CONTENT_TYPE

    /**
     * Optional key that allows callers to force a full replace instead of merging on import.
     */
    const val KEY_REPLACE_EXISTING = "com.walaris.airscout.REPLACE_EXISTING"
}
