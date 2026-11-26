package mx.watchcompanion.phone.drafts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale
import mx.watchcompanion.core.communication.DraftOptions
import mx.watchcompanion.core.communication.DraftStatus.State
import mx.watchcompanion.core.communication.QuickTransactionDraft
import mx.watchcompanion.phone.R

/**
 * Borradores del reloj por confirmar (WC-40). El usuario ve todo lo que se va a registrar y solo «Confirmar» lo manda a
 * Nexora. Tras un envío sin respuesta solo se ofrece «Reintentar» (misma clave, mismo cuerpo): descartarlo podría
 * esconder un movimiento que sí se registró.
 *
 * @param options cuentas y categorías del último resumen, para mostrar nombres en lugar de ids.
 */
@Composable
fun DraftsSection(
    drafts: List<StoredDraft>,
    sending: Set<String>,
    options: DraftOptions?,
    onConfirm: (String) -> Unit,
    onDiscard: (String) -> Unit,
) {
    val visible = drafts.filter { it.state != State.DISCARDED }.sortedByDescending { it.draft.capturedAt }
    if (visible.isEmpty()) return
    Text(stringResource(R.string.drafts_title), style = MaterialTheme.typography.titleSmall)
    for (stored in visible) {
        DraftCard(stored, stored.draft.idempotencyKey in sending, options, onConfirm, onDiscard)
    }
}

@Composable
private fun DraftCard(
    stored: StoredDraft,
    sending: Boolean,
    options: DraftOptions?,
    onConfirm: (String) -> Unit,
    onDiscard: (String) -> Unit,
) {
    val draft = stored.draft
    val account = options?.accounts?.firstOrNull { it.id == draft.accountId }
    val category = draft.categoryId?.let { id -> options?.categories?.firstOrNull { it.id == id } }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val kind = stringResource(if (draft.kind == QuickTransactionDraft.Kind.EXPENSE) R.string.draft_expense else R.string.draft_income)
            Text("$kind · ${amountText(draft.amount, account?.currency)}", style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.draft_account, account?.name ?: stringResource(R.string.draft_unknown_account)))
            if (draft.categoryId != null) {
                Text(stringResource(R.string.draft_category, category?.name ?: stringResource(R.string.draft_unknown_category)))
            }
            draft.note?.let { Text(stringResource(R.string.draft_note, it)) }
            Text(stringResource(R.string.draft_date, DATE.format(LocalDate.parse(draft.date))))
            Text(stateText(stored, sending), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (stored.confirmable) {
                    Button(onClick = { onConfirm(draft.idempotencyKey) }, enabled = !sending) {
                        Text(stringResource(if (stored.state == State.UNKNOWN) R.string.draft_retry else R.string.draft_confirm))
                    }
                }
                if (stored.discardable) {
                    OutlinedButton(onClick = { onDiscard(draft.idempotencyKey) }, enabled = !sending) {
                        Text(stringResource(R.string.draft_discard))
                    }
                }
            }
        }
    }
}

@Composable
private fun stateText(stored: StoredDraft, sending: Boolean): String {
    if (sending) return stringResource(R.string.draft_sending)
    val code = stored.errorCode ?: "?"
    return when (stored.state) {
        State.RECEIVED ->
            if (stored.errorCode == "SIGNED_OUT") stringResource(R.string.draft_needs_login) else stringResource(R.string.draft_received)
        State.CREATED -> stringResource(R.string.draft_created)
        State.REJECTED -> stringResource(R.string.draft_rejected, code)
        State.CONFLICT -> stringResource(R.string.draft_conflict)
        State.UNKNOWN ->
            if (stored.errorCode == "SIGNED_OUT") stringResource(R.string.draft_needs_login) else stringResource(R.string.draft_unknown, code)
        State.DISCARDED -> stringResource(R.string.draft_discarded)
    }
}

/** Solo formato: el importe es el que capturó el reloj. */
private fun amountText(amount: String, currency: String?): String {
    val format = NumberFormat.getCurrencyInstance(ES_MX)
    currency?.let { runCatching { format.currency = Currency.getInstance(it) } }
    return format.format(BigDecimal(amount))
}

private val ES_MX = Locale.forLanguageTag("es-MX")
private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", ES_MX)
