package mx.watchcompanion.core.communication

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WC-42 y 07-security-and-privacy.md §4: ningún código de las apps escribe en Logcat ni en la salida estándar. Lo único
 * que se registra son los `DiagnosticEvent`, cuyos campos no admiten texto libre: un importe no cabe en ninguno. Así
 * ninguna cifra de Nexora ni de un borrador puede acabar en un log.
 */
class NoLogsTest {
    private val prohibidos = listOf(
        Regex("""\bandroid\.util\.Log\b"""),
        Regex("""\bLog\.[vdiwe]\("""),
        Regex("""\bLog\.wtf\("""),
        Regex("""\bTimber\b"""),
        Regex("""\bprintln\("""),
        Regex("""\bprint\("""),
        Regex("""\bprintStackTrace\("""),
        Regex("""\bSystem\.(out|err)\b"""),
    )

    @Test
    fun ningunModuloEscribeLogs() {
        val raiz = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val fuentes = raiz.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "kt" && it.invariantSeparatorsPath.contains("/src/main/") }
            .toList()
        assertTrue("El recorrido debe llegar a las dos apps", fuentes.any { "app-phone" in it.path } && fuentes.any { "app-watch" in it.path })
        val hallazgos = fuentes.flatMap { archivo ->
            archivo.readLines().mapIndexedNotNull { i, linea ->
                if (prohibidos.any { it.containsMatchIn(linea) }) "${archivo.relativeTo(raiz)}:${i + 1}: ${linea.trim()}" else null
            }
        }
        assertTrue("Escrituras a log en el código:\n" + hallazgos.joinToString("\n"), hallazgos.isEmpty())
    }
}
