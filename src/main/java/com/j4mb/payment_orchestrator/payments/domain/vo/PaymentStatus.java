package com.j4mb.payment_orchestrator.payments.domain.vo;

/** Lifecycle state of a payment. */
public enum PaymentStatus {

    /** Created locally; not yet sent to a provider. */
    CREATED,

    /**
     * Sent to the provider; the outcome is not yet known — the call may have thrown before a
     * response arrived, and the provider may or may not have processed it. Resolved by webhook
     * correlation or, failing that, the reconciliation job; never a state a caller chooses.
     */
    AUTHORIZATION_PENDING,

    /** Funds reserved at the provider, not yet taken. */
    AUTHORIZED,

    /** The full authorized amount has been taken. */
    CAPTURED,

    /** Part of the authorized amount has been taken; more may still be captured. */
    PARTIALLY_CAPTURED,

    /**
     * A refund was sent to the provider; the outcome is not yet known. Blocks a further refund
     * attempt until resolved — see {@link #isRefundable()}.
     */
    REFUND_PENDING,

    /** The full captured amount has been returned to the payer. */
    REFUNDED,

    /** Part of the captured amount has been returned to the payer. */
    PARTIALLY_REFUNDED,

    /** The authorization was released before capture. */
    VOIDED,

    /** The provider rejected the payment. */
    FAILED;

    /** Whether money can still be captured in this state. */
    public boolean isCapturable() {
        return this == AUTHORIZED || this == PARTIALLY_CAPTURED;
    }

    /** Whether money can still be refunded in this state. */
    public boolean isRefundable() {
        return this == CAPTURED || this == PARTIALLY_CAPTURED || this == PARTIALLY_REFUNDED;
    }

    /** Whether the authorization can still be released. */
    public boolean isVoidable() {
        return this == AUTHORIZED;
    }

    /** Whether no further transitions are possible. */
    public boolean isTerminal() {
        return this == REFUNDED || this == VOIDED || this == FAILED;
    }
}