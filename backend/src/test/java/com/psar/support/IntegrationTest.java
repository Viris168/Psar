package com.psar.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.psar.TestcontainersConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base for integration tests: full app + real PostgreSQL (Testcontainers) + MockMvc.
 * All subclasses share one cached Spring context and one container, so don't add
 * per-class @MockitoBean / @TestPropertySource (each variation starts a new context).
 * <p>
 * Tests don't roll back: create your own data with unique emails, don't assume an empty table.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, CatalogFixtures.class})
public abstract class IntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    protected String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }
}
