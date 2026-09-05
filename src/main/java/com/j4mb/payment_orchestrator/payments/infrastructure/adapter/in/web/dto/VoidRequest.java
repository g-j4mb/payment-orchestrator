package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Request body for voiding an authorization. */
@Schema(description = "Releases an authorization before capture.")
public record VoidRequest(@Schema(description = "Reason recorded with the void.") String reason) {}