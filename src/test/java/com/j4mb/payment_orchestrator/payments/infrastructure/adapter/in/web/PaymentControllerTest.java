package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.j4mb.payment_orchestrator.payments.application.dto.PaymentResult;
import com.j4mb.payment_orchestrator.payments.application.exception.IdempotencyConflictException;
import com.j4mb.payment_orchestrator.payments.application.exception.PaymentDeclinedException;
import com.j4mb.payment_orchestrator.payments.application.exception.PaymentNotFoundException;
import com.j4mb.payment_orchestrator.payments.application.port.in.AuthorizePaymentUseCase;
import com.j4mb.payment_orchestrator.payments.application.port.in.CapturePaymentUseCase;
import com.j4mb.payment_orchestrator.payments.application.port.in.GetPaymentStatusUseCase;
import com.j4mb.payment_orchestrator.payments.application.port.in.RefundPaymentUseCase;
import com.j4mb.payment_orchestrator.payments.application.port.in.VoidPaymentUseCase;
import com.j4mb.payment_orchestrator.payments.application.exception.UnsupportedProviderException;
import com.j4mb.payment_orchestrator.payments.domain.exception.InvalidCaptureAmountException;
import com.j4mb.payment_orchestrator.payments.domain.exception.InvalidPaymentStateTransitionException;
import com.j4mb.payment_orchestrator.payments.domain.exception.InvalidRefundAmountException;
import com.j4mb.payment_orchestrator.payments.domain.model.PaymentId;
import com.j4mb.payment_orchestrator.payments.domain.vo.IdempotencyKey;
import com.j4mb.payment_orchestrator.payments.domain.vo.Money;
import com.j4mb.payment_orchestrator.payments.domain.vo.PaymentStatus;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import com.j4mb.payment_orchestrator.payments.infrastructure.adapter.in.web.mapper.PaymentWebMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PaymentController.class)
@Import(PaymentWebMapper.class)
class PaymentControllerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID PAYMENT_ID = UUID.randomUUID();

    private static Money usd(String amount) {
        return Money.of(new BigDecimal(amount), "USD");
    }

    private static PaymentResult resultIn(PaymentStatus status) {
        return new PaymentResult(
                PAYMENT_ID,
                ProviderType.STRIPE,
                "pi_123",
                usd("100.00"),
                usd("0.00"),
                usd("0.00"),
                status,
                null,
                NOW,
                NOW);
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthorizePaymentUseCase authorizePayment;

    @MockitoBean
    private CapturePaymentUseCase capturePayment;

    @MockitoBean
    private RefundPaymentUseCase refundPayment;

    @MockitoBean
    private VoidPaymentUseCase voidPayment;

    @MockitoBean
    private GetPaymentStatusUseCase getPaymentStatus;

    @Test
    void create_validRequest_returns201WithThePayment() throws Exception {
        when(authorizePayment.authorize(any())).thenReturn(resultIn(PaymentStatus.AUTHORIZED));

        mockMvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"provider":"STRIPE","amount":100.00,"currency":"USD","paymentMethodToken":"tok_visa","captureMode":"MANUAL"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(PAYMENT_ID.toString()))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));
    }

    @Test
    void create_missingRequiredField_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"amount":100.00,"currency":"USD","paymentMethodToken":"tok_visa"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.provider").exists());
    }

    @Test
    void create_declinedByProvider_returns402WithPaymentId() throws Exception {
        PaymentId id = new PaymentId(PAYMENT_ID);
        when(authorizePayment.authorize(any())).thenThrow(new PaymentDeclinedException(id, "insufficient_funds"));

        mockMvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"provider":"STRIPE","amount":100.00,"currency":"USD","paymentMethodToken":"tok_visa"}
                                """))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.paymentId").value(PAYMENT_ID.toString()));
    }

    @Test
    void create_idempotencyKeyInFlight_returns409Retryable() throws Exception {
        when(authorizePayment.authorize(any()))
                .thenThrow(new IdempotencyConflictException(new IdempotencyKey("key-1")));

        mockMvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"provider":"STRIPE","amount":100.00,"currency":"USD","paymentMethodToken":"tok_visa"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void get_existingPayment_returns200() throws Exception {
        when(getPaymentStatus.getStatus(any())).thenReturn(resultIn(PaymentStatus.CAPTURED));

        mockMvc.perform(get("/api/v1/payments/{id}", PAYMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    @Test
    void get_unknownPayment_returns404() throws Exception {
        when(getPaymentStatus.getStatus(any())).thenThrow(new PaymentNotFoundException(new PaymentId(PAYMENT_ID)));

        mockMvc.perform(get("/api/v1/payments/{id}", PAYMENT_ID)).andExpect(status().isNotFound());
    }

    @Test
    void capture_withoutBody_capturesTheFullAmount() throws Exception {
        when(getPaymentStatus.getStatus(any())).thenReturn(resultIn(PaymentStatus.AUTHORIZED));
        when(capturePayment.capture(any())).thenReturn(resultIn(PaymentStatus.CAPTURED));

        mockMvc.perform(post("/api/v1/payments/{id}/capture", PAYMENT_ID).header("Idempotency-Key", "key-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    @Test
    void capture_amountExceedsCapturable_returns422() throws Exception {
        when(getPaymentStatus.getStatus(any())).thenReturn(resultIn(PaymentStatus.AUTHORIZED));
        when(capturePayment.capture(any())).thenThrow(new InvalidCaptureAmountException(usd("150.00"), usd("100.00")));

        mockMvc.perform(post("/api/v1/payments/{id}/capture", PAYMENT_ID)
                        .header("Idempotency-Key", "key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":150.00}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.capturableAmount").value(100.00));
    }

    @Test
    void capture_unknownPayment_returns404BeforeEnteringTheUseCase() throws Exception {
        when(getPaymentStatus.getStatus(any())).thenThrow(new PaymentNotFoundException(new PaymentId(PAYMENT_ID)));

        mockMvc.perform(post("/api/v1/payments/{id}/capture", PAYMENT_ID).header("Idempotency-Key", "key-2"))
                .andExpect(status().isNotFound());
    }

    @Test
    void refund_withoutBody_refundsTheFullAmount() throws Exception {
        when(getPaymentStatus.getStatus(any())).thenReturn(resultIn(PaymentStatus.CAPTURED));
        when(refundPayment.refund(any())).thenReturn(resultIn(PaymentStatus.REFUNDED));

        mockMvc.perform(post("/api/v1/payments/{id}/refund", PAYMENT_ID).header("Idempotency-Key", "key-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    @Test
    void voidPayment_withoutBody_releasesTheAuthorization() throws Exception {
        when(voidPayment.voidPayment(any())).thenReturn(resultIn(PaymentStatus.VOIDED));

        mockMvc.perform(post("/api/v1/payments/{id}/void", PAYMENT_ID).header("Idempotency-Key", "key-4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOIDED"));
    }

    @Test
    void voidPayment_withBody_passesTheReasonThrough() throws Exception {
        when(voidPayment.voidPayment(any())).thenReturn(resultIn(PaymentStatus.VOIDED));

        mockMvc.perform(post("/api/v1/payments/{id}/void", PAYMENT_ID)
                        .header("Idempotency-Key", "key-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"customer_cancelled"}
                                """))
                .andExpect(status().isOk());

        verify(voidPayment).voidPayment(argThat(cmd -> "customer_cancelled".equals(cmd.reason())));
    }

    @Test
    void create_invalidCaptureMode_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "key-5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"provider":"STRIPE","amount":100.00,"currency":"USD","paymentMethodToken":"tok_visa","captureMode":"NOT_A_MODE"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_unsupportedProvider_returns501() throws Exception {
        when(authorizePayment.authorize(any())).thenThrow(new UnsupportedProviderException(ProviderType.ADYEN));

        mockMvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "key-6")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"provider":"ADYEN","amount":100.00,"currency":"USD","paymentMethodToken":"tok_visa"}
                                """))
                .andExpect(status().isNotImplemented());
    }

    @Test
    void get_unexpectedError_returns500() throws Exception {
        when(getPaymentStatus.getStatus(any())).thenThrow(new RuntimeException("boom"));

        mockMvc.perform(get("/api/v1/payments/{id}", PAYMENT_ID)).andExpect(status().isInternalServerError());
    }

    @Test
    void refund_paymentNotInARefundableStatus_returns409WithCurrentStatus() throws Exception {
        when(getPaymentStatus.getStatus(any())).thenReturn(resultIn(PaymentStatus.AUTHORIZED));
        when(refundPayment.refund(any()))
                .thenThrow(new InvalidPaymentStateTransitionException(PaymentStatus.AUTHORIZED, "refund"));

        mockMvc.perform(post("/api/v1/payments/{id}/refund", PAYMENT_ID).header("Idempotency-Key", "key-7"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("AUTHORIZED"))
                .andExpect(jsonPath("$.operation").value("refund"));
    }

    @Test
    void refund_amountExceedsRefundable_returns422() throws Exception {
        when(getPaymentStatus.getStatus(any())).thenReturn(resultIn(PaymentStatus.CAPTURED));
        when(refundPayment.refund(any())).thenThrow(new InvalidRefundAmountException(usd("150.00"), usd("100.00")));

        mockMvc.perform(post("/api/v1/payments/{id}/refund", PAYMENT_ID)
                        .header("Idempotency-Key", "key-8")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":150.00}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.refundableAmount").value(100.00));
    }

    @Test
    void capture_paymentRowIsLocked_returns409Retryable() throws Exception {
        when(getPaymentStatus.getStatus(any())).thenReturn(resultIn(PaymentStatus.AUTHORIZED));
        when(capturePayment.capture(any()))
                .thenThrow(new PessimisticLockingFailureException("timed out waiting for row lock"));

        mockMvc.perform(post("/api/v1/payments/{id}/capture", PAYMENT_ID).header("Idempotency-Key", "key-9"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.retryable").value(true));
    }
}
