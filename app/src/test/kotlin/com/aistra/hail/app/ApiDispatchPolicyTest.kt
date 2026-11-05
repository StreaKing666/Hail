package com.aistra.hail.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiDispatchPolicyTest {
    @Test fun freshRequestCanDispatch() {
        assertEquals(ApiDispatchPolicy.Decision.DISPATCH, ApiDispatchPolicy.decide(false, 0L, 0L))
    }

    @Test fun retainedManualOperationResumesWithoutAnotherDispatch() {
        assertEquals(ApiDispatchPolicy.Decision.RESUME_RETAINED, ApiDispatchPolicy.decide(true, 1L, 0L))
    }

    @Test fun retainedProfileOperationResumesWithoutAnotherDispatch() {
        assertEquals(ApiDispatchPolicy.Decision.RESUME_RETAINED, ApiDispatchPolicy.decide(true, 0L, 1L))
    }

    @Test fun processRecreationNeverReplaysAnIssuedRequestWithNewViewModels() {
        assertEquals(ApiDispatchPolicy.Decision.INTERRUPTED, ApiDispatchPolicy.decide(true, 0L, 0L))
        assertTrue(ApiDispatchPolicy.requestIssued(true, 0L, 0L))
    }

    @Test fun redirectSheetWithoutAClickCanBeRestoredAndDispatched() {
        assertFalse(ApiDispatchPolicy.requestIssued(false, 0L, 0L))
        assertEquals(ApiDispatchPolicy.Decision.DISPATCH, ApiDispatchPolicy.decide(false, 0L, 0L))
    }

    @Test fun saveRecordsOperationsIssuedAfterOpeningARedirectSheetOrProfile() {
        assertTrue(ApiDispatchPolicy.requestIssued(false, 1L, 0L))
        assertTrue(ApiDispatchPolicy.requestIssued(false, 0L, 1L))
        assertEquals(ApiDispatchPolicy.Decision.RESUME_RETAINED, ApiDispatchPolicy.decide(false, 1L, 0L))
        assertEquals(ApiDispatchPolicy.Decision.RESUME_RETAINED, ApiDispatchPolicy.decide(false, 0L, 1L))
    }
}
