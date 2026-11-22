package mx.watchcompanion.integration.velora

import java.io.File
import java.lang.reflect.Modifier
import mx.watchcompanion.integration.velora.api.apis.DefaultApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WC-34, capa de contrato: en el modo A el companion no crea ni sube actividades (01 §3.1, §7). El cliente generado
 * solo puede tener lecturas (`GET`) y las operaciones de sesión (rutas bajo `/auth/`). Agregar a `companionOperations` una
 * importación o una escritura en Strava hace fallar esta prueba.
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

    /** Operaciones que de verdad tiene el cliente generado (métodos públicos de `DefaultApi` que están en el contrato). */
    private val generated: Map<String, Operation> by lazy {
        DefaultApi::class.java.declaredMethods
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
    fun elClienteSoloTieneLecturasYSesion() {
        val writes = generated.filter { (_, op) -> op.method != "GET" && !op.path.startsWith("/auth/") }
        assertTrue("Operaciones de escritura en el cliente del companion: $writes", writes.isEmpty())
    }

    @Test
    fun ningunaOperacionDeImportacionNiDeEscrituraEnStrava() {
        val prohibited = contract.filter { (_, op) ->
            op.path.startsWith("/activities/imports") || op.path.endsWith("/reprocess") ||
                (op.path.startsWith("/integrations/strava") && op.method != "GET") || op.path.startsWith("/integrations/strava/webhook")
        }.keys
        assertTrue("El contrato debería tener operaciones prohibidas que vigilar", prohibited.size >= 5)
        val present = prohibited.filter { it in generated }
        assertTrue("El cliente del companion incluye importación o escritura en Strava: $present", present.isEmpty())
    }

    private companion object {
        val PATH = Regex("""^  (/\S*):\s*$""")
        val METHOD = Regex("""^    (get|post|put|patch|delete):\s*$""")
        val OPERATION = Regex("""^\s+operationId:\s*(\S+)\s*$""")
    }
}
