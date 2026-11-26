package mx.watchcompanion.phone.drafts

import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import mx.watchcompanion.core.communication.DraftStatus
import mx.watchcompanion.core.communication.DraftStatus.State
import mx.watchcompanion.core.communication.QuickTransactionDraft
import mx.watchcompanion.core.session.SecretCipher
import mx.watchcompanion.core.session.SessionExpiredException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PhoneDraftsTest {
    @get:Rule val tmp = TemporaryFolder()

    private class MemoryStore : DraftStore {
        var saved = emptyList<StoredDraft>()
        override suspend fun read() = saved
        override suspend fun write(drafts: List<StoredDraft>) { saved = drafts }
    }

    private val key = "3d6f0a7e-1b2c-4d5e-8f90-a1b2c3d4e5f6"
    private val borrador = QuickTransactionDraft(
        key, QuickTransactionDraft.Kind.EXPENSE, "250.50", "5b1d6a3e-0c1f-4a2b-9d3e-7f8a9b0c1d2e",
        note = "Tacos", date = "2026-11-25", capturedAt = 0,
    )
    private var now = 1_000L
    private val store = MemoryStore()
    private val enviados = mutableListOf<Pair<UUID, String>>()
    private val avisos = mutableListOf<DraftStatus>()
    private var respuesta: suspend () -> Int = { 201 }
    private var cuerpos = 0

    private fun drafts() = PhoneDrafts(
        store = store,
        buildBody = { cuerpos++; "{\"cuerpo\":$cuerpos}" },
        post = { k, body -> enviados += k to body; respuesta() },
        onChange = { avisos += it },
        clock = { now },
    )

    @Test
    fun recibirNoMandaNadaANexoraYUnReenvioNoLoDuplica() = runTest {
        val drafts = drafts()
        assertEquals(State.RECEIVED, drafts.receive(borrador).state)
        assertEquals(State.RECEIVED, drafts.receive(borrador).state)
        assertEquals(1, drafts.drafts.value.size)
        assertTrue(enviados.isEmpty())
        assertEquals(1, store.saved.size)
    }

    @Test
    fun confirmarLoRegistraConLaClaveDelRelojYAvisa() = runTest {
        val drafts = drafts()
        drafts.receive(borrador)
        val status = drafts.confirm(key)!!
        assertEquals(State.CREATED, status.state)
        assertEquals(listOf(UUID.fromString(key) to "{\"cuerpo\":1}"), enviados)
        assertEquals(listOf(status), avisos)
        assertNull("Registrado: ya no se confirma otra vez", drafts.confirm(key))
        assertNull("Registrado: ya no se descarta", drafts.discard(key))
    }

    @Test
    fun sinRespuestaQuedaEnDudaYElReintentoMandaElMismoCuerpoConLaMismaClave() = runTest {
        val drafts = drafts()
        drafts.receive(borrador)
        respuesta = { throw IOException("sin red") }
        val duda = drafts.confirm(key)!!
        assertEquals(State.UNKNOWN, duda.state)
        assertEquals("UNREACHABLE", duda.errorCode)
        assertNull("Tras un envío sin respuesta no se puede descartar", drafts.discard(key))

        respuesta = { 201 }
        assertEquals(State.CREATED, drafts.confirm(key)?.state)
        assertEquals(2, enviados.size)
        assertEquals(enviados[0], enviados[1])
        assertEquals("El cuerpo se arma una sola vez", 1, cuerpos)
    }

    @Test
    fun cadaRespuestaDeNexoraTieneSuEstado() = runTest {
        val casos = mapOf(
            409 to (State.CONFLICT to "HTTP_409"),
            400 to (State.REJECTED to "HTTP_400"),
            404 to (State.REJECTED to "HTTP_404"),
            503 to (State.UNKNOWN to "HTTP_503"),
        )
        for ((codigo, esperado) in casos) {
            store.saved = emptyList()
            val drafts = drafts()
            drafts.receive(borrador)
            respuesta = { codigo }
            val status = drafts.confirm(key)!!
            assertEquals("HTTP $codigo", esperado, status.state to status.errorCode)
        }
    }

    @Test
    fun sinSesionNoAvanzaYSePuedeDescartar() = runTest {
        val drafts = drafts()
        drafts.receive(borrador)
        respuesta = { throw SessionExpiredException("Nexora") }
        val status = drafts.confirm(key)!!
        assertEquals(State.RECEIVED, status.state)
        assertEquals("SIGNED_OUT", status.errorCode)
        assertEquals(State.DISCARDED, drafts.discard(key)?.state)
        assertNull(drafts.confirm(key))
    }

    @Test
    fun unDobleToqueNoMandaDosVeces() = runTest {
        val drafts = drafts()
        drafts.receive(borrador)
        val gate = CompletableDeferred<Int>()
        respuesta = { gate.await() }
        val primero = async { drafts.confirm(key) }
        testScheduler.runCurrent()
        assertTrue(key in drafts.sending.value)
        assertNull(drafts.confirm(key))
        gate.complete(201)
        assertEquals(State.CREATED, primero.await()?.state)
        assertEquals(1, enviados.size)
        assertFalse(key in drafts.sending.value)
    }

    @Test
    fun losTerminadosSeOlvidanAlDiaSiguiente() = runTest {
        val drafts = drafts()
        drafts.receive(borrador)
        drafts.confirm(key)
        now += PhoneDrafts.KEEP_FINAL_MS + 1
        drafts.load()
        assertTrue(drafts.drafts.value.isEmpty())
    }

    @Test
    fun elArchivoVaCifradoYSeLeeDeNuevo() = runTest {
        val cifrado = object : SecretCipher {
            override fun encrypt(plain: ByteArray) = plain.reversedArray()
            override fun decrypt(data: ByteArray) = data.reversedArray()
        }
        val file = File(tmp.root, "borradores.bin")
        val archivo = EncryptedFileDraftStore(file, cifrado)
        val guardado = listOf(StoredDraft(borrador, State.RECEIVED, 5, requestBody = "{}"))
        archivo.write(guardado)
        assertFalse("La nota no queda en claro", file.readBytes().decodeToString().contains("Tacos"))
        assertEquals(guardado, archivo.read())

        file.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals("Ilegible: se trata como vacío", emptyList<StoredDraft>(), archivo.read())
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
    }

    @Test
    fun suTextoNoLlevaElCuerpoNiElImporte() {
        val texto = StoredDraft(borrador.copy(note = "Cena aniversario"), DraftStatus.State.UNKNOWN, 0, "UNREACHABLE", "{\"amount\":\"250.50\"}").toString()
        assertFalse(texto, texto.contains("250") || texto.contains("Cena") || texto.contains("amount"))
    }
}
