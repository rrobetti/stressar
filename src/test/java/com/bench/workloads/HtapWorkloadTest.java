package com.bench.workloads;

import org.junit.Test;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link HtapWorkload}.
 */
public class HtapWorkloadTest {

    private static final long   SEED      = 42L;
    private static final long   ACCOUNTS  = 1000L;
    private static final long   ITEMS     = 500L;

    @Test
    public void testGetName() {
        TestConnectionProvider provider = new TestConnectionProvider();
        HtapWorkload workload = new HtapWorkload(
            provider, SEED, ACCOUNTS, ITEMS, false, 1.1,
            0.30, 0.0, 0.10);
        assertEquals("W5_HTAP", workload.getName());
    }

    /**
     * With olapPercent=1.0 every call must be routed to the OLAP workload.
     * The captured SQL strings must all be from {@link OlapWorkload#QUERIES}.
     */
    @Test
    public void testAlwaysOlapWhenOlapPercentIsOne() throws SQLException {
        TestConnectionProvider provider = new TestConnectionProvider();
        HtapWorkload workload = new HtapWorkload(
            provider, SEED, ACCOUNTS, ITEMS, false, 1.1,
            0.30, 0.0, 1.0);

        int calls = OlapWorkload.QUERIES.length * 2;
        for (int i = 0; i < calls; i++) {
            workload.execute();
        }

        Set<String> olapQueries = new HashSet<>(Arrays.asList(OlapWorkload.QUERIES));
        for (String sql : provider.capturedSql) {
            assertTrue("SQL should be an OLAP query when olapPercent=1.0: " + sql,
                olapQueries.contains(sql));
        }
    }

    /**
     * With olapPercent=0.0 every call must be routed to the OLTP (Mixed) workload.
     * None of the captured SQL strings should be from {@link OlapWorkload#QUERIES}.
     * writePercent=0.0 ensures only read-path SQL is generated (safe with the stub).
     */
    @Test
    public void testAlwaysOltpWhenOlapPercentIsZero() throws SQLException {
        TestConnectionProvider provider = new TestConnectionProvider();
        HtapWorkload workload = new HtapWorkload(
            provider, SEED, ACCOUNTS, ITEMS, false, 1.1,
            0.30, 0.0, 0.0);

        int calls = 20;
        for (int i = 0; i < calls; i++) {
            workload.execute();
        }

        assertFalse("Some SQL must have been captured", provider.capturedSql.isEmpty());
        Set<String> olapQueries = new HashSet<>(Arrays.asList(OlapWorkload.QUERIES));
        for (String sql : provider.capturedSql) {
            assertFalse("No OLAP SQL should appear when olapPercent=0.0: " + sql,
                olapQueries.contains(sql));
        }
    }

    /**
     * With a mixed olapPercent (0.50) both OLAP and OLTP SQL should appear after
     * enough calls.  This test uses a generous call count so the random split
     * is unlikely to produce an all-one-type result.
     */
    @Test
    public void testMixedSplitProducesBothWorkloadTypes() throws SQLException {
        TestConnectionProvider provider = new TestConnectionProvider();
        // seed=0 gives a well-spread sequence; writePercent=0 keeps the stub safe
        HtapWorkload workload = new HtapWorkload(
            provider, 0L, ACCOUNTS, ITEMS, false, 1.1,
            0.30, 0.0, 0.50);

        int calls = 100;
        for (int i = 0; i < calls; i++) {
            workload.execute();
        }

        Set<String> olapQueries = new HashSet<>(Arrays.asList(OlapWorkload.QUERIES));
        boolean sawOlap  = provider.capturedSql.stream().anyMatch(olapQueries::contains);
        boolean sawOltp  = provider.capturedSql.stream().anyMatch(sql -> !olapQueries.contains(sql));

        assertTrue("Expected OLAP SQL in mixed-split run", sawOlap);
        assertTrue("Expected OLTP SQL in mixed-split run", sawOltp);
    }
}
