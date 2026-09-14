package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Persistence mirror of the {@code Payment} aggregate.
 *
 * <p>Kept separate from the domain model on purpose: JPA needs a no-arg constructor, mutable fields,
 * and its own annotations, none of which belong in an aggregate that protects its invariants.
 * {@code PaymentEntityMapper} translates between the two.
 */
@Entity
@Table(name = "payments")
public class PaymentJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    @Column(name = "provider_reference", length = 255)
    private String providerReference;

    @Column(name = "authorized_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal authorizedAmount;

    @Column(name = "captured_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal capturedAmount;

    @Column(name = "refunded_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal refundedAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "payment_method_token", nullable = false, updatable = false, length = 255)
    private String paymentMethodToken;

    @Column(name = "capture_mode", nullable = false, updatable = false, length = 16)
    private String captureMode;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    @Column(name = "reconciliation_attempts", nullable = false)
    private int reconciliationAttempts;

    @Column(name = "pending_since")
    private Instant pendingSince;

    @Column(name = "pending_refund_amount", precision = 19, scale = 4)
    private BigDecimal pendingRefundAmount;

    @Column(name = "pending_refund_reason", length = 512)
    private String pendingRefundReason;

    @Column(name = "refund_attempt_idempotency_key", length = 255)
    private String refundAttemptIdempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Guards against a stale write overwriting a concurrent update to the same payment. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected PaymentJpaEntity() {
        // required by JPA
    }

    /** Creates a row for a new payment. The fields taken here never change afterwards. */
    public PaymentJpaEntity(
            UUID id,
            String idempotencyKey,
            String paymentMethodToken,
            String captureMode,
            String currency,
            BigDecimal authorizedAmount,
            Instant createdAt) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.paymentMethodToken = paymentMethodToken;
        this.captureMode = captureMode;
        this.currency = currency;
        this.authorizedAmount = authorizedAmount;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public void setProviderReference(String providerReference) {
        this.providerReference = providerReference;
    }

    public BigDecimal getAuthorizedAmount() {
        return authorizedAmount;
    }

    public BigDecimal getCapturedAmount() {
        return capturedAmount;
    }

    public void setCapturedAmount(BigDecimal capturedAmount) {
        this.capturedAmount = capturedAmount;
    }

    public BigDecimal getRefundedAmount() {
        return refundedAmount;
    }

    public void setRefundedAmount(BigDecimal refundedAmount) {
        this.refundedAmount = refundedAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getPaymentMethodToken() {
        return paymentMethodToken;
    }

    public String getCaptureMode() {
        return captureMode;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public int getReconciliationAttempts() {
        return reconciliationAttempts;
    }

    public void setReconciliationAttempts(int reconciliationAttempts) {
        this.reconciliationAttempts = reconciliationAttempts;
    }

    public Instant getPendingSince() {
        return pendingSince;
    }

    public void setPendingSince(Instant pendingSince) {
        this.pendingSince = pendingSince;
    }

    public BigDecimal getPendingRefundAmount() {
        return pendingRefundAmount;
    }

    public void setPendingRefundAmount(BigDecimal pendingRefundAmount) {
        this.pendingRefundAmount = pendingRefundAmount;
    }

    public String getPendingRefundReason() {
        return pendingRefundReason;
    }

    public void setPendingRefundReason(String pendingRefundReason) {
        this.pendingRefundReason = pendingRefundReason;
    }

    public String getRefundAttemptIdempotencyKey() {
        return refundAttemptIdempotencyKey;
    }

    public void setRefundAttemptIdempotencyKey(String refundAttemptIdempotencyKey) {
        this.refundAttemptIdempotencyKey = refundAttemptIdempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
