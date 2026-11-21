package mx.watchcompanion.phone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import mx.watchcompanion.core.communication.LinkState
import mx.watchcompanion.integration.velora.SessionState
import mx.watchcompanion.integration.velora.SignOutReason
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PhoneHomeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun conectadoMuestraRelojLatenciaYVersion() {
        compose.setContent { PhoneHome(LinkState.Connected("Galaxy Watch Ultra", "0.1.0", 42, 0), onCheck = {}) }
        compose.onNodeWithText("Conectado a Galaxy Watch Ultra").assertIsDisplayed()
        compose.onNodeWithText("42 ms · v0.1.0").assertIsDisplayed()
    }

    @Test
    fun sinRelojExplicaQueHacer() {
        compose.setContent { PhoneHome(LinkState.NoPeer, onCheck = {}) }
        compose.onNodeWithText("No encuentro el reloj. Instala Watch Companion en el Galaxy Watch.").assertIsDisplayed()
    }

    @Test
    fun incompatiblePideActualizar() {
        compose.setContent { PhoneHome(LinkState.Incompatible, onCheck = {}) }
        compose.onNodeWithText("Actualiza la app en el reloj o en el teléfono").assertIsDisplayed()
    }

    @Test
    fun elBotonPruebaLaConexionYSeDesactivaMientrasComprueba() {
        var pruebas = 0
        compose.setContent { PhoneHome(LinkState.Unreachable("Galaxy Watch Ultra"), onCheck = { pruebas++ }) }
        compose.onNodeWithText("Galaxy Watch Ultra no responde").assertIsDisplayed()
        compose.onNodeWithText("Probar conexión").performClick()
        assertEquals(1, pruebas)
    }

    @Test
    fun mientrasCompruebaNoSePuedeRepetir() {
        compose.setContent { PhoneHome(LinkState.Checking, onCheck = {}) }
        compose.onNodeWithText("Probar conexión").assertIsNotEnabled()
    }

    @Test
    fun sinSesionDeVeloraOfreceEntrar() {
        var entrar = 0
        compose.setContent { PhoneHome(LinkState.NoPeer, onCheck = {}, velora = SessionState.SignedOut(), onVeloraLogin = { entrar++ }) }
        compose.onNodeWithText("Sin sesión").assertIsDisplayed()
        compose.onNodeWithText("Iniciar sesión en Velora").performClick()
        assertEquals(1, entrar)
    }

    @Test
    fun unaSesionVencidaLoExplica() {
        compose.setContent { PhoneHome(LinkState.NoPeer, onCheck = {}, velora = SessionState.SignedOut(SignOutReason.EXPIRED)) }
        compose.onNodeWithText("Tu sesión de Velora terminó. Vuelve a entrar.").assertIsDisplayed()
    }

    @Test
    fun conSesionMuestraElCorreoYPermiteSalir() {
        var salir = 0
        compose.setContent {
            PhoneHome(
                LinkState.NoPeer,
                onCheck = {},
                velora = SessionState.SignedIn("https://velora.example", "propietario@example.com"),
                onVeloraLogout = { salir++ },
            )
        }
        compose.onNodeWithText("Conectado como propietario@example.com").assertIsDisplayed()
        compose.onNodeWithText("Cerrar sesión").performClick()
        assertEquals(1, salir)
    }
}
