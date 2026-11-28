package com.psar.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.psar.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base for integration tests: full app + real PostgreSQL (Testcontainers) + MockMvc.
 * All subclasses share one cached Spring context and one container, so don't add
 * per-class @MockitoBean / @TestPropertySource (each variation starts a new context).
 * <p>
 * Tests don't roll back: create your own data with unique emails, don't assume an empty table.
 * Exception: every promotion is deactivated after each test, because an automatic shop-wide one (ALL products,
 * free delivery, …) would otherwise change the prices of every later checkout test.
 */
@SpringBootTest(properties = "app.scheduling.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, CatalogFixtures.class, SystemFixtures.class, OrderFixtures.class,
        PaymentFixtures.class, PromotionFixtures.class})
public abstract class IntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    /** The app's Clock: {@code clock.advance(...)} to move time; reset after every test. */
    @Autowired
    protected TestClock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @AfterEach
    void deactivatePromotions() {
        jdbcTemplate.update("update promotions set is_active = false where is_active");
    }

    protected String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }
}
