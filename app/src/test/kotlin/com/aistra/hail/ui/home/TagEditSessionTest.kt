package com.aistra.hail.ui.home

import com.aistra.hail.app.TagAssignment
import com.aistra.hail.app.TagStateApp
import org.junit.Assert.*
import org.junit.Test

class TagEditSessionTest {
    private fun session() = TagEditSession(
        listOf("Default" to 0, "Games" to 11, "Work" to 22),
        listOf(
            TagAssignment("a.games", listOf(11)),
            TagAssignment("b.work", listOf(22)),
            TagAssignment("c.both", listOf(11,22))
        )
    )

    @Test fun cancelByDiscardLeavesTheOriginalInputUnchanged() {
        val tags = mutableListOf("Default" to 0, "Games" to 11)
        val apps = listOf(TagAssignment("pkg", listOf(11)))
        val edited = TagEditSession(tags, apps)
        edited.renameTag(11, "Leisure")
        edited.assign(11, 0, setOf("pkg"), true)
        assertEquals("Games", tags[1].first)
        assertEquals(listOf(11), apps[0].tagIds)
        assertTrue(edited.isDirty)
    }

    @Test fun moveAndRenamePreserveStableIds() {
        val s = session()
        assertTrue(s.move(2, 1))
        assertTrue(s.renameTag(22, "Office"))
        assertEquals(listOf(0, 22, 11), s.tags.map { it.second })
        assertEquals(setOf("b.work","c.both"), s.packagesIn(22))
        assertFalse(s.move(1,0))
    }

    @Test fun moveTransfersOnlySelectedAndKeepsUnrelatedTags() {
        val s = session()
        assertEquals(1, s.assign(11, 22, setOf("a.games"), true))
        assertEquals(listOf(22), s.tagsFor("a.games"))
        assertEquals(listOf(11,22), s.tagsFor("c.both"))
    }

    @Test fun addDoesNotRemoveSource() {
        val s = session()
        assertEquals(1, s.assign(11,22,setOf("a.games"),false))
        assertEquals(listOf(11,22),s.tagsFor("a.games"))
    }

    @Test fun deletionUpdatesAssignmentsButNotOriginal() {
        val s = session()
        assertTrue(s.deleteTag(11))
        assertEquals(emptyList<Int>(),s.tagsFor("a.games"))
        assertEquals(listOf(22),s.tagsFor("c.both"))
        assertFalse(s.deleteTag(0))
    }

    @Test fun onlyNewlyOrphanedAppsAreRemoved() {
        val s = session()
        assertTrue(s.newlyUnassignedPackages().isEmpty())
        assertTrue(s.deleteTag(11))
        assertEquals(setOf("a.games"), s.newlyUnassignedPackages())
        assertEquals(listOf(22), s.tagsFor("c.both"))
    }

    @Test fun preexistingUntaggedEntriesAreNotTreatedAsNewlyOrphaned() {
        val s = TagEditSession(
            listOf("Default" to 0, "Work" to 22),
            listOf(
                TagAssignment("legacy.untagged", emptyList()),
                TagAssignment("live.work", listOf(22))
            )
        )
        assertTrue(s.renameTag(22, "Office"))
        assertTrue(s.newlyUnassignedPackages().isEmpty())
        assertTrue(s.deleteTag(22))
        assertEquals(setOf("live.work"), s.newlyUnassignedPackages())
    }

    @Test fun duplicateAndBlankNamesAreRejected() {
        val s = session()
        assertNull(s.addTag(" "))
        assertNull(s.addTag("games"))
        assertFalse(s.renameTag(22,"games"))
        assertNull(s.addTag("DEFAULT"))
        assertNotNull(s.addTag("Personal"))
    }

    @Test fun createAndBulkAssignNewCategoryBeforeSave() {
        val s = session()
        val id=s.addTag("Favorites")!!
        assertEquals(1,s.assign(11,id,setOf("a.games"),true))
        assertEquals(listOf(id),s.tagsFor("a.games"))
        assertEquals(1,s.appCount(id))
    }

    @Test fun exportedDraftRestoresBaselineAndEveryStagedEdit() {
        val original = session()
        assertTrue(original.renameTag(11, "Leisure"))
        assertTrue(original.move(2, 1))
        assertEquals(1, original.assign(11, 22, setOf("a.games"), true))
        val restored = TagEditSession.importSnapshot(original.exportSnapshot())
        assertEquals(original.tags, restored.tags)
        assertEquals(original.allAssignments(), restored.allAssignments())
        assertTrue(restored.isDirty)
        assertTrue(restored.deleteTag(11))
        assertEquals("Leisure", original.tags.first { it.second == 11 }.first)
        val plan = restored.planCommit(
            listOf("Default" to 0, "Games" to 11, "Work" to 22),
            listOf(TagAssignment("a.games", listOf(11)), TagAssignment("b.work", listOf(22)),
                TagAssignment("c.both", listOf(11, 22)))
        )
        assertEquals(listOf(22), plan.assignments.first { it.packageName == "a.games" }.tagIds)
    }

    @Test fun staleTagOrderOrNameRejectsCommitWithoutMutatingInputs() {
        val edited = session()
        assertTrue(edited.renameTag(11, "Leisure"))
        val externalTags = listOf("Default" to 0, "Games" to 11, "Office" to 22)
        val externalApps = listOf(TagAssignment("a.games", listOf(11)), TagAssignment("b.work", listOf(22)),
            TagAssignment("c.both", listOf(11, 22)))
        assertThrows(TagEditConflictException::class.java) { edited.planCommit(externalTags, externalApps) }
        assertEquals("Office", externalTags.last().first)
        assertThrows(TagEditConflictException::class.java) {
            edited.planCommit(listOf("Default" to 0, "Work" to 22, "Games" to 11), externalApps)
        }
    }

    @Test fun changedOrRemovedKnownAssignmentsRejectsCommit() {
        val edited = session()
        assertTrue(edited.deleteTag(11))
        val tags = listOf("Default" to 0, "Games" to 11, "Work" to 22)
        assertThrows(TagEditConflictException::class.java) {
            edited.planCommit(tags, listOf(TagAssignment("a.games", listOf(0)), TagAssignment("b.work", listOf(22)),
                TagAssignment("c.both", listOf(11, 22))))
        }
        assertThrows(TagEditConflictException::class.java) {
            edited.planCommit(tags, listOf(TagAssignment("a.games", listOf(11)), TagAssignment("b.work", listOf(22))))
        }
    }

    @Test fun unrelatedAddedAppsArePreservedWhileOnlyNewlyOrphanedAppsAreRemoved() {
        val edited = session()
        assertTrue(edited.deleteTag(11))
        val plan = edited.planCommit(
            listOf("Default" to 0, "Games" to 11, "Work" to 22),
            listOf(TagAssignment("a.games", listOf(11)), TagAssignment("b.work", listOf(22)),
                TagAssignment("c.both", listOf(11, 22)), TagAssignment("new.work", listOf(22)))
        )
        assertEquals(setOf("b.work", "c.both", "new.work"), plan.assignments.map { it.packageName }.toSet())
        assertEquals(listOf(22), plan.assignments.first { it.packageName == "new.work" }.tagIds)
        assertEquals(listOf("Games"), plan.deletedTagNames)
    }

    @Test fun addedAppUsingDeletedTagRejectsCommitInsteadOfBreakingItsAssignment() {
        val edited = session()
        assertTrue(edited.deleteTag(11))
        assertThrows(TagEditConflictException::class.java) {
            edited.planCommit(listOf("Default" to 0, "Games" to 11, "Work" to 22),
                listOf(TagAssignment("a.games", listOf(11)), TagAssignment("b.work", listOf(22)),
                    TagAssignment("c.both", listOf(11, 22)), TagAssignment("new.games", listOf(11))))
        }
    }

    @Test fun defaultTagStaysLockedAfterRestoreAndRenamePlanUsesStableIdentity() {
        val edited = TagEditSession.importSnapshot(session().exportSnapshot())
        assertFalse(edited.renameTag(0, "Other"))
        assertFalse(edited.deleteTag(0))
        assertFalse(edited.move(0, 1))
        assertTrue(edited.renameTag(11, "Leisure"))
        val plan = edited.planCommit(listOf("Default" to 0, "Games" to 11, "Work" to 22),
            listOf(TagAssignment("a.games", listOf(11)), TagAssignment("b.work", listOf(22)),
                TagAssignment("c.both", listOf(11, 22))))
        assertEquals(listOf("Games" to "Leisure"), plan.renamedTags)
    }

    @Test fun oversizedOrInvalidDraftCannotBeRestored() {
        assertThrows(IllegalArgumentException::class.java) {
            TagEditSession.importSnapshot(" ".repeat(TagEditSession.MAX_SNAPSHOT_CHARS + 1))
        }
        assertThrows(IllegalArgumentException::class.java) { TagEditSession.importSnapshot("{}") }
    }


    @Test fun samePackageInAnotherUserKeepsItsOwnFlagsAssignmentsAndOrder() {
        val tags = listOf("Default" to 0, "Games" to 11, "Work" to 22)
        for (foreignIds in listOf(listOf(11), listOf(22))) {
            val foreign = TagStateApp("same.pkg", true, true, 12, foreignIds)
            val current = TagStateApp("same.pkg", false, false, 0, listOf(11))
            val edited = TagEditSession(tags, listOf(TagAssignment("same.pkg", listOf(11))), 0)
            assertEquals(1, edited.assign(11, 22, setOf("same.pkg"), true))
            val result = edited.planCommitForUser(tags, listOf(foreign, current), 0)
            assertSame(foreign, result.apps[0])
            assertEquals(foreign, result.apps[0])
            assertEquals(TagStateApp("same.pkg", false, false, 0, listOf(22)), result.apps[1])
        }
    }

    @Test fun deletingATagUsedByAnotherUserRejectsWithoutChangingAnyRecord() {
        val tags = listOf("Default" to 0, "Games" to 11)
        val current = TagStateApp("same.pkg", false, false, 0, listOf(11))
        val foreign = TagStateApp("same.pkg", true, true, 12, listOf(11))
        val edited = TagEditSession(tags, listOf(TagAssignment("same.pkg", listOf(11))), 0)
        assertTrue(edited.deleteTag(11))
        assertThrows(TagEditForeignProfileConflictException::class.java) {
            edited.planCommitForUser(tags, listOf(current, foreign), 0)
        }
        assertEquals(listOf(11), current.tagIds)
        assertEquals(listOf(11), foreign.tagIds)
        assertEquals("Games", tags[1].first)
    }

    @Test fun newlyOrphanedCurrentRecordIsRemovedWhileSamePackageForeignRecordSurvives() {
        val tags = listOf("Default" to 0, "Games" to 11, "Work" to 22)
        val foreign = TagStateApp("same.pkg", true, true, 12, listOf(22))
        val current = TagStateApp("same.pkg", false, false, 0, listOf(11))
        val edited = TagEditSession(tags, listOf(TagAssignment("same.pkg", listOf(11))), 0)
        assertTrue(edited.deleteTag(11))
        val plan = edited.planCommitForUser(tags, listOf(current, foreign), 0)
        assertEquals(listOf(foreign), plan.apps)
        assertSame(foreign, plan.apps.single())
    }

    @Test fun foreignChangesAndNewForeignRecordsDoNotCreateFalseBaselineConflicts() {
        val tags = listOf("Default" to 0, "Games" to 11, "Work" to 22)
        val edited = TagEditSession(tags, listOf(TagAssignment("same.pkg", listOf(11))), 0)
        assertTrue(edited.renameTag(11, "Leisure"))
        val foreign = TagStateApp("same.pkg", true, true, 12, listOf(22, 0))
        val added = TagStateApp("new.foreign", false, true, 12, listOf(11))
        val current = TagStateApp("same.pkg", true, false, 0, listOf(11))
        val plan = edited.planCommitForUser(tags, listOf(foreign, added, current), 0)
        assertEquals(listOf(foreign, added, current), plan.apps)
        assertEquals(listOf("Games" to "Leisure"), plan.renamedTags)
    }

    @Test fun restoredDraftRetainsUserAndCannotBeCommittedForADifferentUser() {
        val tags = listOf("Default" to 0, "Games" to 11)
        val original = TagEditSession(tags, listOf(TagAssignment("same.pkg", listOf(11))), 12)
        assertTrue(original.renameTag(11, "Leisure"))
        val restored = TagEditSession.importSnapshot(original.exportSnapshot())
        assertEquals(12, restored.userId)
        assertEquals(12, restored.copyForCommit().userId)
        assertThrows(TagEditConflictException::class.java) {
            restored.planCommitForUser(tags, listOf(TagStateApp("same.pkg", false, false, 0, listOf(11))), 0)
        }
    }

}
