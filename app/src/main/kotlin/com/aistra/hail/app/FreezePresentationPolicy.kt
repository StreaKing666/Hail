package com.aistra.hail.app

/** UI classification preserves incomplete observations instead of assuming a thawed app. */
internal object FreezePresentationPolicy {
    enum class State { NOT_FOUND, UNFROZEN, FROZEN, UNKNOWN }

    fun classify(currentUser: Boolean, present: Boolean, observed: Boolean?): State = when {
        !currentUser -> State.UNKNOWN
        !present -> State.NOT_FOUND
        observed == true -> State.FROZEN
        observed == false -> State.UNFROZEN
        else -> State.UNKNOWN
    }

    fun flags(state: State, selected: Boolean, whitelisted: Boolean): Int =
        (1 shl state.ordinal) or
            (if (selected) 1 shl State.entries.size else 0) or
            (if (whitelisted) 1 shl (State.entries.size + 1) else 0)

    fun includes(observed: Boolean?, showFrozen: Boolean, showUnfrozen: Boolean): Boolean = when (observed) {
        true -> showFrozen
        false -> showUnfrozen
        null -> showFrozen && showUnfrozen
    }
}
