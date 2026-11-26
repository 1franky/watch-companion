package mx.watchcompanion.watch.finance

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Si el usuario pidió ocultar las cifras de Nexora en el reloj (WC-42). Es una preferencia, no un dato financiero, así
 * que vive en `SharedPreferences` sin cifrar; se recuerda entre aperturas hasta que el usuario vuelva a mostrarlas.
 */
class FinancePrivacy(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _hidden = MutableStateFlow(prefs.getBoolean(KEY_HIDDEN, false))
    val hidden: StateFlow<Boolean> = _hidden.asStateFlow()

    fun setHidden(hidden: Boolean) {
        prefs.edit { putBoolean(KEY_HIDDEN, hidden) }
        _hidden.value = hidden
    }

    private companion object {
        const val PREFS = "privacidad"
        const val KEY_HIDDEN = "ocultar_finanzas"
    }
}
