package com.j4mb.payment_orchestrator.payments.application.exception;

import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;

/** Raised when no gateway adapter is registered for the requested provider. */
public class UnsupportedProviderException extends RuntimeException {

    public UnsupportedProviderException(ProviderType provider) {
        super("no gateway adapter is registered for provider: " + provider);
    }
}