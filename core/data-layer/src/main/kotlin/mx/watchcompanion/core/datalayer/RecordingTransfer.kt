package mx.watchcompanion.core.datalayer

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.tasks.await

/**
 * Traslado de una grabación del modo B del reloj al teléfono (WC-54).
 *
 * No viaja como mensaje del protocolo (`/wc/message` tiene un catálogo cerrado y un límite de tamaño): el reloj publica
 * un *data item* `/wc/recording/<id>` con el GPX como *Asset*. Google Play services lo guarda y lo entrega al teléfono
 * cuando haya enlace, aunque una de las dos apps esté cerrada; los reintentos son suyos. El teléfono, cuando ya guardó el
 * archivo y verificó su SHA-256, **borra el data item**, y ese borrado le llega al reloj como acuse: entonces borra su
 * copia. Como en todo el `/wc/`, la Data Layer solo entrega a la app con el mismo applicationId y firma.
 *
 * Campos del data item (versión [SCHEMA]): `schema`, `clientRecordingId`, `completeness`, `sha256` y el asset `gpx`. Sin
 * datos personales fuera del archivo.
 */
object RecordingTransfer {
    const val PATH_PREFIX = "/wc/recording/"
    const val SCHEMA = 1

    private const val KEY_SCHEMA = "schema"
    private const val KEY_ID = "clientRecordingId"
    private const val KEY_COMPLETENESS = "completeness"
    private const val KEY_SHA256 = "sha256"
    private const val KEY_GPX = "gpx"

    /** Una grabación ofrecida por el reloj, tal como la recibe el teléfono. */
    data class Offered(val uri: Uri, val clientRecordingId: String, val completeness: String, val sha256: String)

    /**
     * Reloj: publica (o vuelve a publicar, sin efecto si no cambió) la grabación [id]. Urgente para que no espere a
     * la siguiente ventana de sincronización.
     */
    suspend fun offer(context: Context, id: String, completeness: String, sha256: String, gpx: File) {
        val request = PutDataMapRequest.create(PATH_PREFIX + id).apply {
            dataMap.putInt(KEY_SCHEMA, SCHEMA)
            dataMap.putString(KEY_ID, id)
            dataMap.putString(KEY_COMPLETENESS, completeness)
            dataMap.putString(KEY_SHA256, sha256)
            dataMap.putAsset(KEY_GPX, Asset.createFromBytes(gpx.readBytes()))
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context.applicationContext).putDataItem(request).await()
    }

    /** Teléfono: lee los campos de un data item de grabación; `null` si no es uno o su esquema no se entiende. */
    fun parse(event: DataEvent): Offered? {
        if (event.type != DataEvent.TYPE_CHANGED) return null
        val item = event.dataItem
        if (item.uri.path?.startsWith(PATH_PREFIX) != true) return null
        val map = DataMapItem.fromDataItem(item).dataMap
        if (map.getInt(KEY_SCHEMA) != SCHEMA) return null
        return Offered(
            uri = item.uri,
            clientRecordingId = map.getString(KEY_ID) ?: return null,
            completeness = map.getString(KEY_COMPLETENESS) ?: return null,
            sha256 = map.getString(KEY_SHA256) ?: return null,
        )
    }

    /** Teléfono: abre el GPX de un data item de grabación. Quien llama lo cierra. */
    suspend fun open(context: Context, event: DataEvent): InputStream {
        val asset = DataMapItem.fromDataItem(event.dataItem).dataMap.getAsset(KEY_GPX)
            ?: error("El data item no trae el GPX")
        return Wearable.getDataClient(context.applicationContext).getFdForAsset(asset).await().inputStream
    }

    /** Teléfono: acuse de recibo. Borra el data item; el reloj lo ve como [acceptedId]. */
    suspend fun accept(context: Context, uri: Uri) {
        Wearable.getDataClient(context.applicationContext).deleteDataItems(uri).await()
    }

    /** Reloj: id de la grabación que el teléfono ya aceptó (data item borrado), o `null` si el evento es otro. */
    fun acceptedId(event: DataEvent): String? {
        if (event.type != DataEvent.TYPE_DELETED) return null
        val path = event.dataItem.uri.path ?: return null
        return path.removePrefix(PATH_PREFIX).takeIf { path.startsWith(PATH_PREFIX) && it.isNotEmpty() }
    }
}

/**
 * La implementa la `Application` que participa en el traslado de grabaciones: el teléfono recibe en
 * [onRecordingOffered], el reloj se entera del acuse en [onRecordingAccepted]. Corre en el hilo del listener.
 */
interface RecordingTransferHandler {
    suspend fun onRecordingOffered(event: DataEvent) {}

    suspend fun onRecordingAccepted(clientRecordingId: String) {}
}
