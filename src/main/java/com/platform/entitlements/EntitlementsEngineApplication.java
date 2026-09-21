/** Starts the Spring Boot application and enables component scanning for the entitlements engine. */
package com.platform.entitlements;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;

@SpringBootApplication(exclude = FlywayAutoConfiguration.class)
// Flyway autoconfiguration excluded because FlywayConfig manually wires
// Flyway against the raw (non-tenant-wrapped) DataSource — see its javadoc.
public class EntitlementsEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(EntitlementsEngineApplication.class, args);
    }
}
