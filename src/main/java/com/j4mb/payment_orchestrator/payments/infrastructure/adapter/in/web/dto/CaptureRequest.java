package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

/** Request body for capturing an authorized payment. */
@Schema(description = "Captures an authorized payment, fully or in part.")
public record CaptureRequest(
        @Schema(description = "Amount to capture. Omit to capture the full authorized amount.")
                @DecimalMin(value = "0.01", message = "amount must be greater than zero")
                BigDecimal amount) {}