package com.bench.load;

import com.bench.config.ConnectionProvider;
import com.bench.metrics.LatencyRecorder;
import com.bench.metrics.MetricsCollector;
import com.bench.metrics.MetricsSnapshot;
import com.bench.workloads.Workload;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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
    private FlowInstrumentation flowInstrumentation;

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
        flowInstrumentation = new FlowInstrumentation();
        connectionProvider = new TestConnectionProvider(DB_URL, flowInstrumentation);
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

        Workload workload = new H2OperationWorkload(connectionProvider, plan, tracking, flowInstrumentation);
        MetricsCollector cumulativeMetrics = new MetricsCollector();
        MetricsCollector intervalMetrics = new MetricsCollector();
        TrueOpenLoopLoadGenerator generator =
            new TrueOpenLoopLoadGenerator(workload, cumulativeMetrics, intervalMetrics, targetRps);
        assertEquals("Expected HikariCP pool size 100", 100, connectionProvider.getPoolSize());

        generator.start();
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);

        while (tracking.getCompletedPlannedOps() < totalOps && System.nanoTime() < deadlineNanos) {
            Thread.sleep(20);
        }
        generator.stop();

        MetricsSnapshot metricsSnapshot = cumulativeMetrics.getSnapshot();
        assertEquals("Load generator should not record errors", 0L, metricsSnapshot.getErrors());
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
                "medianMs: SELECT=%.3f INSERT=%.3f UPDATE=%.3f DELETE=%.3f%n" +
                "%s%n" +
                "grpcParsingCount=%d (H2 JDBC path)",
            scenario, targetRps, totalOps, selectOps, insertOps, updateOps, deleteOps,
            selectMedian, insertMedian, updateMedian, deleteMedian,
            flowInstrumentation.report(),
            flowInstrumentation.grpcParsingCount()
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
        Connection conn = null;
        Statement stmt = null;
        try {
            conn = connectionProvider.getConnection();
            stmt = conn.createStatement();
            final Statement statement = stmt;
            executeUpdateInstrumented(
                () -> statement.executeUpdate("DROP TABLE IF EXISTS latency_test"),
                flowInstrumentation
            );
            executeUpdateInstrumented(
                () -> statement.executeUpdate(
                    "CREATE TABLE latency_test (" +
                        "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY," +
                        "payload VARCHAR(255) NOT NULL," +
                        "updated_at TIMESTAMP NOT NULL" +
                        ")"
                ),
                flowInstrumentation
            );
        } finally {
            closeInstrumented(stmt, flowInstrumentation);
            closeInstrumented(conn, flowInstrumentation);
        }
    }

    private void seedRows(int rows) throws Exception {
        Connection conn = null;
        PreparedStatement stmt = null;
        try {
            conn = connectionProvider.getConnection();
            stmt = conn.prepareStatement("INSERT INTO latency_test(payload, updated_at) VALUES (?, ?)");
            final PreparedStatement statement = stmt;
            for (int i = 0; i < rows; i++) {
                statement.setString(1, "seed-" + i);
                statement.setTimestamp(2, Timestamp.from(Instant.now()));
                executeUpdateInstrumented(() -> statement.executeUpdate(), flowInstrumentation);
            }
        } finally {
            closeInstrumented(stmt, flowInstrumentation);
            closeInstrumented(conn, flowInstrumentation);
        }
    }

    private List<Long> loadExistingIds() throws Exception {
        List<Long> ids = new ArrayList<>();
        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            conn = connectionProvider.getConnection();
            stmt = conn.prepareStatement("SELECT id FROM latency_test");
            final PreparedStatement statement = stmt;
            rs = executeQueryInstrumented(() -> statement.executeQuery(), flowInstrumentation);
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        } finally {
            closeInstrumented(rs, flowInstrumentation);
            closeInstrumented(stmt, flowInstrumentation);
            closeInstrumented(conn, flowInstrumentation);
        }
        return ids;
    }

    @FunctionalInterface
    private interface QuerySupplier {
        ResultSet execute() throws java.sql.SQLException;
    }

    @FunctionalInterface
    private interface UpdateSupplier {
        int execute() throws java.sql.SQLException;
    }

    private static ResultSet executeQueryInstrumented(QuerySupplier supplier, FlowInstrumentation flowInstrumentation)
        throws java.sql.SQLException {
        long startNanos = System.nanoTime();
        try {
            return supplier.execute();
        } finally {
            flowInstrumentation.recordExecuteQuery(System.nanoTime() - startNanos);
        }
    }

    private static int executeUpdateInstrumented(UpdateSupplier supplier, FlowInstrumentation flowInstrumentation)
        throws java.sql.SQLException {
        long startNanos = System.nanoTime();
        try {
            return supplier.execute();
        } finally {
            flowInstrumentation.recordExecuteUpdate(System.nanoTime() - startNanos);
        }
    }

    private static void closeInstrumented(AutoCloseable closeable, FlowInstrumentation flowInstrumentation)
        throws java.sql.SQLException {
        if (closeable == null) {
            return;
        }
        long startNanos = System.nanoTime();
        try {
            closeable.close();
        } catch (Exception e) {
            throw new java.sql.SQLException("Error while closing DB resource", e);
        } finally {
            flowInstrumentation.recordClose(System.nanoTime() - startNanos);
        }
    }

    private enum SqlType {
        INSERT,
        UPDATE,
        DELETE,
        SELECT
    }

    private static final class H2OperationWorkload extends Workload {
        private static final long WORKLOAD_SEED = 1L;
        private static final long BASE_NUM_ACCOUNTS = 1L;
        private static final long BASE_NUM_ITEMS = 1L;
        private static final boolean ZIPF_DISABLED = false;
        private static final double ZIPF_ALPHA = 1.0;

        private final List<SqlType> plan;
        private final OperationTracking tracking;
        private final FlowInstrumentation flowInstrumentation;
        private final AtomicLong insertedSequence = new AtomicLong(0);

        H2OperationWorkload(ConnectionProvider connectionProvider, List<SqlType> plan,
                            OperationTracking tracking, FlowInstrumentation flowInstrumentation) {
            super(connectionProvider, WORKLOAD_SEED, BASE_NUM_ACCOUNTS, BASE_NUM_ITEMS,
                ZIPF_DISABLED, ZIPF_ALPHA);
            this.plan = plan;
            this.tracking = tracking;
            this.flowInstrumentation = flowInstrumentation;
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
            Connection conn = null;
            PreparedStatement stmt = null;
            ResultSet rs = null;
            try {
                conn = connectionProvider.getConnection();
                stmt = conn.prepareStatement(
                    "INSERT INTO latency_test(payload, updated_at) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS
                );
                final PreparedStatement statement = stmt;
                statement.setString(1, "ins-" + seq);
                statement.setTimestamp(2, Timestamp.from(Instant.now()));
                executeUpdateInstrumented(() -> statement.executeUpdate(), flowInstrumentation);
                rs = statement.getGeneratedKeys();
                if (rs.next()) {
                    tracking.trackId(rs.getLong(1));
                }
            } finally {
                closeInstrumented(rs, flowInstrumentation);
                closeInstrumented(stmt, flowInstrumentation);
                closeInstrumented(conn, flowInstrumentation);
            }
        }

        private void executeUpdate() throws java.sql.SQLException {
            Long id = tracking.pollId();
            if (id == null) {
                throw new java.sql.SQLException("No active row available for UPDATE");
            }
            Connection conn = null;
            PreparedStatement stmt = null;
            try {
                conn = connectionProvider.getConnection();
                stmt = conn.prepareStatement("UPDATE latency_test SET payload = ?, updated_at = ? WHERE id = ?");
                final PreparedStatement statement = stmt;
                statement.setString(1, "upd-" + id);
                statement.setTimestamp(2, Timestamp.from(Instant.now()));
                statement.setLong(3, id);
                int updated = executeUpdateInstrumented(() -> statement.executeUpdate(), flowInstrumentation);
                if (updated == 0) {
                    throw new java.sql.SQLException("UPDATE affected 0 rows for id=" + id);
                }
                tracking.trackId(id);
            } finally {
                closeInstrumented(stmt, flowInstrumentation);
                closeInstrumented(conn, flowInstrumentation);
            }
        }

        private void executeDelete() throws java.sql.SQLException {
            Long id = tracking.pollId();
            if (id == null) {
                throw new java.sql.SQLException("No active row available for DELETE");
            }
            Connection conn = null;
            PreparedStatement stmt = null;
            try {
                conn = connectionProvider.getConnection();
                stmt = conn.prepareStatement("DELETE FROM latency_test WHERE id = ?");
                final PreparedStatement statement = stmt;
                statement.setLong(1, id);
                int deleted = executeUpdateInstrumented(() -> statement.executeUpdate(), flowInstrumentation);
                if (deleted == 0) {
                    throw new java.sql.SQLException("DELETE affected 0 rows for id=" + id);
                }
            } finally {
                closeInstrumented(stmt, flowInstrumentation);
                closeInstrumented(conn, flowInstrumentation);
            }
        }

        private void executeSelect() throws java.sql.SQLException {
            Long id = tracking.peekId();
            if (id == null) {
                Connection conn = null;
                PreparedStatement stmt = null;
                ResultSet rs = null;
                try {
                    conn = connectionProvider.getConnection();
                    stmt = conn.prepareStatement("SELECT COUNT(*) FROM latency_test");
                    final PreparedStatement statement = stmt;
                    rs = executeQueryInstrumented(() -> statement.executeQuery(), flowInstrumentation);
                    if (rs.next()) {
                        rs.getLong(1);
                    }
                } finally {
                    closeInstrumented(rs, flowInstrumentation);
                    closeInstrumented(stmt, flowInstrumentation);
                    closeInstrumented(conn, flowInstrumentation);
                }
                return;
            }
            Connection conn = null;
            PreparedStatement stmt = null;
            ResultSet rs = null;
            try {
                conn = connectionProvider.getConnection();
                stmt = conn.prepareStatement("SELECT id, payload, updated_at FROM latency_test WHERE id = ?");
                final PreparedStatement statement = stmt;
                statement.setLong(1, id);
                rs = executeQueryInstrumented(() -> statement.executeQuery(), flowInstrumentation);
                if (rs.next()) {
                    rs.getLong("id");
                    rs.getString("payload");
                    rs.getTimestamp("updated_at");
                }
            } finally {
                closeInstrumented(rs, flowInstrumentation);
                closeInstrumented(stmt, flowInstrumentation);
                closeInstrumented(conn, flowInstrumentation);
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

    private static final class FlowInstrumentation {
        private final LatencyRecorder connect = new LatencyRecorder(60000, 3);
        private final LatencyRecorder executeQuery = new LatencyRecorder(60000, 3);
        private final LatencyRecorder executeUpdate = new LatencyRecorder(60000, 3);
        private final LatencyRecorder close = new LatencyRecorder(60000, 3);
        private final AtomicLong grpcParsingCount = new AtomicLong(0);

        void recordConnect(long latencyNanos) {
            connect.recordNanos(latencyNanos);
        }

        void recordExecuteQuery(long latencyNanos) {
            executeQuery.recordNanos(latencyNanos);
        }

        void recordExecuteUpdate(long latencyNanos) {
            executeUpdate.recordNanos(latencyNanos);
        }

        void recordClose(long latencyNanos) {
            close.recordNanos(latencyNanos);
        }

        long grpcParsingCount() {
            return grpcParsingCount.get();
        }

        String report() {
            return String.format(
                "flowMs(connect/executeQuery/executeUpdate/close) median: %.3f / %.3f / %.3f / %.3f; count: %d / %d / %d / %d",
                connect.getPercentile(50.0),
                executeQuery.getPercentile(50.0),
                executeUpdate.getPercentile(50.0),
                close.getPercentile(50.0),
                connect.getCount(),
                executeQuery.getCount(),
                executeUpdate.getCount(),
                close.getCount()
            );
        }
    }

    private static final class TestConnectionProvider implements ConnectionProvider {
        private static final int POOL_SIZE = 100;

        private final HikariDataSource dataSource;
        private final FlowInstrumentation flowInstrumentation;

        TestConnectionProvider(String jdbcUrl, FlowInstrumentation flowInstrumentation) {
            this.flowInstrumentation = flowInstrumentation;
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(jdbcUrl);
            config.setUsername("sa");
            config.setPassword("");
            config.setMaximumPoolSize(POOL_SIZE);
            config.setMinimumIdle(10);
            config.setAutoCommit(true);
            config.setPoolName("h2-open-loop-test-pool");
            this.dataSource = new HikariDataSource(config);
        }

        @Override
        public Connection getConnection() throws java.sql.SQLException {
            long startNanos = System.nanoTime();
            try {
                return dataSource.getConnection();
            } finally {
                flowInstrumentation.recordConnect(System.nanoTime() - startNanos);
            }
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
            return POOL_SIZE;
        }

        @Override
        public void close() {
            long startNanos = System.nanoTime();
            try {
                dataSource.close();
            } finally {
                flowInstrumentation.recordClose(System.nanoTime() - startNanos);
            }
        }
    }
}
