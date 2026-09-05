package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

/** Request body for creating a payment. */
@Schema(description = "Creates a payment at the chosen provider.")
public record PaymentRequest(
        @Schema(description = "Payment provider to route to.", example = "STRIPE")
                @NotBlank(message = "provider is required")
                String provider,
        @Schema(description = "Amount to authorize.", example = "49.99")
                @NotNull(message = "amount is required")
                @DecimalMin(value = "0.01", message = "amount must be greater than zero")
                BigDecimal amount,
        @Schema(description = "ISO-4217 currency code.", example = "USD")
                @NotBlank(message = "currency is required")
                @Pattern(regexp = "^[A-Za-z]{3}$", message = "currency must be a 3-letter ISO-4217 code")
                String currency,
        @Schema(description = "Tokenized payment method from the provider's client SDK.")
                @NotBlank(message = "paymentMethodToken is required")
                String paymentMethodToken,
        @Schema(
                        description = "AUTOMATIC captures immediately (purchase); MANUAL authorizes only.",
                        example = "MANUAL",
                        allowableValues = {"AUTOMATIC", "MANUAL"})
                String captureMode,
        @Schema(description = "Free-text description stored with the payment.") String description) {}