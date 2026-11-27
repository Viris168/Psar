package com.psar;

import com.psar.support.InMemoryFileStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL (with pgvector) for integration tests, plus an in-memory file store instead of Cloudinary.
 * Requires Docker.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    }

    /** Cloudinary has no local emulator; uploads in tests never leave the JVM. */
    @Bean
    @Primary
    InMemoryFileStore inMemoryFileStore() {
        return new InMemoryFileStore();
    }
}
