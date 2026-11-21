package mx.watchcompanion.core.session

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DataStoreSessionStoreTest {
    /** Cifrado de prueba reversible (XOR); el real con Keystore se prueba en androidTest. */
    private class XorCipher(private val key: Byte = 0x5A) : SecretCipher {
        var broken = false
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor key.toInt()).toByte() }.toByteArray()
        override fun decrypt(data: ByteArray): ByteArray {
            if (broken) error("llave perdida")
            return encrypt(data)
        }
    }

    private val dir: File = Files.createTempDirectory("sesion").toFile()
    private val sesion = StoredSession("https://velora.example", "propietario@example.com", "refresh-1")

    private fun TestScope.store(cipher: SecretCipher) = DataStoreSessionStore(
        PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(dir, "sesion.preferences_pb")
        },
        cipher,
    )

    @Test
    fun guardaLeeYBorraLaSesion() = runTest {
        val store = store(XorCipher())
        assertNull(store.read())
        store.write(sesion)
        assertEquals(sesion, store.read())
        store.clear()
        assertNull(store.read())
    }

    @Test
    fun enDiscoNoQuedaNadaEnClaro() = runTest {
        store(XorCipher()).write(sesion)
        val contenido = dir.listFiles()!!.joinToString { it.readBytes().decodeToString() }
        assertFalse(contenido.contains("refresh-1"))
        assertFalse(contenido.contains("propietario@example.com"))
    }

    @Test
    fun siLaLlaveSePierdeHayQueVolverAEntrar() = runTest {
        val cipher = XorCipher()
        val store = store(cipher)
        store.write(sesion)
        cipher.broken = true
        assertNull(store.read())
    }
}
