package com.bench.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Collects in-process system metrics from the bench JVM during a run:
 * <ul>
 *   <li>CPU load of this JVM process (via {@code com.sun.management.OperatingSystemMXBean})</li>
 *   <li>Total GC pause time (via {@code GarbageCollectorMXBean})</li>
 * </ul>
 *
 * <p>CPU samples are taken every {@value #SAMPLE_INTERVAL_SECONDS} seconds.
 * The baseline GC time is captured at construction; {@link #getGcPauseMsTotal()}
 * returns the delta accrued since then.
 *
 * <p>Close this collector to stop background sampling.
 */
public class SystemMetricsCollector implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SystemMetricsCollector.class);
    private static final int SAMPLE_INTERVAL_SECONDS = 5;

    private final ScheduledExecutorService scheduler;
    private final List<Double> cpuSamples = Collections.synchronizedList(new ArrayList<>());
    private final long gcPauseBaselineMs;

    public SystemMetricsCollector() {
        this.gcPauseBaselineMs = sumGcTimeMs();

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sys-metrics-collector");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::sampleCpu,
                SAMPLE_INTERVAL_SECONDS, SAMPLE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    // ── CPU sampling ─────────────────────────────────────────────────────────

    private void sampleCpu() {
        try {
            java.lang.management.OperatingSystemMXBean osBean =
                    ManagementFactory.getOperatingSystemMXBean();

            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                double load = ((com.sun.management.OperatingSystemMXBean) osBean)
                        .getProcessCpuLoad();
                if (load >= 0.0) {
                    cpuSamples.add(load * 100.0); // convert 0–1 fraction to percentage
                }
            }
        } catch (Exception e) {
            logger.debug("CPU sampling failed: {}", e.getMessage());
        }
    }

    /**
     * Median process CPU load (%) collected during the measurement window,
     * or {@code null} if no samples were recorded.
     */
    public Double getAppCpuMedian() {
        if (cpuSamples.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<>(cpuSamples);
        Collections.sort(sorted);
        int mid = sorted.size() / 2;
        return (sorted.size() % 2 == 0)
                ? (sorted.get(mid - 1) + sorted.get(mid)) / 2.0
                : sorted.get(mid);
    }

    // ── GC pause time ─────────────────────────────────────────────────────────

    private static long sumGcTimeMs() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionTime)
                .filter(t -> t >= 0)
                .sum();
    }

    /**
     * Total GC pause time (ms) accrued since this collector was constructed.
     */
    public Long getGcPauseMsTotal() {
        return sumGcTimeMs() - gcPauseBaselineMs;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void close() {
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
