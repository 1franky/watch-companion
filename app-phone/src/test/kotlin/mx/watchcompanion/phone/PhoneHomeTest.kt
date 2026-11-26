package mx.watchcompanion.phone

import androidx.compose.ui.Modifier
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import mx.watchcompanion.core.communication.LinkState
import mx.watchcompanion.core.session.SessionState
import mx.watchcompanion.core.session.SignOutReason
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

    @Test
    fun nexoraTieneSuPropiaSesionIndependienteDeVelora() {
        var entrar = 0
        var salir = 0
        var nexora by mutableStateOf<SessionState>(SessionState.SignedOut())
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PhoneHome(
                    LinkState.NoPeer,
                    onCheck = {},
                    velora = SessionState.SignedIn("https://velora.example", "velora@example.com"),
                    nexora = nexora,
                    onNexoraLogin = { entrar++ },
                    onNexoraLogout = { salir++ },
                )
            }
        }
        compose.onNodeWithText("Iniciar sesión en Nexora").performScrollTo().performClick()
        assertEquals(1, entrar)

        nexora = SessionState.SignedIn("https://nexora.example", "nexora@example.com")
        compose.onNodeWithText("Conectado como nexora@example.com").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Conectado como velora@example.com").assertExists()
        compose.onNodeWithText("Cerrar sesión y revocarla").performScrollTo().performClick()
        assertEquals(1, salir)

        nexora = SessionState.SignedOut(SignOutReason.EXPIRED)
        compose.onNodeWithText("Tu sesión de Nexora terminó. Vuelve a entrar.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun conSesionDeNexoraYSinPermisoOfreceActivarLosAvisos() {
        var pedir = 0
        var permitido by mutableStateOf(false)
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PhoneHome(
                    LinkState.NoPeer,
                    onCheck = {},
                    nexora = SessionState.SignedIn("https://nexora.example", "nexora@example.com"),
                    alertsAllowed = permitido,
                    onAllowAlerts = { pedir++ },
                )
            }
        }
        compose.onNodeWithText("Permitir avisos").performScrollTo().performClick()
        assertEquals(1, pedir)
        permitido = true
        compose.onNodeWithText("Permitir avisos").assertDoesNotExist()
    }

    @Test
    fun sinSesionDeNexoraNoPideElPermisoDeAvisos() {
        compose.setContent { PhoneHome(LinkState.NoPeer, onCheck = {}, alertsAllowed = false) }
        compose.onNodeWithText("Permitir avisos").assertDoesNotExist()
    }
}
