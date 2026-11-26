package mx.watchcompanion.watch.drafts

import android.app.RemoteInput
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import mx.watchcompanion.core.communication.DraftOptions
import mx.watchcompanion.core.communication.QuickTransactionDraft
import mx.watchcompanion.watch.R
import mx.watchcompanion.watch.formatAmount

private enum class Step { KIND, AMOUNT, ACCOUNT, CATEGORY, NOTE, DATE, REVIEW }

/**
 * Captura de un borrador de gasto o ingreso (WC-40, 03 §2): tipo, importe, cuenta, categoría y nota opcionales, y
 * fecha. Al guardarlo se genera su `Idempotency-Key` y viaja al teléfono, donde se confirma; aquí nada se registra.
 *
 * @param options cuentas y categorías del último resumen de Nexora; sin ellas no se puede elegir cuenta.
 * @param today fecha del reloj; el movimiento puede ser de hoy o de ayer.
 * @param newKey genera la `Idempotency-Key`; inyectable para pruebas.
 */
@Composable
fun DraftScreen(
    options: DraftOptions?,
    today: LocalDate,
    now: Long,
    onSave: (QuickTransactionDraft) -> Unit,
    onCancel: () -> Unit,
    newKey: () -> String = { UUID.randomUUID().toString() },
) {
    var step by rememberSaveable { mutableStateOf(Step.KIND) }
    var kind by rememberSaveable { mutableStateOf(QuickTransactionDraft.Kind.EXPENSE) }
    var amount by rememberSaveable { mutableStateOf("") }
    var accountId by rememberSaveable { mutableStateOf<String?>(null) }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf<String?>(null) }
    var yesterday by rememberSaveable { mutableStateOf(false) }

    val account = options?.accounts?.firstOrNull { it.id == accountId }
    val categories = options?.categories.orEmpty().filter { it.kind == null || it.kind == kind }
    val category = categories.firstOrNull { it.id == categoryId }

    val noteInput = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence(NOTE_KEY)?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            note = text.take(QuickTransactionDraft.MAX_NOTE)
            step = Step.DATE
        }
    }
    val noteLabel = stringResource(R.string.draft_note_label)

    BackHandler {
        val previous = Step.entries.getOrNull(step.ordinal - 1)
        if (previous == null) onCancel() else step = previous
    }

    DraftScaffold {
        if (options == null || options.accounts.isEmpty()) {
            Title(stringResource(R.string.draft_title))
            Text(stringResource(R.string.draft_no_options), textAlign = TextAlign.Center)
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.draft_back)) }
            return@DraftScaffold
        }
        when (step) {
            Step.KIND -> {
                Title(stringResource(R.string.draft_title))
                Button(onClick = { kind = QuickTransactionDraft.Kind.EXPENSE; categoryId = null; step = Step.AMOUNT }) {
                    Text(stringResource(R.string.draft_expense))
                }
                Button(onClick = { kind = QuickTransactionDraft.Kind.INCOME; categoryId = null; step = Step.AMOUNT }) {
                    Text(stringResource(R.string.draft_income))
                }
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.draft_cancel)) }
            }
            Step.AMOUNT -> {
                Title(stringResource(if (kind == QuickTransactionDraft.Kind.EXPENSE) R.string.draft_expense else R.string.draft_income))
                Text(
                    "$" + amount.ifEmpty { "0" },
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.semantics { contentDescription = "Importe ${amount.ifEmpty { "0" }}" },
                )
                Keypad { key -> amount = AmountEntry.press(amount, key) }
                Button(onClick = { step = Step.ACCOUNT }, enabled = AmountEntry.isValid(amount)) {
                    Text(stringResource(R.string.draft_next))
                }
            }
            Step.ACCOUNT -> {
                Title(stringResource(R.string.draft_account))
                for (option in options.accounts) {
                    FilledTonalButton(onClick = { accountId = option.id; step = Step.CATEGORY }, modifier = Modifier.fillMaxWidth()) {
                        Text(option.name)
                    }
                }
            }
            Step.CATEGORY -> {
                Title(stringResource(R.string.draft_category))
                OutlinedButton(onClick = { categoryId = null; step = Step.NOTE }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.draft_no_category))
                }
                for (option in categories) {
                    FilledTonalButton(onClick = { categoryId = option.id; step = Step.NOTE }, modifier = Modifier.fillMaxWidth()) {
                        Text(option.name)
                    }
                }
            }
            Step.NOTE -> {
                Title(stringResource(R.string.draft_note_label))
                Button(onClick = {
                    val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
                    RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder(NOTE_KEY).setLabel(noteLabel).build()))
                    noteInput.launch(intent)
                }) { Text(stringResource(R.string.draft_note_add)) }
                OutlinedButton(onClick = { note = null; step = Step.DATE }) { Text(stringResource(R.string.draft_no_note)) }
            }
            Step.DATE -> {
                Title(stringResource(R.string.draft_date))
                Button(onClick = { yesterday = false; step = Step.REVIEW }) { Text(stringResource(R.string.draft_today)) }
                Button(onClick = { yesterday = true; step = Step.REVIEW }) { Text(stringResource(R.string.draft_yesterday)) }
            }
            Step.REVIEW -> {
                val date = if (yesterday) today.minusDays(1) else today
                Title(stringResource(if (kind == QuickTransactionDraft.Kind.EXPENSE) R.string.draft_expense else R.string.draft_income))
                Text(formatAmount(amount, account?.currency ?: "MXN"), style = MaterialTheme.typography.titleLarge)
                Detail(account?.name.orEmpty())
                category?.let { Detail(it.name) }
                note?.let { Detail(it) }
                Detail(DATE.format(date))
                Text(stringResource(R.string.draft_confirm_on_phone), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    onSave(
                        QuickTransactionDraft(
                            idempotencyKey = newKey(),
                            kind = kind,
                            amount = amount,
                            accountId = checkNotNull(accountId),
                            categoryId = category?.id,
                            note = note,
                            date = date.toString(),
                            capturedAt = now,
                        ),
                    )
                }) { Text(stringResource(R.string.draft_save)) }
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.draft_cancel)) }
            }
        }
    }
}

@Composable
private fun DraftScaffold(content: @Composable () -> Unit) {
    MaterialTheme {
        val scroll = rememberScrollState()
        val focus = remember { FocusRequester() }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .rotaryScrollable(RotaryScrollableDefaults.behavior(scroll), focus)
                .focusRequester(focus)
                .focusable()
                .verticalScroll(scroll)
                .padding(horizontal = 24.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}

@Composable
private fun Title(text: String) = Text(
    text,
    modifier = Modifier.semantics { heading() },
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.labelLarge,
    textAlign = TextAlign.Center,
)

@Composable
private fun Detail(text: String) = Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)

/** Teclado numérico de 3×4 con teclas de 48 dp (área táctil mínima). */
@Composable
private fun Keypad(onKey: (Char) -> Unit) {
    val rows = listOf("123", "456", "789", ".0${AmountEntry.DELETE}")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (key in row) {
                    val description = if (key == AmountEntry.DELETE) stringResource(R.string.draft_delete) else key.toString()
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .semantics { contentDescription = description }
                            .clickable(role = Role.Button) { onKey(key) },
                        contentAlignment = Alignment.Center,
                    ) { Text(key.toString(), style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }
}

private const val NOTE_KEY = "nota"
private val DATE = DateTimeFormatter.ofPattern("EEEE d MMM", Locale.forLanguageTag("es-MX"))
