package com.diplay.ts7

import org.junit.Assert.*
import org.junit.Test

class ConnectionPolicyTest {
    @Test fun waitsForLanWithoutConsumingAnAttempt() {
        val policy = ConnectionPolicy().apply { request() }
        assertFalse(policy.canStart(false, 0))
        assertEquals(0, policy.attempts)
        assertTrue(policy.canStart(true, 0))
    }

    @Test fun duplicateRequestCannotCreateTwoLiveAttempts() {
        val policy = ConnectionPolicy().apply { request(); started(); request() }
        assertEquals(1, policy.attempts)
        assertFalse(policy.canStart(true, Long.MAX_VALUE))
    }

    @Test fun retriesAreDelayedAndStopAtFourAttempts() {
        val policy = ConnectionPolicy().apply { autoConnect = true; request() }
        var now = 0L
        val delays = listOf(2_000L, 5_000L, 15_000L)
        repeat(4) { index ->
            assertTrue(policy.canStart(true, now))
            policy.started()
            policy.failed(true, now)
            if (index < 3) {
                assertEquals(now + delays[index], policy.retryAt)
                assertFalse(policy.canStart(true, policy.retryAt - 1))
                now = policy.retryAt
            }
        }
        assertFalse(policy.desired)
        assertFalse(policy.canStart(true, Long.MAX_VALUE))
    }

    @Test fun manualDisconnectCancelsPendingRetryAndNetworkRecovery() {
        val policy = ConnectionPolicy().apply { autoConnect = true; request(); started(); failed(true, 0); disconnect() }
        assertFalse(policy.canStart(false, Long.MAX_VALUE))
        assertFalse(policy.canStart(true, Long.MAX_VALUE))
        policy.request()
        assertTrue(policy.canStart(true, Long.MAX_VALUE))
    }

    @Test fun configurationFailureWaitsForExplicitUserRetry() {
        val policy = ConnectionPolicy().apply { autoConnect = true; request(); started(); failed(false, 0) }
        assertTrue(policy.blocked)
        assertFalse(policy.canStart(true, Long.MAX_VALUE))
        policy.request()
        assertTrue(policy.canStart(true, 0))
    }

    @Test fun editingPreventsPendingAutoRetry() {
        val policy = ConnectionPolicy().apply { autoConnect = true; request(); started(); failed(true, 0); editing = true }
        assertFalse(policy.canStart(true, Long.MAX_VALUE))
        policy.editing = false
        assertTrue(policy.canStart(true, Long.MAX_VALUE))
    }

    @Test fun disabledAutoConnectDoesNotRetryManualAttempt() {
        val policy = ConnectionPolicy().apply { request(); started(); failed(true, 0) }
        assertFalse(policy.desired)
    }

    @Test fun onlyAStableSessionRenewsTheBudget() {
        val policy = ConnectionPolicy().apply { autoConnect = true; request(); started(); failed(true, 0); started() }
        assertEquals(2, policy.attempts)
        policy.stable()
        assertEquals(0, policy.attempts)
        policy.failed(true, 0)
        assertTrue(policy.canStart(true, 2_000))
    }

    @Test fun processRecreationPreservesRetryDelayAndRemainingBudget() {
        val policy = ConnectionPolicy().apply { autoConnect = true; restorePending(3, 15_000) }
        assertFalse(policy.canStart(true, 14_999))
        assertTrue(policy.canStart(true, 15_000))
        policy.started()
        policy.failed(true, 15_000)
        assertFalse(policy.desired)
        assertFalse(ConnectionPolicy().apply { restorePending(4, 0) }.canStart(true, Long.MAX_VALUE))
    }
}
