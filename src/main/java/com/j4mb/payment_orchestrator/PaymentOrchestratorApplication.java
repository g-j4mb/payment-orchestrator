package com.j4mb.payment_orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Composition root. Component scanning from here wires the adapters in {@code
 * payments.infrastructure} to the ports they implement in {@code payments.application}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PaymentOrchestratorApplication {

	public static void main(String[] args) {
		SpringApplication.run(PaymentOrchestratorApplication.class, args);
	}

}
