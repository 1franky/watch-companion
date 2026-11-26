package mx.watchcompanion.watch.guardian

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import mx.watchcompanion.core.communication.GuardianCommandResult.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuardianHistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private var ahora = 100 * GuardianHistory.DAY_MS
    private var dias = 7
    private val file get() = File(tmp.root, "guardian-historial.json")
    private fun history() = GuardianHistory(file, retentionDays = { dias }, clock = { ahora })

    @Test
    fun guardaDelMasRecienteAlMasAntiguoYSobreviveAlReinicio() = runTest {
        val h = history()
        h.record(GuardianEvent(ahora - 2_000, GuardianEvent.Kind.PHONE_AWAY))
        h.record(GuardianEvent(ahora - 1_000, GuardianEvent.Kind.PHONE_BACK))

        val otra = history().also { it.load() }

        assertEquals(listOf(GuardianEvent.Kind.PHONE_BACK, GuardianEvent.Kind.PHONE_AWAY), otra.events.value.map { it.kind })
    }

    @Test
    fun unaConfirmacionTardiaReemplazaSuEvento() = runTest {
        val h = history()
        h.record(GuardianEvent(ahora, GuardianEvent.Kind.LOCK, Outcome.EXPIRED, ref = "orden-1"))
        h.record(GuardianEvent(ahora + 1, GuardianEvent.Kind.LOCK, Outcome.LOCKED, ref = "orden-1"))

        assertEquals(listOf(Outcome.LOCKED), h.events.value.map { it.outcome })
    }

    @Test
    fun recortaPorRetencionYAlCambiarla() = runTest {
        val h = history()
        h.record(GuardianEvent(ahora - 8 * GuardianHistory.DAY_MS, GuardianEvent.Kind.PHONE_AWAY))
        h.record(GuardianEvent(ahora - 3 * GuardianHistory.DAY_MS, GuardianEvent.Kind.PHONE_AWAY))
        h.record(GuardianEvent(ahora - 1_000, GuardianEvent.Kind.PHONE_BACK))
        assertEquals(2, h.events.value.size)

        dias = 1
        h.prune()

        assertEquals(listOf(GuardianEvent.Kind.PHONE_BACK), h.events.value.map { it.kind })
    }

    @Test
    fun noPasaDelMaximo() = runTest {
        val h = history()
        repeat(GuardianHistory.MAX_EVENTS + 5) { h.record(GuardianEvent(ahora - it, GuardianEvent.Kind.PHONE_AWAY)) }
        assertEquals(GuardianHistory.MAX_EVENTS, h.events.value.size)
        assertEquals(ahora, h.events.value.first().at)
    }

    @Test
    fun borrarEliminaElArchivo() = runTest {
        val h = history()
        h.record(GuardianEvent(ahora, GuardianEvent.Kind.PHONE_AWAY))
        assertTrue(file.exists())

        h.clear()

        assertFalse(file.exists())
        assertTrue(h.events.value.isEmpty())
    }

    @Test
    fun unArchivoDanadoEmpiezaDeNuevo() = runTest {
        file.writeText("{no es json")
        val h = history().also { it.load() }
        assertTrue(h.events.value.isEmpty())
    }

    /** Sin datos de salud ni ubicación (criterio de WC-47): agregar un campo obliga a revisar esta lista. */
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun losEventosSoloTienenQueYCuando() {
        assertEquals(listOf("at", "kind", "outcome", "code", "ref"), GuardianEvent.serializer().descriptor.elementNames.toList())
    }

    @Test
    fun laRetencionRota() {
        assertEquals(7, GuardianHistory.nextRetention(1))
        assertEquals(30, GuardianHistory.nextRetention(7))
        assertEquals(1, GuardianHistory.nextRetention(30))
    }
}
