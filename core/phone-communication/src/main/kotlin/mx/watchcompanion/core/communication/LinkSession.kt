package mx.watchcompanion.core.communication

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import mx.watchcompanion.core.diagnostics.DiagnosticEvent
import mx.watchcompanion.core.diagnostics.DiagnosticModule
import mx.watchcompanion.core.diagnostics.DiagnosticsRecorder
import mx.watchcompanion.core.diagnostics.Outcome
import mx.watchcompanion.core.diagnostics.start

/**
 * Enlace de un extremo (reloj o teléfono) con el otro: comprueba la conexión con Ping/Pong, responde los pings que
 * recibe y avisa de incompatibilidades de versión. Es igual en ambos lados; solo cambia el [transport].
 *
 * @param clock milisegundos epoch; inyectable para pruebas.
 * @param diagnostics registro técnico (WC-23): cada comprobación y cada mensaje descartado.
 * @param syncSummary estado de sincronización que se envía en cada Pong (WC-29). Solo lo da el teléfono; si falla, el
 *   Pong sale igual, sin él: la conexión no depende del estado de los backends.
 */
class LinkSession(
    private val transport: LinkTransport,
    private val codec: ProtocolCodec,
    private val deviceId: String,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val pingTimeoutMs: Long = PING_TIMEOUT_MS,
    private val diagnostics: DiagnosticsRecorder = DiagnosticsRecorder.NONE,
    private val syncSummary: suspend () -> List<SyncSummary> = { emptyList() },
) {
    private val _state = MutableStateFlow<LinkState>(LinkState.Unknown)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val pendingPings = ConcurrentHashMap<String, CompletableDeferred<Pong>>()
    private val lastIncompatibleReply = ConcurrentHashMap<String, Long>()

    /** Busca al otro dispositivo y le manda un Ping; el estado final queda en [state] y se devuelve. */
    suspend fun refresh(): LinkState {
        _state.value = LinkState.Checking
        val span = diagnostics.start(DiagnosticModule.LINK, "link.ping", clock)
        val result = check()
        _state.value = result
        when (result) {
            is LinkState.Connected -> span.ok("CONNECTED")
            LinkState.NoPeer -> span.error("NO_PEER")
            is LinkState.Unreachable -> span.error("UNREACHABLE")
            LinkState.Incompatible -> span.error("INCOMPATIBLE")
            LinkState.Unknown, LinkState.Checking -> Unit
        }
        return result
    }

    private suspend fun check(): LinkState {
        val peer = try {
            transport.reachablePeers().firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return LinkState.NoPeer

        val ping = envelope(Ping)
        val reply = CompletableDeferred<Pong>()
        pendingPings[ping.messageId] = reply
        val sentAt = clock()
        try {
            transport.send(peer.id, codec.encode(ping))
            val pong = withTimeoutOrNull(pingTimeoutMs) { reply.await() }
                ?: return if (_state.value == LinkState.Incompatible) LinkState.Incompatible else LinkState.Unreachable(peer.displayName)
            val now = clock()
            return LinkState.Connected(peer.displayName, pong.appVersion, now - sentAt, now, pong.sync)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return LinkState.Unreachable(peer.displayName)
        } finally {
            pendingPings.remove(ping.messageId)
        }
    }

    /** Procesa un mensaje recibido de [fromPeerId]. Lo llama el servicio que escucha la Data Layer. */
    suspend fun onMessage(fromPeerId: String, bytes: ByteArray) {
        when (val result = codec.decode(bytes, clock())) {
            is DecodeResult.Accepted -> handle(fromPeerId, result.envelope)
            is DecodeResult.Incompatible -> {
                _state.value = LinkState.Incompatible
                rejected("INCOMPATIBLE")
                replyIncompatible(fromPeerId)
            }
            // Ilegible, caducado o repetido: se descarta sin ejecutar nada y queda registrado.
            DecodeResult.Malformed -> rejected("MALFORMED")
            DecodeResult.Expired -> rejected("EXPIRED")
            DecodeResult.Replayed -> rejected("REPLAYED")
            DecodeResult.MissingSecurityFields -> rejected("MISSING_SECURITY_FIELDS")
        }
    }

    private suspend fun handle(fromPeerId: String, envelope: Envelope) {
        when (val payload = envelope.payload) {
            Ping -> sendQuietly(fromPeerId, envelope(Pong(appVersion, currentSync()), correlationId = envelope.messageId))
            is Pong -> envelope.correlationId?.let { pendingPings[it]?.complete(payload) }
            is IntegrationStatus ->
                if (payload.integration == IntegrationStatus.Integration.PHONE_LINK &&
                    payload.state == IntegrationStatus.State.INCOMPATIBLE
                ) {
                    _state.value = LinkState.Incompatible
                }
        }
    }

    private suspend fun currentSync(): List<SyncSummary> = try {
        syncSummary()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    /** Solo el motivo: el contenido del mensaje rechazado no se registra. */
    private suspend fun rejected(code: String) =
        diagnostics.record(DiagnosticModule.LINK, "link.receive", DiagnosticEvent.newCorrelationId(), Outcome.ERROR, code, 0)

    /** Como mucho una respuesta por minuto y dispositivo, para no entrar en un ciclo con la otra app. */
    private suspend fun replyIncompatible(peerId: String) {
        val now = clock()
        val last = lastIncompatibleReply[peerId]
        if (last != null && now - last < INCOMPATIBLE_REPLY_INTERVAL_MS) return
        lastIncompatibleReply[peerId] = now
        val status = IntegrationStatus(IntegrationStatus.Integration.PHONE_LINK, IntegrationStatus.State.INCOMPATIBLE)
        sendQuietly(peerId, envelope(status))
    }

    private suspend fun sendQuietly(peerId: String, envelope: Envelope) {
        try {
            transport.send(peerId, codec.encode(envelope))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Si la respuesta no sale, el otro lado lo verá como Unreachable; no hay nada más que hacer aquí.
        }
    }

    private fun envelope(payload: Payload, correlationId: String? = null) = Envelope(
        messageId = newId(),
        schemaVersion = ProtocolVersion.CURRENT,
        createdAt = clock(),
        deviceId = deviceId,
        payload = payload,
        correlationId = correlationId,
    )

    companion object {
        const val PING_TIMEOUT_MS: Long = 5_000
        const val INCOMPATIBLE_REPLY_INTERVAL_MS: Long = 60_000
    }
}
