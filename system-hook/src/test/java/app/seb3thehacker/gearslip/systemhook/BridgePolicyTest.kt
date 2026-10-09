package app.seb3thehacker.gearslip.systemhook

import org.junit.Assert.*
import org.junit.Test

class BridgePolicyTest {
    @Test
    fun arbitraryBinderInterfacesAndTransactionsAreRejected() {
        for (descriptor in listOf(
            "android.app.IActivityManager",
            "android.os.IServiceManager",
            ""
        )) {
            for (code in listOf(1, 2, 11, 16_777_215)) assertFalse(
                BridgePolicy.allowedTransaction(
                    descriptor,
                    code
                )
            )
        }
        assertFalse(BridgePolicy.allowedTransaction(BridgePolicy.CAR_DESCRIPTOR, 1))
        assertFalse(BridgePolicy.allowedTransaction(BridgePolicy.CAR_DESCRIPTOR, 13))
        assertFalse(BridgePolicy.allowedTransaction(BridgePolicy.BROWSER_DESCRIPTOR, 8))
        assertFalse(BridgePolicy.allowedTransaction(BridgePolicy.MESSENGER_DESCRIPTOR, 2))
    }

    @Test
    fun knownProtocolRangesAreAccepted() {
        for (code in 2..12) assertTrue(
            BridgePolicy.allowedTransaction(
                BridgePolicy.CAR_DESCRIPTOR,
                code
            )
        )
        for (code in 1..7) assertTrue(
            BridgePolicy.allowedTransaction(
                BridgePolicy.BROWSER_DESCRIPTOR,
                code
            )
        )
        assertTrue(BridgePolicy.allowedTransaction(BridgePolicy.MESSENGER_DESCRIPTOR, 1))
        assertTrue(BridgePolicy.allowedTransaction(BridgePolicy.CAR_DESCRIPTOR, 16_777_215))
    }

    @Test
    fun userBoundariesAreNotAppIdComparisons() {
        assertTrue(BridgePolicy.sameUser(10123, 10456))
        assertFalse(BridgePolicy.sameUser(10123, 1010123))
        assertTrue(BridgePolicy.sameUser(1010123, 1010456))
    }

    @Test
    fun deferredSettingsCannotEscalateToAnotherUserOrPrivilegedFlags() {
        assertTrue(BridgePolicy.canDeferComponentSetting(0, 2, 1))
        assertTrue(BridgePolicy.canDeferComponentSetting(0, 1, 0))
        assertFalse(BridgePolicy.canDeferComponentSetting(10, 2, 1))
        assertFalse(BridgePolicy.canDeferComponentSetting(-1, 2, 1))
        assertFalse(BridgePolicy.canDeferComponentSetting(0, 3, 1))
        assertFalse(BridgePolicy.canDeferComponentSetting(0, 2, 2))
        assertFalse(BridgePolicy.canDeferComponentSetting(0, 2, -1))
    }
}
