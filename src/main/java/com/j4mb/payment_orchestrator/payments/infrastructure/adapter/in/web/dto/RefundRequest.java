package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

/** Request body for refunding a captured payment. */
@Schema(description = "Refunds a captured payment, fully or in part.")
public record RefundRequest(
        @Schema(description = "Amount to refund. Omit to refund everything still refundable.")
                @DecimalMin(value = "0.01", message = "amount must be greater than zero")
                BigDecimal amount,
        @Schema(description = "Reason recorded with the refund.") String reason) {}