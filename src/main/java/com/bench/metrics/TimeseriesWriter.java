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
        writer.println("timestamp_iso,attempted_rps,achieved_rps,errors,p50_ms,p95_ms,p99_ms,p999_ms,max_ms");
    }
    
    /**
     * Write a metrics row for a time interval.
     */
    public void writeRow(long timestampMs, double attemptedRps, double achievedRps, 
                         long errors, double p50, double p95, double p99, double p999, double max) {
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
        
        rows.add(row);
        
        writer.printf("%s,%.2f,%.2f,%d,%.2f,%.2f,%.2f,%.2f,%.2f%n",
            timestamp, attemptedRps, achievedRps, errors, p50, p95, p99, p999, max);
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
    }
}
