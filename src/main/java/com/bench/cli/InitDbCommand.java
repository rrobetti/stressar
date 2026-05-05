package com.bench.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.Callable;

/**
 * Initialize database schema and generate test data.
 */
@Command(
    name = "init-db",
    description = "Initialize database schema and generate test data"
)
public class InitDbCommand implements Callable<Integer> {
    private static final Logger logger = LoggerFactory.getLogger(InitDbCommand.class);
    
    @Option(names = {"-u", "--jdbc-url"}, required = true, description = "JDBC URL")
    private String jdbcUrl;
    
    @Option(names = {"--username"}, required = true, description = "Database username")
    private String username;
    
    @Option(names = {"--password"}, required = true, description = "Database password")
    private String password;
    
    @Option(names = {"--accounts"}, description = "Number of accounts to generate", defaultValue = "10000")
    private int numAccounts;
    
    @Option(names = {"--items"}, description = "Number of items to generate", defaultValue = "5000")
    private int numItems;
    
    @Option(names = {"--orders"}, description = "Number of orders to generate", defaultValue = "50000")
    private int numOrders;
    
    @Option(names = {"--seed"}, description = "Random seed for data generation", defaultValue = "0.42")
    private double seed;
    
    @Override
    public Integer call() throws Exception {
        logger.info("Initializing database...");
        logger.info("JDBC URL: {}", jdbcUrl);
        logger.info("Accounts: {}, Items: {}, Orders: {}", numAccounts, numItems, numOrders);
        
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
            conn.setAutoCommit(false);
            
            // Execute DDL
            logger.info("Creating tables...");
            executeSqlFile(conn, "/schema/ddl.sql");
            conn.commit();
            
            // Execute indexes
            logger.info("Creating indexes...");
            executeSqlFile(conn, "/schema/indexes.sql");
            conn.commit();
            
            // Execute data generator
            logger.info("Generating data (this may take a few minutes)...");
            String generatorSql = loadSqlFile("/data/generator.sql");
            
            // Customize the generator SQL with parameters
            generatorSql = generatorSql.replace("setseed(0.42)", "setseed(" + seed + ")");
            generatorSql = generatorSql.replace("generate_series(1, 10000)", 
                                               "generate_series(1, " + numAccounts + ")");
            generatorSql = generatorSql.replace("generate_series(1, 5000)", 
                                               "generate_series(1, " + numItems + ")");
            generatorSql = generatorSql.replace("generate_series(1, 50000)", 
                                               "generate_series(1, " + numOrders + ")");
            
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(generatorSql);
            }
            conn.commit();
            
            // VACUUM ANALYZE must run outside a transaction block
            logger.info("Running VACUUM ANALYZE to update planner statistics...");
            conn.setAutoCommit(true);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("VACUUM ANALYZE accounts");
                stmt.execute("VACUUM ANALYZE items");
                stmt.execute("VACUUM ANALYZE orders");
                stmt.execute("VACUUM ANALYZE order_lines");
            }
            
            logger.info("Database initialization complete!");
            return 0;
            
        } catch (Exception e) {
            logger.error("Failed to initialize database", e);
            return 1;
        }
    }
    
    private void executeSqlFile(Connection conn, String resourcePath) throws Exception {
        String sql = loadSqlFile(resourcePath);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }
    
    private String loadSqlFile(String resourcePath) throws Exception {
        try (InputStream is = getClass().getResourceAsStream(resourcePath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }
}
