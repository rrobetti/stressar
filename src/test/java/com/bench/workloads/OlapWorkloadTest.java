package com.bench.workloads;

import org.junit.Test;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link OlapWorkload}.
 */
public class OlapWorkloadTest {

    private static final long SEED       = 42L;
    private static final long ACCOUNTS   = 1000L;
    private static final long ITEMS      = 500L;

    @Test
    public void testGetName() {
        TestConnectionProvider provider = new TestConnectionProvider();
        OlapWorkload workload = new OlapWorkload(provider, SEED, ACCOUNTS, ITEMS, false, 1.1);
        assertEquals("W4_OLAP", workload.getName());
    }

    @Test
    public void testAllFiveQueriesAreReachedAfterFiveCalls() throws SQLException {
        TestConnectionProvider provider = new TestConnectionProvider();
        OlapWorkload workload = new OlapWorkload(provider, SEED, ACCOUNTS, ITEMS, false, 1.1);

        for (int i = 0; i < OlapWorkload.QUERIES.length; i++) {
            workload.execute();
        }

        Set<String> expected = new HashSet<>(Arrays.asList(OlapWorkload.QUERIES));
        Set<String> actual   = new HashSet<>(provider.capturedSql);
        assertEquals("All 5 OLAP queries must be exercised in one cycle", expected, actual);
    }

    @Test
    public void testRoundRobinCyclesCorrectly() throws SQLException {
        TestConnectionProvider provider = new TestConnectionProvider();
        OlapWorkload workload = new OlapWorkload(provider, SEED, ACCOUNTS, ITEMS, false, 1.1);

        int n = OlapWorkload.QUERIES.length;
        int rounds = 3;

        for (int i = 0; i < n * rounds; i++) {
            workload.execute();
        }

        // Each query should appear exactly 'rounds' times
        for (String query : OlapWorkload.QUERIES) {
            long count = provider.capturedSql.stream().filter(sql -> sql.equals(query)).count();
            assertEquals("Each OLAP query should appear exactly " + rounds + " times in " + rounds + " full rounds",
                (long) rounds, count);
        }
    }

    @Test
    public void testRoundRobinOrderMatchesQueryArray() throws SQLException {
        TestConnectionProvider provider = new TestConnectionProvider();
        OlapWorkload workload = new OlapWorkload(provider, SEED, ACCOUNTS, ITEMS, false, 1.1);

        int n = OlapWorkload.QUERIES.length;
        for (int i = 0; i < n; i++) {
            workload.execute();
        }

        for (int i = 0; i < n; i++) {
            assertEquals("Query at position " + i + " must match QUERIES[" + i + "]",
                OlapWorkload.QUERIES[i], provider.capturedSql.get(i));
        }
    }

    @Test
    public void testQueriesConstantsAreNotEmpty() {
        for (int i = 0; i < OlapWorkload.QUERIES.length; i++) {
            assertNotNull("QUERIES[" + i + "] must not be null", OlapWorkload.QUERIES[i]);
            assertFalse("QUERIES[" + i + "] must not be blank", OlapWorkload.QUERIES[i].trim().isEmpty());
        }
        assertEquals("There must be exactly 5 OLAP queries", 5, OlapWorkload.QUERIES.length);
    }
}
