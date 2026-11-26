package mx.watchcompanion.integration.nexora

import java.io.File
import java.lang.reflect.Modifier
import mx.watchcompanion.integration.nexora.api.apis.NexoraApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WC-36: el cliente de Nexora solo tiene lo que el companion usa. Una escritura financiera (un movimiento, un pago, una
 * transferencia) solo puede entrar con la tarjeta que la justifica y su confirmación en el teléfono (03 §2, WC-40): esta
 * prueba falla si aparece una escritura que no está en [ALLOWED_WRITES].
 */
class NexoraScopeTest {
    private data class Operation(val method: String, val path: String)

    /** `operationId` → método y ruta, leídos del contrato fijado. El YAML de springdoc ordena las claves. */
    private val contract: Map<String, Operation> by lazy {
        val file = File("../../contract/nexora/openapi-v1.yaml").takeIf { it.exists() }
            ?: error("No encuentro el contrato fijado desde ${File(".").absolutePath}")
        val operations = mutableMapOf<String, Operation>()
        var path = ""
        var method = ""
        for (line in file.readLines()) {
            PATH.matchEntire(line)?.let { path = it.groupValues[1] }
            METHOD.matchEntire(line)?.let { method = it.groupValues[1].uppercase() }
            OPERATION.matchEntire(line)?.let { operations[it.groupValues[1]] = Operation(method, path) }
        }
        operations
    }

    private val generated: Map<String, Operation> by lazy {
        // Las generadas y las escritas a mano (NexoraCompanionApi), que también llevan su operationId como nombre.
        (NexoraApi::class.java.declaredMethods + NexoraCompanionApi::class.java.declaredMethods)
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .mapNotNull { m -> contract[m.name]?.let { m.name to it } }
            .toMap()
    }

    @Test
    fun elContratoSeLeyoCompleto() {
        assertTrue("Se leyeron ${contract.size} operaciones", contract.size > 50)
        assertEquals(Operation("POST", "/api/v1/transactions"), contract["transactionCreate"])
        assertEquals(Operation("GET", "/api/v1/dashboard"), contract["dashboardGet"])
        assertTrue("El cliente generado debe tener operaciones", generated.isNotEmpty())
    }

    @Test
    fun soloLecturasSesionYEscriturasJustificadas() {
        val writes = generated.filter { (_, op) -> op.method != "GET" && !op.path.startsWith("/api/v1/auth/") }.keys
        assertEquals("Escrituras en el cliente de Nexora del companion", ALLOWED_WRITES, writes)
    }

    private companion object {
        /**
         * Solo el ingreso o gasto que el usuario confirma en el teléfono (WC-40, 03 §2), siempre con `Idempotency-Key`.
         * Transferencias, pagos y compras de tarjeta no entran.
         */
        val ALLOWED_WRITES = setOf("transactionCreate")

        val PATH = Regex("""^  (/\S*):\s*$""")
        val METHOD = Regex("""^    (get|post|put|patch|delete):\s*$""")
        val OPERATION = Regex("""^\s+operationId:\s*(\S+)\s*$""")
    }
}
