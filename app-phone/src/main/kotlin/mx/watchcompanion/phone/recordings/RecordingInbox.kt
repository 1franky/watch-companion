package mx.watchcompanion.phone.recordings

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Properties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Grabaciones del reloj que el teléfono recibió y aún no confirma Velora (WC-54). Viven en el almacenamiento privado,
 * fuera del respaldo, y se borran en cuanto Velora las tiene o las rechaza (07 §2): el track crudo no se acumula.
 *
 * Una carpeta por grabación: `track.gpx` y `meta.properties` (completitud, SHA-256 y el trabajo de Velora, si ya se
 * subió). Las escrituras son atómicas (temporal y `rename`).
 */
class RecordingInbox(private val root: File) {
    data class Entry(val id: String, val completeness: String, val sha256: String, val jobId: String?, val gpx: File)

    private val _pending = MutableStateFlow(0)

    /** Grabaciones del reloj esperando a Velora. */
    val pending: StateFlow<Int> = _pending.asStateFlow()

    /**
     * Guarda el GPX que llegó del reloj si su SHA-256 coincide con el anunciado. Idempotente: si ya estaba, no lo toca.
     *
     * @return `true` si quedó guardado (ya se puede dar el acuse al reloj)
     */
    @Synchronized
    fun receive(id: String, completeness: String, sha256: String, content: InputStream): Boolean {
        require(ID.matches(id)) { "id inválido" }
        val dir = File(root, id)
        if (entry(dir)?.sha256 == sha256) return true
        dir.mkdirs()
        val tmp = File(dir, "$GPX.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        FileOutputStream(tmp).use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = content.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                out.write(buffer, 0, n)
            }
            out.fd.sync()
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != sha256) {
            tmp.delete()
            if (dir.list().isNullOrEmpty()) dir.delete()
            return false
        }
        tmp.renameTo(File(dir, GPX))
        writeMeta(dir, completeness, sha256, jobId = null)
        refresh()
        return true
    }

    @Synchronized
    fun entries(): List<Entry> = root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { entry(it) }

    /** Recuerda el trabajo de Velora que la está procesando, para consultarlo en vez de volver a subirla. */
    @Synchronized
    fun setJob(id: String, jobId: String?) {
        val e = entry(File(root, id)) ?: return
        writeMeta(File(root, id), e.completeness, e.sha256, jobId)
    }

    /** Velora ya la tiene (o la rechazó para siempre): se borra del teléfono. */
    @Synchronized
    fun remove(id: String) {
        File(root, id).deleteRecursively()
        refresh()
    }

    @Synchronized
    fun refresh() {
        _pending.value = entries().size
    }

    private fun entry(dir: File): Entry? {
        val meta = File(dir, META).takeIf { it.isFile } ?: return null
        val gpx = File(dir, GPX).takeIf { it.isFile } ?: return null
        val props = Properties().apply { meta.inputStream().use { load(it) } }
        return Entry(
            id = dir.name,
            completeness = props.getProperty("completeness") ?: return null,
            sha256 = props.getProperty("sha256") ?: return null,
            jobId = props.getProperty("jobId"),
            gpx = gpx,
        )
    }

    private fun writeMeta(dir: File, completeness: String, sha256: String, jobId: String?) {
        val props = Properties().apply {
            setProperty("completeness", completeness)
            setProperty("sha256", sha256)
            jobId?.let { setProperty("jobId", it) }
        }
        val tmp = File(dir, "$META.tmp")
        FileOutputStream(tmp).use { out ->
            props.store(out, null)
            out.fd.sync()
        }
        tmp.renameTo(File(dir, META))
    }

    private companion object {
        const val GPX = "track.gpx"
        const val META = "meta.properties"

        /** UUID del reloj: nunca se usa un texto ajeno como nombre de carpeta. */
        val ID = Regex("[0-9a-fA-F-]{36}")
    }
}
