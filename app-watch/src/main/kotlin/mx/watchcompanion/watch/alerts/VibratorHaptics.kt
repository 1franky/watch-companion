package mx.watchcompanion.watch.alerts

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager

/**
 * Vibraciones de las alertas de zona (WC-31), distintas para que se reconozcan sin mirar el reloj:
 * - **Sube:** tres pulsos cortos.
 * - **Baja:** un pulso largo.
 *
 * Con uso de alarma: el usuario inició la sesión y espera los avisos aunque tenga el modo No molestar.
 */
class VibratorHaptics(context: Context) : Haptics {
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val attributes = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)

    override fun play(alert: ZoneAlert) {
        if (!vibrator.hasVibrator()) return
        val effect = when (alert.direction) {
            ZoneAlert.Direction.UP -> VibrationEffect.createWaveform(UP_TIMINGS, UP_AMPLITUDES, -1)
            ZoneAlert.Direction.DOWN -> VibrationEffect.createOneShot(DOWN_MS, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        vibrator.vibrate(effect, attributes)
    }

    private companion object {
        val UP_TIMINGS = longArrayOf(0, 90, 90, 90, 90, 90)
        val UP_AMPLITUDES = intArrayOf(0, 255, 0, 255, 0, 255)
        const val DOWN_MS = 450L
    }
}
