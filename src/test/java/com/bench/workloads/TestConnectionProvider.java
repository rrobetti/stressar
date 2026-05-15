package com.bench.workloads;

import com.bench.config.ConnectionProvider;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * A stub {@link ConnectionProvider} for unit tests that records every SQL string
 * passed to {@link Connection#prepareStatement(String)} and returns an empty
 * {@link ResultSet} for every query.
 * <p>
 * Package-private by design: shared across {@link OlapWorkloadTest} and
 * {@link HtapWorkloadTest} within this test package without leaking into
 * production code.
 * <p>
 * Write queries ({@code executeUpdate}) return 1.
 * {@code INSERT … RETURNING} queries (used by ReadWriteWorkload) also return an
 * empty ResultSet, so tests that exercise write paths must configure
 * {@code writePercent=0.0} to avoid the "Failed to get order_id" exception.
 */
class TestConnectionProvider implements ConnectionProvider {

    final List<String> capturedSql = new ArrayList<>();

    @Override
    public Connection getConnection() throws SQLException {
        return makeConnection();
    }

    private Connection makeConnection() {
        return (Connection) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class[]{Connection.class},
            new ConnectionHandler()
        );
    }

    private class ConnectionHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "prepareStatement": {
                    String sql = (String) args[0];
                    capturedSql.add(sql);
                    return makePreparedStatement();
                }
                case "setAutoCommit":
                case "commit":
                case "rollback":
                case "close":
                    return null;
                default:
                    if (method.getReturnType() == boolean.class) return false;
                    return null;
            }
        }
    }

    private PreparedStatement makePreparedStatement() {
        return (PreparedStatement) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class[]{PreparedStatement.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "executeQuery":
                        return makeEmptyResultSet();
                    case "executeUpdate":
                        return 1;
                    case "setLong":
                    case "setInt":
                    case "setTimestamp":
                    case "close":
                        return null;
                    default:
                        if (method.getReturnType() == boolean.class) return false;
                        return null;
                }
            }
        );
    }

    private ResultSet makeEmptyResultSet() {
        return (ResultSet) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class[]{ResultSet.class},
            (proxy, method, args) -> {
                if ("next".equals(method.getName())) return false;
                if ("close".equals(method.getName())) return null;
                if (method.getReturnType() == boolean.class) return false;
                return null;
            }
        );
    }

    @Override
    public DataSource getDataSource() { return null; }

    @Override
    public String getModeName() { return "STUB"; }

    @Override
    public int getPoolSize() { return 1; }

    @Override
    public void close() { /* no-op */ }
}
