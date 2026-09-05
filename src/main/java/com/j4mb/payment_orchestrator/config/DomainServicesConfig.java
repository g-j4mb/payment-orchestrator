package com.j4mb.payment_orchestrator.config;

import com.j4mb.payment_orchestrator.payments.domain.service.RefundPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers domain services as beans.
 *
 * <p>Domain classes carry no Spring annotations — the composition root wires them instead, which is
 * what keeps the domain layer framework-free.
 */
@Configuration
public class DomainServicesConfig {

    @Bean
    public RefundPolicy refundPolicy() {
        return new RefundPolicy();
    }
}