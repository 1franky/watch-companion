package mx.watchcompanion.core.diagnostics

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    private class MemoryStorage(var text: String? = null) : DiagnosticsStorage {
        override suspend fun read() = text
        override suspend fun write(text: String) { this.text = text }
    }

    private var clock = 1_000_000L
    private val id = "6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10"

    private fun diagnostics(storage: DiagnosticsStorage = MemoryStorage(), capacity: Int = 200) =
        Diagnostics(storage, appVersion = "0.1.0", clock = { clock }, capacity = capacity)

    @Test
    fun soloTieneLosCamposPermitidosPorLaPolitica() {
        // 07-security-and-privacy.md §4. Si esta prueba falla, se está cambiando qué se registra: revisa la política.
        val campos = DiagnosticEvent.serializer().descriptor.let { d -> (0 until d.elementsCount).map(d::getElementName) }
        assertEquals(
            listOf("at", "module", "appVersion", "operation", "correlationId", "outcome", "code", "durationMs"),
            campos,
        )
    }

    @Test
    fun registraYPersisteDelMasRecienteAlMasAntiguo() = runTest {
        val storage = MemoryStorage()
        val diagnostics = diagnostics(storage)
        diagnostics.record(DiagnosticModule.LINK, "link.ping", id, Outcome.OK, "CONNECTED", 42)
        clock += 1_000
        diagnostics.record(DiagnosticModule.VELORA, "velora.login", id, Outcome.ERROR, "HTTP_503", 120)

        assertEquals(listOf("velora.login", "link.ping"), diagnostics.events.value.map { it.operation })

        val reloaded = diagnostics(storage)
        reloaded.load()
        assertEquals(diagnostics.events.value, reloaded.events.value)
    }

    @Test
    fun noGuardaTokensCoordenadasNiDatosFinancieros() = runTest {
        val storage = MemoryStorage()
        val diagnostics = diagnostics(storage)
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJh"
        val prohibidos = listOf(
            jwt,
            "Bearer $jwt",
            "19.432608,-99.133209", // coordenadas
            "4152313412345678", // tarjeta
            "p@example.com",
            "MXN 1,250.00",
        )
        for (valor in prohibidos) {
            diagnostics.record(DiagnosticModule.VELORA, valor, valor, Outcome.ERROR, valor, 1)
            diagnostics.record(DiagnosticModule.NEXORA, "nexora.balance", id, Outcome.ERROR, valor, 1)
        }

        val guardado = storage.text.orEmpty()
        for (valor in prohibidos) assertFalse("se guardó «$valor»", guardado.contains(valor))
        assertFalse(guardado.contains("4152"))
        assertFalse(guardado.contains("19.43"))
        assertTrue(diagnostics.events.value.all { it.code == DiagnosticEvent.INVALID_CODE })
    }

    @Test
    fun respetaLaCapacidadYLaRetencion() = runTest {
        val diagnostics = diagnostics(capacity = 3)
        repeat(5) {
            diagnostics.record(DiagnosticModule.LINK, "link.ping", id, Outcome.OK, "CONNECTED", 1)
            clock += 1
        }
        assertEquals(3, diagnostics.events.value.size)

        clock += Diagnostics.DEFAULT_RETENTION_MS
        diagnostics.record(DiagnosticModule.VELORA, "velora.login", id, Outcome.OK, "OK", 1)
        assertEquals(listOf("velora.login"), diagnostics.events.value.map { it.operation })
    }

    @Test
    fun unArchivoIlegibleSeDescartaSinRomper() = runTest {
        val diagnostics = diagnostics(MemoryStorage("{no es json"))
        diagnostics.load()
        assertTrue(diagnostics.events.value.isEmpty())

        val manipulado = diagnostics(
            MemoryStorage(
                """[{"at":1000000,"module":"VELORA","appVersion":"0.1.0","operation":"velora.login",
                "correlationId":"$id","outcome":"ERROR","code":"token secreto","durationMs":1}]""",
            ),
        )
        manipulado.load()
        assertTrue(manipulado.events.value.isEmpty())
    }

    @Test
    fun borrarVaciaElRegistroGuardado() = runTest {
        val storage = MemoryStorage()
        val diagnostics = diagnostics(storage)
        diagnostics.record(DiagnosticModule.LINK, "link.ping", id, Outcome.OK, "CONNECTED", 1)
        diagnostics.clear()
        assertTrue(diagnostics.events.value.isEmpty())
        assertEquals("[]", storage.text)
    }

    @Test
    fun elEstadoDeSincronizacionMuestraElErrorSoloSiEsPosteriorAlUltimoExito() = runTest {
        val diagnostics = diagnostics()
        diagnostics.record(DiagnosticModule.VELORA, "velora.refresh", id, Outcome.ERROR, "UNREACHABLE", 1)
        clock += 10
        diagnostics.record(DiagnosticModule.VELORA, "velora.refresh", id, Outcome.OK, "OK", 1)
        clock += 10
        diagnostics.record(DiagnosticModule.LINK, "link.ping", id, Outcome.ERROR, "NO_PEER", 1)

        val (link, velora) = SyncStatus.from(diagnostics.events.value)
        assertEquals(DiagnosticModule.LINK, link.module)
        assertNull(link.lastSuccessAt)
        assertEquals("NO_PEER", link.pendingError?.code)
        assertEquals(1_000_010L, velora.lastSuccessAt)
        assertNull(velora.pendingError)
    }

    @Test
    fun elSpanMideLaDuracion() = runTest {
        val diagnostics = diagnostics()
        val span = diagnostics.start(DiagnosticModule.LINK, "link.ping", clock = { clock })
        clock += 250
        span.ok("CONNECTED")
        val event = diagnostics.events.value.single()
        assertEquals(250L, event.durationMs)
        assertEquals(span.correlationId, event.correlationId)
    }

    @Test
    fun elArchivoSeReemplazaCompleto() = runTest {
        val file = File(createTempDirectory().toFile(), "diagnostico.json")
        val diagnostics = diagnostics(FileDiagnosticsStorage(file))
        diagnostics.record(DiagnosticModule.LINK, "link.ping", id, Outcome.OK, "CONNECTED", 1)
        diagnostics.record(DiagnosticModule.LINK, "link.ping", id, Outcome.OK, "CONNECTED", 1)

        val reloaded = diagnostics(FileDiagnosticsStorage(file))
        reloaded.load()
        assertEquals(2, reloaded.events.value.size)
        assertFalse(File(file.parentFile, "diagnostico.json.tmp").exists())
    }
}
