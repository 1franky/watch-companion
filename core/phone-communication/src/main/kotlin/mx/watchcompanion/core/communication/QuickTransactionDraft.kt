package mx.watchcompanion.core.communication

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Borrador de un gasto o ingreso capturado en el reloj (WC-40, 03 §2). No registra nada: viaja al teléfono, que lo
 * guarda cifrado y solo lo manda a Nexora cuando el usuario lo confirma allí, con [idempotencyKey] como
 * `Idempotency-Key`. La clave se genera en el reloj al capturarlo, así que reenviar el borrador nunca crea dos.
 *
 * No es un mensaje sensible (sin `nonce`): por sí solo no tiene efecto, y el teléfono lo deduplica por la clave.
 */
@Serializable
@SerialName("QuickTransactionDraft")
data class QuickTransactionDraft(
    val idempotencyKey: String,
    val kind: Kind,
    /** Importe positivo en texto decimal (`"250.50"`), en la moneda de la cuenta. */
    val amount: String,
    val accountId: String,
    val categoryId: String? = null,
    val note: String? = null,
    /** Fecha del movimiento, `yyyy-MM-dd`, en la zona del reloj. */
    val date: String,
    /** Cuándo se capturó (ms epoch UTC). */
    val capturedAt: Long,
) : Payload {
    init {
        require(UUID.matches(idempotencyKey)) { "clave inválida" }
        require(UUID.matches(accountId)) { "cuenta inválida" }
        require(categoryId == null || UUID.matches(categoryId)) { "categoría inválida" }
        require(AMOUNT.matches(amount) && amount.toBigDecimal().signum() > 0) { "importe inválido" }
        require(note == null || note.length <= MAX_NOTE) { "nota demasiado larga" }
        require(DATE.matches(date)) { "fecha inválida" }
    }

    /** Sin importe ni nota (WC-42): nada de lo que capturó el usuario acaba en un log. */
    override fun toString() = "QuickTransactionDraft(idempotencyKey=$idempotencyKey, kind=$kind, date=$date, …)"

    @Serializable
    enum class Kind { EXPENSE, INCOME }

    companion object {
        const val MAX_NOTE = 140
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val AMOUNT = Regex("\\d{1,12}(\\.\\d{1,2})?")
        private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
    }
}

/**
 * En qué va un borrador, tal como lo sabe el teléfono. Solo la clave, el estado y un código: el importe y la nota no
 * vuelven al reloj.
 */
@Serializable
data class DraftStatus(
    val idempotencyKey: String,
    val state: State,
    /** Cuándo cambió (ms epoch UTC, reloj del teléfono). */
    val updatedAt: Long,
    /** Motivo de [State.REJECTED], [State.UNKNOWN] o de un intento que no salió; formato de diagnóstico. */
    val errorCode: String? = null,
) {
    init {
        require(errorCode == null || CODE.matches(errorCode)) { "código de error inválido" }
    }

    @Serializable
    enum class State {
        /** Guardado en el teléfono, esperando que el usuario lo confirme allí. Nada llegó a Nexora. */
        RECEIVED,

        /** Nexora lo registró (2xx). Es el único estado que significa «registrado». */
        CREATED,

        /** Nexora lo rechazó (4xx): no se registró. */
        REJECTED,

        /** 409: la clave ya se había usado con otro movimiento. No se registró este. */
        CONFLICT,

        /** Se envió y no hubo respuesta (sin red, 5xx): pudo registrarse o no. Solo se resuelve reintentando. */
        UNKNOWN,

        /** El usuario lo descartó en el teléfono antes de registrarlo. */
        DISCARDED,
        ;

        /** Ya no va a cambiar. */
        val final: Boolean get() = this == CREATED || this == DISCARDED
    }

    private companion object {
        val CODE = Regex("[A-Z][A-Z0-9_]{0,31}")
    }
}

/** El teléfono avisa al reloj cada vez que un borrador cambia de estado; el Pong lleva todos los vigentes. */
@Serializable
@SerialName("TransactionResult")
data class TransactionResult(val status: DraftStatus) : Payload

/**
 * Cuentas y categorías entre las que el reloj deja elegir (WC-40). Solo id y nombre; viajan con el resumen financiero
 * y, como él, solo en memoria. Sin cuentas de tarjeta: un gasto con tarjeta es una compra (`/credit-cards`), no un
 * `EXPENSE`.
 */
@Serializable
data class DraftOptions(val accounts: List<DraftOption>, val categories: List<DraftOption>) {
    override fun toString() = "DraftOptions(accounts=${accounts.size}, categories=${categories.size})"
}

/**
 * @property currency moneda de la cuenta (el importe se captura en ella); solo en cuentas.
 * @property kind `INCOME` o `EXPENSE`: para qué tipo de movimiento sirve la categoría; solo en categorías.
 */
@Serializable
data class DraftOption(val id: String, val name: String, val currency: String? = null, val kind: QuickTransactionDraft.Kind? = null)
