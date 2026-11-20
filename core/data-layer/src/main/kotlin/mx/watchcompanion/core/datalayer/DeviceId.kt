package mx.watchcompanion.core.datalayer

import android.content.Context
import java.util.UUID

/**
 * Identificador seudónimo de esta instalación para el campo `deviceId` del protocolo. Es aleatorio: no deriva del
 * número de serie, del IMEI ni de la cuenta. Se regenera si se borran los datos de la app.
 */
object DeviceId {
    private const val PREFS = "wc_link"
    private const val KEY = "device_id"

    fun get(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, id).apply()
        return id
    }
}
