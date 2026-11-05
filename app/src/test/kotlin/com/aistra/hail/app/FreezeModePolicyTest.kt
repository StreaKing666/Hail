package com.aistra.hail.app

import org.junit.Assert.*
import org.junit.Test

class FreezeModePolicyTest {
    private val modes = listOf("su_disable", "su_hide", "su_suspend", "owner_hide", "owner_suspend", "privapp_disable")


    @Test fun missingObservationDoesNotClaimAnAppHasThawed() {
        assertNull(FreezeModePolicy.isFrozen("su_disable", false, null, false, false))
        assertNull(FreezeModePolicy.isFrozen("su_stop", false, false, false, null))
        assertEquals(false, FreezeModePolicy.isFrozen("su_disable", false, false, false, null))
    }

    @Test fun knownFrozenLayerIsPreservedWhenAnotherLayerCannotBeObserved() {
        assertEquals(true, FreezeModePolicy.isFrozen("su_disable", true, null, false, false))
        assertEquals(listOf("su_disable", "su_suspend"),
            FreezeModePolicy.thawModes("su_disable", true, null, true, modes))
    }

    @Test fun reversibleStateRemainsVisibleAfterChangingModes() {
        assertEquals(true, FreezeModePolicy.isFrozen("su_disable", false, true, false, false))
        assertEquals(true, FreezeModePolicy.isFrozen("owner_suspend", true, false, false, false))
        assertEquals(false, FreezeModePolicy.isFrozen("su_disable", false, false, false, true))
        assertEquals(true, FreezeModePolicy.isFrozen("su_stop", false, false, false, true))
    }

    @Test fun thawPlansAllObservedLayersUsingOnlySelectedAuthority() {
        assertEquals(listOf("su_disable", "su_hide", "su_suspend"),
            FreezeModePolicy.thawModes("su_disable", true, true, true, modes))
    }

    @Test fun unsupportedLayerDoesNotPretendToThawOrSwitchPrivileges() {
        assertThrows(IllegalStateException::class.java) {
            FreezeModePolicy.thawModes("privapp_disable", false, true, false, modes)
        }
    }
}
