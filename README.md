# Payment Gateway - Multi-Gateway Payment Integration

A production-grade Java Spring Boot platform that orchestrates payments across multiple payment providers through a unified, extensible API.  

---

## What?

backend service that provides a unified interface for integrating multiple payment gateways such as Stripe, Adyen, and Checkout.com.

It abstracts provider-specific implementations behind a common API, allowing applications to process payments without being tightly coupled to a specific payment provider.

---

## Why?

Most applications eventually need to support multiple payment providers for business, regional, or reliability reasons. Unfortunately, each gateway exposes different APIs, request formats, authentication mechanisms, and payment lifecycles.

This project demonstrates how to build a maintainable and extensible payment integration layer using Clean Architecture and the Strategy pattern, making it easy to add or replace payment providers with minimal impact on business logic.

---



## Who for?

This project is intended for:

- Backend developers learning payment integrations
- Software architects designing payment platforms
- Fintech engineers
- Developers building e-commerce or SaaS applications
- Recruiters and hiring managers evaluating Java and Spring Boot expertise



# Payment API - Multi-Gateway Payment Integration

A production-style payment processing service built with **Java 21**, **Spring Boot**, and **Clean Architecture**.

This project demonstrates how to integrate multiple payment providers through a unified API while following enterprise software engineering practices such as dependency inversion, idempotency, webhook processing, audit logging, and secure payment handling.

The goal of this project is to showcase payment integration architecture suitable for fintech companies and enterprise payment platforms.

---



## Features

- Unified Payment API
- Multi-payment gateway support
- Authorization and Capture
- Purchase (Sale)
- Refunds
- Payment Status
- Webhook Processing
- Idempotent Requests
- Audit Logging
- Exception Handling
- OpenAPI / Swagger Documentation
- Docker Support
- Integration Tests

---



## Supported Payment Gateways


| Gateway      | Status |
| ------------ | ------ |
| Stripe       | ✅      |
| Adyen        | 🚧     |
| Checkout.com | 🚧     |


The architecture allows adding additional providers without changing the business layer.

---



## Technology Stack



### Backend

- Java 21
- Spring Boot 3
- Spring Web
- Spring Validation
- Spring Security
- Spring Data JPA



### Database

- PostgreSQL



### Documentation

- OpenAPI 3
- Swagger UI



### Build

- Maven



### Containerization

- Docker
- Docker Compose



### Testing

- JUnit 5
- Mockito
- Testcontainers

---



# Architecture

```text
                    Client
                      │
                      ▼
              REST Controller
                      │
                      ▼
              Payment Service
                      │
          ┌───────────┼────────────┐
          ▼           ▼            ▼
     Stripe      Adyen      Checkout.com
       Adapter     Adapter      Adapter
          │           │            │
          └───────────┴────────────┘
                      │
                 Payment Gateway
```

The application follows the **Strategy Pattern** and **Dependency Inversion Principle**, making it easy to plug in new payment providers.

---



# REST API



## Create Payment

```
POST /api/v1/payments
```



## Capture Payment

```
POST /api/v1/payments/{paymentId}/capture
```



## Refund Payment

```
POST /api/v1/payments/{paymentId}/refund
```



## Void Payment

```
POST /api/v1/payments/{paymentId}/void
```



## Get Payment Status

```
GET /api/v1/payments/{paymentId}
```

---



# Project Structure

```
src
 ├── controller
 ├── service
 ├── gateway
 │     ├── stripe
 │     ├── adyen
 │     └── checkout
 ├── dto
 ├── entity
 ├── repository
 ├── exception
 ├── configuration
 └── webhook
```

---



# Design Principles

- SOLID
- Clean Architecture
- Strategy Pattern
- Adapter Pattern
- Dependency Injection
- RESTful API Design
- Domain-Driven Design (DDD) concepts

---



# Security

- HTTPS
- API Key Authentication
- Secure Secret Management
- Idempotency Keys
- Webhook Signature Verification
- Input Validation
- Sensitive Data Masking

---



# Roadmap

- [x] Stripe Integration
- [ ] Adyen Integration
- [ ] Checkout.com Integration
- [ ] Payment Dashboard
- [ ] Customer Management
- [ ] Subscription Payments
- [ ] Apple Pay
- [ ] Google Pay
- [ ] Metrics & Monitoring
- [ ] CI/CD Pipeline

---



# Running the Project

```bash
git clone https://github.com/g-j4mb/payment-orchestrator.git

cd payment-api

docker compose up -d

./mvnw spring-boot:run
```

Swagger UI

```
http://localhost:8080/swagger-ui.html
```

---



# Why This Project?

This repository demonstrates enterprise payment integration techniques used in modern fintech systems, including:

- Gateway abstraction
- Multi-provider architecture
- Payment lifecycle management
- Secure webhook handling
- Transaction auditability
- Extensible payment provider integrations

It is intended as a portfolio project for backend engineering, payment systems, and solution architecture roles.

---



# Future Enhancements

- Async processing with Kafka
- Distributed tracing
- Redis caching
- Resilience4j circuit breakers
- Event sourcing
- Multi-tenant support
- Payment reconciliation
- PCI DSS best practices
- Cloud deployment (AWS)

---



# License

MIT License
