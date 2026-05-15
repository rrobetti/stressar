package com.bench.cli;

import com.bench.config.BenchmarkConfig;
import com.bench.config.ConnectionMode;
import com.bench.config.DatabaseConfig;
import com.bench.config.OjpConfig;
import com.bench.config.OjpPoolSharing;
import com.bench.config.OjpVirtualConnectionMode;
import com.bench.config.WorkloadConfig;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Map;

/**
 * Loads benchmark configuration from YAML files.
 */
public class ConfigLoader {

    private ConfigLoader() {
        // private constructor
    }

    public static BenchmarkConfig loadFromYaml(String filename) throws Exception {
        Yaml yaml = new Yaml();
        BenchmarkConfig config = new BenchmarkConfig();

        try (InputStream is = new FileInputStream(filename)) {
            Map<String, Object> data = yaml.load(is);

            configDatabase(data, config);

            // Connection mode
            if (data.containsKey("connectionMode")) {
                String mode = (String) data.get("connectionMode");
                config.setConnectionMode(ConnectionMode.valueOf(mode));
            }

            configPoolSettings(data, config);

            configWorkload(data, config);

            configGeneralSettings(data, config);

            configOJP(data, config);

            // Output directory
            if (data.containsKey("outputDir")) {
                config.setOutputDir((String) data.get("outputDir"));
            }
        }

        return config;
    }

    private static void configOJP(Map<String, Object> data, BenchmarkConfig config) {
        if (!data.containsKey("ojp")) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> ojpData = (Map<String, Object>) data.get("ojp");
        OjpConfig ojpConfig = config.getOjpConfig();
        applyOjpConnectionAndPoolKeys(ojpData, ojpConfig);
        applyOjpTimeoutsAndLimits(ojpData, ojpConfig);
        applyOjpPrefixedProperties(ojpData, ojpConfig);
    }

    private static void applyOjpConnectionAndPoolKeys(Map<String, Object> ojpData, OjpConfig ojpConfig) {
        if (ojpData.containsKey("virtualConnectionMode")) {
            String mode = (String) ojpData.get("virtualConnectionMode");
            ojpConfig.setVirtualConnectionMode(OjpVirtualConnectionMode.valueOf(mode));
        }
        if (ojpData.containsKey("poolSharing")) {
            String sharing = (String) ojpData.get("poolSharing");
            ojpConfig.setPoolSharing(OjpPoolSharing.valueOf(sharing));
        }
        if (ojpData.containsKey("minConnections")) {
            ojpConfig.setMinConnections((Integer) ojpData.get("minConnections"));
        }
        if (ojpData.containsKey("maxConnections")) {
            ojpConfig.setMaxConnections((Integer) ojpData.get("maxConnections"));
        }
        if (ojpData.containsKey("connectionTimeoutMs")) {
            ojpConfig.setConnectionTimeoutMs((Integer) ojpData.get("connectionTimeoutMs"));
        }
        if (ojpData.containsKey("poolKey")) {
            ojpConfig.setPoolKey((String) ojpData.get("poolKey"));
        }
    }

    private static void applyOjpTimeoutsAndLimits(Map<String, Object> ojpData, OjpConfig ojpConfig) {
        if (ojpData.containsKey("idleTimeoutMs")) {
            ojpConfig.setIdleTimeoutMs((Integer) ojpData.get("idleTimeoutMs"));
        }
        if (ojpData.containsKey("maxLifetimeMs")) {
            ojpConfig.setMaxLifetimeMs((Integer) ojpData.get("maxLifetimeMs"));
        }
        if (ojpData.containsKey("queueLimit")) {
            ojpConfig.setQueueLimit((Integer) ojpData.get("queueLimit"));
        }
        if (ojpData.containsKey("slowQueryThresholdMs")) {
            ojpConfig.setSlowQueryThresholdMs((Integer) ojpData.get("slowQueryThresholdMs"));
        }
    }

    private static void applyOjpPrefixedProperties(Map<String, Object> ojpData, OjpConfig ojpConfig) {
        ojpData.forEach((key, value) -> {
            if (key.startsWith("properties.")) {
                String propKey = key.substring("properties.".length());
                ojpConfig.addProperty(propKey, String.valueOf(value));
            }
        });
    }

    private static void configGeneralSettings(Map<String, Object> data, BenchmarkConfig config) {
        // Data generation settings
        if (data.containsKey("numAccounts")) {
            config.setNumAccounts((Integer) data.get("numAccounts"));
        }
        if (data.containsKey("numItems")) {
            config.setNumItems((Integer) data.get("numItems"));
        }
        if (data.containsKey("numOrders")) {
            config.setNumOrders((Integer) data.get("numOrders"));
        }
    }

    private static void configPoolSettings(Map<String, Object> data, BenchmarkConfig config) {
        // Pool settings
        if (data.containsKey("poolSize")) {
            config.setPoolSize((Integer) data.get("poolSize"));
        }
        if (data.containsKey("dbConnectionBudget")) {
            config.setDbConnectionBudget((Integer) data.get("dbConnectionBudget"));
        }
        if (data.containsKey("replicas")) {
            config.setReplicas((Integer) data.get("replicas"));
        }
    }

    private static void configWorkload(Map<String, Object> data, BenchmarkConfig config) {
        if (!data.containsKey("workload")) {
            return;
        }
        Object wlRaw = data.get("workload");
        if (!(wlRaw instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("'workload' must be a YAML mapping");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> wlData = (Map<String, Object>) wlRaw;
        config.setWorkload(buildWorkloadConfig(wlData));
    }

    private static WorkloadConfig buildWorkloadConfig(Map<String, Object> wlData) {
        WorkloadConfig wlConfig = new WorkloadConfig();
        applyWorkloadTypeAndRates(wlData, wlConfig);
        applyWorkloadTiming(wlData, wlConfig);
        applyWorkloadMixPercents(wlData, wlConfig);
        applyWorkloadSeed(wlData, wlConfig);
        applyWorkloadZipf(wlData, wlConfig);
        return wlConfig;
    }

    private static void applyWorkloadTypeAndRates(Map<String, Object> wlData, WorkloadConfig wlConfig) {
        if (wlData.containsKey("type")) {
            wlConfig.setType(WorkloadConfig.WorkloadType.valueOf((String) wlData.get("type")));
        }
        if (wlData.containsKey("targetRps")) {
            wlConfig.setTargetRps((Integer) wlData.get("targetRps"));
        }
        if (wlData.containsKey("concurrency")) {
            wlConfig.setConcurrency((Integer) wlData.get("concurrency"));
        }
        if (wlData.containsKey("openLoop")) {
            wlConfig.setOpenLoop((Boolean) wlData.get("openLoop"));
        }
    }

    private static void applyWorkloadTiming(Map<String, Object> wlData, WorkloadConfig wlConfig) {
        if (wlData.containsKey("warmupSeconds")) {
            wlConfig.setWarmupSeconds((Integer) wlData.get("warmupSeconds"));
        }
        if (wlData.containsKey("durationSeconds")) {
            wlConfig.setDurationSeconds((Integer) wlData.get("durationSeconds"));
        }
        if (wlData.containsKey("cooldownSeconds")) {
            wlConfig.setCooldownSeconds((Integer) wlData.get("cooldownSeconds"));
        }
    }

    private static void applyWorkloadMixPercents(Map<String, Object> wlData, WorkloadConfig wlConfig) {
        if (wlData.containsKey("queryAPercent")) {
            wlConfig.setQueryAPercent(yamlDouble(wlData.get("queryAPercent")));
        }
        if (wlData.containsKey("writePercent")) {
            wlConfig.setWritePercent(yamlDouble(wlData.get("writePercent")));
        }
        if (wlData.containsKey("slowQueryPercent")) {
            wlConfig.setSlowQueryPercent(yamlDouble(wlData.get("slowQueryPercent")));
        }
        if (wlData.containsKey("olapPercent")) {
            wlConfig.setOlapPercent(yamlDouble(wlData.get("olapPercent")));
        }
    }

    private static void applyWorkloadSeed(Map<String, Object> wlData, WorkloadConfig wlConfig) {
        if (wlData.containsKey("seed")) {
            wlConfig.setSeed(yamlLong(wlData.get("seed")));
        }
    }

    private static void applyWorkloadZipf(Map<String, Object> wlData, WorkloadConfig wlConfig) {
        if (wlData.containsKey("useZipf")) {
            wlConfig.setUseZipf((Boolean) wlData.get("useZipf"));
        }
        if (wlData.containsKey("zipfAlpha")) {
            wlConfig.setZipfAlpha(yamlDouble(wlData.get("zipfAlpha")));
        }
    }

    private static long yamlLong(Object value) {
        if (value instanceof Integer) {
            return ((Integer) value).longValue();
        }
        return (Long) value;
    }

    private static double yamlDouble(Object value) {
        if (value instanceof Integer) {
            return ((Integer) value).doubleValue();
        }
        return (Double) value;
    }

    private static void configDatabase(Map<String, Object> data, BenchmarkConfig config) {
        // Database configuration
        if (data.containsKey("database")) {
            Map<String, String> dbData = (Map<String, String>) data.get("database");
            DatabaseConfig dbConfig = new DatabaseConfig();
            dbConfig.setJdbcUrl(dbData.get("jdbcUrl"));
            dbConfig.setUsername(dbData.get("username"));
            dbConfig.setPassword(dbData.get("password"));
            config.setDatabase(dbConfig);
        }
    }
}
