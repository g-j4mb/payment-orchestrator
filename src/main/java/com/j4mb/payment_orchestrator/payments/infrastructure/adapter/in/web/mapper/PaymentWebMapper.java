package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.in.web.mapper;

import com.j4mb.payment_orchestrator.payments.application.command.AuthorizePaymentCommand;
import com.j4mb.payment_orchestrator.payments.application.command.CapturePaymentCommand;
import com.j4mb.payment_orchestrator.payments.application.command.RefundPaymentCommand;
import com.j4mb.payment_orchestrator.payments.application.command.VoidPaymentCommand;
import com.j4mb.payment_orchestrator.payments.application.dto.PaymentResult;
import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.vo.IdempotencyKey;
import com.j4mb.payment_orchestrator.payments.domain.vo.Money;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import com.j4mb.payment_orchestrator.payments.infrastructure.adapter.in.web.dto.CaptureRequest;
import com.j4mb.payment_orchestrator.payments.infrastructure.adapter.in.web.dto.PaymentRequest;
import com.j4mb.payment_orchestrator.payments.infrastructure.adapter.in.web.dto.PaymentResponse;
import com.j4mb.payment_orchestrator.payments.infrastructure.adapter.in.web.dto.RefundRequest;
import com.j4mb.payment_orchestrator.payments.infrastructure.adapter.in.web.dto.VoidRequest;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Translates between the HTTP contract and the application layer's commands and results.
 *
 * <p>Keeping this here means the API's shape can change without touching a use case, and the
 * application layer never sees an HTTP type.
 */
@Component
public class PaymentWebMapper {

    public AuthorizePaymentCommand toCommand(PaymentRequest request, String idempotencyKey) {
        return new AuthorizePaymentCommand(
                ProviderType.from(request.provider()),
                Money.of(request.amount(), request.currency().toUpperCase(Locale.ROOT)),
                new IdempotencyKey(idempotencyKey),
                parseCaptureMode(request.captureMode()),
                request.paymentMethodToken(),
                request.description());
    }

    /**
     * A partial amount is only meaningful alongside the payment's currency, so the controller reads
     * the payment first and passes its currency in.
     */
    public CapturePaymentCommand toCommand(
            PaymentId paymentId, CaptureRequest request, String idempotencyKey, Currency currency) {
        return new CapturePaymentCommand(
                paymentId, toMoney(request.amount(), currency), new IdempotencyKey(idempotencyKey));
    }

    public RefundPaymentCommand toCommand(
            PaymentId paymentId, RefundRequest request, String idempotencyKey, Currency currency) {
        return new RefundPaymentCommand(
                paymentId, toMoney(request.amount(), currency), new IdempotencyKey(idempotencyKey), request.reason());
    }

    public VoidPaymentCommand toCommand(PaymentId paymentId, VoidRequest request, String idempotencyKey) {
        return new VoidPaymentCommand(
                paymentId, new IdempotencyKey(idempotencyKey), request == null ? null : request.reason());
    }

    public PaymentResponse toResponse(PaymentResult result) {
        return new PaymentResponse(
                result.paymentId(),
                result.provider().name(),
                result.providerReference(),
                result.authorizedAmount().amount(),
                result.capturedAmount().amount(),
                result.refundedAmount().amount(),
                result.authorizedAmount().currency().getCurrencyCode(),
                result.status().name(),
                result.failureReason(),
                result.createdAt(),
                result.updatedAt());
    }

    private Optional<Money> toMoney(BigDecimal amount, Currency currency) {
        return Optional.ofNullable(amount).map(value -> new Money(value, currency));
    }

    private AuthorizePaymentCommand.CaptureMode parseCaptureMode(String value) {
        if (value == null || value.isBlank()) {
            return AuthorizePaymentCommand.CaptureMode.MANUAL;
        }
        try {
            return AuthorizePaymentCommand.CaptureMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("captureMode must be AUTOMATIC or MANUAL, got: " + value, ex);
        }
    }
}