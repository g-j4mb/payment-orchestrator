package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.in.web;

import com.j4mb.payment_orchestrator.payments.application.exception.IdempotencyConflictException;
import com.j4mb.payment_orchestrator.payments.application.exception.PaymentDeclinedException;
import com.j4mb.payment_orchestrator.payments.application.exception.PaymentNotFoundException;
import com.j4mb.payment_orchestrator.payments.application.exception.ProviderNotImplementedException;
import com.j4mb.payment_orchestrator.payments.application.exception.UnsupportedProviderException;
import com.j4mb.payment_orchestrator.payments.application.exception.WebhookVerificationException;
import com.j4mb.payment_orchestrator.payments.domain.exception.InvalidCaptureAmountException;
import com.j4mb.payment_orchestrator.payments.domain.exception.InvalidPaymentStateTransitionException;
import com.j4mb.payment_orchestrator.payments.domain.exception.InvalidRefundAmountException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates this context's exceptions into HTTP responses.
 *
 * <p>It lives with the web adapter, not with the exceptions themselves, so the domain and
 * application layers stay unaware that HTTP exists. It runs ahead of the application-wide fallback
 * handler.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {PaymentController.class, WebhookController.class})
public class PaymentExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentExceptionHandler.class);

    @ExceptionHandler(PaymentNotFoundException.class)
    public ProblemDetail handleNotFound(PaymentNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Payment not found", ex.getMessage());
    }

    @ExceptionHandler(InvalidPaymentStateTransitionException.class)
    public ProblemDetail handleInvalidTransition(InvalidPaymentStateTransitionException ex) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Operation not allowed in current state", ex.getMessage());
        problem.setProperty("currentStatus", ex.currentStatus().name());
        problem.setProperty("operation", ex.operation());
        return problem;
    }

    @ExceptionHandler(InvalidCaptureAmountException.class)
    public ProblemDetail handleInvalidCapture(InvalidCaptureAmountException ex) {
        ProblemDetail problem =
                problem(HttpStatus.UNPROCESSABLE_ENTITY, "Capture amount not allowed", ex.getMessage());
        problem.setProperty("capturableAmount", ex.capturable().amount());
        return problem;
    }

    @ExceptionHandler(InvalidRefundAmountException.class)
    public ProblemDetail handleInvalidRefund(InvalidRefundAmountException ex) {
        ProblemDetail problem =
                problem(HttpStatus.UNPROCESSABLE_ENTITY, "Refund amount not allowed", ex.getMessage());
        problem.setProperty("refundableAmount", ex.refundable().amount());
        return problem;
    }

    /**
     * The key is held by a request that has not finished yet.
     *
     * <p>Retryable: once the original request completes, the same key returns its outcome instead of
     * conflicting — which is the whole point of sending one.
     */
    @ExceptionHandler(IdempotencyConflictException.class)
    public ProblemDetail handleIdempotencyConflict(IdempotencyConflictException ex) {
        return retryableConflict("Idempotency key in use", ex.getMessage());
    }

    /**
     * Another operation holds the row lock on this payment and did not release it within
     * {@code lock_timeout}.
     *
     * <p>Reported as a conflict rather than a server error, because nothing is broken: the request
     * was well-formed and simply lost a race. It is safe to retry — the caller's idempotency key
     * makes a retry return the original outcome if the other operation turns out to have been the
     * same request.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ProblemDetail handleLockTimeout(PessimisticLockingFailureException ex) {
        log.warn("Timed out waiting for a payment row lock", ex);
        return retryableConflict(
                "Payment is busy", "Another operation on this payment is in progress. Retry the request.");
    }

    /**
     * A provider decline is reported as 402, not 422: the request was well-formed and the amount
     * allowed — the provider simply refused it. {@code paymentId} is included because the payment is
     * persisted even when declined, so the caller can read its full state or retry against it.
     */
    @ExceptionHandler(PaymentDeclinedException.class)
    public ProblemDetail handleDeclined(PaymentDeclinedException ex) {
        ProblemDetail problem = problem(HttpStatus.PAYMENT_REQUIRED, "Payment declined", ex.getMessage());
        problem.setProperty("paymentId", ex.paymentId().value());
        return problem;
    }

    @ExceptionHandler({UnsupportedProviderException.class, ProviderNotImplementedException.class})
    public ProblemDetail handleUnsupportedProvider(RuntimeException ex) {
        return problem(HttpStatus.NOT_IMPLEMENTED, "Provider not available", ex.getMessage());
    }

    @ExceptionHandler(WebhookVerificationException.class)
    public ProblemDetail handleWebhookVerification(WebhookVerificationException ex) {
        // Deliberately terse: an unverified caller learns nothing about why the check failed.
        return problem(HttpStatus.BAD_REQUEST, "Webhook signature verification failed", "Signature is not valid.");
    }

    /**
     * A 409 the caller should try again — the request was valid and only lost a race.
     *
     * <p>Not every conflict qualifies: an invalid state transition is also a 409, but retrying a void
     * on a captured payment will fail identically forever. Only conflicts that time can resolve carry
     * {@code retryable}, so a client can back off on these and surface the rest to a human.
     */
    private ProblemDetail retryableConflict(String title, String detail) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, title, detail);
        problem.setProperty("retryable", true);
        return problem;
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(title);
        problem.setDetail(detail);
        return problem;
    }
}