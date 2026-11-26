package mx.watchcompanion.watch.finance

import java.io.File
import java.nio.file.Files
import mx.watchcompanion.core.communication.FinanceFigures
import mx.watchcompanion.core.communication.FinanceSnapshot
import mx.watchcompanion.core.session.SecretCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceCacheTest {
    /** Sustituto del Keystore (no existe fuera del dispositivo): invierte los bytes y los marca. */
    private class CifradoDePrueba : SecretCipher {
        var falla = false
        override fun encrypt(plain: ByteArray): ByteArray {
            check(!falla)
            return byteArrayOf(7) + plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
        override fun decrypt(data: ByteArray): ByteArray {
            check(!falla && data.first() == 7.toByte())
            return data.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    private val dir = Files.createTempDirectory("finanzas").toFile()
    private val archivo = File(dir, "finanzas-nexora.bin")
    private val cifrado = CifradoDePrueba()
    private var ahora = 1_000_000L
    private val cache = FinanceCache(archivo, cifrado) { ahora }

    private val resumen = FinanceSnapshot(
        FinanceSnapshot.State.OK,
        checkedAt = ahora,
        figures = FinanceFigures(ahora, "2026-11", "15230.50", "4800.00", "9120.75"),
    )

    @Test
    fun guardaCifradoYLoRecupera() {
        cache.save(resumen)
        val bytes = archivo.readBytes().decodeToString()
        assertFalse("En disco no debe quedar ningún importe en claro", bytes.contains("15230") || bytes.contains("availableBalance"))
        assertEquals(resumen, cache.load())
    }

    @Test
    fun caducadoSeBorraEnVezDeLeerse() {
        cache.save(resumen)
        ahora += FinanceSnapshot.EXPIRE_AFTER_MS + 1
        assertNull(cache.load())
        assertFalse(archivo.exists())
    }

    @Test
    fun cerrarSesionEnNexoraBorraLaCopia() {
        cache.save(resumen)
        cache.save(FinanceSnapshot(FinanceSnapshot.State.SIGNED_OUT, checkedAt = ahora))
        assertFalse(archivo.exists())
        assertNull(cache.load())
    }

    @Test
    fun unResumenSinCifrasNoSeGuardaYBorraLoAnterior() {
        cache.save(resumen)
        cache.save(FinanceSnapshot(FinanceSnapshot.State.ERROR, checkedAt = ahora, errorCode = "UNREACHABLE"))
        assertFalse(archivo.exists())
    }

    @Test
    fun siNoSePuedeLeerSeBorra() {
        cache.save(resumen)
        cifrado.falla = true
        assertNull(cache.load())
        assertFalse(archivo.exists())
    }

    @Test
    fun siNoSePuedeCifrarNoQuedaUnaCopiaVieja() {
        cache.save(resumen)
        assertTrue(archivo.exists())
        cifrado.falla = true
        cache.save(resumen.copy(checkedAt = ahora + 1))
        assertFalse(archivo.exists())
        assertFalse(File(dir, "finanzas-nexora.bin.tmp").exists())
    }
}
