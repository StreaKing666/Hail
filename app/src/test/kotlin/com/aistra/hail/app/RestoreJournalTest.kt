package com.aistra.hail.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RestoreJournalTest {
    private fun withJournal(test: (File) -> Unit) {
        val dir = Files.createTempDirectory("hail-journal").toFile()
        try { test(dir) } finally { dir.deleteRecursively() }
    }
    @Test fun successfulRestoreCommitsAndCleansJournal() = withJournal { dir ->
        var value = "old"
        RestoreJournal(dir).execute("old".toByteArray(), { value = "new" }, { value = String(it) })
        assertEquals("new", value); assertFalse(dir.exists())
    }
    @Test fun failedApplyRollsBackAndKeepsOriginalError() = withJournal { dir ->
        var value = "old"
        assertThrows(IllegalArgumentException::class.java) {
            RestoreJournal(dir).execute("old".toByteArray(), { value = "partial"; throw IllegalArgumentException("write failed") }, { value = String(it) })
        }
        assertEquals("old", value); assertFalse(dir.exists())
    }
    @Test fun failedRollbackRetainsSnapshotAndRecoversOnNextInstance() = withJournal { dir ->
        assertThrows(RecoveryRequiredException::class.java) {
            RestoreJournal(dir).execute("old".toByteArray(), { error("apply") }, { error("rollback") })
        }
        assertTrue(File(dir, "journal.json").isFile)
        var restored = ""
        RestoreJournal(dir).recover { restored = String(it) }
        assertEquals("old", restored); assertFalse(dir.exists())
    }
    @Test fun interruptedProcessAndCorruptSnapshotNeverApplyUncheckedBytes() = withJournal { dir ->
        try { RestoreJournal(dir).execute("old".toByteArray(), { throw AssertionError("process death") }, {}) }
        catch (_: AssertionError) { }
        File(dir, "pre-restore.zip").writeText("tampered")
        var applied = false
        assertThrows(IllegalArgumentException::class.java) { RestoreJournal(dir).recover { applied = true } }
        assertFalse(applied); assertTrue(File(dir, "journal.json").exists())
    }
    @Test fun invalidJournalIsRetainedAndPendingJournalCannotBeOverwritten() = withJournal { dir ->
        File(dir, "journal.json").writeText("invalid")
        assertThrows(Exception::class.java) { RestoreJournal(dir).recover {} }
        assertThrows(IllegalStateException::class.java) { RestoreJournal(dir).execute(byteArrayOf(1), {}, {}) }
        assertEquals("invalid", File(dir, "journal.json").readText())
    }
}
