package com.j4mb.payment_orchestrator;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for tests that need a real Postgres — anything exercising Postgres-specific schema
 * features (partial unique indexes, {@code TIMESTAMPTZ}) that an embedded database could not
 * validate.
 *
 * <p>The container is a singleton shared by every subclass in this JVM, started once in a static
 * initializer rather than through {@code @Testcontainers}' per-class lifecycle — the latter would
 * start and stop a fresh container for each test class instead of reusing one. It is never stopped
 * explicitly; Testcontainers' Ryuk reaper container removes it when the JVM exits.
 */
public abstract class AbstractPostgresIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    static {
        POSTGRES.start();
    }
}
