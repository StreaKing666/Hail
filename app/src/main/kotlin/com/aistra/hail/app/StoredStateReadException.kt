package com.aistra.hail.app

/** Only a fixed internal filename and exception type may be exposed, never JSON contents. */
internal class StoredStateReadException(val stateFile: String, cause: Exception) :
    IllegalStateException("Could not read $stateFile", cause)
