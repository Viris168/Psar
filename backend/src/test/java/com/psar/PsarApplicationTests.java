package com.psar;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Starts the full app against a real PostgreSQL: proves Flyway migrations run
 * and JPA entities match the schema (ddl-auto=validate).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PsarApplicationTests {

    @Test
    void contextLoads() {
    }
}
