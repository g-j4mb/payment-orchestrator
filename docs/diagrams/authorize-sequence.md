# Authorization Sequence — Checkpoint-First Authorize

Every authorize call is durably recorded as `AUTHORIZATION_PENDING` in its own committed
transaction *before* Stripe is ever contacted — so a crash, timeout, or open circuit breaker
mid-call leaves a resolvable record instead of a lost payment. This traces one request from
`POST /api/v1/payments` through to final resolution, including the two independent paths — a
webhook and a backstop job — that close out a call whose outcome never came back synchronously.

**This is a server-side confirmation flow, not Stripe's client-side one.** The `Client` node below
is this API's own caller (e.g. a checkout backend or a thin frontend), not the end user's browser
talking to Stripe. Card collection happens upstream of this diagram: the caller tokenizes the card
into a Stripe `PaymentMethod` via Stripe.js/Elements (raw card data never reaches this service) and
sends that token — `paymentMethodToken` below — to `POST /api/v1/payments`. From there, this
service creates *and* confirms the `PaymentIntent` with Stripe in one server-to-server call
(`setConfirm(true)`); there is no `client_secret` handed back and no client-side
`stripe.confirmPayment()` anywhere in this flow. The consequence: a card that comes back
`requires_action` (3D Secure / SCA) has nowhere to redirect to, so `StripeGatewayAdapter` reports it
through the same path as `card declined` below — it is not a true decline, just a flow this adapter
does not yet support.

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant API as PaymentController
    participant Svc as AuthorizePaymentService
    participant Pay as Payment
    participant Idem as IdempotencyCheck
    participant Repo as PaymentRepository
    participant Bus as EventPublisher
    participant GW as StripeGatewayAdapter
    participant Stripe as Stripe API
    participant WH as WebhookController
    participant Job as ReconciliationJob

    Client->>API: POST /api/v1/payments<br/>Idempotency-Key: K
    API->>Svc: authorize(command)

    note over Svc,Idem: phase 1, replay guard
    Svc->>Idem: claim(K, "authorize")
    alt key already claimed
        Idem-->>Svc: existing paymentId
        Svc->>Repo: findById(paymentId)
        Repo-->>Svc: Payment
        alt status is FAILED
            Svc-->>API: rethrow original PaymentDeclinedException
            API-->>Client: 402 same decline as before
        else any other status
            Svc-->>API: PaymentResult(current status)
            API-->>Client: 200 or 201 or 202, replay, no gateway call
        end
    end

    note over Svc,Pay: fresh key, continues below

    note over Svc,Bus: phase 2, pre-call checkpoint, own transaction, committed before Stripe is ever contacted
    Svc->>Pay: initiate(provider, amount, key, token, captureMode)
    Svc->>Pay: markAuthorizationPending(now)
    Pay-->>Svc: raises PaymentAuthorizationPending
    Svc->>Repo: save(payment)<br/>status is AUTHORIZATION_PENDING
    Svc->>Idem: complete(K, payment.id)
    Svc->>Bus: publishAll(PaymentAuthorizationPending)

    note over Svc,Stripe: phase 3, synchronous call, guarded by the stripe circuit breaker
    Svc->>GW: authorize(payment, token, captureMode)
    GW->>Stripe: POST /v1/payment_intents<br/>Idempotency-Key is payment.id<br/>metadata.payment_id is payment.id
    alt Stripe responds
        alt succeeded or requires_capture
            Stripe-->>GW: 200, status and pi_id
            GW-->>Svc: authorized or captured
            Svc->>Pay: markAuthorized(reference)
            opt captureMode is AUTOMATIC
                Svc->>Pay: capture(amount)
            end
            Svc->>Repo: save(payment)<br/>status is AUTHORIZED or CAPTURED
            Svc->>Bus: publishAll(events)
            Svc-->>API: PaymentResult
            API-->>Client: 201 Created
        else card declined, or requires_action (3DS/SCA — no redirect support)
            Stripe-->>GW: 402 decline, or 200 requires_action
            GW-->>Svc: declined
            note right of GW: requires_action has no card decline code —<br/>reported as declined since this adapter cannot<br/>drive a redirect-based challenge
            Svc->>Pay: markFailed(reason)
            Svc->>Repo: save(payment)<br/>status is FAILED
            Svc->>Bus: publishAll(PaymentFailed)
            Svc-->>API: throw PaymentDeclinedException
            API-->>Client: 402 Payment Declined
        end
    else timeout, network error, or circuit open
        Stripe-->>GW: no response, or CallNotPermittedException
        GW-->>Svc: throws RuntimeException
        note right of Svc: ambiguous outcome, key already<br/>completed, nothing to abandon
        Svc-->>API: PaymentResult, AUTHORIZATION_PENDING
        API-->>Client: 202 Accepted<br/>poll GET /payments/id
    end

    note over Stripe,Job: phase 4, resolution, fast path and backstop
    par fast path, webhook correlation, usually seconds later
        Stripe->>WH: payment_intent.succeeded<br/>metadata.payment_id
        WH->>Repo: findByProviderReference, miss
        WH->>Repo: findById(metadata.payment_id), hit
        WH->>Pay: markAuthorized(reference)
        WH->>Repo: save(payment)
        WH->>Bus: publishAll(events)
    and backstop, every 10 minutes, only while still pending
        Job->>Repo: findAuthorizationPendingOlderThan(threshold)
        Repo-->>Job: pending payments
        Job->>GW: authorize(payment, token, captureMode)<br/>same Idempotency-Key
        GW->>Stripe: replays the original request
        Stripe-->>GW: cached response
        GW-->>Job: resolved outcome
        alt resolved
            Job->>Pay: markAuthorized or markFailed
        else still ambiguous
            Job->>Pay: recordReconciliationAttempt()
            note right of Job: exhausts after<br/>max-attempts or max-age
        end
        Job->>Repo: save(payment)
    end
```

## Why it's shaped this way

**The checkpoint runs pre-call.** `markAuthorizationPending` and `save()` commit in their own
`PROPAGATION_REQUIRES_NEW` transaction before `gateway.authorize()` is invoked. A JVM crash
mid-call still leaves a row reconciliation can act on — a reactive checkpoint written only from a
catch block cannot make that guarantee.

**Webhook is the fast path, the job is the backstop.** `metadata[payment_id]` set on the
PaymentIntent lets `ProcessWebhookService` correlate a payment that never got a
`providerReference` recorded. `ReconcilePendingPaymentsService` exists only for what the webhook
misses — it runs every ten minutes, not ten seconds.

**Reconciliation is bounded, not infinite.** Every pass reuses the same `Idempotency-Key` Stripe
already saw, so a resolved call just replays its cached answer. An unresolved one increments
`reconciliationAttempts` until `max-attempts` or `max-age` trips — then it's marked `FAILED` for a
human to look at.

## Source

- [AuthorizePaymentService.java](../../src/main/java/com/j4mb/payment_orchestrator/payments/application/service/AuthorizePaymentService.java)
- [Payment.java](../../src/main/java/com/j4mb/payment_orchestrator/payments/domain/model/Payment.java)
- [StripePaymentGatewayAdapter.java](../../src/main/java/com/j4mb/payment_orchestrator/payments/infrastructure/adapter/out/gateway/stripe/StripePaymentGatewayAdapter.java)
- [ProcessWebhookService.java](../../src/main/java/com/j4mb/payment_orchestrator/payments/application/service/ProcessWebhookService.java)
- [ReconcilePendingPaymentsService.java](../../src/main/java/com/j4mb/payment_orchestrator/payments/application/service/ReconcilePendingPaymentsService.java)
