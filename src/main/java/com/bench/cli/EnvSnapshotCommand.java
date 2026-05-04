package com.bench.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.OperatingSystem;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Captures environment information for reproducibility.
 */
@Command(
    name = "env-snapshot",
    description = "Capture system and environment information"
)
public class EnvSnapshotCommand implements Callable<Integer> {
    private static final Logger logger = LoggerFactory.getLogger(EnvSnapshotCommand.class);

    private static final String JSON_KEY_OS = "os";
    private static final String JSON_KEY_CPU = "cpu";
    private static final String JSON_KEY_MEMORY = "memory";
    private static final String JSON_KEY_JAVA = "java";
    private static final String JSON_KEY_GIT = "git";
    private static final String JSON_KEY_DEPENDENCIES = "dependencies";
    private static final String JSON_KEY_TIMESTAMP = "timestamp";
    private static final String JSON_KEY_CAPTURED_AT = "capturedAt";

    private static final String FIELD_FAMILY = "family";
    private static final String FIELD_MANUFACTURER = "manufacturer";
    private static final String FIELD_VERSION = "version";
    private static final String FIELD_BITNESS = "bitness";
    private static final String FIELD_MODEL = "model";
    private static final String FIELD_VENDOR = "vendor";
    private static final String FIELD_PHYSICAL_CORES = "physicalCores";
    private static final String FIELD_LOGICAL_CORES = "logicalCores";
    private static final String FIELD_MAX_FREQ_HZ = "maxFreqHz";
    private static final String FIELD_TOTAL_BYTES = "totalBytes";
    private static final String FIELD_TOTAL_GB = "totalGB";
    private static final String FIELD_RUNTIME = "runtime";
    private static final String FIELD_VM = "vm";
    private static final String FIELD_VM_VERSION = "vmVersion";
    private static final String FIELD_JVM_FLAGS = "jvmFlags";
    private static final String FIELD_COMMIT = "commit";
    private static final String FIELD_BRANCH = "branch";
    private static final String FIELD_IS_DIRTY = "isDirty";

    private static final String NOT_AVAILABLE = "N/A";
    private static final String FILE_SNAPSHOT_JSON = "snapshot.json";
    private static final String FILE_SNAPSHOT_MD = "snapshot.md";
    private static final String MD_PLACEHOLDER_ETC = "# etc.\n";
    private static final String MD_CODE_FENCE = "```\n";
    private static final String MD_CODE_FENCE_END_SECTION = "```\n\n";

    @Option(names = {"-o", "--output-dir"}, description = "Output directory", defaultValue = "results/env")
    private String outputDir;
    
    @Override
    public Integer call() throws Exception {
        logger.info("Capturing environment snapshot...");
        
        // Create timestamp
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        
        // Create output directory
        Path outputPath = Paths.get(outputDir, timestamp);
        Files.createDirectories(outputPath);
        
        // Collect system information
        Map<String, Object> envData = new HashMap<>();
        
        // OSHI system info
        SystemInfo si = new SystemInfo();
        HardwareAbstractionLayer hal = si.getHardware();
        OperatingSystem os = si.getOperatingSystem();
        CentralProcessor cpu = hal.getProcessor();
        GlobalMemory memory = hal.getMemory();
        
        // OS information
        Map<String, String> osInfo = new HashMap<>();
        osInfo.put(FIELD_FAMILY, os.getFamily());
        osInfo.put(FIELD_MANUFACTURER, os.getManufacturer());
        osInfo.put(FIELD_VERSION, os.getVersionInfo().toString());
        osInfo.put(FIELD_BITNESS, os.getBitness() + "-bit");
        envData.put(JSON_KEY_OS, osInfo);
        
        // CPU information
        Map<String, Object> cpuInfo = new HashMap<>();
        cpuInfo.put(FIELD_MODEL, cpu.getProcessorIdentifier().getName());
        cpuInfo.put(FIELD_VENDOR, cpu.getProcessorIdentifier().getVendor());
        cpuInfo.put(FIELD_PHYSICAL_CORES, cpu.getPhysicalProcessorCount());
        cpuInfo.put(FIELD_LOGICAL_CORES, cpu.getLogicalProcessorCount());
        cpuInfo.put(FIELD_MAX_FREQ_HZ, cpu.getMaxFreq());
        envData.put(JSON_KEY_CPU, cpuInfo);
        
        // Memory information
        Map<String, Object> memoryInfo = new HashMap<>();
        memoryInfo.put(FIELD_TOTAL_BYTES, memory.getTotal());
        memoryInfo.put(FIELD_TOTAL_GB, String.format("%.2f", memory.getTotal() / (1024.0 * 1024.0 * 1024.0)));
        envData.put(JSON_KEY_MEMORY, memoryInfo);
        
        // Java information
        Map<String, String> javaInfo = new HashMap<>();
        javaInfo.put(FIELD_VERSION, System.getProperty("java.version"));
        javaInfo.put(FIELD_VENDOR, System.getProperty("java.vendor"));
        javaInfo.put(FIELD_RUNTIME, System.getProperty("java.runtime.name"));
        javaInfo.put(FIELD_VM, System.getProperty("java.vm.name"));
        javaInfo.put(FIELD_VM_VERSION, System.getProperty("java.vm.version"));
        
        // JVM flags
        try {
            String jvmArgs = System.getProperty("java.vm.info");
            if (jvmArgs == null) {
                jvmArgs = java.lang.management.ManagementFactory.getRuntimeMXBean()
                    .getInputArguments().toString();
            }
            javaInfo.put(FIELD_JVM_FLAGS, jvmArgs);
        } catch (Exception e) {
            javaInfo.put(FIELD_JVM_FLAGS, NOT_AVAILABLE);
        }
        envData.put(JSON_KEY_JAVA, javaInfo);
        
        // Git information
        Map<String, String> gitInfo = new HashMap<>();
        try {
            String gitCommit = executeCommand("git rev-parse HEAD");
            String gitBranch = executeCommand("git rev-parse --abbrev-ref HEAD");
            String gitStatus = executeCommand("git status --porcelain");
            
            gitInfo.put(FIELD_COMMIT, gitCommit);
            gitInfo.put(FIELD_BRANCH, gitBranch);
            gitInfo.put(FIELD_IS_DIRTY, Boolean.toString(!gitStatus.isEmpty()));
        } catch (Exception e) {
            logger.warn("Could not get git information: {}", e.getMessage());
            gitInfo.put(FIELD_COMMIT, NOT_AVAILABLE);
            gitInfo.put(FIELD_BRANCH, NOT_AVAILABLE);
            gitInfo.put(FIELD_IS_DIRTY, NOT_AVAILABLE);
        }
        envData.put(JSON_KEY_GIT, gitInfo);
        
        // Dependency versions (from build.gradle - update when dependencies change)
        Map<String, String> dependencies = new HashMap<>();
        dependencies.put("postgresql-driver", "42.7.11");
        dependencies.put("hikaricp", "5.1.0");
        dependencies.put("hdrhistogram", "2.1.12");
        dependencies.put("oshi", "6.4.10");
        dependencies.put("picocli", "4.7.5");
        dependencies.put("slf4j", "2.0.9");
        envData.put(JSON_KEY_DEPENDENCIES, dependencies);
        
        // Timestamp
        envData.put(JSON_KEY_TIMESTAMP, timestamp);
        envData.put(JSON_KEY_CAPTURED_AT, LocalDateTime.now().toString());
        
        // Write JSON
        Path jsonPath = outputPath.resolve(FILE_SNAPSHOT_JSON);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(envData);
        Files.write(jsonPath, json.getBytes());
        logger.info("Environment snapshot written to: {}", jsonPath);
        
        // Write Markdown
        Path mdPath = outputPath.resolve(FILE_SNAPSHOT_MD);
        String markdown = generateMarkdown(envData);
        Files.write(mdPath, markdown.getBytes());
        logger.info("Environment snapshot markdown written to: {}", mdPath);
        
        return 0;
    }
    
    private String executeCommand(String command) throws Exception {
        Process process = Runtime.getRuntime().exec(command);
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        StringBuilder output = new StringBuilder();
        String line;
        
        while ((line = reader.readLine()) != null) {
            output.append(line).append("\n");
        }
        
        process.waitFor();
        return output.toString().trim();
    }
    
    @SuppressWarnings("unchecked")
    private String generateMarkdown(Map<String, Object> envData) {
        StringBuilder md = new StringBuilder();
        
        md.append("# Environment Snapshot\n\n");
        md.append("**Generated:** ").append(envData.get(JSON_KEY_CAPTURED_AT)).append("\n\n");
        
        // Operating System
        md.append("## Operating System\n\n");
        Map<String, String> osInfo = (Map<String, String>) envData.get(JSON_KEY_OS);
        md.append("- **Family:** ").append(osInfo.get(FIELD_FAMILY)).append("\n");
        md.append("- **Manufacturer:** ").append(osInfo.get(FIELD_MANUFACTURER)).append("\n");
        md.append("- **Version:** ").append(osInfo.get(FIELD_VERSION)).append("\n");
        md.append("- **Bitness:** ").append(osInfo.get(FIELD_BITNESS)).append("\n\n");
        
        // CPU
        md.append("## CPU\n\n");
        Map<String, Object> cpuInfo = (Map<String, Object>) envData.get(JSON_KEY_CPU);
        md.append("- **Model:** ").append(cpuInfo.get(FIELD_MODEL)).append("\n");
        md.append("- **Vendor:** ").append(cpuInfo.get(FIELD_VENDOR)).append("\n");
        md.append("- **Physical Cores:** ").append(cpuInfo.get(FIELD_PHYSICAL_CORES)).append("\n");
        md.append("- **Logical Cores:** ").append(cpuInfo.get(FIELD_LOGICAL_CORES)).append("\n");
        md.append("- **Max Frequency:** ").append(cpuInfo.get(FIELD_MAX_FREQ_HZ)).append(" Hz\n\n");
        
        // Memory
        md.append("## Memory\n\n");
        Map<String, Object> memoryInfo = (Map<String, Object>) envData.get(JSON_KEY_MEMORY);
        md.append("- **Total:** ").append(memoryInfo.get(FIELD_TOTAL_GB)).append(" GB\n\n");
        
        // Java
        md.append("## Java\n\n");
        Map<String, String> javaInfo = (Map<String, String>) envData.get(JSON_KEY_JAVA);
        md.append("- **Version:** ").append(javaInfo.get(FIELD_VERSION)).append("\n");
        md.append("- **Vendor:** ").append(javaInfo.get(FIELD_VENDOR)).append("\n");
        md.append("- **Runtime:** ").append(javaInfo.get(FIELD_RUNTIME)).append("\n");
        md.append("- **VM:** ").append(javaInfo.get(FIELD_VM)).append(" (").append(javaInfo.get(FIELD_VM_VERSION)).append(")\n");
        md.append("- **JVM Flags:** `").append(javaInfo.get(FIELD_JVM_FLAGS)).append("`\n\n");
        
        // Git
        md.append("## Git\n\n");
        Map<String, String> gitInfo = (Map<String, String>) envData.get(JSON_KEY_GIT);
        md.append("- **Commit:** ").append(gitInfo.get(FIELD_COMMIT)).append("\n");
        md.append("- **Branch:** ").append(gitInfo.get(FIELD_BRANCH)).append("\n");
        md.append("- **Dirty:** ").append(gitInfo.get(FIELD_IS_DIRTY)).append("\n\n");
        
        // Dependencies
        md.append("## Dependencies\n\n");
        Map<String, String> deps = (Map<String, String>) envData.get(JSON_KEY_DEPENDENCIES);
        for (Map.Entry<String, String> entry : deps.entrySet()) {
            md.append("- **").append(entry.getKey()).append(":** ").append(entry.getValue()).append("\n");
        }
        md.append("\n");
        
        // Placeholders for manual entry
        md.append("## Database Configuration\n\n");
        md.append("### PostgreSQL\n\n");
        md.append(MD_CODE_FENCE);
        md.append("# Paste relevant postgresql.conf settings here:\n");
        md.append("# max_connections = \n");
        md.append("# shared_buffers = \n");
        md.append("# effective_cache_size = \n");
        md.append("# work_mem = \n");
        md.append("# maintenance_work_mem = \n");
        md.append(MD_PLACEHOLDER_ETC);
        md.append(MD_CODE_FENCE_END_SECTION);
        
        md.append("### PgBouncer\n\n");
        md.append(MD_CODE_FENCE);
        md.append("# Paste relevant pgbouncer.ini settings here:\n");
        md.append("# pool_mode = \n");
        md.append("# max_client_conn = \n");
        md.append("# default_pool_size = \n");
        md.append(MD_PLACEHOLDER_ETC);
        md.append(MD_CODE_FENCE_END_SECTION);
        
        md.append("## Application Configuration\n\n");
        md.append(MD_CODE_FENCE);
        md.append("# Paste benchmark configuration (YAML) here\n");
        md.append(MD_CODE_FENCE);
        
        return md.toString();
    }
}
