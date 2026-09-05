package com.j4mb.payment_orchestrator.payments.infrastructure.adapter.in.web;

import com.j4mb.payment_orchestrator.payments.application.command.ProcessWebhookCommand;
import com.j4mb.payment_orchestrator.payments.application.port.in.ProcessWebhookUseCase;
import com.j4mb.payment_orchestrator.payments.domain.vo.ProviderType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound adapter receiving asynchronous provider notifications.
 *
 * <p>The body is passed through as raw text: signature verification runs over the exact bytes the
 * provider signed, so deserializing first would break it. Only the provider's own adapter knows how
 * to verify and read it.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
@Tag(name = "Webhooks", description = "Receives asynchronous payment notifications from providers.")
public class WebhookController {

    private final ProcessWebhookUseCase processWebhook;

    public WebhookController(ProcessWebhookUseCase processWebhook) {
        this.processWebhook = processWebhook;
    }

    @PostMapping("/{provider}")
    @Operation(
            summary = "Receive a provider webhook",
            description = "Verifies the signature and applies the notification to the matching payment.")
    public ResponseEntity<Void> receive(
            @PathVariable String provider, @RequestBody String rawPayload, HttpServletRequest request) {
        processWebhook.process(
                new ProcessWebhookCommand(ProviderType.from(provider), rawPayload, headersOf(request)));
        return ResponseEntity.noContent().build();
    }

    private Map<String, String> headersOf(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Collections.list(request.getHeaderNames())
                .forEach(name -> headers.put(name.toLowerCase(java.util.Locale.ROOT), request.getHeader(name)));
        return headers;
    }
}