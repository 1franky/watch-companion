package mx.watchcompanion.phone.drafts

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import mx.watchcompanion.core.communication.DraftOption
import mx.watchcompanion.core.communication.DraftOptions
import mx.watchcompanion.core.communication.QuickTransactionDraft
import mx.watchcompanion.integration.nexora.NexoraClient
import mx.watchcompanion.integration.nexora.NexoraSession
import mx.watchcompanion.integration.nexora.api.models.AccountResponse
import mx.watchcompanion.integration.nexora.api.models.CategoryResponse
import mx.watchcompanion.integration.nexora.api.models.CreateTransactionRequest
import mx.watchcompanion.phone.NexoraHttpException

/** El `CreateTransactionRequest` de un borrador, en JSON. Se genera una sola vez y se guarda tal cual (ver [PhoneDrafts]). */
fun transactionBody(draft: QuickTransactionDraft): String = NexoraClient.json.encodeToString(
    CreateTransactionRequest.serializer(),
    CreateTransactionRequest(
        accountId = UUID.fromString(draft.accountId),
        amount = BigDecimal(draft.amount).toDouble(),
        date = LocalDate.parse(draft.date),
        type = when (draft.kind) {
            QuickTransactionDraft.Kind.EXPENSE -> CreateTransactionRequest.Type.EXPENSE
            QuickTransactionDraft.Kind.INCOME -> CreateTransactionRequest.Type.INCOME
        },
        categoryId = draft.categoryId?.let(UUID::fromString),
        description = draft.note,
    ),
)

/** `POST /api/v1/transactions` con la clave del reloj; devuelve el código HTTP. */
suspend fun postTransaction(session: NexoraSession, key: UUID, body: String): Int =
    session.call("nexora.transactionCreate") { transactionCreate(key, body) }.status

/**
 * Cuentas y categorías activas para el borrador. Sin tarjetas de crédito: en Nexora un gasto con tarjeta es una compra
 * (`/credit-cards`), y `POST /transactions` solo admite `INCOME`/`EXPENSE`.
 */
fun draftOptions(accounts: List<AccountResponse>, categories: List<CategoryResponse>) = DraftOptions(
    accounts = accounts
        .filter { it.status == AccountResponse.Status.ACTIVE && it.type != AccountResponse.Type.CREDIT_CARD }
        .mapNotNull { a -> a.id?.let { id -> a.name?.let { DraftOption(id.toString(), it, currency = a.currency) } } },
    categories = categories
        .filter { it.status == CategoryResponse.Status.ACTIVE }
        .mapNotNull { c ->
            val kind = when (c.type) {
                CategoryResponse.Type.INCOME -> QuickTransactionDraft.Kind.INCOME
                CategoryResponse.Type.EXPENSE -> QuickTransactionDraft.Kind.EXPENSE
                null -> null
            }
            c.id?.let { id -> c.name?.let { DraftOption(id.toString(), it, kind = kind) } }
        },
)

suspend fun nexoraDraftOptions(session: NexoraSession): DraftOptions {
    val accounts = session.call("nexora.accountList") { accountList() }
    if (!accounts.success) throw NexoraHttpException(accounts.status)
    val categories = session.call("nexora.categoryList") { categoryList() }
    if (!categories.success) throw NexoraHttpException(categories.status)
    return draftOptions(accounts.body(), categories.body())
}
