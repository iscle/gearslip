package app.seb3thehacker.gearslip.systemhook

import org.junit.Assert.*
import org.junit.Test

class ReflectionBoundaryTest {
    @Test
    fun missingOemMethodIsAnOrdinaryRecoverableFailure() {
        val missing = NoSuchMethodError("OEM API")
        val failure = assertThrows(IllegalStateException::class.java) {
            HookTools.reflect<Unit> { throw missing }
        }
        assertSame(missing, failure.cause)
    }

    @Test
    fun virtualMachineFailureIsNotSilentlySwallowed() {
        val fatal = OutOfMemoryError("simulated")
        val failure = assertThrows(OutOfMemoryError::class.java) {
            HookTools.reflect<Unit> { throw fatal }
        }
        assertSame(fatal, failure)
    }
}
