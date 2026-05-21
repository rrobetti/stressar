package com.bench.load;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link TrueOpenLoopLoadGenerator#autoSizeWorkers(int, int, int)}.
 *
 * <p>The worker pool size is derived from Little's Law over the
 * connection-acquisition timeout (the per-request latency ceiling), so that
 * the open-loop dispatcher can absorb the worst latency the SUT is permitted
 * to produce without silently queuing operations behind a too-small worker
 * pool.
 */
public class TrueOpenLoopLoadGeneratorTest {

    @Test
    public void autoSizeAppliesLittlesLawWithHeadroom() {
        // 100 rps × 30 s timeout = 3000 in-flight floor; +10% headroom -> 3300,
        // then clamped at AUTO_SIZE_CAP = 2000.
        int sized = TrueOpenLoopLoadGenerator.autoSizeWorkers(100, 30_000, 0);
        assertEquals("clamped at AUTO_SIZE_CAP", TrueOpenLoopLoadGenerator.AUTO_SIZE_CAP, sized);
    }

    @Test
    public void autoSizeForLowRpsBelowCap() {
        // 10 rps × 30 s = 300; +10% headroom -> 330; below cap, above min.
        int sized = TrueOpenLoopLoadGenerator.autoSizeWorkers(10, 30_000, 0);
        assertEquals(330, sized);
    }

    @Test
    public void autoSizeForShortTimeoutBelowCap() {
        // 200 rps × 5 s = 1000; +10% -> 1100.
        int sized = TrueOpenLoopLoadGenerator.autoSizeWorkers(200, 5_000, 0);
        assertEquals(1100, sized);
    }

    @Test
    public void autoSizeFloorsAtMinimum() {
        // 1 rps × 1 s = 1; +10% -> ceil(1.1) = 2; floored at AUTO_SIZE_MIN.
        int sized = TrueOpenLoopLoadGenerator.autoSizeWorkers(1, 1_000, 0);
        assertEquals(TrueOpenLoopLoadGenerator.AUTO_SIZE_MIN, sized);
    }

    @Test
    public void autoSizeReturnsMinForNonPositiveInputs() {
        assertEquals(TrueOpenLoopLoadGenerator.AUTO_SIZE_MIN,
                TrueOpenLoopLoadGenerator.autoSizeWorkers(0, 30_000, 0));
        assertEquals(TrueOpenLoopLoadGenerator.AUTO_SIZE_MIN,
                TrueOpenLoopLoadGenerator.autoSizeWorkers(100, 0, 0));
        assertEquals(TrueOpenLoopLoadGenerator.AUTO_SIZE_MIN,
                TrueOpenLoopLoadGenerator.autoSizeWorkers(-5, 30_000, 0));
    }

    @Test
    public void explicitOverrideBypassesAutoCap() {
        // An explicit operator override can exceed AUTO_SIZE_CAP (e.g. for stress
        // probes deliberately allowing very long tails); auto-sizing's cap is a
        // safety net, not a hard limit on what the user can request.
        int sized = TrueOpenLoopLoadGenerator.autoSizeWorkers(100, 30_000, 5000);
        assertEquals(5000, sized);
    }

    @Test
    public void explicitOverrideStillFlooredAtMinimum() {
        // Override below AUTO_SIZE_MIN is bumped up, to avoid degenerate single-
        // thread pools that defeat the dispatcher.
        int sized = TrueOpenLoopLoadGenerator.autoSizeWorkers(100, 30_000, 1);
        assertEquals(TrueOpenLoopLoadGenerator.AUTO_SIZE_MIN, sized);
    }

    @Test
    public void autoSizeIsMonotonicInRps() {
        int low = TrueOpenLoopLoadGenerator.autoSizeWorkers(10, 30_000, 0);
        int mid = TrueOpenLoopLoadGenerator.autoSizeWorkers(50, 30_000, 0);
        int high = TrueOpenLoopLoadGenerator.autoSizeWorkers(100, 30_000, 0);
        assertTrue("workers should not decrease with higher RPS", low <= mid);
        assertTrue("workers should not decrease with higher RPS", mid <= high);
    }
}
