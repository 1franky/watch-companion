package mx.watchcompanion.watch.recording

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import java.util.UUID
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Un punto del track del reloj. Lo que falta es `null`, nunca 0 (02 §5). */
data class TrackPoint(
    val timeEpochMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val heartRateBpm: Int? = null,
)

/** Lectura de frecuencia cardíaca con su hora, para asociarla al punto GPS más cercano. */
data class HeartRateSample(val timeEpochMs: Long, val bpm: Int)

/** Avance de la grabación en curso. */
data class RecordingProgress(val points: Int, val distanceMeters: Double)

/** Estado de una grabación guardada en el reloj. */
enum class RecordingStatus {
    /** En curso, o interrumpida sin cerrar (se descubre al abrir la app: ver [TrackRecorder.recover]). */
    RECORDING,

    /** Terminada por el usuario o por Health Services con normalidad. */
    FINISHED,

    /** Interrumpida (batería agotada, proceso muerto) y cerrada después con los puntos que alcanzaron a guardarse. */
    PARTIAL,
}

data class RecordingSummary(
    val id: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,
    val status: RecordingStatus,
    val points: Int,
    val distanceMeters: Double,
    /** GPX listo para Velora; `null` mientras se graba o si no hubo ningún punto. */
    val gpx: File?,
    /** SHA-256 del GPX (02 §6): detecta reenvíos idénticos, no es la clave semántica. */
    val gpxSha256: String?,
)

/**
 * Grabación GPS del modo B (WC-53): guarda el track del reloj en su almacenamiento privado, punto por punto, para que
 * sobreviva a un cierre de la app o a quedarse sin batería.
 *
 * Cada grabación es una carpeta `<raíz>/<uuid>/` con:
 * - `meta.properties`: id, inicio, fin y estado. Se reescribe de forma atómica (archivo temporal y `rename`).
 * - `points.csv`: diario de solo-añadir, una línea por punto, sincronizado a disco en cada lote. Si el reloj se apaga a
 *   media escritura, la última línea queda cortada y se descarta al leer: se pierde como mucho el último lote.
 * - `track.gpx`: se genera al cerrar la grabación. Es lo que se subirá a Velora como fuente secundaria (WC-54); nunca a
 *   Strava (02 §7). El reloj no tiene red propia: no puede subir nada por su cuenta.
 *
 * El UUID se genera al empezar y no cambia aunque la app se reinicie: es la identidad estable de la fuente Watch (02 §6).
 */
class TrackRecorder(
    private val root: File,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private var current: Open? = null

    private class Open(val id: String, val dir: File, val startedAt: Long) {
        var points = 0
        var distance = 0.0
        var last: TrackPoint? = null
        val heartRates = ArrayDeque<HeartRateSample>()
    }

    /** Id de la grabación abierta en este proceso, si hay una. */
    @get:Synchronized
    val activeId: String? get() = current?.id

    /** Empieza una grabación nueva y devuelve su id. Si ya había una abierta, la cierra antes como parcial. */
    @Synchronized
    fun begin(startedAtEpochMs: Long): String {
        current?.let { close(it, RecordingStatus.PARTIAL, it.last?.timeEpochMs ?: it.startedAt) }
        val id = newId()
        val dir = File(root, id).apply { mkdirs() }
        writeMeta(dir, Meta(id, startedAtEpochMs, null, RecordingStatus.RECORDING))
        current = Open(id, dir, startedAtEpochMs)
        return id
    }

    /**
     * Retoma en este proceso la grabación que quedó en [RecordingStatus.RECORDING] (la app se reinició con el ejercicio
     * aún en curso en Health Services). Devuelve su id, o `null` si no había ninguna.
     */
    @Synchronized
    fun resume(): String? {
        current?.let { return it.id }
        val (dir, meta) = recordings().firstOrNull { it.second.status == RecordingStatus.RECORDING } ?: return null
        val open = Open(meta.id, dir, meta.startedAt)
        readPoints(dir).forEach { open.track(it) }
        current = open
        return open.id
    }

    /**
     * Agrega un lote: [locations] tal como llegan de Health Services y las lecturas de FC del mismo lote. Cada punto
     * lleva la lectura de FC más cercana en el tiempo (±[HR_MATCH_WINDOW_MS]); si no hay, va sin FC.
     *
     * Se descartan coordenadas inválidas y puntos que no avanzan en el tiempo (un lote repetido no duplica el track).
     */
    @Synchronized
    fun append(locations: List<TrackPoint>, heartRates: List<HeartRateSample>) {
        val open = current ?: return
        open.heartRates.addAll(heartRates.sortedBy { it.timeEpochMs })
        val accepted = mutableListOf<TrackPoint>()
        for (raw in locations.sortedBy { it.timeEpochMs }) {
            if (!raw.isValid()) continue
            val lastTime = (accepted.lastOrNull() ?: open.last)?.timeEpochMs
            if (lastTime != null && raw.timeEpochMs <= lastTime) continue
            accepted += raw.copy(heartRateBpm = raw.heartRateBpm ?: nearestHeartRate(open.heartRates, raw.timeEpochMs))
        }
        open.heartRates.lastOrNull()?.let { newest ->
            while (open.heartRates.isNotEmpty() && newest.timeEpochMs - open.heartRates.first().timeEpochMs > HR_BUFFER_MS) {
                open.heartRates.removeFirst()
            }
        }
        if (accepted.isEmpty()) return
        FileOutputStream(File(open.dir, POINTS), true).use { out ->
            out.write(accepted.joinToString(separator = "") { it.toCsvLine() }.toByteArray())
            out.fd.sync()
        }
        accepted.forEach { open.track(it) }
    }

    /** Puntos y distancia de la grabación abierta, para la pantalla. */
    @Synchronized
    fun progress(): RecordingProgress? = current?.let { RecordingProgress(it.points, it.distance) }

    /** Descarta la grabación abierta sin dejar rastro (el ejercicio no llegó a empezar). */
    @Synchronized
    fun discard() {
        val open = current ?: return
        open.dir.deleteRecursively()
        current = null
    }

    /** Cierra la grabación abierta: genera el GPX y la marca como terminada. */
    @Synchronized
    fun finish(endedAtEpochMs: Long): RecordingSummary? {
        val open = current ?: return null
        return close(open, RecordingStatus.FINISHED, endedAtEpochMs)
    }

    /**
     * Cierra como [RecordingStatus.PARTIAL] las grabaciones que quedaron abiertas sin un ejercicio en curso (el reloj se
     * apagó o el proceso murió). Se llama al abrir la app cuando Health Services ya no tiene el ejercicio.
     */
    @Synchronized
    fun recover(): List<RecordingSummary> = recordings()
        .filter { (_, meta) -> meta.status == RecordingStatus.RECORDING && meta.id != current?.id }
        .map { (dir, meta) ->
            val open = Open(meta.id, dir, meta.startedAt)
            readPoints(dir).forEach { open.track(it) }
            close(open, RecordingStatus.PARTIAL, open.last?.timeEpochMs ?: meta.startedAt)
        }

    /** Grabaciones guardadas, de la más reciente a la más antigua. */
    @Synchronized
    fun list(): List<RecordingSummary> = recordings().map { (dir, meta) ->
        val open = Open(meta.id, dir, meta.startedAt)
        readPoints(dir).forEach { open.track(it) }
        summary(open, meta)
    }

    private fun close(open: Open, status: RecordingStatus, endedAt: Long): RecordingSummary {
        val points = readPoints(open.dir)
        val gpx = File(open.dir, GPX)
        if (points.isNotEmpty()) {
            val tmp = File(open.dir, "$GPX.tmp")
            tmp.writeText(GpxWriter.write(open.startedAt, points))
            tmp.renameTo(gpx)
        }
        val meta = Meta(open.id, open.startedAt, endedAt, status)
        writeMeta(open.dir, meta)
        if (current === open) current = null
        return summary(open, meta)
    }

    private fun summary(open: Open, meta: Meta): RecordingSummary {
        val gpx = File(open.dir, GPX).takeIf { it.isFile && meta.status != RecordingStatus.RECORDING }
        return RecordingSummary(
            id = meta.id,
            startedAtEpochMs = meta.startedAt,
            endedAtEpochMs = meta.endedAt,
            status = meta.status,
            points = open.points,
            distanceMeters = open.distance,
            gpx = gpx,
            gpxSha256 = gpx?.let { sha256(it) },
        )
    }

    private fun Open.track(point: TrackPoint) {
        last?.let { distance += haversineMeters(it, point) }
        last = point
        points++
    }

    private fun recordings(): List<Pair<File, Meta>> = root.listFiles().orEmpty()
        .filter { it.isDirectory }
        .mapNotNull { dir -> readMeta(dir)?.let { dir to it } }
        .sortedByDescending { it.second.startedAt }

    private data class Meta(val id: String, val startedAt: Long, val endedAt: Long?, val status: RecordingStatus)

    private fun writeMeta(dir: File, meta: Meta) {
        val props = Properties().apply {
            setProperty("id", meta.id)
            setProperty("startedAt", meta.startedAt.toString())
            meta.endedAt?.let { setProperty("endedAt", it.toString()) }
            setProperty("status", meta.status.name)
        }
        val tmp = File(dir, "$META.tmp")
        FileOutputStream(tmp).use { out ->
            props.store(out, null)
            out.fd.sync()
        }
        tmp.renameTo(File(dir, META))
    }

    private fun readMeta(dir: File): Meta? {
        val file = File(dir, META).takeIf { it.isFile } ?: return null
        val props = Properties().apply { file.inputStream().use { load(it) } }
        return Meta(
            id = props.getProperty("id") ?: return null,
            startedAt = props.getProperty("startedAt")?.toLongOrNull() ?: return null,
            endedAt = props.getProperty("endedAt")?.toLongOrNull(),
            status = props.getProperty("status")?.let { runCatching { RecordingStatus.valueOf(it) }.getOrNull() } ?: return null,
        )
    }

    /** Lee el diario; una línea incompleta o ilegible (escritura cortada) se ignora. */
    private fun readPoints(dir: File): List<TrackPoint> {
        val file = File(dir, POINTS).takeIf { it.isFile } ?: return emptyList()
        val text = file.readText()
        // Solo cuentan las líneas terminadas en salto de línea: la última sin él quedó a medio escribir.
        val complete = text.substring(0, text.lastIndexOf('\n') + 1)
        return complete.lineSequence().mapNotNull { parseCsvLine(it) }.toList()
    }

    companion object {
        /** Ventana para asociar una lectura de FC a un punto GPS. */
        const val HR_MATCH_WINDOW_MS = 5_000L

        /** Cuántas lecturas de FC se guardan en memoria para asociar puntos del lote siguiente. */
        private const val HR_BUFFER_MS = 30_000L

        private const val META = "meta.properties"
        private const val POINTS = "points.csv"
        private const val GPX = "track.gpx"
        private const val EARTH_RADIUS_M = 6_371_008.8

        internal fun nearestHeartRate(samples: Collection<HeartRateSample>, timeEpochMs: Long): Int? =
            samples.filter { abs(it.timeEpochMs - timeEpochMs) <= HR_MATCH_WINDOW_MS }
                .minByOrNull { abs(it.timeEpochMs - timeEpochMs) }?.bpm?.takeIf { it > 0 }

        internal fun haversineMeters(a: TrackPoint, b: TrackPoint): Double {
            val dLat = Math.toRadians(b.latitude - a.latitude)
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val h = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).let { it * it }
            return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }

        private fun TrackPoint.isValid() = !latitude.isNaN() && !longitude.isNaN() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0 && !(latitude == 0.0 && longitude == 0.0)

        private fun TrackPoint.toCsvLine(): String = listOf(
            timeEpochMs.toString(),
            "%.7f".format(Locale.ROOT, latitude),
            "%.7f".format(Locale.ROOT, longitude),
            altitudeMeters?.takeUnless { it.isNaN() }?.let { "%.1f".format(Locale.ROOT, it) }.orEmpty(),
            heartRateBpm?.toString().orEmpty(),
        ).joinToString(",") + "\n"

        private fun parseCsvLine(line: String): TrackPoint? {
            val f = line.split(',')
            if (f.size != 5) return null
            return TrackPoint(
                timeEpochMs = f[0].toLongOrNull() ?: return null,
                latitude = f[1].toDoubleOrNull() ?: return null,
                longitude = f[2].toDoubleOrNull() ?: return null,
                altitudeMeters = f[3].takeIf { it.isNotEmpty() }?.let { it.toDoubleOrNull() ?: return null },
                heartRateBpm = f[4].takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: return null },
            )
        }

        private fun sha256(file: File): String =
            MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    }
}
