package mx.watchcompanion.core.communication

import kotlinx.serialization.Serializable

/**
 * Zonas de frecuencia cardíaca que usa el reloj para mostrar la zona y para las alertas (WC-31, WC-32).
 *
 * Parten de la FC máxima de Velora con su misma definición (`hr-zones/1`, ADR-020 de Velora: Z1 50–60 %, Z2 60–70 %,
 * Z3 70–80 %, Z4 80–90 %, Z5 90 % o más), así que coinciden con las analíticas de Velora mientras no se ajusten a mano.
 *
 * @property maxHeartRateBpm FC máxima en lpm, en el rango que acepta Velora (100–230).
 * @property lowerBoundsBpm límite inferior de Z1…Z5, estrictamente creciente. Por debajo de Z1 no hay zona.
 * @property source de dónde salieron los límites.
 * @property updatedAt instante (ms epoch UTC) de la última modificación; gana la versión más reciente.
 */
@Serializable
data class HeartRateZones(
    val maxHeartRateBpm: Int,
    val lowerBoundsBpm: List<Int>,
    val source: Source,
    val updatedAt: Long,
) {
    init {
        require(maxHeartRateBpm in MAX_HR_RANGE) { "FC máxima fuera de rango" }
        require(lowerBoundsBpm.size == ZONES) { "se esperan $ZONES límites" }
        require(lowerBoundsBpm.first() >= MIN_BOUND_BPM) { "límite inferior demasiado bajo" }
        require(lowerBoundsBpm.zipWithNext().all { (a, b) -> a < b }) { "los límites deben ser crecientes" }
        require(lowerBoundsBpm.last() <= maxHeartRateBpm) { "Z5 empieza por encima de la FC máxima" }
    }

    @Serializable
    enum class Source {
        /** Calculadas de la FC máxima de Velora con `hr-zones/1`. */
        VELORA,

        /** Ajustadas a mano en el teléfono. */
        MANUAL,
    }

    /** Zona 1–5 de [bpm], o `null` si está por debajo de Z1 o no hay lectura. */
    fun zoneOf(bpm: Int?): Int? {
        if (bpm == null) return null
        val index = lowerBoundsBpm.indexOfLast { bpm >= it }
        return if (index < 0) null else index + 1
    }

    companion object {
        const val ZONES = 5
        val MAX_HR_RANGE = 100..230
        const val MIN_BOUND_BPM = 40

        /** Porcentajes de `hr-zones/1` (ADR-020 de Velora) para el límite inferior de Z1…Z5. */
        private val PERCENTS = listOf(50, 60, 70, 80, 90)

        /** Zonas de Velora para [maxHeartRateBpm], redondeando al lpm más cercano. */
        fun fromMax(maxHeartRateBpm: Int, source: Source, updatedAt: Long) = HeartRateZones(
            maxHeartRateBpm = maxHeartRateBpm,
            lowerBoundsBpm = PERCENTS.map { (maxHeartRateBpm * it + 50) / 100 },
            source = source,
            updatedAt = updatedAt,
        )
    }
}
