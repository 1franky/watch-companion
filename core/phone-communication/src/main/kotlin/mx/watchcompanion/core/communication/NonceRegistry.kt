package mx.watchcompanion.core.communication

/** Recuerda los nonce ya vistos hasta que su mensaje caduca, para rechazar repeticiones (04-watch-guardian.md §6). */
interface NonceRegistry {
    /** Registra [nonce] si es nuevo y devuelve `true`; devuelve `false` si ya se había visto. */
    fun register(nonce: String, expiresAt: Long, now: Long): Boolean
}

/** Implementación en memoria; suficiente porque los mensajes sensibles caducan en segundos. */
class InMemoryNonceRegistry : NonceRegistry {
    private val seen = HashMap<String, Long>()

    @Synchronized
    override fun register(nonce: String, expiresAt: Long, now: Long): Boolean {
        seen.entries.removeAll { it.value <= now }
        if (nonce in seen) return false
        seen[nonce] = expiresAt
        return true
    }
}
