package mx.watchcompanion.watch.drafts

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import mx.watchcompanion.core.communication.DraftOption
import mx.watchcompanion.core.communication.DraftOptions
import mx.watchcompanion.core.communication.QuickTransactionDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w227dp-h227dp-small-notlong-round-watch-xhdpi-keyshidden-nonav")
class DraftScreenTest {
    @get:Rule val compose = createComposeRule()

    private val opciones = DraftOptions(
        accounts = listOf(DraftOption("5b1d6a3e-0c1f-4a2b-9d3e-7f8a9b0c1d2e", "Nómina", currency = "MXN")),
        categories = listOf(
            DraftOption("6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10", "Comida", kind = QuickTransactionDraft.Kind.EXPENSE),
            DraftOption("7a2d4b73-6b1f-4b5f-8a62-1c3d9e0f3a21", "Sueldo", kind = QuickTransactionDraft.Kind.INCOME),
        ),
    )
    private val clave = "3d6f0a7e-1b2c-4d5e-8f90-a1b2c3d4e5f6"

    private fun toca(texto: String) = compose.onNodeWithText(texto).performScrollTo().performClick()

    @Test
    fun capturaUnGastoCompletoConSuClaveYNoRegistraNada() {
        var guardado: QuickTransactionDraft? = null
        compose.setContent {
            DraftScreen(opciones, today = LocalDate.of(2026, 11, 25), now = 42, onSave = { guardado = it }, onCancel = {}, newKey = { clave })
        }
        toca("Gasto")
        compose.onNodeWithText("Siguiente").performScrollTo().assertIsNotEnabled()
        for (tecla in "250.5") compose.onNodeWithContentDescription(tecla.toString()).performScrollTo().performClick()
        toca("Siguiente")
        toca("Nómina")
        compose.onNodeWithText("Sueldo").assertDoesNotExist()
        toca("Comida")
        toca("Sin nota")
        toca("Ayer")
        compose.onNodeWithText("Se registra cuando lo confirmes en el teléfono.").performScrollTo()
        assertNull("Nada sale antes de enviarlo", guardado)
        toca("Enviar al teléfono")

        assertEquals(
            QuickTransactionDraft(
                idempotencyKey = clave,
                kind = QuickTransactionDraft.Kind.EXPENSE,
                amount = "250.5",
                accountId = "5b1d6a3e-0c1f-4a2b-9d3e-7f8a9b0c1d2e",
                categoryId = "6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10",
                date = "2026-11-24",
                capturedAt = 42,
            ),
            guardado,
        )
    }

    @Test
    fun sinCuentasDeNexoraNoDejaCapturar() {
        var cancelado = false
        compose.setContent { DraftScreen(null, LocalDate.of(2026, 11, 25), 0, onSave = {}, onCancel = { cancelado = true }) }
        compose.onNodeWithText("Gasto").assertDoesNotExist()
        toca("Volver")
        assertTrue(cancelado)
    }
}
