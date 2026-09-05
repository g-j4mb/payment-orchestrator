package com.j4mb.payment_orchestrator.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the {@link Clock} the application layer reads time from.
 *
 * <p>Use cases take a {@code Clock} rather than calling {@code Instant.now()} so their timestamps can
 * be fixed in tests.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}