# Payment Orchestrator

> A production-grade payment orchestration platform built with Java and Spring Boot that provides a unified API for integrating multiple payment providers.

Payment Orchestrator abstracts provider-specific implementations behind a common interface, enabling applications to process payments through a consistent API without being tightly coupled to any single payment provider.

The project demonstrates enterprise software engineering practices including Clean Architecture, SOLID principles, gateway abstraction, idempotent payment processing, webhook handling, audit logging, and secure payment integrations.

---

## Overview

Modern applications often need to integrate with multiple payment providers for business, regional, cost, or reliability reasons. Each provider exposes different APIs, authentication mechanisms, payment workflows, and webhook formats, resulting in duplicated code and tightly coupled integrations.

Payment Orchestrator solves this problem by introducing a unified orchestration layer that decouples business logic from payment providers. New providers can be added or existing ones replaced with minimal changes to the application.

---

## Who Is This Project For?

This project is intended for:

* Backend developers learning payment integrations
* Software architects designing payment platforms
* Fintech engineers
* Developers building e-commerce and SaaS applications
* Recruiters and hiring managers evaluating Java and Spring Boot expertise

---

## Architecture

```text
                        Client Application
                                │
                                ▼
                         REST Controller
                                │
                                ▼
                     Payment Orchestrator
                                │
                ┌───────────────┼────────────────┐
                ▼               ▼                ▼
         Stripe Adapter   Adyen Adapter   Checkout Adapter
                │               │                │
                └───────────────┴────────────────┘
                                │
                                ▼
                       Payment Providers
```

The application follows the **Strategy Pattern**, **Adapter Pattern**, and **Dependency Inversion Principle**, allowing payment providers to be added without modifying the business layer.

---

## Key Highlights

* Unified Payment API
* Multi-provider architecture
* Provider abstraction
* Authorization & Capture
* Purchase (Sale)
* Refunds
* Payment Status
* Webhook Processing
* Idempotent Requests
* Audit Logging
* OpenAPI / Swagger Documentation
* Docker Support
* Integration Tests

---

## Supported Payment Providers

| Provider     | Status |
| ------------ | :----: |
| Stripe       |    ✅   |
| Adyen        |   🚧   |
| Checkout.com |   🚧   |

The architecture is designed to support additional providers with minimal implementation effort.

---

## Technology Stack

### Backend

* Java 21
* Spring Boot 3
* Spring Web
* Spring Validation
* Spring Security
* Spring Data JPA

### Database

* PostgreSQL

### Documentation

* OpenAPI 3
* Swagger UI

### Build & Deployment

* Maven
* Docker
* Docker Compose

### Testing

* JUnit 5
* Mockito
* Testcontainers

---

## REST API

### Create Payment

```http
POST /api/v1/payments
```

### Capture Payment

```http
POST /api/v1/payments/{paymentId}/capture
```

### Refund Payment

```http
POST /api/v1/payments/{paymentId}/refund
```

### Void Payment

```http
POST /api/v1/payments/{paymentId}/void
```

### Get Payment Status

```http
GET /api/v1/payments/{paymentId}
```

---

## Project Structure

```text
src
├── controller
├── service
├── gateway
│   ├── stripe
│   ├── adyen
│   └── checkout
├── dto
├── entity
├── repository
├── configuration
├── exception
└── webhook
```

---

## Design Principles

* Clean Architecture
* SOLID Principles
* Strategy Pattern
* Adapter Pattern
* Dependency Injection
* RESTful API Design
* Domain-Driven Design (DDD)

---

## Security

* HTTPS
* API Key Authentication
* Secure Secret Management
* Idempotency Keys
* Webhook Signature Verification
* Input Validation
* Sensitive Data Masking

---

## Roadmap

* [x] Stripe Integration
* [ ] Adyen Integration
* [ ] Checkout.com Integration
* [ ] Payment Dashboard
* [ ] Customer Management
* [ ] Subscription Payments
* [ ] Apple Pay
* [ ] Google Pay
* [ ] Metrics & Monitoring
* [ ] CI/CD Pipeline

---

## Running the Project

```bash
git clone https://github.com/g-j4mb/payment-orchestrator.git

cd payment-orchestrator

docker compose up -d

./mvnw spring-boot:run
```

Swagger UI

```text
http://localhost:8080/swagger-ui.html
```

---

## Future Enhancements

* Kafka Event Streaming
* Distributed Tracing
* Redis Caching
* Resilience4j Circuit Breakers
* Event Sourcing
* Multi-tenancy
* Payment Reconciliation
* PCI DSS Best Practices
* AWS Deployment

---

## License

This project is licensed under the MIT License.
