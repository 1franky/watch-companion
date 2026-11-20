package mx.watchcompanion.integration.velora

import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule

/**
 * Serializadores de los tipos que los modelos generados marcan como `@Contextual` y el generador no incluye:
 * `format: uuid`, `date` y `date-time` del contrato, como texto ISO (igual que velora-android).
 */
internal object ContractSerializers {
    private class AsText<T : Any>(name: String, private val parse: (String) -> T) : KSerializer<T> {
        override val descriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)
        override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(value.toString())
        override fun deserialize(decoder: Decoder): T = parse(decoder.decodeString())
    }

    val module = SerializersModule {
        contextual(UUID::class, AsText("mx.watchcompanion.velora.UUID", UUID::fromString))
        contextual(LocalDate::class, AsText("mx.watchcompanion.velora.LocalDate", LocalDate::parse))
        contextual(OffsetDateTime::class, AsText("mx.watchcompanion.velora.OffsetDateTime", OffsetDateTime::parse))
    }
}
