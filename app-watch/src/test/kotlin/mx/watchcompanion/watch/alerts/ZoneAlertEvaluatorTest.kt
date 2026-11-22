package mx.watchcompanion.watch.alerts

import mx.watchcompanion.core.communication.HeartRateZones
import mx.watchcompanion.watch.alerts.ZoneAlert.Direction.DOWN
import mx.watchcompanion.watch.alerts.ZoneAlert.Direction.UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZoneAlertEvaluatorTest {
    // FC máxima 200: Z1 desde 100, Z2 120, Z3 140, Z4 160, Z5 180.
    private val zonas = HeartRateZones.fromMax(200, HeartRateZones.Source.VELORA, 0)
    private val evaluador = ZoneAlertEvaluator(hysteresisBpm = 3, dwellMs = 5_000)

    /** Lecturas cada segundo desde [inicio]; devuelve las alertas que hubo. */
    private fun lecturas(vararg bpm: Int?, inicio: Long = 0): List<ZoneAlert> =
        bpm.mapIndexedNotNull { i, b -> evaluador.onReading(b, zonas, inicio + i * 1_000L) }

    @Test
    fun laPrimeraLecturaFijaLaZonaSinAvisar() {
        assertEquals(emptyList<ZoneAlert>(), lecturas(150))
        assertEquals(3, evaluador.currentZone)
    }

    @Test
    fun subirDeZonaAvisaTrasLaPermanencia() {
        val alertas = lecturas(135, 141, 142, 143, 142, 141, 142)
        // 141 a los 1 s; se confirma a los 6 s (5 s en Z3).
        assertEquals(listOf(ZoneAlert(UP, 2, 3)), alertas)
    }

    @Test
    fun bajarDeZonaAvisaConOtraDireccion() {
        val alertas = lecturas(145, 130, 130, 130, 130, 130, 130)
        assertEquals(listOf(ZoneAlert(DOWN, 3, 2)), alertas)
    }

    @Test
    fun unPicoSueltoNoAvisa() {
        assertEquals(emptyList<ZoneAlert>(), lecturas(135, 142, 143, 136, 135, 135, 135, 135))
        assertEquals(2, evaluador.currentZone)
    }

    @Test
    fun oscilarAlrededorDelLimiteNoRepiteLaAlerta() {
        lecturas(135)
        val subida = lecturas(140, 141, 140, 141, 140, 141, inicio = 1_000)
        assertEquals(listOf(ZoneAlert(UP, 2, 3)), subida)
        // Ya en Z3: bajar a 138–139 queda dentro de la histéresis (140 − 3): no avisa de bajada.
        val oscilacion = lecturas(139, 138, 140, 139, 138, 139, 140, 138, 139, inicio = 7_000)
        assertEquals(emptyList<ZoneAlert>(), oscilacion)
        assertEquals(3, evaluador.currentZone)
    }

    @Test
    fun bajarMasAllaDeLaHisteresisSiAvisa() {
        lecturas(145)
        assertEquals(listOf(ZoneAlert(DOWN, 3, 2)), lecturas(136, 136, 136, 136, 136, 136, inicio = 1_000))
    }

    @Test
    fun subirVariasZonasSeguidasAvisaUnaVezConLaZonaFinal() {
        lecturas(125)
        val alertas = lecturas(145, 165, 170, 172, 171, 170, inicio = 1_000)
        assertEquals(listOf(ZoneAlert(UP, 2, 4)), alertas)
    }

    @Test
    fun porDebajoDeZ1EsZonaCero() {
        lecturas(105)
        assertEquals(listOf(ZoneAlert(DOWN, 1, 0)), lecturas(90, 90, 90, 90, 90, 90, inicio = 1_000))
    }

    @Test
    fun sinLecturaSeReiniciaLaEsperaPeroSeConservaLaZona() {
        lecturas(135)
        val alertas = lecturas(145, 145, 145, null, 145, 145, 145, 145, inicio = 1_000)
        // La espera se reinicia con la lectura de los 5 s; 5 s después (a los 10 s) aún falta el último segundo.
        assertEquals(emptyList<ZoneAlert>(), alertas)
        assertEquals(listOf(ZoneAlert(UP, 2, 3)), lecturas(145, 145, inicio = 9_000))
    }

    @Test
    fun sinZonasNoHaceNada() {
        assertNull(evaluador.onReading(150, null, 0))
        assertNull(evaluador.currentZone)
    }

    @Test
    fun resetOlvidaLaZona() {
        lecturas(150)
        evaluador.reset()
        assertEquals(emptyList<ZoneAlert>(), lecturas(110, inicio = 100_000))
        assertEquals(1, evaluador.currentZone)
    }
}
