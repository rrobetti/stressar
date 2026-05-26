package com.bench.cli;

import com.bench.config.BenchmarkConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;

import static org.junit.Assert.assertEquals;

public class ConfigLoaderTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void loadFromYamlParsesTopLevelConnectionTimeout() throws Exception {
        File yaml = temp.newFile("benchmark.yaml");
        try (FileWriter writer = new FileWriter(yaml)) {
            writer.write(
                "database:\n" +
                "  jdbcUrl: \"jdbc:postgresql://localhost:5432/benchdb\"\n" +
                "  username: \"benchuser\"\n" +
                "  password: \"benchpass\"\n" +
                "connectionMode: HIKARI_DISCIPLINED\n" +
                "dbConnectionBudget: 48\n" +
                "replicas: 4\n" +
                "connectionTimeout: 12345\n" +
                "workload:\n" +
                "  type: W1_READ_ONLY\n"
            );
        }

        BenchmarkConfig config = ConfigLoader.loadFromYaml(yaml.getAbsolutePath());

        assertEquals(12345, config.getConnectionTimeout());
    }
}
