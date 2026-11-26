package mx.watchcompanion.watch.drafts

/**
 * Teclado del importe en el reloj: dígitos, un punto y hasta dos decimales, sin ceros a la izquierda. El resultado es
 * texto decimal tal cual (sin cálculos), listo para [mx.watchcompanion.core.communication.QuickTransactionDraft.amount].
 */
object AmountEntry {
    const val DELETE = '⌫'
    private const val MAX_INTEGER_DIGITS = 9

    fun press(current: String, key: Char): String = when {
        key == DELETE -> current.dropLast(1)
        key == '.' -> if ('.' in current) current else (current.ifEmpty { "0" } + ".")
        !key.isDigit() -> current
        '.' in current -> if (current.substringAfter('.').length >= 2) current else current + key
        current == "0" -> key.toString()
        current.length >= MAX_INTEGER_DIGITS -> current
        else -> current + key
    }

    /** Un importe que se puede enviar: mayor que cero y sin punto colgando. */
    fun isValid(amount: String): Boolean =
        Regex("\\d{1,12}(\\.\\d{1,2})?").matches(amount) && amount.toBigDecimal().signum() > 0
}
