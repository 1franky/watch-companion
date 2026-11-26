package mx.watchcompanion.phone

import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.Modifier
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import mx.watchcompanion.core.communication.LinkState
import mx.watchcompanion.core.diagnostics.DiagnosticEvent
import mx.watchcompanion.core.diagnostics.DiagnosticModule
import mx.watchcompanion.core.diagnostics.Outcome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class DiagnosticsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val id = "6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10"

    private fun event(at: Long, module: DiagnosticModule, operation: String, outcome: Outcome, code: String) =
        DiagnosticEvent(at, module, "0.1.0", operation, id, outcome, code, durationMs = 120)

    @Test
    fun sinEventosLoDice() {
        compose.setContent { DiagnosticsScreen(emptyList(), onClear = {}, onBack = {}) }
        compose.onNodeWithText("Sin eventos registrados.").assertIsDisplayed()
    }

    @Test
    fun muestraElEstadoDeSincronizacionYLosEventos() {
        val events = listOf(
            event(3, DiagnosticModule.LINK, "link.ping", Outcome.ERROR, "NO_PEER"),
            event(2, DiagnosticModule.VELORA, "velora.refresh", Outcome.OK, "OK"),
            event(1, DiagnosticModule.VELORA, "velora.refresh", Outcome.ERROR, "UNREACHABLE"),
        )
        compose.setContent { DiagnosticsScreen(events, onClear = {}, onBack = {}, formatTime = { "t$it" }) }

        compose.onNodeWithText("Enlace con el reloj").assertIsDisplayed()
        compose.onNodeWithText("Sin éxitos registrados").assertIsDisplayed()
        compose.onNodeWithText("Error NO_PEER (t3)").assertIsDisplayed()
        compose.onNodeWithText("Último éxito: t2").assertIsDisplayed()
        // El error anterior al último éxito ya no está pendiente.
        compose.onNodeWithText("Error UNREACHABLE (t1)").assertDoesNotExist()
        compose.onNodeWithText("Eventos (3)").assertIsDisplayed()
        compose.onNodeWithText("t1 · velora.refresh").assertExists()
        compose.onNodeWithText("UNREACHABLE · 120 ms · $id").assertExists()
    }

    @Test
    fun borrarYVolver() {
        var cleared = false
        var back = false
        compose.setContent {
            DiagnosticsScreen(
                listOf(event(1, DiagnosticModule.LINK, "link.ping", Outcome.OK, "CONNECTED")),
                onClear = { cleared = true },
                onBack = { back = true },
            )
        }
        compose.onNodeWithText("Borrar registro").performClick()
        compose.onNodeWithText("Volver").performClick()
        assertEquals(true to true, cleared to back)
    }

    @Test
    fun elInicioAbreElDiagnostico() {
        var opened = false
        compose.setContent {
            // En la app, la pantalla principal va dentro de una columna desplazable.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PhoneHome(LinkState.NoPeer, onCheck = {}, onDiagnostics = { opened = true })
            }
        }
        compose.onNodeWithText("Diagnóstico").performScrollTo().performClick()
        assertEquals(true, opened)
    }
}
