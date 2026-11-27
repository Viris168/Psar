package com.psar;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL (with pgvector) and MinIO (S3 API) for integration tests. Requires Docker.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    static final String BUCKET = "psar-test";

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    }

    /** minio/minio is no longer published on Docker Hub; pgsty/minio is a maintained community build of it. */
    @Bean
    MinIOContainer minioContainer() {
        return new MinIOContainer(DockerImageName.parse("pgsty/minio:RELEASE.2026-08-04T00-00-00Z")
                .asCompatibleSubstituteFor("minio/minio"));
    }

    /** Points app.storage.* at the container; the bucket is created on startup (StorageConfig). */
    @Bean
    DynamicPropertyRegistrar storageProperties(MinIOContainer minio) {
        return registry -> {
            registry.add("app.storage.endpoint", minio::getS3URL);
            registry.add("app.storage.access-key", minio::getUserName);
            registry.add("app.storage.secret-key", minio::getPassword);
            registry.add("app.storage.bucket", () -> BUCKET);
            registry.add("app.storage.public-url", () -> minio.getS3URL() + "/" + BUCKET);
            registry.add("app.storage.create-bucket", () -> "true");
        };
    }
}
