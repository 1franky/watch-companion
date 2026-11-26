package mx.watchcompanion.core.communication

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Resumen financiero de solo lectura de Nexora (WC-38, 03 §2), tal como lo consultó el teléfono en
 * `GET /api/v1/dashboard`. El reloj solo lo muestra: no calcula ninguna cifra (03 §1).
 *
 * Si una consulta falla, [figures] conserva las últimas cifras buenas con su [FinanceFigures.fetchedAt] y el estado es
 * [State.ERROR]: el reloj las marca como desactualizadas, nunca como vigentes. Sin sesión en Nexora no viaja ninguna
 * cifra, así que cerrar sesión en el teléfono las borra del reloj.
 */
@Serializable
data class FinanceSnapshot(
    val state: State,
    /** Cuándo intentó el teléfono la última consulta (ms epoch UTC), haya salido bien o no. */
    val checkedAt: Long,
    val figures: FinanceFigures? = null,
    /** Motivo de [State.ERROR], con el formato de los códigos de diagnóstico. */
    val errorCode: String? = null,
    /** Cuentas y categorías para el borrador del reloj (WC-40); se conservan como las cifras si una consulta falla. */
    val options: DraftOptions? = null,
) {
    init {
        require(errorCode == null || CODE.matches(errorCode)) { "código de error inválido" }
        require(state != State.SIGNED_OUT || (figures == null && options == null)) { "sin sesión no viajan cifras" }
    }

    /** Desactualizado: la última consulta falló o las cifras tienen más de [STALE_AFTER_MS]. */
    fun isStale(now: Long): Boolean {
        val fetchedAt = figures?.fetchedAt ?: return true
        return state != State.OK || now - fetchedAt > STALE_AFTER_MS
    }

    @Serializable
    enum class State {
        /** La última consulta salió bien: [figures] es de [checkedAt]. */
        OK,

        /** No hay sesión de Nexora en el teléfono. */
        SIGNED_OUT,
        ERROR,
    }

    companion object {
        /** Pasado este tiempo, una cifra se marca como desactualizada aunque la última consulta saliera bien. */
        const val STALE_AFTER_MS: Long = 15 * 60_000L
        private val CODE = Regex("[A-Z][A-Z0-9_]{0,31}")
    }
}

/**
 * Las cifras del dashboard que muestra el reloj, tal como las da Nexora (ya convertidas a MXN). Los importes viajan
 * como texto decimal (`"1234.50"`) para que nadie los recalcule ni pierda precisión por el camino.
 */
@Serializable
data class FinanceFigures(
    /** Cuándo respondió Nexora (ms epoch UTC). */
    val fetchedAt: Long,
    /** Mes de los gastos, `yyyy-MM`. */
    val month: String,
    val availableBalance: String,
    /** Deuda total de tarjetas (no lo que toca pagar este corte). */
    val creditCardDebt: String,
    val expenseThisMonth: String,
    val currency: String = "MXN",
    /** Próximos pagos de tarjeta del dashboard (WC-39), por fecha límite. */
    val upcomingPayments: List<UpcomingPayment> = emptyList(),
    /** Últimos movimientos del dashboard (WC-39), del más reciente al más viejo. */
    val recentTransactions: List<RecentTransaction> = emptyList(),
) {
    init {
        require(MONTH.matches(month)) { "mes inválido" }
        require(listOf(availableBalance, creditCardDebt, expenseThisMonth).all { AMOUNT.matches(it) }) { "importe inválido" }
        require(CURRENCY.matches(currency)) { "moneda inválida" }
        require(upcomingPayments.size <= MAX_ITEMS && recentTransactions.size <= MAX_ITEMS) { "demasiados elementos" }
    }

    companion object {
        const val MAX_ITEMS = 5
        const val MAX_LABEL = 40
        internal val MONTH = Regex("\\d{4}-\\d{2}")
        internal val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
        internal val AMOUNT = Regex("-?\\d{1,15}(\\.\\d{1,4})?")
        internal val CURRENCY = Regex("[A-Z]{3}")

        /** Una cadena de 6 o más dígitos (cuenta, tarjeta, CLABE) se reduce a sus 4 últimos: «••1234». */
        private val LONG_NUMBER = Regex("\\d[\\d -]{4,}\\d")

        fun mask(text: String): String = LONG_NUMBER.replace(text) { match ->
            val digits = match.value.filter(Char::isDigit)
            if (digits.length >= 6) "••" + digits.takeLast(4) else match.value
        }
    }
}

/**
 * Un pago de tarjeta próximo (WC-39): el nombre que el usuario le puso a la tarjeta, nunca su número, la fecha límite y
 * el pago estimado tal como lo calculó Nexora.
 */
@Serializable
data class UpcomingPayment(val cardName: String, val dueDate: String, val expectedPayment: String) {
    init {
        require(cardName.length <= FinanceFigures.MAX_LABEL && FinanceFigures.mask(cardName) == cardName) { "nombre inválido" }
        require(FinanceFigures.DATE.matches(dueDate)) { "fecha inválida" }
        require(FinanceFigures.AMOUNT.matches(expectedPayment)) { "importe inválido" }
    }
}

/**
 * Un movimiento reciente (WC-39). [signedAmount] es el `balanceEffect` de Nexora (negativo si bajó el saldo); [label]
 * sale del comercio o la descripción, ya enmascarado. La referencia bancaria nunca viaja.
 *
 * @property type el tipo de Nexora (`EXPENSE`, `INCOME`, `TRANSFER`…); el reloj muestra uno genérico si no lo conoce.
 */
@Serializable
data class RecentTransaction(val date: String, val label: String, val signedAmount: String, val type: String) {
    init {
        require(FinanceFigures.DATE.matches(date)) { "fecha inválida" }
        require(label.length <= FinanceFigures.MAX_LABEL && FinanceFigures.mask(label) == label) { "etiqueta inválida" }
        require(FinanceFigures.AMOUNT.matches(signedAmount)) { "importe inválido" }
        require(TYPE.matches(type)) { "tipo inválido" }
    }

    private companion object {
        val TYPE = Regex("[A-Z_]{1,32}")
    }
}

/** El teléfono envía el resumen en cuanto termina de consultarlo o cambia la sesión de Nexora; el Pong lleva el último. */
@Serializable
@SerialName("FinanceSnapshotReport")
data class FinanceSnapshotReport(val snapshot: FinanceSnapshot) : Payload
