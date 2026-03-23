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
                Map<String, Object> dbData = (Map<String, Object>) data.get("database");
                DatabaseConfig dbConfig = new DatabaseConfig();
                dbConfig.setJdbcUrl((String) dbData.get("jdbcUrl"));
                dbConfig.setUsername((String) dbData.get("username"));
                dbConfig.setPassword((String) dbData.get("password"));
                
                // SSL configuration (optional)
                if (dbData.containsKey("ssl")) {
                    Map<String, Object> sslData = (Map<String, Object>) dbData.get("ssl");
                    com.bench.config.SslConfig sslConfig = new com.bench.config.SslConfig();
                    
                    if (sslData.containsKey("enabled")) {
                        sslConfig.setEnabled((Boolean) sslData.get("enabled"));
                    }
                    if (sslData.containsKey("mode")) {
                        Object modeVal = sslData.get("mode");
                        if (modeVal == null) {
                            throw new IllegalArgumentException(
                                "database.ssl.mode is present but has no value; " +
                                "valid values: DISABLE, ALLOW, PREFER, REQUIRE, VERIFY_CA, VERIFY_FULL");
                        }
                        sslConfig.setMode(com.bench.config.SslMode.valueOf(
                                modeVal.toString().toUpperCase().replace('-', '_')));
                    }
                    if (sslData.containsKey("certPath")) {
                        sslConfig.setCertPath((String) sslData.get("certPath"));
                    }
                    if (sslData.containsKey("keyPath")) {
                        sslConfig.setKeyPath((String) sslData.get("keyPath"));
                    }
                    if (sslData.containsKey("rootCertPath")) {
                        sslConfig.setRootCertPath((String) sslData.get("rootCertPath"));
                    }
                    
                    dbConfig.setSsl(sslConfig);
                }
                
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
            
            // OJP configuration
            if (data.containsKey("ojp")) {
                Map<String, Object> ojpData = (Map<String, Object>) data.get("ojp");
                com.bench.config.OjpConfig ojpConfig = config.getOjpConfig();
                
                if (ojpData.containsKey("virtualConnectionMode")) {
                    String mode = (String) ojpData.get("virtualConnectionMode");
                    ojpConfig.setVirtualConnectionMode(
                        com.bench.config.OjpVirtualConnectionMode.valueOf(mode));
                }
                if (ojpData.containsKey("poolSharing")) {
                    String sharing = (String) ojpData.get("poolSharing");
                    ojpConfig.setPoolSharing(
                        com.bench.config.OjpPoolSharing.valueOf(sharing));
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
                if (ojpData.containsKey("poolKey")) {
                    ojpConfig.setPoolKey((String) ojpData.get("poolKey"));
                }
                
                // Additional properties with ojp. prefix
                ojpData.forEach((key, value) -> {
                    if (key.startsWith("properties.")) {
                        String propKey = key.substring("properties.".length());
                        ojpConfig.addProperty(propKey, String.valueOf(value));
                    }
                });
            }
            
            // Output directory
            if (data.containsKey("outputDir")) {
                config.setOutputDir((String) data.get("outputDir"));
            }
        }
        
        return config;
    }
}
