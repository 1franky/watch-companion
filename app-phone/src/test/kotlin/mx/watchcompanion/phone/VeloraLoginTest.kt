package mx.watchcompanion.phone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import mx.watchcompanion.core.session.LoginResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class VeloraLoginTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun enviaCorreoYContrasenaAlServidorDeProduccion() {
        var enviado: Triple<String, String, String>? = null
        compose.setContent {
            BackendLogin(backend = "Velora", defaultServer = "https://velora.example", showServerField = false, busy = false, error = null, onSubmit = { s, e, p -> enviado = Triple(s, e, p) }, onCancel = {})
        }
        compose.onNodeWithText("Servidor (solo desarrollo)").assertDoesNotExist()
        compose.onNodeWithText("Entrar").assertIsNotEnabled()
        compose.onNodeWithText("Correo").performTextInput(" propietario@example.com ")
        compose.onNodeWithText("Contraseña").performTextInput("secreta")
        compose.onNodeWithText("Entrar").performClick()
        assertEquals(Triple("https://velora.example", "propietario@example.com", "secreta"), enviado)
    }

    @Test
    fun enDebugSePuedeCambiarElServidor() {
        compose.setContent { BackendLogin(backend = "Velora", defaultServer = "https://velora.example", showServerField = true, busy = false, error = null, onSubmit = { _, _, _ -> }, onCancel = {}) }
        compose.onNodeWithText("Servidor (solo desarrollo)").assertIsDisplayed()
    }

    @Test
    fun muestraCadaErrorConUnMensajeClaro() {
        val casos = mapOf(
            LoginResult.InvalidCredentials to "Correo o contraseña incorrectos.",
            LoginResult.TooManyAttempts(60) to "Demasiados intentos. Espera 60 s.",
            LoginResult.TooManyAttempts(null) to "Demasiados intentos. Espera un momento.",
            LoginResult.InsecureServer to "El servidor debe usar HTTPS.",
            LoginResult.Unreachable to "No hay conexión con Velora.",
            LoginResult.Unavailable to "Velora no está disponible. Intenta más tarde.",
        )
        var actual by mutableStateOf<LoginResult?>(null)
        compose.setContent { BackendLogin(backend = "Velora", defaultServer = "https://velora.example", showServerField = false, busy = false, error = actual, onSubmit = { _, _, _ -> }, onCancel = {}) }
        casos.forEach { (resultado, mensaje) ->
            actual = resultado
            compose.onNodeWithText(mensaje).assertIsDisplayed()
        }
    }

    @Test
    fun mientrasEntraNoSePuedeRepetirNiCancelar() {
        compose.setContent { BackendLogin(backend = "Velora", defaultServer = "https://velora.example", showServerField = false, busy = true, error = null, onSubmit = { _, _, _ -> }, onCancel = {}) }
        compose.onNodeWithText("Cancelar").assertIsNotEnabled()
    }
}
