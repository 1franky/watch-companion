package mx.watchcompanion.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import mx.watchcompanion.core.communication.IntegrationStatus
import mx.watchcompanion.core.communication.LinkState
import mx.watchcompanion.core.communication.GuardianCommandResult.Outcome
import mx.watchcompanion.watch.guardian.PhoneLock
import mx.watchcompanion.watch.guardian.PhoneLockState
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import mx.watchcompanion.core.communication.SyncSummary
import mx.watchcompanion.core.permissions.ModuleStatus
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Currency
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import mx.watchcompanion.core.communication.ActivityStatus
import mx.watchcompanion.core.communication.DraftStatus
import mx.watchcompanion.core.communication.FinanceSnapshot
import mx.watchcompanion.core.communication.QuickTransactionDraft
import mx.watchcompanion.watch.drafts.WatchDraft
import mx.watchcompanion.watch.live.LiveMetricsState

/**
 * Pantalla Resumen (01 §5, WC-29): conexión con el teléfono, modo activo y estado de sincronización con Velora.
 *
 * En modo ambiente solo quedan los textos, en gris sobre negro y sin botones: es lo que admite una pantalla OLED
 * siempre encendida. El estado de sincronización es el que el teléfono informó en su último Pong; sin enlace no se
 * muestra ninguno, para no presentar como vigente un dato viejo.
 *
 * @param now instante actual (ms epoch), para el «hace N min»; inyectable para pruebas.
 * @param activity último estado de la actividad en Velora que informó el teléfono (WC-33).
 * @param finance último resumen financiero de Nexora que informó el teléfono (WC-38). No se muestra en ambiente: la
 *   pantalla siempre encendida queda a la vista de cualquiera (03 §6).
 * @param financeHidden el usuario pidió ocultar las cifras de Nexora (WC-42): solo queda el título y «Mostrar cifras».
 *   También oculta los importes de los borradores.
 * @param drafts borradores de Nexora capturados aquí y su estado según el teléfono (WC-40); tampoco en ambiente.
 * @param disconnectDelay plazo del aviso de desconexión en minutos, o `null` si está apagado (WC-45).
 * @param awaySince desde cuándo no está el teléfono cerca, solo si ya se avisó.
 * @param phoneLock en qué va el bloqueo del teléfono pedido desde aquí (WC-44); solo dice «bloqueado» si el teléfono
 *   lo confirmó.
 * @param pendingRecordings grabaciones del modo B que siguen en el reloj esperando al teléfono (WC-54).
 * @param zoneId zona horaria para mostrar la fecha de la actividad; inyectable para pruebas.
 */
@Composable
fun SummaryScreen(
    link: LinkState,
    now: Long,
    onCheck: () -> Unit,
    ambient: Boolean = false,
    velora: ModuleStatus = ModuleStatus.Disabled,
    onVeloraAction: (ModuleAction) -> Unit = {},
    live: LiveMetricsState = LiveMetricsState.Idle,
    onLive: () -> Unit = {},
    onRecord: () -> Unit = {},
    pendingRecordings: Int = 0,
    activity: ActivityStatus? = null,
    finance: FinanceSnapshot? = null,
    financeHidden: Boolean = false,
    onFinanceHidden: (Boolean) -> Unit = {},
    drafts: List<WatchDraft> = emptyList(),
    onNewDraft: () -> Unit = {},
    disconnectDelay: Int? = null,
    onDisconnectDelay: () -> Unit = {},
    awaySince: Long? = null,
    phoneLock: PhoneLockState = PhoneLockState.Idle,
    onLockPhone: () -> Unit = {},
    zoneId: ZoneId = ZoneId.systemDefault(),
) {
    MaterialTheme {
        val scroll = rememberScrollState()
        val focus = remember { FocusRequester() }
        val primary = if (ambient) AMBIENT_PRIMARY else MaterialTheme.colorScheme.onSurface
        val secondary = if (ambient) AMBIENT_SECONDARY else MaterialTheme.colorScheme.onSurfaceVariant
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                // Bisel giratorio del Galaxy Watch Ultra y corona: desplazan la pantalla.
                .rotaryScrollable(RotaryScrollableDefaults.behavior(scroll), focus)
                .focusRequester(focus)
                .focusable()
                .verticalScroll(scroll)
                .padding(horizontal = 24.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.summary_title),
                modifier = Modifier.semantics { heading() },
                color = secondary,
                style = MaterialTheme.typography.labelLarge,
            )
            SummaryItem(stringResource(R.string.summary_phone), linkTitle(link), primary, secondary)
            SummaryItem(stringResource(R.string.summary_mode), stringResource(R.string.summary_mode_a), primary, secondary)
            SummaryItem(stringResource(R.string.summary_velora), veloraSyncText(link, now), primary, secondary)
            ActivityItem(activity, now, zoneId, primary, secondary)
            if (!ambient) {
                // Caducado (WC-42): no se muestra ni como desactualizado, aunque la app aún no lo haya borrado.
                val current = finance?.takeUnless { it.isExpired(now) }
                FinanceSection(current, now, zoneId, primary, secondary, financeHidden, onFinanceHidden)
                // Borrador de gasto o ingreso (WC-40): solo con cuentas de Nexora que elegir.
                if (!current?.options?.accounts.isNullOrEmpty()) {
                    FilledTonalButton(onClick = onNewDraft) { Text(stringResource(R.string.draft_new)) }
                }
                if (!financeHidden) for (entry in drafts) {
                    val currency = finance?.options?.accounts?.firstOrNull { it.id == entry.draft.accountId }?.currency
                    DraftItem(entry, currency ?: "MXN", primary, secondary)
                }
            }
            // Modo B (WC-54): rutas grabadas que aún no confirma el teléfono; salen solas cuando haya enlace.
            if (pendingRecordings > 0) {
                SummaryItem(
                    stringResource(R.string.summary_recordings),
                    pluralStringResource(R.plurals.recordings_pending, pendingRecordings, pendingRecordings),
                    primary,
                    secondary,
                )
            }
            if (live is LiveMetricsState.Active) {
                SummaryItem(stringResource(if (live.recording != null) R.string.summary_recording else R.string.summary_live), liveSummary(live), primary, secondary)
            }
            if (!ambient) {
                // Las métricas no dependen del teléfono: solo de que Velora tenga su permiso (01 §3.1).
                if (velora == ModuleStatus.Ready || live != LiveMetricsState.Idle) {
                    Button(onClick = onLive) {
                        Text(stringResource(if (live == LiveMetricsState.Idle) R.string.live_start else R.string.live_open))
                    }
                    // Modo B (WC-53): misma sesión, además graba la ruta con el GPS del reloj.
                    if (live == LiveMetricsState.Idle) {
                        FilledTonalButton(onClick = onRecord) { Text(stringResource(R.string.record_start)) }
                    }
                }
                Button(onClick = onCheck, enabled = link != LinkState.Checking) {
                    Text(stringResource(R.string.link_check))
                }
                ModuleRow(title = stringResource(R.string.module_velora), status = velora, onAction = onVeloraAction)
                GuardianSection(disconnectDelay, onDisconnectDelay, awaySince, phoneLock, onLockPhone, zoneId, primary, secondary)
            }
        }
        LaunchedEffect(ambient) { if (!ambient) focus.requestFocus() }
    }
}

/** Etiqueta y valor como un solo elemento para TalkBack («Teléfono, Conectado a …»). */
@Composable
private fun SummaryItem(label: String, value: String, primary: Color, secondary: Color) {
    Column(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = secondary, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        Text(value, color = primary, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    }
}

/** Última actividad en Velora con la hora de la consulta: un dato sin fecha no se presenta como vigente. */
@Composable
private fun ActivityItem(activity: ActivityStatus?, now: Long, zoneId: ZoneId, primary: Color, secondary: Color) {
    Column(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.summary_activity), color = secondary, style = MaterialTheme.typography.labelMedium)
        if (activity == null) {
            Text(stringResource(R.string.sync_no_data), color = primary, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            return@Column
        }
        val value = when (activity.state) {
            ActivityStatus.State.SYNCED -> stringResource(R.string.activity_synced)
            ActivityStatus.State.SYNCING -> stringResource(R.string.activity_syncing)
            ActivityStatus.State.PENDING -> stringResource(R.string.activity_pending)
            ActivityStatus.State.ERROR -> stringResource(R.string.activity_error, activity.errorCode ?: "?")
        }
        Text(value, color = primary, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        activity.activityStartedAt?.let { startedAt ->
            Text(activityDetail(startedAt, activity.distanceMeters, zoneId), color = primary, textAlign = TextAlign.Center)
        }
        Text(stringResource(R.string.activity_checked, elapsed(now - activity.checkedAt)), color = secondary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun activityDetail(startedAt: Long, distanceMeters: Double?, zoneId: ZoneId): String {
    val date = ACTIVITY_DATE.format(Instant.ofEpochMilli(startedAt).atZone(zoneId))
    return distanceMeters?.let { stringResource(R.string.activity_detail_km, date, it / 1000) } ?: date
}

private val ACTIVITY_DATE = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.forLanguageTag("es-MX"))

/**
 * Resumen financiero de Nexora (WC-38): las cifras tal como las calculó Nexora, cada una con su hora de actualización.
 * Si la última consulta falló o pasaron más de [FinanceSnapshot.STALE_AFTER_MS], se marcan como desactualizadas.
 * Con [hidden] (WC-42) no se dibuja ninguna cifra: solo el botón para mostrarlas.
 */
@Composable
private fun FinanceSection(
    finance: FinanceSnapshot?,
    now: Long,
    zoneId: ZoneId,
    primary: Color,
    secondary: Color,
    hidden: Boolean,
    onHidden: (Boolean) -> Unit,
) {
    Text(
        stringResource(R.string.summary_finance),
        modifier = Modifier.semantics { heading() },
        color = secondary,
        style = MaterialTheme.typography.labelLarge,
    )
    if (hidden) {
        Text(stringResource(R.string.finance_hidden), color = primary, textAlign = TextAlign.Center)
        OutlinedButton(onClick = { onHidden(false) }) { Text(stringResource(R.string.finance_show)) }
        return
    }
    val figures = finance?.figures
    if (finance == null || figures == null) {
        val message = when (finance?.state) {
            null -> stringResource(R.string.sync_no_data)
            FinanceSnapshot.State.SIGNED_OUT -> stringResource(R.string.finance_signed_out)
            else -> stringResource(R.string.finance_error, finance.errorCode ?: "?")
        }
        Text(message, color = primary, textAlign = TextAlign.Center)
        return
    }
    val stale = finance.isStale(now)
    if (stale) {
        val reason = if (finance.state == FinanceSnapshot.State.ERROR) {
            stringResource(R.string.finance_stale_error, finance.errorCode ?: "?")
        } else {
            stringResource(R.string.finance_stale, elapsed(now - figures.fetchedAt))
        }
        Text(reason, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    }
    val time = FINANCE_TIME.format(Instant.ofEpochMilli(figures.fetchedAt).atZone(zoneId))
    val month = YearMonth.parse(figures.month).month.getDisplayName(TextStyle.FULL_STANDALONE, ES_MX)
    FigureItem(stringResource(R.string.finance_available), figures.availableBalance, figures.currency, time, stale, primary, secondary)
    FigureItem(stringResource(R.string.finance_card_debt), figures.creditCardDebt, figures.currency, time, stale, primary, secondary)
    FigureItem(stringResource(R.string.finance_expenses, month), figures.expenseThisMonth, figures.currency, time, stale, primary, secondary)
    // Próximos pagos y últimos movimientos (WC-39): misma consulta, misma hora y misma marca de desactualizado.
    val itemColor = if (stale) secondary else primary
    if (figures.upcomingPayments.isNotEmpty()) {
        ListTitle(stringResource(R.string.finance_upcoming, time), secondary)
        for (payment in figures.upcomingPayments) {
            ListItem(
                stringResource(R.string.finance_upcoming_item, payment.cardName, SHORT_DATE.format(LocalDate.parse(payment.dueDate))),
                formatAmount(payment.expectedPayment, figures.currency),
                itemColor,
                secondary,
            )
        }
    }
    if (figures.recentTransactions.isNotEmpty()) {
        ListTitle(stringResource(R.string.finance_recent, time), secondary)
        for (transaction in figures.recentTransactions) {
            val label = transaction.label.ifEmpty { transactionType(transaction.type) }
            ListItem(
                "$label · ${SHORT_DATE.format(LocalDate.parse(transaction.date))}",
                formatAmount(transaction.signedAmount, figures.currency),
                itemColor,
                secondary,
            )
        }
    }
    OutlinedButton(onClick = { onHidden(true) }) { Text(stringResource(R.string.finance_hide)) }
}

/**
 * Guardian: bloquear el teléfono (WC-44) con su resultado real, el plazo del aviso de desconexión (WC-45), que se
 * cambia tocándolo, y desde cuándo falta el teléfono.
 */
@Composable
private fun GuardianSection(
    delay: Int?,
    onDelay: () -> Unit,
    awaySince: Long?,
    lock: PhoneLockState,
    onLock: () -> Unit,
    zoneId: ZoneId,
    primary: Color,
    secondary: Color,
) {
    Text(
        stringResource(R.string.summary_guardian),
        modifier = Modifier.semantics { heading() },
        color = secondary,
        style = MaterialTheme.typography.labelLarge,
    )
    awaySince?.let {
        val time = FINANCE_TIME.format(Instant.ofEpochMilli(it).atZone(zoneId))
        Text(stringResource(R.string.guardian_away_since, time), color = primary, textAlign = TextAlign.Center)
    }
    val sending = lock is PhoneLockState.Sending
    Button(onClick = onLock, enabled = !sending) {
        Text(stringResource(if (sending) R.string.guardian_locking else R.string.guardian_lock))
    }
    if (lock is PhoneLockState.Done) {
        Text(
            lockResultText(lock, zoneId),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = primary,
            textAlign = TextAlign.Center,
        )
    }
    OutlinedButton(onClick = onDelay) {
        Text(if (delay == null) stringResource(R.string.guardian_delay_off) else stringResource(R.string.guardian_delay, delay))
    }
}

/** Solo [Outcome.LOCKED] se lee como «bloqueado»; lo demás dice por qué no, o que no hubo confirmación. */
@Composable
private fun lockResultText(lock: PhoneLockState.Done, zoneId: ZoneId): String {
    val code = lock.result.errorCode
    return when (lock.result.outcome) {
        Outcome.LOCKED -> stringResource(R.string.guardian_locked, FINANCE_TIME.format(Instant.ofEpochMilli(lock.at).atZone(zoneId)))
        Outcome.EXPIRED -> stringResource(R.string.guardian_lock_expired)
        Outcome.REJECTED -> stringResource(R.string.guardian_lock_rejected, code ?: "?")
        Outcome.UNAVAILABLE -> when (code) {
            PhoneLock.NO_PHONE -> stringResource(R.string.guardian_lock_no_phone)
            "ADMIN_INACTIVE" -> stringResource(R.string.guardian_lock_admin_inactive)
            "NO_SECURE_LOCK" -> stringResource(R.string.guardian_lock_no_pin)
            "NOT_CONFIRMED" -> stringResource(R.string.guardian_lock_not_confirmed)
            else -> stringResource(R.string.guardian_lock_unavailable, code ?: "?")
        }
    }
}

@Composable
private fun ListTitle(text: String, color: Color) = Text(
    text,
    modifier = Modifier.semantics { heading() },
    color = color,
    style = MaterialTheme.typography.labelMedium,
    textAlign = TextAlign.Center,
)

/** Etiqueta y monto como un solo elemento para TalkBack. */
@Composable
private fun ListItem(label: String, amount: String, primary: Color, secondary: Color) {
    Column(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = secondary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, maxLines = 2)
        Text(amount, color = primary, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

@Composable
private fun transactionType(type: String): String = stringResource(
    when (type) {
        "EXPENSE" -> R.string.tx_expense
        "INCOME" -> R.string.tx_income
        "TRANSFER" -> R.string.tx_transfer
        "CREDIT_CARD_PURCHASE" -> R.string.tx_card_purchase
        "CREDIT_CARD_PAYMENT" -> R.string.tx_card_payment
        else -> R.string.tx_other
    },
)

/** Un borrador y su estado. Nunca dice «registrado» sin que el teléfono lo confirme con Nexora. */
@Composable
private fun DraftItem(entry: WatchDraft, currency: String, primary: Color, secondary: Color) {
    val draft = entry.draft
    val kind = stringResource(if (draft.kind == QuickTransactionDraft.Kind.EXPENSE) R.string.draft_expense else R.string.draft_income)
    val code = entry.status?.errorCode ?: "?"
    val state = when (entry.status?.state) {
        null -> stringResource(R.string.draft_state_sending)
        DraftStatus.State.RECEIVED -> stringResource(R.string.draft_state_received)
        DraftStatus.State.CREATED -> stringResource(R.string.draft_state_created)
        DraftStatus.State.REJECTED -> stringResource(R.string.draft_state_rejected, code)
        DraftStatus.State.CONFLICT -> stringResource(R.string.draft_state_conflict)
        DraftStatus.State.UNKNOWN -> stringResource(R.string.draft_state_unknown)
        DraftStatus.State.DISCARDED -> stringResource(R.string.draft_state_discarded)
    }
    Column(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$kind · ${formatAmount(draft.amount, currency)}", color = secondary, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        Text(state, color = primary, textAlign = TextAlign.Center)
    }
}

/** Una cifra con su hora; para TalkBack, «Disponible, actualizado 12:33, desactualizado, $15,230.50». */
@Composable
private fun FigureItem(label: String, amount: String, currency: String, time: String, stale: Boolean, primary: Color, secondary: Color) {
    Column(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val status = if (stale) stringResource(R.string.finance_figure_stale, time) else stringResource(R.string.finance_figure_time, time)
        Text("$label · $status", color = secondary, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        Text(
            formatAmount(amount, currency),
            color = if (stale) secondary else primary,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
    }
}

/** Solo da formato (es-MX): el importe es el que mandó Nexora, sin recalcular. */
internal fun formatAmount(amount: String, currency: String): String =
    NumberFormat.getCurrencyInstance(ES_MX).apply { this.currency = Currency.getInstance(currency) }.format(BigDecimal(amount))

private val ES_MX = Locale.forLanguageTag("es-MX")
private val FINANCE_TIME = DateTimeFormatter.ofPattern("HH:mm", ES_MX)
private val SHORT_DATE = DateTimeFormatter.ofPattern("d MMM", ES_MX)

@Composable
private fun liveSummary(live: LiveMetricsState.Active): String = when {
    live.paused -> stringResource(R.string.live_paused)
    live.heartRateBpm != null -> stringResource(R.string.live_bpm_value, live.heartRateBpm)
    else -> stringResource(R.string.live_running)
}

@Composable
private fun linkTitle(state: LinkState): String = when (state) {
    LinkState.Unknown, LinkState.Checking -> stringResource(R.string.link_checking)
    LinkState.NoPeer -> stringResource(R.string.link_no_peer)
    is LinkState.Unreachable -> stringResource(R.string.link_unreachable, state.peerName)
    is LinkState.Connected -> stringResource(R.string.link_connected, state.peerName)
    LinkState.Incompatible -> stringResource(R.string.link_incompatible)
}

@Composable
private fun veloraSyncText(link: LinkState, now: Long): String {
    val sync = (link as? LinkState.Connected)?.peerSync?.firstOrNull { it.integration == IntegrationStatus.Integration.VELORA }
        ?: return stringResource(R.string.sync_no_data)
    return when (sync.state) {
        SyncSummary.State.SIGNED_OUT -> stringResource(R.string.sync_signed_out)
        SyncSummary.State.SESSION_EXPIRED -> stringResource(R.string.sync_session_expired)
        SyncSummary.State.NEVER_SYNCED -> stringResource(R.string.sync_never)
        SyncSummary.State.SYNCED -> sync.lastSuccessAt?.let { stringResource(R.string.sync_ok, elapsed(now - it)) }
            ?: stringResource(R.string.sync_never)
        SyncSummary.State.ERROR -> stringResource(R.string.sync_error, sync.errorCode ?: "?")
    }
}

/** «hace un momento», «hace 5 min», «hace 2 h», «hace 3 días». Un reloj adelantado al teléfono no da negativos. */
@Composable
private fun elapsed(ms: Long): String {
    val minutes = ms.coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> stringResource(R.string.elapsed_now)
        minutes < 60 -> stringResource(R.string.elapsed_minutes, minutes)
        minutes < 24 * 60 -> stringResource(R.string.elapsed_hours, minutes / 60)
        else -> (minutes / (24 * 60)).toInt().let { pluralStringResource(R.plurals.elapsed_days, it, it) }
    }
}

/** Gris claro en lugar de blanco puro: menos consumo y menos desgaste del OLED en ambiente. */
private val AMBIENT_PRIMARY = Color(0xFFD0D0D0)
private val AMBIENT_SECONDARY = Color(0xFF9E9E9E)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun SummaryScreenPreview() = SummaryScreen(
    link = LinkState.Connected(
        "Galaxy S26 Ultra", "0.1.0", roundTripMs = 42, checkedAt = 0,
        peerSync = listOf(SyncSummary(IntegrationStatus.Integration.VELORA, SyncSummary.State.SYNCED, lastSuccessAt = 0)),
    ),
    now = 5 * 60_000,
    onCheck = {},
    velora = ModuleStatus.Ready,
)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun SummaryScreenAmbientPreview() =
    SummaryScreen(link = LinkState.Unreachable("Galaxy S26 Ultra"), now = 0, onCheck = {}, ambient = true)
