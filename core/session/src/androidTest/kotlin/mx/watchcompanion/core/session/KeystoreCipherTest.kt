package mx.watchcompanion.core.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Solo en dispositivo o emulador: `./gradlew :core:session:connectedDebugAndroidTest`. */
@RunWith(AndroidJUnit4::class)
class KeystoreCipherTest {
    @Test
    fun cifraYDescifraConLaLlaveDelKeystore() {
        val cipher = KeystoreCipher("wc-prueba")
        val plano = "refresh-token".encodeToByteArray()
        val cifrado = cipher.encrypt(plano)
        assertFalse(cifrado.decodeToString().contains("refresh-token"))
        assertArrayEquals(plano, cipher.decrypt(cifrado))
    }

    @Test
    fun cadaBackendUsaSuPropiaLlave() {
        val velora = KeystoreCipher(Backend.VELORA.keyAlias).encrypt("x".encodeToByteArray())
        val error = runCatching { KeystoreCipher(Backend.NEXORA.keyAlias).decrypt(velora) }.exceptionOrNull()
        assert(error != null)
    }
}
