package mx.watchcompanion.phone.guardian

import kotlinx.coroutines.test.runTest
import mx.watchcompanion.core.communication.GuardianCommand
import mx.watchcompanion.core.communication.GuardianCommandResult
import mx.watchcompanion.core.communication.GuardianCommandResult.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** WC-44: el teléfono solo responde LOCKED si comprobó que quedó bloqueado. */
class PhoneGuardianTest {
    private val id = "0b9f2c4e-5d6a-4f1b-8c7d-9e0f1a2b3c4d"
    private val lock = GuardianCommand(id, GuardianCommand.Action.LOCK)

    private class FakeLocker(
        var admin: Boolean = true,
        var secure: Boolean = true,
        var locksAfterCalls: Int = 1,
        var throwOnLock: Boolean = false,
    ) : DeviceLocker {
        var lockCalls = 0
        private var polls = 0
        override fun isAdminActive() = admin
        override fun isDeviceSecure() = secure
        override fun lockNow() {
            if (throwOnLock) throw SecurityException("admin quitado")
            lockCalls++
        }
        override fun isLocked(): Boolean = lockCalls > 0 && ++polls >= locksAfterCalls
    }

    @Test
    fun bloqueaYLoComprueba() = runTest {
        val locker = FakeLocker(locksAfterCalls = 3)
        val guardian = PhoneGuardian(locker, clock = { testScheduler.currentTime })

        assertEquals(GuardianCommandResult(id, Outcome.LOCKED), guardian.execute(lock, expiresAt = 15_000))
        assertEquals(1, locker.lockCalls)
    }

    @Test
    fun siElBloqueoNoSeCompruebaNoDiceBloqueado() = runTest {
        val locker = FakeLocker(locksAfterCalls = Int.MAX_VALUE)
        val guardian = PhoneGuardian(locker, clock = { testScheduler.currentTime })

        assertEquals(GuardianCommandResult(id, Outcome.UNAVAILABLE, "NOT_CONFIRMED"), guardian.execute(lock, expiresAt = 15_000))
    }

    @Test
    fun sinAdministradorNoIntenta() = runTest {
        val locker = FakeLocker(admin = false)

        assertEquals(GuardianCommandResult(id, Outcome.UNAVAILABLE, "ADMIN_INACTIVE"), PhoneGuardian(locker).execute(lock, Long.MAX_VALUE))
        assertEquals(0, locker.lockCalls)
    }

    @Test
    fun sinPinNoPrometeUnBloqueoQueNoProtege() = runTest {
        val locker = FakeLocker(secure = false)

        assertEquals(GuardianCommandResult(id, Outcome.UNAVAILABLE, "NO_SECURE_LOCK"), PhoneGuardian(locker).execute(lock, Long.MAX_VALUE))
        assertEquals(0, locker.lockCalls)
    }

    @Test
    fun siElSistemaLoNiegaEsRechazado() = runTest {
        val locker = FakeLocker(throwOnLock = true)

        assertEquals(GuardianCommandResult(id, Outcome.REJECTED, "SECURITY_EXCEPTION"), PhoneGuardian(locker).execute(lock, Long.MAX_VALUE))
    }

    @Test
    fun caducadaNoSeEjecuta() = runTest {
        val locker = FakeLocker()

        assertEquals(GuardianCommandResult(id, Outcome.EXPIRED), PhoneGuardian(locker, clock = { 20_000 }).execute(lock, expiresAt = 15_000))
        assertEquals(0, locker.lockCalls)
    }

    @Test
    fun unaOrdenConIdInvalidoSeRechaza() = runTest {
        val locker = FakeLocker()

        val result = PhoneGuardian(locker).execute(GuardianCommand("no-es-uuid", GuardianCommand.Action.LOCK), Long.MAX_VALUE)

        assertEquals(Outcome.REJECTED, result.outcome)
        assertFalse(locker.lockCalls > 0)
    }
}
