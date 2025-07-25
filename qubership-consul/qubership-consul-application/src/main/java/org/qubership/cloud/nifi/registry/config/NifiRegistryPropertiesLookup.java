package org.qubership.cloud.nifi.registry.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@SpringBootApplication
@EnableAutoConfiguration
@ComponentScan(basePackages = "org.qubership.cloud.nifi.registry.config")
public class NifiRegistryPropertiesLookup implements CommandLineRunner {
    private static final Logger LOG = LoggerFactory.getLogger(NifiRegistryPropertiesLookup.class);

    @Autowired
    private PropertiesManager propertiesManager;

    @Value("${config.notify-completion.path}")
    private String path;

    /**
     * Main application entrypoint.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(NifiRegistryPropertiesLookup.class, args);
    }

    /**
     * Generating a property file.
     * @throws IOException
     * @throws ParserConfigurationException
     * @throws TransformerException
     * @throws SAXException
     */
    @Override
    public void run(String... args) throws IOException, ParserConfigurationException, TransformerException,
            SAXException {
        propertiesManager.generateNifiRegistryProperties();
        notifyCompletionToStartScript();
    }

    private void notifyCompletionToStartScript() {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            Path fPath = Paths.get(path + "initial-config-completed.txt");
            Files.write(fPath, timestamp.getBytes());
            LOG.info("Consul App completion file created:{} ", fPath.toAbsolutePath());
        } catch (Exception e) {
            LOG.error("Error while creating completion file for consul app", e);
        }
    }

}
