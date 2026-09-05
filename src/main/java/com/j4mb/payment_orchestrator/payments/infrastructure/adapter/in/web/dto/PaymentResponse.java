package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** API representation of a payment. */
@Schema(description = "Current state of a payment.")
public record PaymentResponse(
        @Schema(description = "Orchestrator-assigned payment id.") UUID id,
        @Schema(description = "Provider the payment was routed to.", example = "STRIPE") String provider,
        @Schema(description = "The provider's own identifier for this payment.") String providerReference,
        @Schema(description = "Amount authorized.") BigDecimal authorizedAmount,
        @Schema(description = "Amount captured so far.") BigDecimal capturedAmount,
        @Schema(description = "Amount refunded so far.") BigDecimal refundedAmount,
        @Schema(description = "ISO-4217 currency code.", example = "USD") String currency,
        @Schema(description = "Lifecycle state.", example = "AUTHORIZED") String status,
        @Schema(description = "Why the provider rejected the payment, if it did.") String failureReason,
        @Schema(description = "When the payment was created.") Instant createdAt,
        @Schema(description = "When the payment last changed.") Instant updatedAt) {}