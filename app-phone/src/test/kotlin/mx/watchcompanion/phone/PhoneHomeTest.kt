package mx.watchcompanion.phone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PhoneHomeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun muestraElEstadoInicial() {
        compose.setContent { PhoneHome() }
        compose.onNodeWithText("Watch Companion").assertIsDisplayed()
        compose.onNodeWithText("Fase 0: el puente con el reloj todavía no está activo.").assertIsDisplayed()
    }
}
