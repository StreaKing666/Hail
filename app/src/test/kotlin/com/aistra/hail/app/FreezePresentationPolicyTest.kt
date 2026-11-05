package com.aistra.hail.app

import org.junit.Assert.*
import org.junit.Test

class FreezePresentationPolicyTest {
    @Test fun incompleteObservationIsNotAnUnfrozenApp() {
        assertEquals(FreezePresentationPolicy.State.UNKNOWN,
            FreezePresentationPolicy.classify(currentUser = true, present = true, observed = null))
        assertFalse(FreezePresentationPolicy.includes(null, showFrozen = false, showUnfrozen = true))
        assertFalse(FreezePresentationPolicy.includes(null, showFrozen = true, showUnfrozen = false))
    }

    @Test fun missingCurrentUserAppDiffersFromAnUnobservableOtherUser() {
        assertEquals(FreezePresentationPolicy.State.NOT_FOUND,
            FreezePresentationPolicy.classify(currentUser = true, present = false, observed = null))
        assertEquals(FreezePresentationPolicy.State.UNKNOWN,
            FreezePresentationPolicy.classify(currentUser = false, present = false, observed = null))
        assertEquals(FreezePresentationPolicy.State.UNKNOWN,
            FreezePresentationPolicy.classify(currentUser = false, present = true, observed = true))
    }

    @Test fun verifiedStatesRetainTheirClassification() {
        assertEquals(FreezePresentationPolicy.State.FROZEN,
            FreezePresentationPolicy.classify(currentUser = true, present = true, observed = true))
        assertEquals(FreezePresentationPolicy.State.UNFROZEN,
            FreezePresentationPolicy.classify(currentUser = true, present = true, observed = false))
    }

    @Test fun unknownStateAndSelectionFlagsNeverCollide() {
        val flags = buildList {
            for (state in FreezePresentationPolicy.State.entries) {
                for (selected in listOf(false, true)) for (whitelisted in listOf(false, true)) {
                    add(FreezePresentationPolicy.flags(state, selected, whitelisted))
                }
            }
        }
        assertEquals(16, flags.toSet().size)
    }

    @Test fun everyFilterCombinationPreservesUnknownAsASeparateState() {
        for (showFrozen in listOf(false, true)) for (showUnfrozen in listOf(false, true)) {
            assertEquals(showFrozen, FreezePresentationPolicy.includes(true, showFrozen, showUnfrozen))
            assertEquals(showUnfrozen, FreezePresentationPolicy.includes(false, showFrozen, showUnfrozen))
            assertEquals(showFrozen && showUnfrozen,
                FreezePresentationPolicy.includes(null, showFrozen, showUnfrozen))
        }
    }
}
