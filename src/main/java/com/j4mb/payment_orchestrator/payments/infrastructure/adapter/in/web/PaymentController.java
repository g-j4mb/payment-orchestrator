package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.in.web;

import com.j4mb.payment_orchestrator.payments.application.dto.PaymentResult;
import com.j4mb.payment_orchestrator.payments.application.port.in.AuthorizePaymentUseCase;
import com.j4mb.payment_orchestrator.payments.application.port.in.CapturePaymentUseCase;
import com.j4mb.payment_orchestrator.payments.application.port.in.GetPaymentStatusUseCase;
import com.j4mb.payment_orchestrator.payments.application.port.in.RefundPaymentUseCase;
import com.j4mb.payment_orchestrator.payments.application.port.in.VoidPaymentUseCase;
import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.vo.PaymentStatus;
import com.j4mb.payment_orchestrator.payments.infrastructure.adapter.in.web.dto.CaptureRequest;
import com.j4mb.payment_orchestrator.payments.infrastructure.adapter.in.web.dto.PaymentRequest;
import com.j4mb.payment_orchestrator.payments.infrastructure.adapter.in.web.dto.PaymentResponse;
import com.j4mb.payment_orchestrator.payments.infrastructure.adapter.in.web.dto.RefundRequest;
import com.j4mb.payment_orchestrator.payments.infrastructure.adapter.in.web.dto.VoidRequest;
import com.j4mb.payment_orchestrator.payments.infrastructure.adapter.in.web.mapper.PaymentWebMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Currency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound adapter exposing the payment lifecycle over HTTP.
 *
 * <p>It does no business work: it maps the request, calls one inbound port, and maps the result
 * back. Every mutating endpoint requires an {@code Idempotency-Key} header so a retried request
 * cannot charge twice.
 */
@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "Create and manage payments across providers.")
public class PaymentController {

    private final AuthorizePaymentUseCase authorizePayment;
    private final CapturePaymentUseCase capturePayment;
    private final RefundPaymentUseCase refundPayment;
    private final VoidPaymentUseCase voidPayment;
    private final GetPaymentStatusUseCase getPaymentStatus;
    private final PaymentWebMapper mapper;

    public PaymentController(
            AuthorizePaymentUseCase authorizePayment,
            CapturePaymentUseCase capturePayment,
            RefundPaymentUseCase refundPayment,
            VoidPaymentUseCase voidPayment,
            GetPaymentStatusUseCase getPaymentStatus,
            PaymentWebMapper mapper) {
        this.authorizePayment = authorizePayment;
        this.capturePayment = capturePayment;
        this.refundPayment = refundPayment;
        this.voidPayment = voidPayment;
        this.getPaymentStatus = getPaymentStatus;
        this.mapper = mapper;
    }

    @PostMapping
    @Operation(
            summary = "Create a payment",
            description = "Authorizes, and captures too when captureMode=AUTOMATIC. "
                    + "202 means the provider's response is not back yet — poll GET /{paymentId} or retry "
                    + "this same request with the same Idempotency-Key.")
    public ResponseEntity<PaymentResponse> create(
            @Valid @RequestBody PaymentRequest request,
            @Parameter(description = "Makes the request safely repeatable.", required = true)
                    @RequestHeader("Idempotency-Key")
                    String idempotencyKey) {
        PaymentResult result = authorizePayment.authorize(mapper.toCommand(request, idempotencyKey));
        HttpStatus status =
                result.status() == PaymentStatus.AUTHORIZATION_PENDING ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(mapper.toResponse(result));
    }

    @PostMapping("/{paymentId}/capture")
    @Operation(summary = "Capture an authorized payment")
    public PaymentResponse capture(
            @PathVariable UUID paymentId,
            @Valid @RequestBody(required = false) CaptureRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        PaymentId id = new PaymentId(paymentId);
        CaptureRequest body = request == null ? new CaptureRequest(null) : request;
        PaymentResult result =
                capturePayment.capture(mapper.toCommand(id, body, idempotencyKey, currencyOf(id)));
        return mapper.toResponse(result);
    }

    @PostMapping("/{paymentId}/refund")
    @Operation(
            summary = "Refund a captured payment",
            description = "202 means the provider's response is not back yet — poll GET /{paymentId} or retry "
                    + "this same request with the same Idempotency-Key.")
    public ResponseEntity<PaymentResponse> refund(
            @PathVariable UUID paymentId,
            @Valid @RequestBody(required = false) RefundRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        PaymentId id = new PaymentId(paymentId);
        RefundRequest body = request == null ? new RefundRequest(null, null) : request;
        PaymentResult result = refundPayment.refund(mapper.toCommand(id, body, idempotencyKey, currencyOf(id)));
        HttpStatus status = result.status() == PaymentStatus.REFUND_PENDING ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(mapper.toResponse(result));
    }

    @PostMapping("/{paymentId}/void")
    @Operation(summary = "Void an authorization")
    public PaymentResponse voidPayment(
            @PathVariable UUID paymentId,
            @RequestBody(required = false) VoidRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        PaymentResult result =
                voidPayment.voidPayment(mapper.toCommand(new PaymentId(paymentId), request, idempotencyKey));
        return mapper.toResponse(result);
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Get a payment's current state")
    public PaymentResponse get(@PathVariable UUID paymentId) {
        return mapper.toResponse(getPaymentStatus.getStatus(new PaymentId(paymentId)));
    }

    /**
     * A partial capture or refund amount arrives without a currency, so it is read from the payment
     * itself. This also fails fast with 404 before the use case is entered.
     */
    private Currency currencyOf(PaymentId paymentId) {
        return getPaymentStatus.getStatus(paymentId).authorizedAmount().currency();
    }
}