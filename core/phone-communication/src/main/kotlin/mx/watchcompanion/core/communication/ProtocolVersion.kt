package mx.watchcompanion.core.communication

/**
 * Regla de compatibilidad entre versiones de reloj y teléfono (R-05): se acepta la versión actual y la anterior.
 *
 * Al subir [CURRENT], la versión anterior sigue aceptándose durante un release para que reloj y teléfono puedan
 * actualizarse en momentos distintos. Fuera de ese rango, la respuesta es [IntegrationStatus.State.INCOMPATIBLE].
 */
object ProtocolVersion {
    const val CURRENT: Int = 1
    const val MIN_SUPPORTED: Int = 1

    fun isSupported(version: Int): Boolean = version in MIN_SUPPORTED..CURRENT
}
