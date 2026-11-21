package mx.watchcompanion.watch

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import mx.watchcompanion.core.communication.LinkState
import mx.watchcompanion.core.permissions.ModuleStatus
import mx.watchcompanion.watch.live.ActiveDuration
import mx.watchcompanion.watch.live.EndReason
import mx.watchcompanion.watch.live.HeartRateAvailability
import mx.watchcompanion.watch.live.LiveMetricsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w227dp-h227dp-small-notlong-round-watch-xhdpi-keyshidden-nonav")
class MetricsScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun active(hr: Int?, availability: HeartRateAvailability = HeartRateAvailability.AVAILABLE, paused: Boolean = false) =
        LiveMetricsState.Active(hr, availability, ActiveDuration(atEpochMs = 0, activeMs = 3_753_000, running = !paused), paused)

    @Test
    fun muestraFrecuenciaYDuracionQueAvanza() {
        compose.setContent { MetricsScreen(active(132), now = 7_000, onAction = {}) }
        compose.onNodeWithText("132").assertIsDisplayed()
        compose.onNodeWithText("lpm").assertIsDisplayed()
        compose.onNodeWithText("1:02:40").assertIsDisplayed() // 3 753 s + 7 s
    }

    @Test
    fun sinLecturaMuestraGuionesYElMotivoNuncaCero() {
        compose.setContent { MetricsScreen(active(null, HeartRateAvailability.ACQUIRING), now = 0, onAction = {}) }
        compose.onNodeWithText("--").assertIsDisplayed()
        compose.onNodeWithText("Buscando pulso…").assertIsDisplayed()
        compose.onAllNodesWithText("0").assertCountEquals(0)
    }

    @Test
    fun relojFueraDeLaMunecaSeExplica() {
        compose.setContent { MetricsScreen(active(null, HeartRateAvailability.OFF_BODY), now = 0, onAction = {}) }
        compose.onNodeWithText("Ajusta el reloj a la muñeca").assertIsDisplayed()
    }

    @Test
    fun sensorNoDisponibleSeExplica() {
        compose.setContent { MetricsScreen(active(null, HeartRateAvailability.UNAVAILABLE), now = 0, onAction = {}) }
        compose.onNodeWithText("Sensor no disponible").assertIsDisplayed()
    }

    @Test
    fun pausarYReanudar() {
        var accion: LiveAction? = null
        compose.setContent { MetricsScreen(active(120), now = 0, onAction = { accion = it }) }
        compose.onNodeWithText("Pausar").performScrollTo().performClick()
        assertEquals(LiveAction.PAUSE, accion)
    }

    @Test
    fun enPausaLoDiceYOfreceReanudar() {
        var accion: LiveAction? = null
        compose.setContent { MetricsScreen(active(120, paused = true), now = 99_000, onAction = { accion = it }) }
        compose.onNodeWithText("En pausa").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1:02:33").assertIsDisplayed() // en pausa no avanza
        compose.onNodeWithText("Reanudar").performScrollTo().performClick()
        assertEquals(LiveAction.RESUME, accion)
    }

    @Test
    fun terminarPideConfirmacion() {
        val acciones = mutableListOf<LiveAction>()
        compose.setContent { MetricsScreen(active(120), now = 0, onAction = { acciones += it }) }
        compose.onNodeWithText("Terminar").performScrollTo().performClick()
        assertEquals(emptyList<LiveAction>(), acciones)
        compose.onNodeWithText("Seguir").performScrollTo().performClick()
        compose.onNodeWithText("Terminar").performScrollTo().performClick()
        compose.onNodeWithText("Sí, terminar").performScrollTo().performClick()
        assertEquals(listOf(LiveAction.END), acciones)
    }

    @Test
    fun enAmbienteSoloCifrasSinSegundosNiBotones() {
        compose.setContent { MetricsScreen(active(140), now = 0, ambient = true, onAction = {}) }
        compose.onNodeWithText("140").assertIsDisplayed()
        compose.onNodeWithText("1:02").assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun alTerminarMuestraLaDuracionYElMotivoSiNoFueElUsuario() {
        var accion: LiveAction? = null
        compose.setContent { MetricsScreen(LiveMetricsState.Ended(3_753_000, EndReason.PERMISSION_LOST), now = 0, onAction = { accion = it }) }
        compose.onNodeWithText("Sesión terminada").assertIsDisplayed()
        compose.onNodeWithText("1:02:33").assertIsDisplayed()
        compose.onNodeWithText("Terminó porque se retiró el permiso de frecuencia cardíaca.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Aceptar").performScrollTo().performClick()
        assertEquals(LiveAction.DISMISS, accion)
    }

    @Test
    fun otraAppConEjercicioEnCursoSeExplica() {
        compose.setContent { MetricsScreen(LiveMetricsState.OtherAppInProgress, now = 0, onAction = {}) }
        compose.onNodeWithText("Otra app tiene un ejercicio en curso. Termínalo para usar las métricas.").assertIsDisplayed()
    }

    @Test
    fun accesibilidadLaFrecuenciaSeLeeCompleta() {
        compose.setContent { MetricsScreen(active(132), now = 0, onAction = {}) }
        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Frecuencia cardíaca, 132 latidos por minuto")),
        ).assertExists()
    }

    @Test
    fun accesibilidadBotonesDe48dpOMas() {
        compose.setContent { MetricsScreen(active(132), now = 0, onAction = {}) }
        for (boton in compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
            val alto = with(compose.density) { boton.size.height.toDp() }
            assertTrue("Botón de $alto: el mínimo es 48 dp", alto >= 48.dp)
        }
    }

    @Test
    fun elResumenOfreceIniciarSoloConVeloraListo() {
        var abrir = 0
        compose.setContent { SummaryScreen(link = LinkState.NoPeer, now = 0, onCheck = {}, velora = ModuleStatus.Ready, onLive = { abrir++ }) }
        compose.onNodeWithText("Iniciar métricas").performScrollTo().performClick()
        assertEquals(1, abrir)
    }

    @Test
    fun elResumenSinVeloraNoOfreceMetricas() {
        compose.setContent { SummaryScreen(link = LinkState.NoPeer, now = 0, onCheck = {}, velora = ModuleStatus.Disabled) }
        compose.onNodeWithText("Iniciar métricas").assertDoesNotExist()
    }

    @Test
    fun elResumenMuestraLaSesionEnCurso() {
        compose.setContent {
            SummaryScreen(link = LinkState.NoPeer, now = 0, onCheck = {}, velora = ModuleStatus.Ready, live = active(128))
        }
        compose.onNodeWithText("128 lpm").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ver métricas").performScrollTo().assertIsDisplayed()
    }
}
