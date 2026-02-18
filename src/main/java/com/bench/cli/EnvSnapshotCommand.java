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
        osInfo.put("family", os.getFamily());
        osInfo.put("manufacturer", os.getManufacturer());
        osInfo.put("version", os.getVersionInfo().toString());
        osInfo.put("bitness", os.getBitness() + "-bit");
        envData.put("os", osInfo);
        
        // CPU information
        Map<String, Object> cpuInfo = new HashMap<>();
        cpuInfo.put("model", cpu.getProcessorIdentifier().getName());
        cpuInfo.put("vendor", cpu.getProcessorIdentifier().getVendor());
        cpuInfo.put("physicalCores", cpu.getPhysicalProcessorCount());
        cpuInfo.put("logicalCores", cpu.getLogicalProcessorCount());
        cpuInfo.put("maxFreqHz", cpu.getMaxFreq());
        envData.put("cpu", cpuInfo);
        
        // Memory information
        Map<String, Object> memoryInfo = new HashMap<>();
        memoryInfo.put("totalBytes", memory.getTotal());
        memoryInfo.put("totalGB", String.format("%.2f", memory.getTotal() / (1024.0 * 1024.0 * 1024.0)));
        envData.put("memory", memoryInfo);
        
        // Java information
        Map<String, String> javaInfo = new HashMap<>();
        javaInfo.put("version", System.getProperty("java.version"));
        javaInfo.put("vendor", System.getProperty("java.vendor"));
        javaInfo.put("runtime", System.getProperty("java.runtime.name"));
        javaInfo.put("vm", System.getProperty("java.vm.name"));
        javaInfo.put("vmVersion", System.getProperty("java.vm.version"));
        
        // JVM flags
        try {
            String jvmArgs = System.getProperty("java.vm.info");
            if (jvmArgs == null) {
                jvmArgs = java.lang.management.ManagementFactory.getRuntimeMXBean()
                    .getInputArguments().toString();
            }
            javaInfo.put("jvmFlags", jvmArgs);
        } catch (Exception e) {
            javaInfo.put("jvmFlags", "N/A");
        }
        envData.put("java", javaInfo);
        
        // Git information
        Map<String, String> gitInfo = new HashMap<>();
        try {
            String gitCommit = executeCommand("git rev-parse HEAD");
            String gitBranch = executeCommand("git rev-parse --abbrev-ref HEAD");
            String gitStatus = executeCommand("git status --porcelain");
            
            gitInfo.put("commit", gitCommit);
            gitInfo.put("branch", gitBranch);
            gitInfo.put("isDirty", !gitStatus.isEmpty() ? "true" : "false");
        } catch (Exception e) {
            logger.warn("Could not get git information: {}", e.getMessage());
            gitInfo.put("commit", "N/A");
            gitInfo.put("branch", "N/A");
            gitInfo.put("isDirty", "N/A");
        }
        envData.put("git", gitInfo);
        
        // Dependency versions
        Map<String, String> dependencies = new HashMap<>();
        dependencies.put("postgresql-driver", "42.7.1");
        dependencies.put("hikaricp", "5.1.0");
        dependencies.put("hdrhistogram", "2.1.12");
        dependencies.put("oshi", "6.4.10");
        dependencies.put("picocli", "4.7.5");
        dependencies.put("slf4j", "2.0.9");
        envData.put("dependencies", dependencies);
        
        // Timestamp
        envData.put("timestamp", timestamp);
        envData.put("capturedAt", LocalDateTime.now().toString());
        
        // Write JSON
        Path jsonPath = outputPath.resolve("snapshot.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(envData);
        Files.write(jsonPath, json.getBytes());
        logger.info("Environment snapshot written to: {}", jsonPath);
        
        // Write Markdown
        Path mdPath = outputPath.resolve("snapshot.md");
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
        md.append("**Generated:** ").append(envData.get("capturedAt")).append("\n\n");
        
        // Operating System
        md.append("## Operating System\n\n");
        Map<String, String> osInfo = (Map<String, String>) envData.get("os");
        md.append("- **Family:** ").append(osInfo.get("family")).append("\n");
        md.append("- **Manufacturer:** ").append(osInfo.get("manufacturer")).append("\n");
        md.append("- **Version:** ").append(osInfo.get("version")).append("\n");
        md.append("- **Bitness:** ").append(osInfo.get("bitness")).append("\n\n");
        
        // CPU
        md.append("## CPU\n\n");
        Map<String, Object> cpuInfo = (Map<String, Object>) envData.get("cpu");
        md.append("- **Model:** ").append(cpuInfo.get("model")).append("\n");
        md.append("- **Vendor:** ").append(cpuInfo.get("vendor")).append("\n");
        md.append("- **Physical Cores:** ").append(cpuInfo.get("physicalCores")).append("\n");
        md.append("- **Logical Cores:** ").append(cpuInfo.get("logicalCores")).append("\n");
        md.append("- **Max Frequency:** ").append(cpuInfo.get("maxFreqHz")).append(" Hz\n\n");
        
        // Memory
        md.append("## Memory\n\n");
        Map<String, Object> memoryInfo = (Map<String, Object>) envData.get("memory");
        md.append("- **Total:** ").append(memoryInfo.get("totalGB")).append(" GB\n\n");
        
        // Java
        md.append("## Java\n\n");
        Map<String, String> javaInfo = (Map<String, String>) envData.get("java");
        md.append("- **Version:** ").append(javaInfo.get("version")).append("\n");
        md.append("- **Vendor:** ").append(javaInfo.get("vendor")).append("\n");
        md.append("- **Runtime:** ").append(javaInfo.get("runtime")).append("\n");
        md.append("- **VM:** ").append(javaInfo.get("vm")).append(" (").append(javaInfo.get("vmVersion")).append(")\n");
        md.append("- **JVM Flags:** `").append(javaInfo.get("jvmFlags")).append("`\n\n");
        
        // Git
        md.append("## Git\n\n");
        Map<String, String> gitInfo = (Map<String, String>) envData.get("git");
        md.append("- **Commit:** ").append(gitInfo.get("commit")).append("\n");
        md.append("- **Branch:** ").append(gitInfo.get("branch")).append("\n");
        md.append("- **Dirty:** ").append(gitInfo.get("isDirty")).append("\n\n");
        
        // Dependencies
        md.append("## Dependencies\n\n");
        Map<String, String> deps = (Map<String, String>) envData.get("dependencies");
        for (Map.Entry<String, String> entry : deps.entrySet()) {
            md.append("- **").append(entry.getKey()).append(":** ").append(entry.getValue()).append("\n");
        }
        md.append("\n");
        
        // Placeholders for manual entry
        md.append("## Database Configuration\n\n");
        md.append("### PostgreSQL\n\n");
        md.append("```\n");
        md.append("# Paste relevant postgresql.conf settings here:\n");
        md.append("# max_connections = \n");
        md.append("# shared_buffers = \n");
        md.append("# effective_cache_size = \n");
        md.append("# work_mem = \n");
        md.append("# maintenance_work_mem = \n");
        md.append("# etc.\n");
        md.append("```\n\n");
        
        md.append("### PgBouncer\n\n");
        md.append("```\n");
        md.append("# Paste relevant pgbouncer.ini settings here:\n");
        md.append("# pool_mode = \n");
        md.append("# max_client_conn = \n");
        md.append("# default_pool_size = \n");
        md.append("# etc.\n");
        md.append("```\n\n");
        
        md.append("## Application Configuration\n\n");
        md.append("```\n");
        md.append("# Paste benchmark configuration (YAML) here\n");
        md.append("```\n");
        
        return md.toString();
    }
}
