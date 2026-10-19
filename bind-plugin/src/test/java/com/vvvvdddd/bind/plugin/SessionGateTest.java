package com.vvvvdddd.bind.plugin;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class SessionGateTest {
    @Test
    void spendsBurstAndRefillsFractionalTokensOverTime() {
        SessionGate gate = new SessionGate(2, 0);
        assertTrue(gate.acquire(2, 1, 2, 0));
        gate.release();
        assertTrue(gate.acquire(2, 1, 2, 0));
        gate.release();
        assertFalse(gate.acquire(2, 1, 2, 500_000_000));
        assertTrue(gate.acquire(2, 1, 2, 1_000_000_000));
        gate.release();
        assertFalse(gate.acquire(2, 1, 2, 1_000_000_000));
    }

    @Test
    void inFlightRejectionDoesNotSpendAnAdditionalToken() {
        SessionGate gate = new SessionGate(2, 0);
        assertTrue(gate.acquire(2, 1, 1, 0));
        assertFalse(gate.acquire(2, 1, 1, 0));
        gate.release();
        assertTrue(gate.acquire(2, 1, 1, 0));
        gate.release();
        assertFalse(gate.acquire(2, 1, 1, 0));
    }

    @Test
    void refillIsCappedAtCurrentBurstAndReleaseCannotUnderflow() {
        SessionGate gate = new SessionGate(5, 0);
        gate.release();
        gate.release();
        assertTrue(gate.acquire(1, 20, 1, 10_000_000_000L));
        gate.release();
        assertFalse(gate.acquire(1, 20, 1, 10_000_000_000L));
    }

    @Test
    void simultaneousRequestsCannotExceedInFlightLimit() throws Exception {
        SessionGate gate = new SessionGate(100, 0);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(16)) {
            var tasks = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int index = 0; index < 16; index++) {
                tasks.add(executor.submit(() -> {
                    start.await();
                    return gate.acquire(100, 1, 2, 0);
                }));
            }
            start.countDown();
            int accepted = 0;
            for (var task : tasks) if (task.get()) accepted++;
            assertEquals(2, accepted);
            gate.release();
            gate.release();
            assertTrue(gate.acquire(100, 1, 2, 0));
        }
    }
}
