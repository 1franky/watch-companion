package mx.watchcompanion.phone

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import mx.watchcompanion.core.communication.HeartRateZones
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ZonesScreenTest {
    @get:Rule val compose = createComposeRule()

    private var guardadas: HeartRateZones? = null
    private var consultas = 0

    private fun pantalla(saved: HeartRateZones? = null, velora: VeloraMax? = null, delivery: ZonesDelivery? = null) =
        compose.setContent {
            Desplazable {
                ZonesScreen(
                saved = saved, veloraMax = velora, delivery = delivery, now = { 42 },
                onUseVelora = { consultas++ }, onSave = { guardadas = it }, onBack = {},
                )
            }
        }

    /** Como en MainActivity: la pantalla vive dentro de una columna desplazable. */
    @Composable
    private fun Desplazable(content: @Composable () -> Unit) =
        Column(Modifier.verticalScroll(rememberScrollState())) { content() }

    private fun campo(etiqueta: String) = compose.onNode(hasSetTextAction() and hasText(etiqueta))

    @Test
    fun conLaFcMaximaDeVeloraRellenaLasZonasYLasGuardaComoDeVelora() {
        var velora by mutableStateOf<VeloraMax?>(null)
        compose.setContent {
            Desplazable {
                ZonesScreen(saved = null, veloraMax = velora, delivery = null, now = { 42 }, onUseVelora = { consultas++ }, onSave = { guardadas = it }, onBack = {})
            }
        }
        compose.onNodeWithText("Usar la FC máxima de Velora").performClick()
        assertEquals(1, consultas)
        velora = VeloraMax.Value(185)

        compose.onNodeWithText("Velora: FC máxima 185 lpm. Revisa las zonas y guarda.").assertIsDisplayed()
        compose.onNodeWithText("Guardar y enviar al reloj").performScrollTo().performClick()

        assertEquals(HeartRateZones.fromMax(185, HeartRateZones.Source.VELORA, 42), guardadas)
    }

    @Test
    fun ajustarUnaZonaLaGuardaComoManual() {
        pantalla(saved = HeartRateZones.fromMax(185, HeartRateZones.Source.VELORA, 1))
        campo("Z3 desde (lpm)").performScrollTo().performTextReplacement("125")
        compose.onNodeWithText("Guardar y enviar al reloj").performScrollTo().performClick()
        assertEquals(HeartRateZones(185, listOf(93, 111, 125, 148, 167), HeartRateZones.Source.MANUAL, 42), guardadas)
    }

    @Test
    fun recalcularConOtraFcMaxima() {
        pantalla()
        campo("FC máxima (lpm)").performTextReplacement("200")
        compose.onNodeWithText("Recalcular zonas con esta FC máxima").performScrollTo().performClick()
        compose.onNodeWithText("Guardar y enviar al reloj").performScrollTo().performClick()
        assertEquals(HeartRateZones.fromMax(200, HeartRateZones.Source.MANUAL, 42), guardadas)
    }

    @Test
    fun zonasDesordenadasNoSeGuardanYSeExplica() {
        pantalla(saved = HeartRateZones.fromMax(185, HeartRateZones.Source.VELORA, 1))
        campo("Z2 desde (lpm)").performScrollTo().performTextReplacement("140")
        compose.onNodeWithText("Guardar y enviar al reloj").performScrollTo().performClick()
        compose.onNodeWithText("Cada zona debe empezar por encima de la anterior.").performScrollTo().assertIsDisplayed()
        assertEquals(null, guardadas)
    }

    @Test
    fun veloraSinFcMaximaLoDice() {
        pantalla(velora = VeloraMax.NotSet)
        compose.onNodeWithText("Velora no tiene tu FC máxima. Escríbela aquí o indícala en Velora.").assertIsDisplayed()
    }

    @Test
    fun avisaSiQuedaronPendientesDeLlegarAlReloj() {
        pantalla(saved = HeartRateZones.fromMax(185, HeartRateZones.Source.VELORA, 1), delivery = ZonesDelivery.PENDING)
        compose.onNodeWithText("Guardadas: FC máxima 185 lpm, de Velora").assertIsDisplayed()
        compose.onNodeWithText("Guardadas. El reloj no está al alcance: las recibirá en cuanto se conecte.").performScrollTo().assertIsDisplayed()
    }
}
