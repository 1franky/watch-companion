package mx.watchcompanion.core.communication

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateZonesStoreTest {
    private val archivo = Files.createTempDirectory("zonas").resolve("zonas.json").toFile()
    private fun zonas(max: Int, at: Long) = HeartRateZones.fromMax(max, HeartRateZones.Source.VELORA, updatedAt = at)

    @Test
    fun guardaYLeeDeArchivo() = runTest {
        HeartRateZonesStore.inFile(archivo).save(zonas(185, 1))
        val otra = HeartRateZonesStore.inFile(archivo).apply { load() }
        assertEquals(zonas(185, 1), otra.zones.value)
    }

    @Test
    fun elRelojSoloAceptaUnaVersionMasReciente() = runTest {
        val store = HeartRateZonesStore.inFile(archivo)
        assertTrue(store.saveIfNewer(zonas(185, 10)))
        assertFalse(store.saveIfNewer(zonas(170, 5)))
        assertFalse(store.saveIfNewer(zonas(170, 10)))
        assertEquals(185, store.zones.value?.maxHeartRateBpm)
        assertTrue(store.saveIfNewer(zonas(190, 11)))
        assertEquals(190, store.zones.value?.maxHeartRateBpm)
    }

    @Test
    fun sinCargarAunCompararContraLoGuardado() = runTest {
        HeartRateZonesStore.inFile(archivo).save(zonas(185, 10))
        val recienArrancada = HeartRateZonesStore.inFile(archivo) // sin load()
        assertFalse(recienArrancada.saveIfNewer(zonas(170, 5)))
        assertEquals(185, recienArrancada.zones.value?.maxHeartRateBpm)
    }

    @Test
    fun unArchivoIlegibleCuentaComoVacio() = runTest {
        archivo.writeText("{basura")
        val store = HeartRateZonesStore.inFile(archivo).apply { load() }
        assertNull(store.zones.value)
    }
}
