package mx.watchcompanion.watch.drafts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmountEntryTest {
    private fun teclea(teclas: String) = teclas.fold("") { actual, tecla -> AmountEntry.press(actual, tecla) }

    @Test
    fun armaElImporteSinCerosALaIzquierdaYConDosDecimales() {
        assertEquals("250.5", teclea("0250.5"))
        assertEquals("0.75", teclea(".75"))
        assertEquals("12.34", teclea("12.345"))
        assertEquals("1.2", teclea("1..2"))
        assertEquals("12", teclea("123${AmountEntry.DELETE}"))
        assertEquals("123456789", teclea("1234567890"))
    }

    @Test
    fun soloEsValidoMayorQueCeroYSinPuntoColgando() {
        assertFalse(AmountEntry.isValid(""))
        assertFalse(AmountEntry.isValid("0"))
        assertFalse(AmountEntry.isValid("0.00"))
        assertFalse(AmountEntry.isValid("12."))
        assertTrue(AmountEntry.isValid("0.5"))
        assertTrue(AmountEntry.isValid("250"))
    }
}
