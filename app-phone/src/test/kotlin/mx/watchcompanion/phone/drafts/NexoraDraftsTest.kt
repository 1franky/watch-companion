package mx.watchcompanion.phone.drafts

import java.util.UUID
import mx.watchcompanion.core.communication.DraftOption
import mx.watchcompanion.core.communication.QuickTransactionDraft
import mx.watchcompanion.integration.nexora.api.models.AccountResponse
import mx.watchcompanion.integration.nexora.api.models.CategoryResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class NexoraDraftsTest {
    @Test
    fun elCuerpoEsElCreateTransactionRequestDelContrato() {
        val draft = QuickTransactionDraft(
            "3d6f0a7e-1b2c-4d5e-8f90-a1b2c3d4e5f6", QuickTransactionDraft.Kind.INCOME, "1200.50",
            "5b1d6a3e-0c1f-4a2b-9d3e-7f8a9b0c1d2e", categoryId = "6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10",
            note = "Venta", date = "2026-11-25", capturedAt = 0,
        )
        assertEquals(
            """{"accountId":"5b1d6a3e-0c1f-4a2b-9d3e-7f8a9b0c1d2e","amount":1200.5,"date":"2026-11-25","type":"INCOME",""" +
                """"categoryId":"6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10","description":"Venta"}""",
            transactionBody(draft),
        )
    }

    @Test
    fun soloCuentasActivasSinTarjetasYCategoriasActivasConSuTipo() {
        val id = { n: Int -> UUID.fromString("00000000-0000-0000-0000-00000000000$n") }
        val options = draftOptions(
            accounts = listOf(
                AccountResponse(id = id(1), name = "Nómina", currency = "MXN", status = AccountResponse.Status.ACTIVE, type = AccountResponse.Type.DEBIT),
                AccountResponse(id = id(2), name = "Tarjeta", status = AccountResponse.Status.ACTIVE, type = AccountResponse.Type.CREDIT_CARD),
                AccountResponse(id = id(3), name = "Vieja", status = AccountResponse.Status.ARCHIVED, type = AccountResponse.Type.DEBIT),
            ),
            categories = listOf(
                CategoryResponse(id = id(4), name = "Comida", status = CategoryResponse.Status.ACTIVE, type = CategoryResponse.Type.EXPENSE),
                CategoryResponse(id = id(5), name = "Archivada", status = CategoryResponse.Status.ARCHIVED, type = CategoryResponse.Type.EXPENSE),
            ),
        )
        assertEquals(listOf(DraftOption(id(1).toString(), "Nómina", currency = "MXN")), options.accounts)
        assertEquals(listOf(DraftOption(id(4).toString(), "Comida", kind = QuickTransactionDraft.Kind.EXPENSE)), options.categories)
    }
}
