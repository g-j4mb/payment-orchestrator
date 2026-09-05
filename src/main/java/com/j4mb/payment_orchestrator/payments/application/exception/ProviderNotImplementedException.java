package com.j4mb.payment_orchestrator.payments.application.exception;

import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;

/**
 * Raised by a gateway adapter that is registered but not yet built out.
 *
 * <p>Distinct from {@link UnsupportedProviderException}: the provider is known and wired, its
 * integration simply has not landed yet.
 */
public class ProviderNotImplementedException extends RuntimeException {

    public ProviderNotImplementedException(ProviderType provider, String operation) {
        super("%s does not support '%s' yet".formatted(provider, operation));
    }
}