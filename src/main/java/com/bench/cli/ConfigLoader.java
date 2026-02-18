package com.bench.cli;

import com.bench.config.BenchmarkConfig;
import com.bench.config.ConnectionMode;
import com.bench.config.DatabaseConfig;
import com.bench.config.WorkloadConfig;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Map;

/**
 * Loads benchmark configuration from YAML files.
 */
public class ConfigLoader {
    
    public static BenchmarkConfig loadFromYaml(String filename) throws Exception {
        Yaml yaml = new Yaml();
        BenchmarkConfig config = new BenchmarkConfig();
        
        try (InputStream is = new FileInputStream(filename)) {
            Map<String, Object> data = yaml.load(is);
            
            // Database configuration
            if (data.containsKey("database")) {
                Map<String, String> dbData = (Map<String, String>) data.get("database");
                DatabaseConfig dbConfig = new DatabaseConfig();
                dbConfig.setJdbcUrl(dbData.get("jdbcUrl"));
                dbConfig.setUsername(dbData.get("username"));
                dbConfig.setPassword(dbData.get("password"));
                config.setDatabase(dbConfig);
            }
            
            // Connection mode
            if (data.containsKey("connectionMode")) {
                String mode = (String) data.get("connectionMode");
                config.setConnectionMode(ConnectionMode.valueOf(mode));
            }
            
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
            
            // Workload configuration
            if (data.containsKey("workload")) {
                Map<String, Object> wlData = (Map<String, Object>) data.get("workload");
                WorkloadConfig wlConfig = new WorkloadConfig();
                
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
                if (wlData.containsKey("warmupSeconds")) {
                    wlConfig.setWarmupSeconds((Integer) wlData.get("warmupSeconds"));
                }
                if (wlData.containsKey("durationSeconds")) {
                    wlConfig.setDurationSeconds((Integer) wlData.get("durationSeconds"));
                }
                if (wlData.containsKey("cooldownSeconds")) {
                    wlConfig.setCooldownSeconds((Integer) wlData.get("cooldownSeconds"));
                }
                if (wlData.containsKey("seed")) {
                    Object seedObj = wlData.get("seed");
                    if (seedObj instanceof Integer) {
                        wlConfig.setSeed(((Integer) seedObj).longValue());
                    } else {
                        wlConfig.setSeed((Long) seedObj);
                    }
                }
                if (wlData.containsKey("useZipf")) {
                    wlConfig.setUseZipf((Boolean) wlData.get("useZipf"));
                }
                if (wlData.containsKey("zipfAlpha")) {
                    Object alphaObj = wlData.get("zipfAlpha");
                    if (alphaObj instanceof Integer) {
                        wlConfig.setZipfAlpha(((Integer) alphaObj).doubleValue());
                    } else {
                        wlConfig.setZipfAlpha((Double) alphaObj);
                    }
                }
                
                config.setWorkload(wlConfig);
            }
            
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
            
            // Output directory
            if (data.containsKey("outputDir")) {
                config.setOutputDir((String) data.get("outputDir"));
            }
        }
        
        return config;
    }
}
