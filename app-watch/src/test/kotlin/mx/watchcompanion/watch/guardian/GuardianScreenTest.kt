package mx.watchcompanion.watch.guardian

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.ZoneOffset
import mx.watchcompanion.core.communication.GuardianCommandResult.Outcome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w227dp-h227dp-small-notlong-round-watch-xhdpi-keyshidden-nonav")
class GuardianScreenTest {
    @get:Rule val compose = createComposeRule()

    private val ahora = 1_800_000_000_000L // 15 ene 2027, 08:00 UTC

    @Test
    fun muestraLosEventosYRevocarPideConfirmacion() {
        var revocar = 0
        var estado by mutableStateOf<RevokeState>(RevokeState.Idle)
        compose.setContent {
            GuardianScreen(
                events = listOf(
                    GuardianEvent(ahora, GuardianEvent.Kind.LOCK, Outcome.LOCKED, ref = "a"),
                    GuardianEvent(ahora - 60_000, GuardianEvent.Kind.LOCK, Outcome.UNAVAILABLE, "ADMIN_INACTIVE", ref = "b"),
                    GuardianEvent(ahora - 120_000, GuardianEvent.Kind.PHONE_AWAY),
                ),
                retentionDays = 7, onRetention = {}, revoke = estado, onRevoke = { revocar++ }, onBack = {}, zoneId = ZoneOffset.UTC,
            )
        }
        compose.onNodeWithText("Teléfono bloqueado").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Bloqueo no realizado (ADMIN_INACTIVE)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Aviso: teléfono fuera de alcance").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Conservar: 7 días").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Revocar Guardian").performScrollTo().performClick()
        assertEquals(0, revocar)
        compose.onNodeWithText("Se borra el historial", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Revocar Guardian").performClick()
        assertEquals(1, revocar)

        estado = RevokeState.Done(phoneConfirmed = false)
        compose.onNodeWithText("El teléfono no respondió", substring = true).assertIsDisplayed()
    }
}
