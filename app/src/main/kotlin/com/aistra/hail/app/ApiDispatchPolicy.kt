package com.aistra.hail.app

/** A restored request may already have changed the device before its process died. */
internal object ApiDispatchPolicy {
    enum class Decision { DISPATCH, RESUME_RETAINED, INTERRUPTED }

    fun requestIssued(savedRequestIssued: Boolean, manualOperationId: Long, profileOperationId: Long): Boolean =
        savedRequestIssued || manualOperationId != 0L || profileOperationId != 0L

    fun decide(savedRequestIssued: Boolean, manualOperationId: Long, profileOperationId: Long): Decision = when {
        manualOperationId != 0L || profileOperationId != 0L -> Decision.RESUME_RETAINED
        savedRequestIssued -> Decision.INTERRUPTED
        else -> Decision.DISPATCH
    }
}
