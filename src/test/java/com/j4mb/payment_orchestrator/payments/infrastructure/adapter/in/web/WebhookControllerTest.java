package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.j4mb.payment_orchestrator.payments.application.exception.WebhookVerificationException;
import com.j4mb.payment_orchestrator.payments.application.port.in.ProcessWebhookUseCase;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WebhookController.class)
class WebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProcessWebhookUseCase processWebhook;

    @Test
    void receive_validPayload_returns204AndProcessesTheCommand() throws Exception {
        doNothing().when(processWebhook).process(any());

        mockMvc.perform(post("/api/v1/webhooks/{provider}", "stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=123,v1=abc")
                        .content("{\"reference\":\"pi_123\",\"type\":\"CAPTURED\"}"))
                .andExpect(status().isNoContent());

        verify(processWebhook)
                .process(argThat(command -> command.provider() == ProviderType.STRIPE
                        && command.rawPayload().equals("{\"reference\":\"pi_123\",\"type\":\"CAPTURED\"}")
                        && "t=123,v1=abc".equals(command.headers().get("stripe-signature"))));
    }

    @Test
    void receive_unknownProvider_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/{provider}", "not-a-provider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void receive_signatureVerificationFails_returns400() throws Exception {
        doThrow(new WebhookVerificationException(ProviderType.STRIPE, "bad signature"))
                .when(processWebhook)
                .process(any());

        mockMvc.perform(post("/api/v1/webhooks/{provider}", "stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
