package com.psar.common.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * Time and scheduled jobs. Every {@code @Scheduled} job also has {@code @SchedulerLock}, so only one instance runs it
 * (CLAUDE.md). {@code app.scheduling.enabled=false} turns the schedule off (tests call job methods directly).
 */
@Configuration
public class SchedulingConfig {

    /** Inject this instead of calling Instant.now(), so tests can move time. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Locks in the shedlock table (V4), using the database clock so instances don't need synced clocks. */
    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }

    @Configuration
    @EnableScheduling
    @EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
    @ConditionalOnProperty(prefix = "app.scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class Enabled {
    }
}
