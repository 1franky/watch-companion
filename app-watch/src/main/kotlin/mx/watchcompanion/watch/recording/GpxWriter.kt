package mx.watchcompanion.watch.recording

import java.time.Instant
import java.util.Locale

/**
 * GPX 1.1 del track del reloj, en el formato que ya lee el `GpxParser` de Velora: `ele`, `time` y la FC en la extensión
 * `gpxtpx:hr` de Garmin (la misma que usan Strava y la mayoría de los ciclocomputadores).
 *
 * Sin nombre de usuario, modelo ni id de dispositivo: el archivo solo lleva el recorrido (07).
 */
object GpxWriter {
    fun write(startedAtEpochMs: Long, points: List<TrackPoint>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="Watch Companion" xmlns="http://www.topografix.com/GPX/1/1" """)
        append("""xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">""").append('\n')
        append("  <metadata><time>").append(time(startedAtEpochMs)).append("</time></metadata>\n")
        append("  <trk>\n    <type>cycling</type>\n    <trkseg>\n")
        for (p in points) {
            append("""      <trkpt lat="""").append(coordinate(p.latitude))
            append("""" lon="""").append(coordinate(p.longitude)).append("\">")
            p.altitudeMeters?.takeUnless { it.isNaN() }?.let { append("<ele>").append("%.1f".format(Locale.ROOT, it)).append("</ele>") }
            append("<time>").append(time(p.timeEpochMs)).append("</time>")
            p.heartRateBpm?.let {
                append("<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>").append(it)
                append("</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>")
            }
            append("</trkpt>\n")
        }
        append("    </trkseg>\n  </trk>\n</gpx>\n")
    }

    private fun coordinate(value: Double) = "%.7f".format(Locale.ROOT, value)

    private fun time(epochMs: Long) = Instant.ofEpochMilli(epochMs).toString()
}
