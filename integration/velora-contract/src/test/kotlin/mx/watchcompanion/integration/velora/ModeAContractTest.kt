package mx.watchcompanion.integration.velora

import java.io.File
import java.lang.reflect.Modifier
import mx.watchcompanion.integration.velora.api.apis.DefaultApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WC-34, capa de contrato: el companion no crea ni sube actividades (01 §3.1, §7). El cliente solo puede tener lecturas
 * (`GET`), las operaciones de sesión (rutas bajo `/auth/`) y la única escritura del modo B: subir la grabación del reloj
 * como **fuente secundaria** (WC-54, ADR-032 de Velora), que por contrato nunca crea una actividad. Agregar una
 * importación, la recuperación como actividad o una escritura en Strava hace fallar esta prueba.
 */
class ModeAContractTest {
    private data class Operation(val method: String, val path: String)

    /** `operationId` → método y ruta, leídos del contrato fijado (`contract/velora/openapi-v1.yaml`). */
    private val contract: Map<String, Operation> by lazy {
        val file = File("../../contract/velora/openapi-v1.yaml").takeIf { it.exists() }
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

    /**
     * Operaciones que de verdad tiene el cliente: métodos públicos de `DefaultApi` (generado) y de [VeloraClient] (las
     * escritas a mano) que están en el contrato.
     */
    private val generated: Map<String, Operation> by lazy {
        (DefaultApi::class.java.declaredMethods + VeloraClient::class.java.declaredMethods).asList()
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .mapNotNull { m -> contract[m.name]?.let { m.name to it } }
            .toMap()
    }

    @Test
    fun elContratoSeLeyoCompleto() {
        // Si el formato del YAML cambia y el lector deja de encontrar operaciones, esta prueba no debe pasar en falso.
        assertTrue("Se leyeron ${contract.size} operaciones", contract.size > 40)
        assertEquals(Operation("POST", "/activities/imports"), contract["importActivity"])
        assertEquals(Operation("GET", "/integrations/strava"), contract["getStravaStatus"])
        assertTrue("El cliente generado debe tener operaciones", generated.isNotEmpty())
    }

    @Test
    fun elClienteSoloTieneLecturasSesionYLaSubidaSecundaria() {
        val writes = generated.filter { (_, op) -> op.method != "GET" && !op.path.startsWith("/auth/") }
        assertEquals("Escrituras en el cliente del companion", MODE_B_WRITES, writes.keys)
        assertEquals(Operation("POST", "/secondary-recordings"), generated["uploadSecondaryRecording"])
    }

    @Test
    fun ningunaOperacionDeImportacionNiDeEscrituraEnStrava() {
        val prohibited = contract.filter { (_, op) ->
            op.path.startsWith("/activities/imports") || op.path.endsWith("/reprocess") || op.path.endsWith("/promote") ||
                (op.path.startsWith("/integrations/strava") && op.method != "GET") || op.path.startsWith("/integrations/strava/webhook")
        }.keys
        assertTrue("El contrato debería tener operaciones prohibidas que vigilar", prohibited.size >= 6)
        assertTrue("La recuperación como actividad crea una actividad", "promoteSecondaryRecording" in prohibited)
        val present = prohibited.filter { it in generated }
        assertTrue("El cliente del companion incluye importación o escritura en Strava: $present", present.isEmpty())
    }

    private companion object {
        /** La única escritura permitida: la grabación del reloj como fuente secundaria (modo B). */
        val MODE_B_WRITES = setOf("uploadSecondaryRecording")

        val PATH = Regex("""^  (/\S*):\s*$""")
        val METHOD = Regex("""^    (get|post|put|patch|delete):\s*$""")
        val OPERATION = Regex("""^\s+operationId:\s*(\S+)\s*$""")
    }
}
