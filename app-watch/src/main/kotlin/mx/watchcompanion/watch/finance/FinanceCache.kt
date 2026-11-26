package mx.watchcompanion.watch.finance

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import mx.watchcompanion.core.communication.FinanceSnapshot
import mx.watchcompanion.core.session.SecretCipher

/**
 * Último resumen financiero de Nexora guardado en el reloj (WC-42), para verlo al abrir la app sin esperar al teléfono.
 * Cifrado con una llave propia del Keystore, en `noBackupFilesDir` y con caducidad: pasado
 * [FinanceSnapshot.EXPIRE_AFTER_MS] desde que respondió Nexora se borra en vez de leerse.
 *
 * Solo se guarda un resumen con cifras. Uno sin sesión de Nexora borra el archivo: cerrar sesión en el teléfono lo
 * revoca también aquí. Si algo falla (llave perdida, archivo dañado), se borra: la caché nunca es la única copia.
 */
class FinanceCache(
    private val file: File,
    private val cipher: SecretCipher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** El resumen guardado si sigue vigente; si caducó o no se puede leer, lo borra y devuelve `null`. */
    fun load(): FinanceSnapshot? {
        if (!file.exists()) return null
        val snapshot = try {
            Json.decodeFromString(FinanceSnapshot.serializer(), cipher.decrypt(file.readBytes()).decodeToString())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (snapshot == null || snapshot.figures == null || snapshot.isExpired(clock())) {
            clear()
            return null
        }
        return snapshot
    }

    /** Guarda [snapshot] si trae cifras vigentes; si no (sin sesión, caducado, sin cifras), borra lo guardado. */
    fun save(snapshot: FinanceSnapshot) {
        if (snapshot.state == FinanceSnapshot.State.SIGNED_OUT || snapshot.figures == null || snapshot.isExpired(clock())) {
            clear()
            return
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            file.parentFile?.mkdirs()
            tmp.writeBytes(cipher.encrypt(Json.encodeToString(FinanceSnapshot.serializer(), snapshot).encodeToByteArray()))
            if (!tmp.renameTo(file)) throw IOException("No se pudo guardar el resumen")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Sin Keystore o sin espacio: el resumen sigue en memoria y una copia vieja no debe quedarse.
            tmp.delete()
            clear()
        }
    }

    fun clear() {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }
}
