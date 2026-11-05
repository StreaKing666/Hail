package com.aistra.hail.utils

import org.junit.Assert.*
import org.junit.Test

class UserScopedCommandsTest {
    @Test fun reinstallAndUninstallAlwaysTargetExplicitProfileEvenForOrdinaryApps() {
        assertEquals("pm uninstall --user 10 org.example.app", UserScopedCommands.uninstall("org.example.app", 10))
        assertEquals("pm install-existing --user 12 org.example.app", UserScopedCommands.reinstall("org.example.app", 12))
        assertEquals("pm uninstall --user 0 org.example.app", UserScopedCommands.uninstall("org.example.app", 0))
    }
    @Test fun rejectsAmbiguousUserAndShellInjection() {
        assertThrows(IllegalArgumentException::class.java) { UserScopedCommands.uninstall("org.example.app; id", 0) }
        assertThrows(IllegalArgumentException::class.java) { UserScopedCommands.reinstall("org.example.app", -1) }
    }
}
