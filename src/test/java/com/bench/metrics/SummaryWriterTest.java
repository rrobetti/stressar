package com.bench.metrics;

import com.bench.workloads.WorkloadClass;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Tests for {@link SummaryWriter} output format and backward compatibility.
 */
public class SummaryWriterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static MetricsSnapshot buildSnapshot(long completed, long errors) {
        MetricsSnapshot s = new MetricsSnapshot();
        s.setStartTimeMs(0L);
        s.setTimestampMs(10_000L); // 10 seconds elapsed
        s.setAttemptedRequests(completed + errors);
        s.setCompletedRequests(completed);
        s.setErrors(errors);
        return s;
    }

    private static SummaryWriter.BenchmarkRunInfo buildRunInfo(String workload) {
        SummaryWriter.BenchmarkRunInfo info = new SummaryWriter.BenchmarkRunInfo();
        info.workload = workload;
        info.sut = "hikari";
        info.loadMode = "closed-loop";
        info.durationSeconds = 10;
        info.timestamp = "2026-01-01T00:00:00Z";
        return info;
    }

    @Test
    public void testSummaryContainsWorkloadClassMetrics() throws Exception {
        MetricsSnapshot snapshot = buildSnapshot(100, 5);

        // Set class snapshots
        MetricsSnapshot oltpSnap = buildSnapshot(80, 3);
        MetricsSnapshot olapSnap = buildSnapshot(20, 2);
        snapshot.setClassSnapshot(WorkloadClass.OLTP, oltpSnap);
        snapshot.setClassSnapshot(WorkloadClass.OLAP, olapSnap);

        SummaryWriter.SummaryData summary = SummaryWriter.createSummary(snapshot, buildRunInfo("W5_HTAP"));

        assertNotNull("workloadClassMetrics must not be null", summary.workloadClassMetrics);
        assertTrue("must contain TOTAL", summary.workloadClassMetrics.containsKey("TOTAL"));
        assertTrue("must contain OLTP",  summary.workloadClassMetrics.containsKey("OLTP"));
        assertTrue("must contain OLAP",  summary.workloadClassMetrics.containsKey("OLAP"));
    }

    @Test
    public void testTopLevelFieldsMatchWorkloadClassMetricsTotal() throws Exception {
        MetricsSnapshot snapshot = buildSnapshot(200, 10);
        SummaryWriter.SummaryData summary = SummaryWriter.createSummary(snapshot, buildRunInfo("W5_HTAP"));

        SummaryWriter.WorkloadClassData total = summary.workloadClassMetrics.get("TOTAL");
        assertNotNull(total);

        assertEquals(summary.successfulRequests, total.successfulRequests);
        assertEquals(summary.failedRequests,     total.failedRequests);
        assertEquals(summary.totalRequests,      total.completedRequests);
        assertEquals(summary.errorRate,          total.errorRate, 0.0001);
    }

    @Test
    public void testOltpAndOlapSectionsContainCounts() throws Exception {
        MetricsSnapshot snapshot = buildSnapshot(100, 5);

        MetricsSnapshot oltpSnap = buildSnapshot(80, 3);
        MetricsSnapshot olapSnap = buildSnapshot(20, 2);
        snapshot.setClassSnapshot(WorkloadClass.OLTP, oltpSnap);
        snapshot.setClassSnapshot(WorkloadClass.OLAP, olapSnap);

        SummaryWriter.SummaryData summary = SummaryWriter.createSummary(snapshot, buildRunInfo("W5_HTAP"));

        assertEquals(80L, summary.workloadClassMetrics.get("OLTP").successfulRequests);
        assertEquals(3L,  summary.workloadClassMetrics.get("OLTP").failedRequests);
        assertEquals(20L, summary.workloadClassMetrics.get("OLAP").successfulRequests);
        assertEquals(2L,  summary.workloadClassMetrics.get("OLAP").failedRequests);
    }

    @Test
    public void testTopLevelFieldsStillPresentInJsonOutput() throws Exception {
        MetricsSnapshot snapshot = buildSnapshot(50, 2);
        SummaryWriter.SummaryData summary = SummaryWriter.createSummary(snapshot, buildRunInfo("W1_READ_ONLY"));

        File outFile = tmp.newFile("summary.json");
        new SummaryWriter().writeSummary(outFile.getAbsolutePath(), summary);

        // Parse the JSON with Gson and verify top-level fields survive serialisation
        Gson gson = new GsonBuilder().create();
        Type mapType = new TypeToken<Map<String, Object>>(){}.getType();
        try (FileReader reader = new FileReader(outFile)) {
            Map<String, Object> parsed = gson.fromJson(reader, mapType);
            assertTrue("successfulRequests must be present", parsed.containsKey("successfulRequests"));
            assertTrue("failedRequests must be present",     parsed.containsKey("failedRequests"));
            assertTrue("totalRequests must be present",      parsed.containsKey("totalRequests"));
            assertTrue("errorRate must be present",          parsed.containsKey("errorRate"));
            assertTrue("latencyMs must be present",          parsed.containsKey("latencyMs"));
            assertTrue("workloadClassMetrics must be present", parsed.containsKey("workloadClassMetrics"));
        }
    }

    @Test
    public void testOltpAndOlapThroughputAndErrorRatePresent() throws Exception {
        MetricsSnapshot snapshot = buildSnapshot(100, 10);

        MetricsSnapshot oltpSnap = buildSnapshot(70, 5);
        MetricsSnapshot olapSnap = buildSnapshot(30, 5);
        snapshot.setClassSnapshot(WorkloadClass.OLTP, oltpSnap);
        snapshot.setClassSnapshot(WorkloadClass.OLAP, olapSnap);

        SummaryWriter.SummaryData summary = SummaryWriter.createSummary(snapshot, buildRunInfo("W5_HTAP"));

        SummaryWriter.WorkloadClassData oltp = summary.workloadClassMetrics.get("OLTP");
        SummaryWriter.WorkloadClassData olap = summary.workloadClassMetrics.get("OLAP");

        assertTrue("OLTP successfulThroughput should be > 0", oltp.successfulThroughput > 0);
        assertTrue("OLAP successfulThroughput should be > 0", olap.successfulThroughput > 0);
        assertTrue("OLTP errorRate should be > 0", oltp.errorRate > 0);
        assertTrue("OLAP errorRate should be > 0", olap.errorRate > 0);
        assertNotNull("OLTP latency must not be null", oltp.latency);
        assertNotNull("OLAP latency must not be null", olap.latency);
    }

    @Test
    public void testNonHtapWorkloadStillProducesWorkloadClassMetrics() throws Exception {
        // Non-HTAP snapshots have no class sub-snapshots; TOTAL must still be present and match
        MetricsSnapshot snapshot = buildSnapshot(100, 0);
        SummaryWriter.SummaryData summary = SummaryWriter.createSummary(snapshot, buildRunInfo("W1_READ_ONLY"));

        assertNotNull(summary.workloadClassMetrics);
        assertNotNull(summary.workloadClassMetrics.get("TOTAL"));
        assertNotNull(summary.workloadClassMetrics.get("OLTP")); // empty but present
        assertNotNull(summary.workloadClassMetrics.get("OLAP")); // empty but present

        // TOTAL should match top-level
        assertEquals(summary.successfulRequests, summary.workloadClassMetrics.get("TOTAL").successfulRequests);
    }
}
