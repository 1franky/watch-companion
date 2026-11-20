package mx.watchcompanion.core.permissions

/** Módulos funcionales del companion (README de docs). Cada uno se activa por separado. */
enum class CompanionModule { VELORA, NEXORA, GUARDIAN, SMART_HOME }

enum class DeviceKind { WATCH, PHONE }

/**
 * Permisos peligrosos que necesita cada módulo, por dispositivo y versión de Android. Solo se listan los que ya usa
 * código existente; cada fase agrega los suyos (y su `<uses-permission>`) en la tarjeta que los necesita.
 */
object ModulePermissions {
    /** Frecuencia cardíaca en Android 16+ / Wear OS 6 (sustituye a BODY_SENSORS para apps con target 36+). */
    const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
    const val BODY_SENSORS = "android.permission.BODY_SENSORS"

    fun required(module: CompanionModule, device: DeviceKind, sdkInt: Int): List<String> = when (module to device) {
        // Fase 1: HR en vivo con ExerciseClient (WC-30). La ubicación llega con el modo B (WC-53).
        CompanionModule.VELORA to DeviceKind.WATCH -> listOf(if (sdkInt >= 36) READ_HEART_RATE else BODY_SENSORS)
        else -> emptyList()
    }
}
