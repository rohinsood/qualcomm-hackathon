package com.wayfinder.app.core.loop

import com.wayfinder.app.core.model.SteeringDecision
import java.util.concurrent.atomic.AtomicReference

/**
 * Atomic "latest decision" holder. The inference loop writes; the haptic loop,
 * speech gate, and UI each poll on their own cadences. Decouples producers from
 * consumers entirely.
 */
class DecisionStore {
    private val ref = AtomicReference<SteeringDecision?>(null)

    @Volatile
    var lastUpdateMs: Long = 0L
        private set

    fun set(decision: SteeringDecision) {
        lastUpdateMs = decision.timestampMs
        ref.set(decision)
    }

    fun latest(): SteeringDecision? = ref.get()

    fun clear() {
        ref.set(null)
    }
}
