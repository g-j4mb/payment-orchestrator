# Architecture

> Written when the structure was scaffolded on 2026-07-26. Update it when the structure changes —
> a stale architecture doc is worse than none.

## What this service does

Payment Orchestrator exposes one API for processing payments through several external providers.
Callers authorize, capture, refund, and void payments without knowing which provider handles them, so
adding or replacing a provider does not ripple into application code.

## Styles in use

- **Clean Architecture** — the backbone. Three layers with dependencies pointing inward only, so the
  business model can be reasoned about and unit-tested without a container or a database.
- **Hexagonal (Ports & Adapters)** — there are three interchangeable providers behind one outbound
  port, which is exactly the case explicit ports pay for. `PaymentGatewayPort` is the Strategy
  interface; each provider is an adapter behind it.
- **Tactical DDD** — the payment lifecycle has invariants that must never be bypassed: you cannot
  capture more than was authorized, refund more than was captured, or void after capture. Those rules
  live in the `Payment` aggregate rather than being re-checked in each caller.
- **Strategic DDD** — one context today (`payments`), but as the top-level package. That leaves room
  for `disputes`, `reconciliation`, or `customers` as siblings without restructuring anything.

## Bounded contexts

### `payments`

**Owns:** the payment lifecycle end to end — authorization, capture, refund, void, provider routing,
idempotency, webhook handling, and the audit trail.

**Ubiquitous language:** A *Payment* is money a merchant collects from a payer through one external
*provider*. It is *authorized* (funds reserved), then *captured* (funds taken), or *voided* before
capture. Captured money can be *refunded*, fully or in part. A *purchase* (sale) is an authorization
captured immediately. Each provider identifies the payment by its own *provider reference*. Callers
make requests safely repeatable with an *idempotency key*.

**Boundary:** Provider integrations (Stripe, Adyen, Checkout.com) belong *inside* this context as
outbound adapters — not as a context of their own. A bounded context is justified by having its own
ubiquitous language and lifecycle; these adapters have neither, and exist solely to satisfy this
context's gateway port. Naming external systems as contexts is the most common strategic-DDD mistake,
and it produces boundaries that cost coordination without buying isolation.

**Aggregates:** `Payment` — enforces that captures never exceed the authorized amount, refunds never
exceed the captured amount, voids only happen before capture, and terminal states are final.

## The dependency rule

```text
infrastructure ──▶ application ──▶ domain
```

- `domain` depends on nothing but the JDK and `common` — no Spring, no JPA, no HTTP
- `application` depends only on `domain` and its own ports
- `infrastructure` depends on both; nothing depends on `infrastructure`
- `config` is the composition root: it wires beans and holds no business logic

Enforced by `ArchitectureTest` (8 rules). It runs on every build; a violation fails it.

## Module strategy

**Single Maven module.**

One bounded context and roughly forty classes do not justify a parent POM, a POM per module, an
install ordering, and slower IDE reimports. Multi-module buys exactly one thing a single module
cannot — the *compiler* refusing a forbidden dependency rather than a test refusing it. Both fail the
build; the ArchUnit test costs one file and two seconds.

**Revisit when** a second bounded context needs independent deployability, separate team ownership,
or cross-repository reuse. Because the ArchUnit rules hold meanwhile, the dependency graph is already
acyclic and inward-pointing, which makes that split close to mechanical.

## Package layout

```text
com.j4mb.payment_orchestrator
├── common/                          Shared kernel — DomainEvent, AggregateRoot (framework-free)
├── config/                          Composition root — OpenAPI, Clock, domain-service beans,
│                                    application-wide exception fallback
└── payments/                        Bounded context
    ├── domain/                      Tactical DDD — no Spring, no JPA, no HTTP
    │   ├── model/                   Payment (aggregate root), PaymentId
    │   ├── vo/                      Money, PaymentStatus, ProviderType, ProviderReference,
    │   │                            IdempotencyKey
    │   ├── event/                   PaymentAuthorized / Captured / Refunded / Voided
    │   ├── service/                 RefundPolicy
    │   └── exception/               Invariant violations
    ├── application/                 Use cases and the ports they talk through
    │   ├── port/in/                 AuthorizePayment, CapturePayment, RefundPayment,
    │   │                            VoidPayment, GetPaymentStatus, ProcessWebhook
    │   ├── port/out/                PaymentGatewayPort, PaymentRepositoryPort,
    │   │                            IdempotencyStorePort, DomainEventPublisherPort
    │   ├── command/                 Input records per use case
    │   ├── dto/                     PaymentResult (read-only projection)
    │   ├── service/                 Use-case implementations + PaymentGatewayResolver
    │   └── exception/               Application-level failures
    └── infrastructure/              Adapters — depend inward only
        └── adapter/
            ├── in/web/              PaymentController, WebhookController, DTOs, mapper,
            │                        context-specific exception handler
            └── out/
                ├── persistence/     JPA entities, repositories, PaymentPersistenceAdapter
                │   └── idempotency/ IdempotencyStoreAdapter
                ├── gateway/         stripe/ · adyen/ · checkout/
                └── audit/           Domain-event listener writing the append-only audit log
```

## Conventions

| Suffix | Means |
|--------|-------|
| `UseCase` | Inbound port (interface) |
| `Service` | Use-case implementation |
| `Port` | Outbound port (interface) |
| `Adapter` | Port implementation in infrastructure |
| `JpaEntity` | Persistence mirror of an aggregate — not the aggregate |
| `Command` / `Result` | Use-case input / output |

- Domain types carry no suffix and no annotations
- Domain events are past tense: `PaymentCaptured`, not `CapturePaymentEvent`
- Ports name the capability (`PaymentGatewayPort`), adapters name the vendor
  (`StripePaymentGatewayAdapter`)
- `package-info.java` at every layer boundary states that boundary's rule

## Decisions worth remembering

### Providers are adapters, not a bounded context

**Chose:** Stripe/Adyen/Checkout live under `payments/infrastructure/adapter/out/gateway/`.
**Because:** they have no independent ubiquitous language or lifecycle — they exist only to satisfy
`PaymentGatewayPort`. Modelling them as a sibling context would create a boundary that costs
coordination and buys nothing.
**Revisit when:** provider *onboarding* becomes a domain of its own — credential lifecycle, fee
schedules, merchant-provider linking. That has its own language and would justify a real context.

### All three gateway adapters exist now, two as stubs

**Chose:** `AdyenPaymentGatewayAdapter` and `CheckoutPaymentGatewayAdapter` are registered and throw
`ProviderNotImplementedException`.
**Because:** designing `PaymentGatewayPort` against three concrete implementations catches
interface mistakes immediately, instead of shaping the port around whichever provider landed first.
It also makes routing to a planned provider fail with "not implemented" rather than "unknown
provider".
**Revisit when:** never — fill the stubs in, do not remove them.

### Idempotency is an application service, not a domain service

**Chose:** `IdempotencyCheckService` in the application layer, backed by `IdempotencyStorePort`.
**Because:** deciding whether a key has been seen requires a store, and a domain service that reaches
for infrastructure breaks the dependency rule. The store's primary key *is* the idempotency key, so a
duplicate claim is rejected by the database rather than by a check-then-insert race.
**Revisit when:** keys need expiry or scoping per merchant — still application-layer work.

### Audit logging flows through domain events

**Chose:** `Payment` raises events → `DomainEventPublisherPort` → `PaymentAuditLogEventListener`
persists them, before commit.
**Because:** the aggregate already knows exactly when something happened and what changed; deriving
the audit trail from anywhere else means reconstructing that. Listening *before* commit puts the
audit row in the same transaction as the change it describes — either both land or neither does.
**Revisit when:** audit volume makes same-transaction writes too costly, or events need to reach a
broker. The broker case is additive: another adapter behind the same port.

### The domain layer holds real logic, adapters hold TODOs

**Chose:** `Payment`, the value objects, and `RefundPolicy` are fully implemented; the gateway
adapters are stubs.
**Because:** the invariants are the entire reason this structure exists, and they are cheap to write
and free of external dependencies. A skeleton aggregate with no rules would be a data class wearing a
costume, and would not validate the design.
**Revisit when:** filling in Stripe first — it is the only adapter with real integration work queued.

## Extending this

**Adding a use case:** inbound port in `application/port/in`, implementation in
`application/service`, endpoint in `infrastructure/adapter/in/web`. If it needs something external,
add an outbound port before adding an adapter.

**Adding a provider:** one `ProviderType` constant plus one `PaymentGatewayPort` implementation.
`PaymentGatewayResolver` picks it up automatically — it indexes every port bean by the provider it
declares. No domain or application change.

**Adding a bounded context:** a sibling package under `com.j4mb.payment_orchestrator` with the same
internal shape. Contexts integrate through published events or an explicit port — never by importing
each other's `domain` package. Add an ArchUnit rule for the new pair.
