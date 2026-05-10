package com.bench.load;

import com.bench.config.ConnectionProvider;
import com.bench.metrics.LatencyRecorder;
import com.bench.metrics.MetricsCollector;
import com.bench.workloads.Workload;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(Parameterized.class)
public class H2OpenLoopIntegrationTest {

    private static final String DB_URL =
        "jdbc:h2:mem:h2_open_loop_integration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false";

    private final String scenario;
    private final int targetRps;
    private final int selectOps;
    private final int insertOps;
    private final int updateOps;
    private final int deleteOps;
    private final int timeoutSeconds;

    private TestConnectionProvider connectionProvider;

    public H2OpenLoopIntegrationTest(String scenario, int targetRps, int selectOps, int insertOps,
                                     int updateOps, int deleteOps, int timeoutSeconds) {
        this.scenario = scenario;
        this.targetRps = targetRps;
        this.selectOps = selectOps;
        this.insertOps = insertOps;
        this.updateOps = updateOps;
        this.deleteOps = deleteOps;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> scenarios() throws Exception {
        List<Object[]> cases = new ArrayList<>();
        try (InputStream in = H2OpenLoopIntegrationTest.class.getClassLoader()
            .getResourceAsStream("h2-open-loop-scenarios.csv")) {
            if (in == null) {
                throw new IllegalStateException("Missing CSV source: h2-open-loop-scenarios.csv");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                boolean header = true;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    if (header) {
                        header = false;
                        continue;
                    }
                    String[] parts = trimmed.split("\\s*,\\s*");
                    if (parts.length != 7) {
                        throw new IllegalArgumentException("Expected 7 CSV columns, got " + parts.length + ": " + line);
                    }
                    cases.add(new Object[] {
                        parts[0],
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3]),
                        Integer.parseInt(parts[4]),
                        Integer.parseInt(parts[5]),
                        Integer.parseInt(parts[6])
                    });
                }
            }
        }
        return cases;
    }

    @Before
    public void setUp() throws Exception {
        connectionProvider = new TestConnectionProvider(DB_URL);
        createSchema();
        seedRows(3000);
    }

    @After
    public void tearDown() throws Exception {
        if (connectionProvider != null) {
            connectionProvider.close();
        }
    }

    @Test
    public void openLoopH2ReportsMedianLatencyBySqlType() throws Exception {
        int totalWriteOps = insertOps + updateOps + deleteOps;
        int totalOps = selectOps + totalWriteOps;

        assertEquals("Expected 1000 SELECT operations", 1000, selectOps);
        assertEquals("Expected 1000 write operations", 1000, totalWriteOps);

        List<SqlType> plan = buildPlan(selectOps, insertOps, updateOps, deleteOps);
        OperationTracking tracking = new OperationTracking();
        tracking.trackIds(loadExistingIds());

        Workload workload = new H2OperationWorkload(connectionProvider, plan, tracking);
        MetricsCollector cumulativeMetrics = new MetricsCollector();
        MetricsCollector intervalMetrics = new MetricsCollector();
        TrueOpenLoopLoadGenerator generator =
            new TrueOpenLoopLoadGenerator(workload, cumulativeMetrics, intervalMetrics, targetRps);

        generator.start();
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);

        while (tracking.getCompletedPlannedOps() < totalOps && System.nanoTime() < deadlineNanos) {
            Thread.sleep(20);
        }
        generator.stop();

        assertEquals("All planned operations should complete", totalOps, tracking.getCompletedPlannedOps());
        assertEquals("SELECT count mismatch", selectOps, tracking.count(SqlType.SELECT));
        assertEquals("INSERT count mismatch", insertOps, tracking.count(SqlType.INSERT));
        assertEquals("UPDATE count mismatch", updateOps, tracking.count(SqlType.UPDATE));
        assertEquals("DELETE count mismatch", deleteOps, tracking.count(SqlType.DELETE));

        double selectMedian = tracking.medianMs(SqlType.SELECT);
        double insertMedian = tracking.medianMs(SqlType.INSERT);
        double updateMedian = tracking.medianMs(SqlType.UPDATE);
        double deleteMedian = tracking.medianMs(SqlType.DELETE);

        assertTrue("SELECT median latency should be >= 0", selectMedian >= 0.0);
        assertTrue("INSERT median latency should be >= 0", insertMedian >= 0.0);
        assertTrue("UPDATE median latency should be >= 0", updateMedian >= 0.0);
        assertTrue("DELETE median latency should be >= 0", deleteMedian >= 0.0);

        String report = String.format(
            "H2 open-loop latency report [%s]%n" +
                "targetRps=%d plannedOps=%d (SELECT=%d INSERT=%d UPDATE=%d DELETE=%d)%n" +
                "medianMs: SELECT=%.3f INSERT=%.3f UPDATE=%.3f DELETE=%.3f",
            scenario, targetRps, totalOps, selectOps, insertOps, updateOps, deleteOps,
            selectMedian, insertMedian, updateMedian, deleteMedian
        );
        System.out.println(report);
    }

    private static List<SqlType> buildPlan(int selectOps, int insertOps, int updateOps, int deleteOps) {
        List<SqlType> plan = new ArrayList<>(selectOps + insertOps + updateOps + deleteOps);
        int maxOps = Math.max(Math.max(selectOps, insertOps), Math.max(updateOps, deleteOps));
        for (int i = 0; i < maxOps; i++) {
            if (i < selectOps) {
                plan.add(SqlType.SELECT);
            }
            if (i < insertOps) {
                plan.add(SqlType.INSERT);
            }
            if (i < updateOps) {
                plan.add(SqlType.UPDATE);
            }
            if (i < deleteOps) {
                plan.add(SqlType.DELETE);
            }
        }
        return Collections.unmodifiableList(plan);
    }

    private void createSchema() throws Exception {
        try (Connection conn = connectionProvider.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS latency_test");
            stmt.execute(
                "CREATE TABLE latency_test (" +
                    "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY," +
                    "payload VARCHAR(255) NOT NULL," +
                    "updated_at TIMESTAMP NOT NULL" +
                    ")"
            );
        }
    }

    private void seedRows(int rows) throws Exception {
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "INSERT INTO latency_test(payload, updated_at) VALUES (?, ?)")) {
            for (int i = 0; i < rows; i++) {
                stmt.setString(1, "seed-" + i);
                stmt.setTimestamp(2, Timestamp.from(Instant.now()));
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    private List<Long> loadExistingIds() throws Exception {
        List<Long> ids = new ArrayList<>();
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT id FROM latency_test");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        }
        return ids;
    }

    private enum SqlType {
        INSERT,
        UPDATE,
        DELETE,
        SELECT
    }

    private static final class H2OperationWorkload extends Workload {
        private static final long WORKLOAD_SEED = 1L;
        private static final long UNUSED_NUM_ACCOUNTS = 1L;
        private static final long UNUSED_NUM_ITEMS = 1L;
        private static final boolean ZIPF_DISABLED = false;
        private static final double UNUSED_ZIPF_ALPHA = 1.0;

        private final List<SqlType> plan;
        private final OperationTracking tracking;
        private final AtomicLong insertedSequence = new AtomicLong(0);

        H2OperationWorkload(ConnectionProvider connectionProvider, List<SqlType> plan, OperationTracking tracking) {
            super(connectionProvider, WORKLOAD_SEED, UNUSED_NUM_ACCOUNTS, UNUSED_NUM_ITEMS,
                ZIPF_DISABLED, UNUSED_ZIPF_ALPHA);
            this.plan = plan;
            this.tracking = tracking;
        }

        @Override
        public void execute() throws java.sql.SQLException {
            int index = tracking.nextPlanIndex();
            if (index >= plan.size()) {
                return;
            }
            SqlType type = plan.get(index);
            long start = System.nanoTime();
            executeByType(type);
            long elapsed = System.nanoTime() - start;
            tracking.record(type, elapsed);
        }

        private void executeByType(SqlType type) throws java.sql.SQLException {
            switch (type) {
                case INSERT:
                    executeInsert();
                    break;
                case UPDATE:
                    executeUpdate();
                    break;
                case DELETE:
                    executeDelete();
                    break;
                case SELECT:
                    executeSelect();
                    break;
                default:
                    throw new IllegalStateException("Unsupported type: " + type);
            }
        }

        private void executeInsert() throws java.sql.SQLException {
            long seq = insertedSequence.incrementAndGet();
            try (Connection conn = connectionProvider.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO latency_test(payload, updated_at) VALUES (?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
                stmt.setString(1, "ins-" + seq);
                stmt.setTimestamp(2, Timestamp.from(Instant.now()));
                stmt.executeUpdate();
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        tracking.trackId(rs.getLong(1));
                    }
                }
            }
        }

        private void executeUpdate() throws java.sql.SQLException {
            Long id = tracking.peekId();
            if (id == null) {
                executeInsert();
                return;
            }
            try (Connection conn = connectionProvider.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE latency_test SET payload = ?, updated_at = ? WHERE id = ?")) {
                stmt.setString(1, "upd-" + id);
                stmt.setTimestamp(2, Timestamp.from(Instant.now()));
                stmt.setLong(3, id);
                int updated = stmt.executeUpdate();
                if (updated == 0) {
                    executeInsert();
                }
            }
        }

        private void executeDelete() throws java.sql.SQLException {
            Long id = tracking.pollId();
            if (id == null) {
                executeInsert();
                return;
            }
            try (Connection conn = connectionProvider.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM latency_test WHERE id = ?")) {
                stmt.setLong(1, id);
                int deleted = stmt.executeUpdate();
                if (deleted == 0) {
                    executeInsert();
                }
            }
        }

        private void executeSelect() throws java.sql.SQLException {
            Long id = tracking.peekId();
            if (id == null) {
                try (Connection conn = connectionProvider.getConnection();
                     PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM latency_test");
                     ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        rs.getLong(1);
                    }
                }
                return;
            }
            try (Connection conn = connectionProvider.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "SELECT id, payload, updated_at FROM latency_test WHERE id = ?")) {
                stmt.setLong(1, id);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        rs.getLong("id");
                        rs.getString("payload");
                        rs.getTimestamp("updated_at");
                    }
                }
            }
        }

        @Override
        public String getName() {
            return "H2_OPEN_LOOP_INTEGRATION";
        }
    }

    private static final class OperationTracking {
        private final AtomicInteger planIndex = new AtomicInteger(0);
        private final AtomicInteger completedPlannedOps = new AtomicInteger(0);
        private final ConcurrentLinkedQueue<Long> activeIds = new ConcurrentLinkedQueue<>();
        private final Map<SqlType, AtomicInteger> counts = new HashMap<>();
        private final Map<SqlType, LatencyRecorder> recorders = new HashMap<>();

        OperationTracking() {
            for (SqlType type : SqlType.values()) {
                counts.put(type, new AtomicInteger(0));
                recorders.put(type, new LatencyRecorder(60000, 3));
            }
        }

        int nextPlanIndex() {
            return planIndex.getAndIncrement();
        }

        void record(SqlType type, long latencyNanos) {
            counts.get(type).incrementAndGet();
            recorders.get(type).recordNanos(latencyNanos);
            completedPlannedOps.incrementAndGet();
        }

        int getCompletedPlannedOps() {
            return completedPlannedOps.get();
        }

        int count(SqlType type) {
            return counts.get(type).get();
        }

        double medianMs(SqlType type) {
            return recorders.get(type).getPercentile(50.0);
        }

        void trackId(long id) {
            activeIds.add(id);
        }

        void trackIds(List<Long> ids) {
            activeIds.addAll(ids);
        }

        Long peekId() {
            return activeIds.peek();
        }

        Long pollId() {
            return activeIds.poll();
        }
    }

    private static final class TestConnectionProvider implements ConnectionProvider {
        private final org.h2.jdbcx.JdbcDataSource dataSource;

        TestConnectionProvider(String jdbcUrl) {
            this.dataSource = new org.h2.jdbcx.JdbcDataSource();
            this.dataSource.setURL(jdbcUrl);
            this.dataSource.setUser("sa");
            this.dataSource.setPassword("");
        }

        @Override
        public Connection getConnection() throws java.sql.SQLException {
            return dataSource.getConnection();
        }

        @Override
        public DataSource getDataSource() {
            return dataSource;
        }

        @Override
        public String getModeName() {
            return "H2_TEST";
        }

        @Override
        public int getPoolSize() {
            return 0;
        }

        @Override
        public void close() {
            // No resources to close for JdbcDataSource.
        }
    }
}
