package com.j4mb.payment_orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Composition root. Component scanning from here wires the adapters in {@code
 * payments.infrastructure} to the ports they implement in {@code payments.application}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PaymentOrchestratorApplication {

	public static void main(String[] args) {
		SpringApplication.run(PaymentOrchestratorApplication.class, args);
	}

}
