package mx.watchcompanion.watch

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w227dp-h227dp-small-notlong-round-watch-xhdpi-keyshidden-nonav")
class WatchHomeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun muestraElEstadoInicial() {
        compose.setContent { WatchHome() }
        compose.onNodeWithText("Fase 0: sin conexión con el teléfono todavía").assertIsDisplayed()
    }
}
