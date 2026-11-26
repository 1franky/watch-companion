package mx.watchcompanion.core.communication

import kotlinx.coroutines.test.TestScope
import mx.watchcompanion.core.diagnostics.DiagnosticModule
import mx.watchcompanion.core.diagnostics.DiagnosticsRecorder
import mx.watchcompanion.core.diagnostics.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSessionTest {
    private val codec = ProtocolCodec(InMemoryNonceRegistry())

    /** Une dos sesiones en memoria: lo que una envía, la otra lo recibe al instante. */
    private class Loopback(private val peers: List<Peer>) : LinkTransport {
        var other: LinkSession? = null
        var failSend = false
        val sent = mutableListOf<ByteArray>()
        override suspend fun reachablePeers() = peers
        override suspend fun send(peerId: String, bytes: ByteArray) {
            if (failSend) error("Data Layer no disponible")
            sent += bytes
            other?.onMessage("self", bytes)
        }
    }

    private fun TestScope.session(transport: LinkTransport, version: String) =
        LinkSession(transport, codec, deviceId = "dev-$version", appVersion = version, clock = { testScheduler.currentTime })

    private val telefono = Peer(id = "nodo-telefono", displayName = "Galaxy S26 Ultra", isNearby = true)

    @Test
    fun conectaCuandoElOtroLadoRespondeElPing() = runTest {
        val haciaTelefono = Loopback(listOf(telefono))
        val haciaReloj = Loopback(listOf(Peer("nodo-reloj", "Galaxy Watch Ultra", true)))
        val reloj = session(haciaTelefono, "0.1.0")
        val phone = session(haciaReloj, "0.1.1")
        haciaTelefono.other = phone
        haciaReloj.other = reloj

        val estado = reloj.refresh()

        assertTrue(estado is LinkState.Connected)
        estado as LinkState.Connected
        assertEquals("Galaxy S26 Ultra", estado.peerName)
        assertEquals("0.1.1", estado.peerAppVersion)
        assertEquals(estado, reloj.state.value)
    }

    private val veloraAlDia =
        SyncSummary(IntegrationStatus.Integration.VELORA, SyncSummary.State.SYNCED, lastSuccessAt = 1_000)

    @Test
    fun elPongDelTelefonoTraeSuEstadoDeSincronizacion() = runTest {
        val haciaTelefono = Loopback(listOf(telefono))
        val haciaReloj = Loopback(listOf(Peer("nodo-reloj", "Galaxy Watch Ultra", true)))
        val reloj = session(haciaTelefono, "0.1.0")
        val phone = LinkSession(
            haciaReloj, codec, deviceId = "dev-tel", appVersion = "0.1.0",
            clock = { testScheduler.currentTime }, syncSummary = { listOf(veloraAlDia) },
        )
        haciaTelefono.other = phone
        haciaReloj.other = reloj

        val estado = reloj.refresh() as LinkState.Connected

        assertEquals(listOf(veloraAlDia), estado.peerSync)
    }

    @Test
    fun siElEstadoDeSincronizacionFallaElPongSaleIgualSinEl() = runTest {
        val haciaTelefono = Loopback(listOf(telefono))
        val haciaReloj = Loopback(listOf(Peer("nodo-reloj", "Galaxy Watch Ultra", true)))
        val reloj = session(haciaTelefono, "0.1.0")
        val phone = LinkSession(
            haciaReloj, codec, deviceId = "dev-tel", appVersion = "0.1.0",
            clock = { testScheduler.currentTime }, syncSummary = { error("sin acceso al registro") },
        )
        haciaTelefono.other = phone
        haciaReloj.other = reloj

        val estado = reloj.refresh() as LinkState.Connected

        assertEquals(emptyList<SyncSummary>(), estado.peerSync)
    }

    private val zonas = HeartRateZones.fromMax(185, HeartRateZones.Source.VELORA, updatedAt = 7)

    @Test
    fun elRelojRecibeLasZonasEnElPongDelTelefono() = runTest {
        val haciaTelefono = Loopback(listOf(telefono))
        val haciaReloj = Loopback(listOf(Peer("nodo-reloj", "Galaxy Watch Ultra", true)))
        val recibidas = mutableListOf<HeartRateZones>()
        val reloj = LinkSession(
            haciaTelefono, codec, deviceId = "dev-reloj", appVersion = "0.1.0",
            clock = { testScheduler.currentTime }, onZones = { recibidas += it },
        )
        val phone = LinkSession(
            haciaReloj, codec, deviceId = "dev-tel", appVersion = "0.1.0",
            clock = { testScheduler.currentTime }, zonesProvider = { zonas },
        )
        haciaTelefono.other = phone
        haciaReloj.other = reloj

        assertTrue(reloj.refresh() is LinkState.Connected)
        assertEquals(listOf(zonas), recibidas)
    }

    @Test
    fun elTelefonoEnviaLasZonasAlGuardar() = runTest {
        val haciaReloj = Loopback(listOf(Peer("nodo-reloj", "Galaxy Watch Ultra", true)))
        val recibidas = mutableListOf<HeartRateZones>()
        val reloj = LinkSession(
            Loopback(emptyList()), codec, deviceId = "dev-reloj", appVersion = "0.1.0",
            clock = { testScheduler.currentTime }, onZones = { recibidas += it },
        )
        val phone = session(haciaReloj, "0.1.0")
        haciaReloj.other = reloj

        assertTrue(phone.sendToPeer(HeartRateZonesConfig(zonas)))
        assertEquals(listOf(zonas), recibidas)
    }

    @Test
    fun sinRelojAlcanzableElEnvioDirectoDevuelveFalse() = runTest {
        assertEquals(false, session(Loopback(emptyList()), "0.1.0").sendToPeer(HeartRateZonesConfig(zonas)))
        val fallido = Loopback(listOf(telefono)).apply { failSend = true }
        assertEquals(false, session(fallido, "0.1.0").sendToPeer(HeartRateZonesConfig(zonas)))
    }

    @Test
    fun unPongNoSolicitadoNoEntregaZonas() = runTest {
        val recibidas = mutableListOf<HeartRateZones>()
        val reloj = LinkSession(
            Loopback(listOf(telefono)), codec, deviceId = "dev-reloj", appVersion = "0.1.0",
            clock = { testScheduler.currentTime }, onZones = { recibidas += it },
        )
        val pong = Envelope("m", ProtocolVersion.CURRENT, 0, "d", Pong("0.1.0", zones = zonas), correlationId = "desconocido")
        reloj.onMessage("nodo-telefono", codec.encode(pong))
        assertEquals(emptyList<HeartRateZones>(), recibidas)
    }

    private val actividad = ActivityStatus(ActivityStatus.State.SYNCED, checkedAt = 50, activityStartedAt = 10, distanceMeters = 42_300.0)

    @Test
    fun elPongTraeElUltimoEstadoDeActividadYAvisaDelPingParaRefrescar() = runTest {
        val haciaTelefono = Loopback(listOf(telefono))
        val haciaReloj = Loopback(listOf(Peer("nodo-reloj", "Galaxy Watch Ultra", true)))
        val recibidas = mutableListOf<ActivityStatus>()
        var pingsRecibidos = 0
        val reloj = LinkSession(
            haciaTelefono, codec, deviceId = "dev-reloj", appVersion = "0.1.0",
            clock = { testScheduler.currentTime }, onActivity = { recibidas += it },
        )
        val phone = LinkSession(
            haciaReloj, codec, deviceId = "dev-tel", appVersion = "0.1.0",
            clock = { testScheduler.currentTime }, activityProvider = { actividad }, onPeerPing = { pingsRecibidos++ },
        )
        haciaTelefono.other = phone
        haciaReloj.other = reloj

        assertTrue(reloj.refresh() is LinkState.Connected)
        assertEquals(listOf(actividad), recibidas)
        assertEquals(1, pingsRecibidos)
    }

    @Test
    fun elRelojRecibeElInformeDeActividadEnviadoAparte() = runTest {
        val haciaReloj = Loopback(listOf(Peer("nodo-reloj", "Galaxy Watch Ultra", true)))
        val recibidas = mutableListOf<ActivityStatus>()
        val reloj = LinkSession(
            Loopback(emptyList()), codec, deviceId = "dev-reloj", appVersion = "0.1.0",
            clock = { testScheduler.currentTime }, onActivity = { recibidas += it },
        )
        haciaReloj.other = reloj
        assertTrue(session(haciaReloj, "0.1.0").sendToPeer(ActivityStatusReport(actividad)))
        assertEquals(listOf(actividad), recibidas)
    }

    private val finanzas = FinanceSnapshot(
        FinanceSnapshot.State.OK,
        checkedAt = 1_000,
        figures = FinanceFigures(1_000, "2026-11", "15230.50", "4800.00", "9120.75"),
    )

    @Test
    fun elPongTraeElResumenFinancieroYElInformeAparteTambienLlega() = runTest {
        val haciaTelefono = Loopback(listOf(telefono))
        val haciaReloj = Loopback(listOf(Peer("nodo-reloj", "Galaxy Watch Ultra", true)))
        val recibidos = mutableListOf<FinanceSnapshot>()
        val reloj = LinkSession(
            haciaTelefono, codec, deviceId = "dev-reloj", appVersion = "0.1.0",
            clock = { testScheduler.currentTime }, onFinance = { recibidos += it },
        )
        val phone = LinkSession(
            haciaReloj, codec, deviceId = "dev-tel", appVersion = "0.1.0",
            clock = { testScheduler.currentTime }, financeProvider = { finanzas },
        )
        haciaTelefono.other = phone
        haciaReloj.other = reloj

        assertTrue(reloj.refresh() is LinkState.Connected)
        val sinSesion = FinanceSnapshot(FinanceSnapshot.State.SIGNED_OUT, checkedAt = 2_000)
        assertTrue(phone.sendToPeer(FinanceSnapshotReport(sinSesion)))
        assertEquals(listOf(finanzas, sinSesion), recibidos)
    }

    @Test
    fun siElResumenFinancieroFallaElPongSaleSinEl() = runTest {
        val haciaTelefono = Loopback(listOf(telefono))
        val haciaReloj = Loopback(listOf(Peer("nodo-reloj", "Galaxy Watch Ultra", true)))
        val recibidos = mutableListOf<FinanceSnapshot>()
        val reloj = LinkSession(
            haciaTelefono, codec, deviceId = "dev-reloj", appVersion = "0.1.0",
            clock = { testScheduler.currentTime }, onFinance = { recibidos += it },
        )
        val phone = LinkSession(
            haciaReloj, codec, deviceId = "dev-tel", appVersion = "0.1.0",
            clock = { testScheduler.currentTime }, financeProvider = { error("x") },
        )
        haciaTelefono.other = phone
        haciaReloj.other = reloj
        assertTrue(reloj.refresh() is LinkState.Connected)
        assertTrue(recibidos.isEmpty())
    }

    @Test
    fun elBorradorDelRelojRecibeSuAcuseYElPongLlevaLosEstados() = runTest {
        val haciaTelefono = Loopback(listOf(telefono))
        val haciaReloj = Loopback(listOf(Peer("nodo-reloj", "Galaxy Watch Ultra", true)))
        val estadosEnReloj = mutableListOf<DraftStatus>()
        val recibidos = mutableListOf<QuickTransactionDraft>()
        val reloj = LinkSession(
            haciaTelefono, codec, deviceId = "dev-reloj", appVersion = "0.1.0",
            clock = { testScheduler.currentTime }, onDraftStatus = { estadosEnReloj += it },
        )
        val enTelefono = DraftStatus("3d6f0a7e-1b2c-4d5e-8f90-a1b2c3d4e5f6", DraftStatus.State.RECEIVED, updatedAt = 5)
        val phone = LinkSession(
            haciaReloj, codec, deviceId = "dev-tel", appVersion = "0.1.0",
            clock = { testScheduler.currentTime },
            onDraft = { recibidos += it; enTelefono },
            draftsProvider = { listOf(enTelefono) },
        )
        haciaTelefono.other = phone
        haciaReloj.other = reloj
        val borrador = QuickTransactionDraft(
            "3d6f0a7e-1b2c-4d5e-8f90-a1b2c3d4e5f6", QuickTransactionDraft.Kind.EXPENSE, "250.50",
            "5b1d6a3e-0c1f-4a2b-9d3e-7f8a9b0c1d2e", date = "2026-11-25", capturedAt = 0,
        )

        assertTrue(reloj.sendToPeer(borrador))
        assertEquals(listOf(borrador), recibidos)
        assertEquals(listOf(enTelefono), estadosEnReloj)

        assertTrue(reloj.refresh() is LinkState.Connected)
        assertEquals(listOf(enTelefono, enTelefono), estadosEnReloj)
    }

    @Test
    fun siRefrescarFallaElPingSigueRespondido() = runTest {
        val haciaTelefono = Loopback(listOf(telefono))
        val haciaReloj = Loopback(listOf(Peer("nodo-reloj", "Galaxy Watch Ultra", true)))
        val reloj = session(haciaTelefono, "0.1.0")
        val phone = LinkSession(
            haciaReloj, codec, deviceId = "dev-tel", appVersion = "0.1.0",
            clock = { testScheduler.currentTime }, activityProvider = { error("x") }, onPeerPing = { error("sin red") },
        )
        haciaTelefono.other = phone
        haciaReloj.other = reloj
        assertTrue(reloj.refresh() is LinkState.Connected)
    }

    @Test
    fun sinDispositivosQuedaSinPar() = runTest {
        assertEquals(LinkState.NoPeer, session(Loopback(emptyList()), "0.1.0").refresh())
    }

    @Test
    fun sinRespuestaQuedaInalcanzableTrasElTiempoLimite() = runTest {
        val reloj = session(Loopback(listOf(telefono)), "0.1.0") // nadie contesta
        val estado = reloj.refresh()
        assertEquals(LinkState.Unreachable("Galaxy S26 Ultra"), estado)
        assertEquals(LinkSession.PING_TIMEOUT_MS, testScheduler.currentTime)
    }

    @Test
    fun siElEnvioFallaQuedaInalcanzable() = runTest {
        val transporte = Loopback(listOf(telefono)).apply { failSend = true }
        assertEquals(LinkState.Unreachable("Galaxy S26 Ultra"), session(transporte, "0.1.0").refresh())
    }

    @Test
    fun unPongSinPingPendienteSeIgnora() = runTest {
        val reloj = session(Loopback(listOf(telefono)), "0.1.0")
        val pong = Envelope("m", ProtocolVersion.CURRENT, 0, "d", Pong("9.9.9"), correlationId = "desconocido")
        reloj.onMessage("nodo-telefono", codec.encode(pong))
        assertEquals(LinkState.Unknown, reloj.state.value)
    }

    @Test
    fun unaVersionIncompatibleSeAvisaUnaSolaVezPorMinuto() = runTest {
        val transporte = Loopback(listOf(telefono))
        val reloj = session(transporte, "0.1.0")
        val futuro = """{"messageId":"m","schemaVersion":99,"createdAt":0,"deviceId":"d","payload":{"type":"Ping"}}"""
            .encodeToByteArray()

        reloj.onMessage("nodo-telefono", futuro)
        reloj.onMessage("nodo-telefono", futuro)

        assertEquals(LinkState.Incompatible, reloj.state.value)
        assertEquals(1, transporte.sent.size)
        val respuesta = (codec.decode(transporte.sent.single(), 0) as DecodeResult.Accepted).envelope.payload
        assertEquals(
            IntegrationStatus(IntegrationStatus.Integration.PHONE_LINK, IntegrationStatus.State.INCOMPATIBLE),
            respuesta,
        )
    }

    @Test
    fun siElOtroLadoAvisaIncompatibleElPingTerminaIncompatible() = runTest {
        lateinit var reloj: LinkSession
        val responde = object : LinkTransport {
            override suspend fun reachablePeers() = listOf(telefono)
            override suspend fun send(peerId: String, bytes: ByteArray) {
                val aviso = IntegrationStatus(IntegrationStatus.Integration.PHONE_LINK, IntegrationStatus.State.INCOMPATIBLE)
                reloj.onMessage(peerId, codec.encode(Envelope("r", ProtocolVersion.CURRENT, 0, "d", aviso)))
            }
        }
        reloj = session(responde, "0.1.0")
        assertEquals(LinkState.Incompatible, reloj.refresh())
    }

    private class Recorded : DiagnosticsRecorder {
        val events = mutableListOf<Triple<String, Outcome, String>>()
        override suspend fun record(
            module: DiagnosticModule,
            operation: String,
            correlationId: String,
            outcome: Outcome,
            code: String,
            durationMs: Long,
        ) {
            assertEquals(DiagnosticModule.LINK, module)
            events += Triple(operation, outcome, code)
        }
    }

    @Test
    fun registraCadaComprobacionYLosMensajesDescartados() = runTest {
        val recorded = Recorded()
        val reloj = LinkSession(
            Loopback(emptyList()), codec, deviceId = "dev", appVersion = "0.1.0",
            clock = { testScheduler.currentTime }, diagnostics = recorded,
        )
        reloj.refresh()
        reloj.onMessage("nodo-telefono", "{basura".encodeToByteArray())

        assertEquals(
            listOf(
                Triple("link.ping", Outcome.ERROR, "NO_PEER"),
                Triple("link.receive", Outcome.ERROR, "MALFORMED"),
            ),
            recorded.events,
        )
    }
}
