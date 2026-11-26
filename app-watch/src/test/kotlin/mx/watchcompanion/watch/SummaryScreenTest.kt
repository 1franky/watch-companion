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
import mx.watchcompanion.core.communication.ActivityStatus
import mx.watchcompanion.core.communication.FinanceFigures
import mx.watchcompanion.core.communication.FinanceSnapshot
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

    private val utc = java.time.ZoneId.of("UTC")

    @Test
    fun ultimaActividadSincronizadaConFechaDistanciaYHoraDeConsulta() {
        // 2027-01-15 08:12 UTC
        val inicio = java.time.Instant.parse("2027-01-15T08:12:00Z").toEpochMilli()
        val actividad = ActivityStatus(ActivityStatus.State.SYNCED, checkedAt = AHORA - 3 * MIN, activityStartedAt = inicio, distanceMeters = 42_300.0)
        compose.setContent { SummaryScreen(now = AHORA, link = LinkState.NoPeer, onCheck = {}, activity = actividad, zoneId = utc) }
        compose.onNodeWithText("Sincronizada").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("15 ene 08:12", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("km", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Consultado hace 3 min").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun cadaEstadoDeLaActividadTieneSuTextoYNuncaSimulaExito() {
        var actividad by mutableStateOf<ActivityStatus?>(null)
        compose.setContent { SummaryScreen(now = AHORA, link = LinkState.NoPeer, onCheck = {}, activity = actividad, zoneId = utc) }
        val casos = listOf(
            ActivityStatus(ActivityStatus.State.PENDING, AHORA) to "Pendiente: aún no hay actividades",
            ActivityStatus(ActivityStatus.State.SYNCING, AHORA) to "Sincronizando con Strava",
            ActivityStatus(ActivityStatus.State.ERROR, AHORA, errorCode = "STRAVA_UNAVAILABLE") to "Error (STRAVA_UNAVAILABLE)",
        )
        for ((estado, texto) in casos) {
            actividad = estado
            compose.onNodeWithText(texto).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Sincronizada").assertDoesNotExist()
            compose.onNodeWithText("Consultado hace un momento").performScrollTo().assertIsDisplayed()
        }
    }

    private fun finanzas(fetchedAt: Long, state: FinanceSnapshot.State = FinanceSnapshot.State.OK, checkedAt: Long = fetchedAt, error: String? = null) =
        FinanceSnapshot(state, checkedAt, FinanceFigures(fetchedAt, "2027-01", "15230.50", "4800.00", "9120.75"), error)

    @Test
    fun resumenFinancieroConCadaCifraYSuHora() {
        // AHORA es 2027-01-15 08:00 UTC; consultado hace 3 min.
        compose.setContent { SummaryScreen(now = AHORA, link = conectado(), onCheck = {}, finance = finanzas(AHORA - 3 * MIN), zoneId = utc) }
        compose.onNodeWithText("Finanzas · Nexora").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Disponible · 07:57") and hasText("15,230.50", substring = true)).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Deuda de tarjetas · 07:57") and hasText("4,800.00", substring = true)).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Gastos de enero · 07:57") and hasText("9,120.75", substring = true)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Desactualizado", substring = true).assertDoesNotExist()
    }

    @Test
    fun cifrasViejasOConConsultaFallidaSeMarcanDesactualizadas() {
        var finanzas by mutableStateOf(finanzas(AHORA - 20 * MIN))
        compose.setContent { SummaryScreen(now = AHORA, link = conectado(), onCheck = {}, finance = finanzas, zoneId = utc) }
        compose.onNodeWithText("Desactualizado: consultado hace 20 min").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Disponible · 07:40, desactualizado").performScrollTo().assertIsDisplayed()

        finanzas = finanzas(AHORA - 2 * MIN, FinanceSnapshot.State.ERROR, checkedAt = AHORA, error = "UNREACHABLE")
        compose.onNodeWithText("Desactualizado: la última consulta falló (UNREACHABLE)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Disponible · 07:58, desactualizado").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sinSesionOSinDatosNoHayCifras() {
        var finanzas by mutableStateOf<FinanceSnapshot?>(null)
        compose.setContent { SummaryScreen(now = AHORA, link = conectado(), onCheck = {}, finance = finanzas, zoneId = utc) }
        compose.onNodeWithText("Finanzas · Nexora").performScrollTo().assertIsDisplayed()
        finanzas = FinanceSnapshot(FinanceSnapshot.State.SIGNED_OUT, AHORA)
        compose.onNodeWithText("Sin sesión de Nexora en el teléfono").performScrollTo().assertIsDisplayed()
        finanzas = FinanceSnapshot(FinanceSnapshot.State.ERROR, AHORA, errorCode = "HTTP_503")
        compose.onNodeWithText("No se pudo consultar (HTTP_503)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Disponible", substring = true).assertDoesNotExist()
    }

    @Test
    fun enAmbienteNoSeMuestranLasFinanzas() {
        compose.setContent { SummaryScreen(now = AHORA, link = conectado(), onCheck = {}, ambient = true, finance = finanzas(AHORA)) }
        compose.onNodeWithText("Finanzas · Nexora").assertDoesNotExist()
        compose.onNodeWithText("15,230.50", substring = true).assertDoesNotExist()
    }

    @Test
    fun sinInformeDeActividadLoDice() {
        compose.setContent { SummaryScreen(now = AHORA, link = conectado(), onCheck = {}) }
        compose.onNode(hasText("Última actividad") and hasText("Sin datos del teléfono")).performScrollTo().assertExists()
    }

    @Test
    fun sinEnlaceNoMuestraUnEstadoDeSincronizacionViejo() {
        compose.setContent { SummaryScreen(now = AHORA, link = LinkState.Unreachable("Galaxy S26 Ultra"), onCheck = {}) }
        compose.onNode(hasText("Velora") and hasText("Sin datos del teléfono")).assertIsDisplayed()
    }

    @Test
    fun conectadoSinInformeDeVeloraNoInventaUnEstado() {
        compose.setContent { SummaryScreen(now = AHORA, link = conectado(), onCheck = {}) }
        compose.onNode(hasText("Velora") and hasText("Sin datos del teléfono")).assertIsDisplayed()
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
