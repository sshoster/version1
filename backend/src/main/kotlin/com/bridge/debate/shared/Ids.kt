package com.bridge.debate.shared

import com.github.f4b6a3.uuid.UuidCreator

/** Time-ordered UUIDv7 identifiers, stored as strings (see docs/architecture.md §2). */
object Ids {
    fun newId(): String = UuidCreator.getTimeOrderedEpoch().toString()
}
