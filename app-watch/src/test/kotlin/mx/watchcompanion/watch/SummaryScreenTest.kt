package mx.watchcompanion.watch

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import mx.watchcompanion.core.communication.IntegrationStatus
import mx.watchcompanion.core.communication.SyncSummary
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import mx.watchcompanion.core.communication.LinkState
import mx.watchcompanion.core.permissions.ModulePermissions
import mx.watchcompanion.core.permissions.ModuleStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w227dp-h227dp-small-notlong-round-watch-xhdpi-keyshidden-nonav")
class SummaryScreenTest {
    @get:Rule val compose = createComposeRule()

    private companion object {
        const val AHORA = 1_800_000_000_000L
        const val MIN = 60_000L
    }

    private fun conectado(vararg sync: SyncSummary) =
        LinkState.Connected("Galaxy S26 Ultra", "0.1.0", roundTripMs = 42, checkedAt = AHORA, peerSync = sync.toList())

    private fun velora(state: SyncSummary.State, lastSuccessAt: Long? = null, errorCode: String? = null) =
        SyncSummary(IntegrationStatus.Integration.VELORA, state, lastSuccessAt, errorCode)

    @Test
    fun muestraTelefonoModoYSincronizacion() {
        compose.setContent { SummaryScreen(now = AHORA, link = conectado(velora(SyncSummary.State.SYNCED, AHORA - 5 * MIN)), onCheck = {}) }
        compose.onNodeWithText("Resumen").assertIsDisplayed()
        compose.onNodeWithText("Conectado a Galaxy S26 Ultra").assertIsDisplayed()
        compose.onNodeWithText("A · iGPSPORT principal").assertIsDisplayed()
        compose.onNodeWithText("Sincronizado hace 5 min").assertIsDisplayed()
    }

    @Test
    fun cadaEstadoDeSincronizacionTieneSuTexto() {
        val casos = listOf(
            velora(SyncSummary.State.SIGNED_OUT) to "Inicia sesión en el teléfono",
            velora(SyncSummary.State.SESSION_EXPIRED) to "Sesión vencida: entra de nuevo en el teléfono",
            velora(SyncSummary.State.NEVER_SYNCED) to "Aún sin sincronizar",
            velora(SyncSummary.State.ERROR, AHORA - MIN, "HTTP_503") to "Error al sincronizar (HTTP_503)",
            velora(SyncSummary.State.SYNCED, AHORA - 10_000) to "Sincronizado hace un momento",
            velora(SyncSummary.State.SYNCED, AHORA - 3 * 60 * MIN) to "Sincronizado hace 3 h",
            velora(SyncSummary.State.SYNCED, AHORA - 2 * 24 * 60 * MIN) to "Sincronizado hace 2 días",
            // Reloj del teléfono adelantado: no se muestra un tiempo negativo.
            velora(SyncSummary.State.SYNCED, AHORA + MIN) to "Sincronizado hace un momento",
        )
        var link by mutableStateOf<LinkState>(LinkState.Unknown)
        compose.setContent { SummaryScreen(now = AHORA, link = link, onCheck = {}) }
        for ((sync, texto) in casos) {
            link = conectado(sync)
            compose.onNodeWithText(texto).assertIsDisplayed()
        }
    }

    @Test
    fun sinEnlaceNoMuestraUnEstadoDeSincronizacionViejo() {
        compose.setContent { SummaryScreen(now = AHORA, link = LinkState.Unreachable("Galaxy S26 Ultra"), onCheck = {}) }
        compose.onNodeWithText("Sin datos del teléfono").assertIsDisplayed()
    }

    @Test
    fun conectadoSinInformeDeVeloraNoInventaUnEstado() {
        compose.setContent { SummaryScreen(now = AHORA, link = conectado(), onCheck = {}) }
        compose.onNodeWithText("Sin datos del teléfono").assertIsDisplayed()
    }

    @Test
    fun enAmbienteSoloQuedanLosTextos() {
        compose.setContent {
            SummaryScreen(now = AHORA, link = conectado(velora(SyncSummary.State.NEVER_SYNCED)), onCheck = {}, ambient = true)
        }
        compose.onNodeWithText("Conectado a Galaxy S26 Ultra").assertIsDisplayed()
        compose.onNodeWithText("Aún sin sincronizar").assertIsDisplayed()
        compose.onNodeWithText("Probar conexión").assertDoesNotExist()
        compose.onNodeWithText("Velora · Desactivado").assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun accesibilidadTituloComoEncabezadoYCadaDatoLeidoConSuEtiqueta() {
        compose.setContent { SummaryScreen(now = AHORA, link = conectado(velora(SyncSummary.State.NEVER_SYNCED)), onCheck = {}) }
        compose.onNode(hasText("Resumen") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
        // TalkBack lee etiqueta y valor juntos: un solo nodo fusionado con ambos textos.
        compose.onNode(hasText("Teléfono") and hasText("Conectado a Galaxy S26 Ultra")).assertExists()
        compose.onNode(hasText("Modo") and hasText("A · iGPSPORT principal")).assertExists()
        compose.onNode(hasText("Velora") and hasText("Aún sin sincronizar")).assertExists()
    }

    @Test
    fun accesibilidadLosBotonesTienenAreaTactilSuficiente() {
        compose.setContent { SummaryScreen(now = AHORA, link = LinkState.NoPeer, onCheck = {}) }
        for (boton in compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
            val alto = with(compose.density) { boton.size.height.toDp() }
            assertTrue("Botón de $alto: el mínimo es 48 dp", alto >= 48.dp)
        }
    }

    @Test
    fun accesibilidadConLetraGrandeTodoSigueAlAlcanceDesplazando() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                SummaryScreen(now = AHORA, link = conectado(velora(SyncSummary.State.SESSION_EXPIRED)), onCheck = {})
            }
        }
        compose.onNodeWithText("Velora · Desactivado").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sinTelefonoExplicaQueHacer() {
        compose.setContent { SummaryScreen(now = AHORA, link = LinkState.NoPeer, onCheck = {}) }
        compose.onNodeWithText("No encuentro el teléfono. Instala Watch Companion en él.").assertIsDisplayed()
    }

    @Test
    fun incompatiblePideActualizar() {
        compose.setContent { SummaryScreen(now = AHORA, link = LinkState.Incompatible, onCheck = {}) }
        compose.onNodeWithText("Actualiza la app en el reloj o en el teléfono").assertIsDisplayed()
    }

    @Test
    fun elBotonPruebaLaConexionYSeDesactivaMientrasComprueba() {
        var pruebas = 0
        compose.setContent { SummaryScreen(now = AHORA, link = LinkState.Unreachable("Galaxy S26 Ultra"), onCheck = { pruebas++ }) }
        compose.onNodeWithText("Galaxy S26 Ultra no responde").assertIsDisplayed()
        compose.onNodeWithText("Probar conexión").performScrollTo().performClick()
        assertEquals(1, pruebas)
    }

    @Test
    fun mientrasCompruebaNoSePuedeRepetir() {
        compose.setContent { SummaryScreen(now = AHORA, link = LinkState.Checking, onCheck = {}) }
        compose.onNodeWithText("Probar conexión").assertIsNotEnabled()
    }

    @Test
    fun veloraDesactivadoOfreceActivar() {
        var accion: ModuleAction? = null
        compose.setContent { SummaryScreen(now = AHORA, link = LinkState.NoPeer, onCheck = {}, velora = ModuleStatus.Disabled, onVeloraAction = { accion = it }) }
        compose.onNodeWithText("Velora · Desactivado").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Activar").performScrollTo().performClick()
        assertEquals(ModuleAction.ENABLE, accion)
    }

    @Test
    fun veloraSinPermisoPideConcederlo() {
        var accion: ModuleAction? = null
        val falta = ModuleStatus.NeedsPermissions(listOf(ModulePermissions.READ_HEART_RATE), permanentlyDenied = false)
        compose.setContent { SummaryScreen(now = AHORA, link = LinkState.NoPeer, onCheck = {}, velora = falta, onVeloraAction = { accion = it }) }
        compose.onNodeWithText("Velora · Falta el permiso de frecuencia cardíaca").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Conceder permiso").performScrollTo().performClick()
        assertEquals(ModuleAction.REQUEST, accion)
    }

    @Test
    fun veloraConPermisoDenegadoLlevaAAjustes() {
        var accion: ModuleAction? = null
        val denegado = ModuleStatus.NeedsPermissions(listOf(ModulePermissions.READ_HEART_RATE), permanentlyDenied = true)
        compose.setContent { SummaryScreen(now = AHORA, link = LinkState.NoPeer, onCheck = {}, velora = denegado, onVeloraAction = { accion = it }) }
        compose.onNodeWithText("Velora · Permiso de frecuencia cardíaca denegado: actívalo en Ajustes").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Abrir Ajustes").performScrollTo().performClick()
        assertEquals(ModuleAction.OPEN_SETTINGS, accion)
    }

    @Test
    fun veloraListoPermiteDesactivar() {
        var accion: ModuleAction? = null
        compose.setContent { SummaryScreen(now = AHORA, link = LinkState.NoPeer, onCheck = {}, velora = ModuleStatus.Ready, onVeloraAction = { accion = it }) }
        compose.onNodeWithText("Velora · Listo").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Desactivar").performScrollTo().performClick()
        assertEquals(ModuleAction.DISABLE, accion)
    }
}
