package com.diplay.ts7

/** Main-thread policy. Activity visibility is deliberately not a connection event. */
internal class ConnectionPolicy {
    var autoConnect = false
    var desired = false
        private set
    var active = false
        private set
    var editing = false
    var attempts = 0
        private set
    var retryAt = 0L
        private set
    var blocked = false
        private set

    fun request() {
        if (active) return
        desired = true
        attempts = 0
        retryAt = 0
        blocked = false
    }

    fun disconnect() {
        desired = false
        active = false
        retryAt = 0
    }

    fun restorePending(attempts: Int, retryAt: Long) {
        require(attempts in 0..MAX_ATTEMPTS)
        this.attempts = attempts
        this.retryAt = retryAt
        desired = attempts < MAX_ATTEMPTS
    }

    fun canStart(networkReady: Boolean, now: Long): Boolean =
        desired && !active && !editing && !blocked && networkReady &&
            attempts < MAX_ATTEMPTS && now >= retryAt

    fun started() {
        check(!active && attempts < MAX_ATTEMPTS)
        active = true
        attempts++
    }

    fun failed(retryable: Boolean, now: Long) {
        active = false
        if (!retryable) blocked = true
        desired = desired && autoConnect && retryable && attempts < MAX_ATTEMPTS
        retryAt = now + RETRY_DELAYS[(attempts - 1).coerceIn(0, RETRY_DELAYS.lastIndex)]
    }

    fun stable() {
        if (active) attempts = 0
    }

    companion object {
        const val MAX_ATTEMPTS = 4
        private val RETRY_DELAYS = longArrayOf(2_000, 5_000, 15_000)
    }
}
