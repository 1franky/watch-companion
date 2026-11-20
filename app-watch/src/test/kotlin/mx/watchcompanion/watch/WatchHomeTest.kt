package mx.watchcompanion.watch

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import mx.watchcompanion.core.communication.LinkState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w227dp-h227dp-small-notlong-round-watch-xhdpi-keyshidden-nonav")
class WatchHomeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun conectadoMuestraTelefonoLatenciaYVersion() {
        compose.setContent { WatchHome(LinkState.Connected("Galaxy S26 Ultra", "0.1.0", 42, 0), onCheck = {}) }
        compose.onNodeWithText("Conectado a Galaxy S26 Ultra").assertIsDisplayed()
        compose.onNodeWithText("42 ms · v0.1.0").assertIsDisplayed()
    }

    @Test
    fun sinTelefonoExplicaQueHacer() {
        compose.setContent { WatchHome(LinkState.NoPeer, onCheck = {}) }
        compose.onNodeWithText("No encuentro el teléfono. Instala Watch Companion en él.").assertIsDisplayed()
    }

    @Test
    fun incompatiblePideActualizar() {
        compose.setContent { WatchHome(LinkState.Incompatible, onCheck = {}) }
        compose.onNodeWithText("Actualiza la app en el reloj o en el teléfono").assertIsDisplayed()
    }

    @Test
    fun elBotonPruebaLaConexionYSeDesactivaMientrasComprueba() {
        var pruebas = 0
        compose.setContent { WatchHome(LinkState.Unreachable("Galaxy S26 Ultra"), onCheck = { pruebas++ }) }
        compose.onNodeWithText("Galaxy S26 Ultra no responde").assertIsDisplayed()
        compose.onNodeWithText("Probar conexión").performClick()
        assertEquals(1, pruebas)
    }

    @Test
    fun mientrasCompruebaNoSePuedeRepetir() {
        compose.setContent { WatchHome(LinkState.Checking, onCheck = {}) }
        compose.onNodeWithText("Probar conexión").assertIsNotEnabled()
    }
}
