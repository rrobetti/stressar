package com.bench.metrics;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Writer for timeseries metrics to CSV file.
 * <p>
 * Existing columns are preserved unchanged. Per-class columns are appended with
 * {@code total_}, {@code oltp_}, and {@code olap_} prefixes so that existing
 * consumers of the original columns continue to work without modification.
 */
public class TimeseriesWriter implements AutoCloseable {
    private final PrintWriter writer;
    private final DateTimeFormatter formatter = DateTimeFormatter.ISO_INSTANT;
    private final List<TimeseriesRow> rows = new ArrayList<>();
    
    public TimeseriesWriter(String filename) throws IOException {
        this.writer = new PrintWriter(new FileWriter(filename));
        writeHeader();
    }
    
    private void writeHeader() {
        writer.println("timestamp_iso,attempted_rps,achieved_rps,errors,p50_ms,p95_ms,p99_ms,p999_ms,max_ms,"
            + "total_attempted,total_successful,total_failed,total_p95_ms,total_p99_ms,"
            + "oltp_attempted,oltp_successful,oltp_failed,oltp_p95_ms,oltp_p99_ms,"
            + "olap_attempted,olap_successful,olap_failed,olap_p95_ms,olap_p99_ms");
    }
    
    /**
     * Write a metrics row for a time interval (legacy columns only; per-class values default to zero).
     */
    public void writeRow(long timestampMs, double attemptedRps, double achievedRps, 
                         long errors, double p50, double p95, double p99, double p999, double max) {
        writeRow(timestampMs, attemptedRps, achievedRps, errors, p50, p95, p99, p999, max,
                 new ClassIntervalMetrics(), new ClassIntervalMetrics(), new ClassIntervalMetrics());
    }

    /**
     * Write a metrics row including per-class interval metrics.
     */
    public void writeRow(long timestampMs, double attemptedRps, double achievedRps,
                         long errors, double p50, double p95, double p99, double p999, double max,
                         ClassIntervalMetrics total, ClassIntervalMetrics oltp, ClassIntervalMetrics olap) {
        String timestamp = Instant.ofEpochMilli(timestampMs)
            .atOffset(ZoneOffset.UTC)
            .format(formatter);
        
        TimeseriesRow row = new TimeseriesRow();
        row.timestamp = timestamp;
        row.attemptedRps = attemptedRps;
        row.achievedRps = achievedRps;
        row.errors = errors;
        row.p50 = p50;
        row.p95 = p95;
        row.p99 = p99;
        row.p999 = p999;
        row.max = max;
        row.total = total;
        row.oltp = oltp;
        row.olap = olap;
        
        rows.add(row);
        
        writer.printf("%s,%.2f,%.2f,%d,%.2f,%.2f,%.2f,%.2f,%.2f,"
            + "%d,%d,%d,%.2f,%.2f,"
            + "%d,%d,%d,%.2f,%.2f,"
            + "%d,%d,%d,%.2f,%.2f%n",
            timestamp, attemptedRps, achievedRps, errors, p50, p95, p99, p999, max,
            total.attempted, total.successful, total.failed, total.p95, total.p99,
            oltp.attempted, oltp.successful, oltp.failed, oltp.p95, oltp.p99,
            olap.attempted, olap.successful, olap.failed, olap.p95, olap.p99);
        writer.flush();
    }
    
    public List<TimeseriesRow> getRows() {
        return rows;
    }
    
    @Override
    public void close() {
        if (writer != null) {
            writer.close();
        }
    }
    
    public static class TimeseriesRow {
        public String timestamp;
        public double attemptedRps;
        public double achievedRps;
        public long errors;
        public double p50;
        public double p95;
        public double p99;
        public double p999;
        public double max;
        public ClassIntervalMetrics total = new ClassIntervalMetrics();
        public ClassIntervalMetrics oltp = new ClassIntervalMetrics();
        public ClassIntervalMetrics olap = new ClassIntervalMetrics();
    }

    /** Per-class interval counters and latency percentiles for one timeseries row. */
    public static class ClassIntervalMetrics {
        public long attempted;
        public long successful;
        public long failed;
        public double p95;
        public double p99;
    }
}
