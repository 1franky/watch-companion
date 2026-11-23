package mx.watchcompanion.core.communication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateZonesTest {
    @Test
    fun lasZonasDeVeloraSalenDeLaFcMaximaConHrZones1() {
        val zonas = HeartRateZones.fromMax(185, HeartRateZones.Source.VELORA, updatedAt = 0)
        // 50, 60, 70, 80 y 90 % de 185, redondeados: igual que las analíticas de Velora (ADR-020 de Velora).
        assertEquals(listOf(93, 111, 130, 148, 167), zonas.lowerBoundsBpm)
    }

    @Test
    fun zonaDeUnaLectura() {
        val zonas = HeartRateZones.fromMax(200, HeartRateZones.Source.VELORA, updatedAt = 0) // 100, 120, 140, 160, 180
        assertNull(zonas.zoneOf(null))
        assertNull(zonas.zoneOf(99))
        assertEquals(1, zonas.zoneOf(100))
        assertEquals(2, zonas.zoneOf(139 - 19))
        assertEquals(3, zonas.zoneOf(159))
        assertEquals(5, zonas.zoneOf(180))
        assertEquals(5, zonas.zoneOf(215)) // por encima de la máxima sigue siendo Z5
    }

    @Test(expected = IllegalArgumentException::class)
    fun fcMaximaFueraDelRangoDeVelora() {
        HeartRateZones.fromMax(240, HeartRateZones.Source.MANUAL, updatedAt = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun limitesQueNoCrecen() {
        HeartRateZones(180, listOf(90, 110, 110, 140, 160), HeartRateZones.Source.MANUAL, updatedAt = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun z5PorEncimaDeLaMaxima() {
        HeartRateZones(150, listOf(90, 100, 110, 120, 160), HeartRateZones.Source.MANUAL, updatedAt = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun numeroDeLimitesIncorrecto() {
        HeartRateZones(180, listOf(90, 110, 130, 150), HeartRateZones.Source.MANUAL, updatedAt = 0)
    }
}
