# E-Commerce Services

Backend Microservices platform for multi-channel e-commerce system built with Spring Boot, Spring Cloud, Gradle, and Docker.

- **Artifact Name:** `e-commerce-services`
- **Version:** `0.1.17` (Semantic Versioning)
- **External Config Repository:** `../e-commerce-services-config`

---

## 1. System Architecture & Ports Matrix

| Service | Port | Description | Technology Stack |
| :--- | :---: | :--- | :--- |
| **common-library** | N/A | v0.1.8: Shared Library: API Response, Global Exception, Tracing & Zero-Trust Filters, Transactional Outbox, Event Contracts, Idempotency | Plain Java Library (`java-library`), Java 21, Spring Boot BOM |
| **api-gateway** | `8080` | Single Public Entrypoint, Centralized Routing, Header Sanitization, JWT Auth & Blacklist, RSA Assertion Signing (Audience Binding), Redis Rate Limiter, W3C CORS & Security Headers | Spring Cloud Gateway (WebFlux), Reactive Redis, Eureka Client |
| **identity-service** | Internal (`8081`) | v0.1.17: Authentication, Authorization, Users, IAM, Audit Logs, Inter-Service Auth, Internal Address Lookup (`GET /internal/users/{userId}/addresses/{addressId}`), Local JWT Filter Fallback (Isolated in private Docker network) | Spring Boot WebMVC, PostgreSQL (`identity_db`), Redis Blacklist, Hibernate DDL Auto & IdentityDataSeeder |
| **catalog-service** | Internal (`8082`) | v0.1.2: Products, Categories, Brands, SKUs, Elasticsearch Search & Safe Reindexing (Isolated in private Docker network) | Spring Boot WebMVC, PostgreSQL (`catalog_db`), Hibernate DDL Auto, Elasticsearch 8.13, Spring Kafka |
| **inventory-service** | Internal (`8083`) | v0.1.3: Stock ledger, reservations, expiry, oversell-safe stock updates, Batch Stock Availability (`POST /internal/inventory/stocks/batch`) | Spring Boot WebMVC, PostgreSQL (`inventory_db`), Hibernate DDL Auto, Kafka |
| **order-service** | Internal (`8084`) | v0.1.2: Cart, Orders, Checkout, Outbox, Idempotency, Order Lifecycle State Machine (Isolated in private Docker network) | Spring Boot WebMVC, PostgreSQL (`order_db`), Hibernate DDL Auto, Kafka |
| **payment-service** | Internal (`8085`) | v0.1.2: Payment Transactions, Refunds (Isolated in private Docker network) | Spring Boot WebMVC, PostgreSQL (`payment_db`), Hibernate DDL Auto |
| **notification-service** | Internal (`8086`) | v0.1.4: Durable Kafka event ingestion, Vietnamese HTML email rendering, bounded JavaMailSender SMTP delivery, retry/DLQ auto-redrive, PII retention purge, Admin DLQ APIs (Isolated in private Docker network) | Spring Boot WebMVC, PostgreSQL (`notification_db`), Spring Kafka, JavaMailSender, Hibernate DDL Auto & TemplateDataSeeder |
| **eureka-server** | `8761` | Service Registry & Discovery | Spring Cloud Netflix Eureka Server |
| **config-server** | `8888` | Centralized Configuration Management | Spring Cloud Config Server (`native` profile) |
| **PostgreSQL** | `5432` | Multi-Tenant Database | PostgreSQL 16 Alpine (6 independent databases) |
| **Kafka Broker** | `9092` | Event Broker (KRaft Mode) | Confluent cp-kafka:7.4.0 (Internal: 29092, Host: 9092) |
| **Kafka UI** | `8089` | Kafka Cluster Management UI | Provectus Kafka-UI |
| **Redis** | `6379` | Cache & Distributed Locks | Redis 7.0 Alpine (`--requirepass rikkeiacademy`) |
| **Redis Insight** | `5540` | Redis GUI Management Web UI | Redis Insight (`http://localhost:5540`) |
| **Elasticsearch** | `9200` | Full-Text Search Engine | Elasticsearch 8.13.4 |

---

## 2. Technology Stack & Versions

- **JDK:** Java 21 LTS (Eclipse Temurin)
- **Framework:** Spring Boot 4.1.1 / Spring Cloud 2025.1.3
- **Build Tool:** Gradle 9.8.1 (Independent subproject `build.gradle` modules)
- **Containerization:** Docker Compose, Multi-Stage Dockerfiles (JRE 21 Alpine runtime, non-root user)
- **CI/CD:** CircleCI (`.circleci/config.yml`)

---

## 3. Project Structure

```
e-commerce-services/
├── gradlew
├── settings.gradle                   # Includes subprojects dynamically
├── build.gradle                      # Root minimal build script
├── gradle.properties                 # version=0.1.5
├── docker-compose.yml                # Backing Services + Config Server + Eureka Server
├── docker-compose.apps.yml           # 7 Business Microservices
├── .circleci/config.yml              # Automated CI Pipeline
├── .env.example                      # Environment variables template
├── docker/postgres/init-databases.sql# Script initializing 6 databases
├── common-library/                   # Shared Library (API format, filters, outbox, events, idempotency)
├── config-server/                    # Spring Cloud Config Server (Port 8888)
├── eureka-server/                    # Eureka Discovery Server (Port 8761)
├── api-gateway/                      # Spring Cloud Gateway (Port 8080)
├── identity-service/                 # Port 8081
├── catalog-service/                  # Port 8082
├── inventory-service/                # Port 8083
├── order-service/                    # Port 8084
├── payment-service/                  # Port 8085
└── notification-service/             # Port 8086
```

---

## 4. Getting Started

### Prerequisites
- JDK 21 installed (`JAVA_HOME` pointing to JDK 21)
- Docker & Docker Compose v2+
- External configuration repository cloned at `../e-commerce-services-config`

### Local Build & Unit Tests
Run unit tests across all 9 microservices:
```bash
./gradlew clean test
```

### Run Backing Infrastructure & Core Services
Start PostgreSQL, Kafka, Redis, Elasticsearch, Config Server, and Eureka Server:
```bash
docker compose -p ecom up -d
```

Verify services are healthy:
- Config Server Health: `curl http://localhost:8888/actuator/health`
- Eureka Server Dashboard: `http://localhost:8761`
- Kafka UI: `http://localhost:8089`

### Run All Microservices
To launch all business microservices alongside backing infrastructure:
```bash
docker compose -p ecom -f docker-compose.yml -f docker-compose.apps.yml up -d --build
```

Configure `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, and `MAIL_FROM` for SMTP delivery.

---

## 5. Identity Service Architecture (`identity-service` v0.1.17)

Identity Service handles authentication, authorization, user profiles, address books, and administrative IAM capabilities with zero-trust inter-service boundaries.

### Key Architectural Invariants
- **Password Security:** Hashes stored using BCrypt with cost factor 12 (`BCryptPasswordEncoder(12)`).
- **Session & Refresh Token Security:** Refresh tokens are never stored plaintext; only SHA-256 hashes are persisted. Supports token rotation with pessimistic locking (`PESSIMISTIC_WRITE`). Token reuse attacks immediately revoke the entire token family.
- **Token Invalidation:** Logout invalidates access tokens using Redis TTL-based blacklisting and immediately revokes active refresh tokens.
- **Local JWT Authentication Filter Fallback:** `LocalJwtAuthenticationFilter` intercepts requests with `Authorization: Bearer <token>` when no `X-Gateway-Assertion` is present (for internal/integration testing), verifies signature, checks Redis blacklist, and establishes `SecurityContextHolder` authentication.
- **Anti-BOLA Protection:** All address book operations (`GET`, `PUT`, `DELETE`, `PATCH /default`) strictly enforce `user_id == currentUserId`.
- **Atomic Password Resets & Outbox Event:** Reset tokens are consumed using atomic CAS SQL updates. Requesting password reset emits transactional outbox event `UserPasswordResetRequestedEvent` with topic `user.password-reset-requested`.
- **Inter-Service Security & Address Lookup:** Internal endpoints (`/internal/**`) require caller-specific keys (`X-Calling-Service` & `X-Internal-Service-Key`), assigning caller-scoped roles (`ROLE_SERVICE_ORDER`, `ROLE_SERVICE_NOTIFICATION`). Includes `GET /internal/users/{userId}/addresses/{addressId}` specifically permitted for `ROLE_SERVICE_ORDER` returning address snapshots without leaking foreign addresses. Rejects `X-Gateway-Assertion` to enforce private network boundary.
- **Audit Logging:** Administrative IAM modifications (role assignment/revocation) and security-critical operations are recorded in `audit_logs` with actor details, deltas, and correlation IDs.
- **E2E Integration Verification:** 25 production-grade test scenarios across public authentication, session security, BOLA prevention, RBAC, and zero-trust internal endpoints.

---

## 6. Phase 4 End-to-End Test Suite

An automated shell script is provided to verify all 25 security and functional scenarios against running services (API Gateway on port `8080` and Identity Service on port `8081`):

```bash
bash /home/trgphun/.gemini/antigravity-cli/brain/a807e32a-a68d-4dfe-98f4-0e82fb87f635/scratch/test-identity-gateway-e2e.sh
```

---

## 7. Catalog Service Architecture (`catalog-service` v0.1.2)

Catalog Service manages products, hierarchical categories, brands, SKUs, and provides full-text search with asynchronous Elasticsearch indexing and zero-trust internal endpoints.

### Key Architectural Invariants
- **Source of Truth & Schemas:** PostgreSQL (`catalog_db`) owns categories, brands, products, SKUs, product images, reindex jobs, and transactional outbox tables via Flyway migrations (`V1__init_catalog_schema.sql`).
- **Deterministic Category Hierarchy:** Prevents reparenting cycles using transactional pessimistic locking (`findByIdForUpdate`). Public tree (`GET /api/v1/catalog/categories`) outputs only `ACTIVE` nodes sorted deterministically by `displayOrder ASC, name ASC, id ASC`.
- **Transactional Outbox & Dual-Write Prevention:** All product mutations atomically record `CatalogProductChangedEvent` (with aggregate revision timestamps) to `outbox_events` in the same database transaction, published to Kafka `catalog.product.changed`.
- **Versioned Elasticsearch Read Model:** Kafka consumers (`CatalogProductEventConsumer`) idempotently synchronize products and active SKUs into Elasticsearch index alias `product_catalog`. Stale event deliveries (`aggregateRevision <= existingRevision`) are discarded.
- **Stock Read Model Isolation:** Catalog consumes versioned `inventory.stock.changed` events (`InventoryStockEventConsumer`) with monotonic version guards. Persists stock state in a PostgreSQL read model (`sku_stock_snapshots`) to preserve out-of-order stock events before initial document indexing; no unknown state overwrites newer known inventory versions. Synchronous queries from Catalog to Inventory are strictly prohibited.
- **Resilient Search & Deep Pagination Protection:** `GET /api/v1/catalog/search` supports multi-match full-text querying, filters, and deterministic tie-breaking on `skuId ASC`. Offset pagination is capped at `(page + 1) * size <= 200`. Deeper paging requires opaque Base64 URL-safe SHA-256 fingerprint search cursors backed by Elasticsearch Point-In-Time (PIT) with 1-minute keepalive.
- **Graceful Fallback:** If Elasticsearch becomes unreachable, Catalog automatically falls back to PostgreSQL querying (`source = "postgres-fallback"`), rejecting unsupported cursor pagination or unmaterialized stock filtering with `503 UPSTREAM_UNAVAILABLE`.
- **Zero-Downtime Reindexing:** Admin-triggered reindexing (`POST /api/v1/catalog/admin/reindex`) writes to a new physical index (`product_catalog-v<timestamp>`) and atomically swaps the alias upon complete verification without downtime. If an error occurs, the live alias remains intact.
- **Inter-Service Security Boundary:** SKU lookup (`GET /internal/catalog/skus/{skuId}`) and batch querying (`POST /internal/catalog/skus/batch`) enforce `InternalServiceFilter` with caller key validation for `order-service` (`ROLE_SERVICE_ORDER`). Gateway assertions on internal routes are strictly blocked with 403.
- **Automated Verification:** 161 positive and negative automated tests covering domain rules, schema constraints, idempotency, security chains, search/fallback, durable stock read models, and reindex workflows.

---

## 7.1. Inventory Service Architecture (`inventory-service` v0.1.3)

Inventory Service manages the multi-warehouse stock ledger, stock adjustments, reservation lifecycle, and zero-trust service queries.

### Key Architectural Invariants
- **Stock Ledger & Reservations:** Manages `stocks`, `reservations`, `reservation_items`, and `stock_movements` in PostgreSQL (`inventory_db`). Stock reservations are created atomically for up to 100 SKUs with a 15-minute default TTL via `POST /internal/inventory/reservations`.
- **Internal Batch Stock Availability:** Exposes `POST /internal/inventory/stocks/batch` for `ROLE_SERVICE_ORDER` returning SKU availability (`availableQuantity`) and missing SKU IDs across active warehouses without holding or mutating stock.
- **Compensating Release & Event Recovery:** Supports synchronous idempotent reservation release (`POST /internal/inventory/reservations/{orderId}/release`) and emits `inventory.reservation-expired` or `inventory.reservation-commit-failed` for downstream reconciliation.

---

## 8. Order Service Architecture (`order-service` v0.1.2)

Order Service owns customer shopping carts, idempotent order checkout, order lifecycle management, transactional outbox publishing, and internal order queries.

### Key Architectural Invariants
- **Database & Persistence:** Dedicated PostgreSQL database (`order_db`) with Hibernate DDL Auto (`spring.jpa.hibernate.ddl-auto: update`) managing `carts`, `cart_items`, `orders`, `order_items`, `order_status_history`, `idempotency_records`, `outbox_events`, and `processed_events`.
- **Owner-Scoped Security & Tenant Isolation:** Current customer ID is derived strictly from trusted Gateway-signed assertion tokens via `TrustedHeaderAuthenticationFilter` and `SecurityUtils`. Customer APIs reject spoofed customer IDs and scope all database operations by authenticated customer UUID.
- **Idempotent Checkout Boundary:** Checkout requests require an `Idempotency-Key` header and payload fingerprinting. Network calls to Identity (address snapshot), Catalog (current price snapshot), and Inventory (stock reservation) occur strictly outside local database transactions.
- **Atomic Local Persistence & Synchronous Compensation:** Upon successful Inventory reservation, local order persistence (`PENDING` order, item snapshots, initial status history, outbox event, idempotency record completion, cart clearing) commits atomically. If local commit fails, the transaction is rolled back, the reservation is synchronously released via `POST /internal/inventory/reservations/{orderId}/release`, and the idempotency record is recorded as `FAILED`.
- **State Machine & Kafka Event Processing:** Order state transitions (`PENDING -> CONFIRMED -> SHIPPING -> COMPLETED` or `CANCELLED`) follow strict CAS state machine logic. Kafka consumers process `payment.succeeded`, `payment.failed`, `inventory.reservation-expired`, and `inventory.reservation-commit-failed` idempotently using `common-library`'s `ProcessedEventRepository`.
- **Internal Payment Service Contract:** Exposes `GET /internal/orders/{orderId}` strictly for `ROLE_SERVICE_PAYMENT` via `InternalServiceFilter`, omitting customer PII and providing authoritative order amount and status.

---

## 9. Notification Service Architecture (`notification-service` v0.1.4)

Notification Service manages durable transactional email notifications, template rendering, bounded SMTP delivery, automatic DLQ redrive, and PII retention lifecycle.

### Key Architectural Invariants
- **Database & Persistence:** Dedicated PostgreSQL database (`notification_db`) with Hibernate DDL Auto (`spring.jpa.hibernate.ddl-auto: update`) and `NotificationTemplateDataSeeder` managing `notification_templates`, `notification_logs`, and `processed_events`.
- **Durable Kafka Ingestion & Deduplication:** Subscribes to 6 approved domain topics (`user.registered`, `order.status-changed`, `order.cancelled`, `payment.succeeded`, `payment.refund-completed`, `user.password-reset-requested`). Filters `order.status-changed` for `CONFIRMED` orders only. In a single local database transaction, saves `NotificationLog` in `QUEUED` state and records `processed_events` for consumer group `notification-service-group` before acknowledging offsets. Duplicates are safely acknowledged as no-ops.
- **Zero-Trust Inter-Service Snapshots:** Calls `GET /internal/orders/{orderId}/notification-snapshot` (`ROLE_SERVICE_NOTIFICATION`) for order snapshot and item rows, and `GET /internal/users/{userId}` for recipient email/name. Secrets are configured externally via `${INTERNAL_SERVICE_KEYS_NOTIFICATION_SERVICE}` without hardcoded fallbacks.
- **Template Rendering & Bounded SMTP Worker:** Vietnamese HTML templates render with escaped values and safe item rows. A bounded worker atomically claims `QUEUED`/`FAILED` jobs with a 10-minute lease and delivers through `JavaMailSender` outside database transactions, applying exponential backoff and transitioning to `DEAD_LETTER` after 3 attempts.
- **Auto-Redrive & DLQ Fencing:** Scheduled worker scans `DEAD_LETTER` jobs with a 5-minute cooldown (up to 10 total attempts), claims atomically with a unique claim token and 10-minute lease, fencing stale workers.
- **PII Retention Purge:** Daily job (03:00 UTC) scrubs records older than 30 days (`[PURGED_PII]`, masked recipient `j***e@domain.com`, `[PURGED_PII_LOG]`), converts `QUEUED`/`REDRIVING` to `EXPIRED_UNSENT`, and preserves deduplication keys and audit history.
- **Admin Management APIs:** `GET /api/v1/admin/notifications/dlq` (paginated, masked recipient), `GET /api/v1/admin/notifications/{id}` (full detail), and `POST /api/v1/admin/notifications/redrive-dlq` (rejects purged records with 422 `BUSINESS_RULE_VIOLATION`), strictly protected by `ROLE_ADMINISTRATOR`.

## 10. Payment Service Architecture (`payment-service` v0.1.2)

Payment Service handles payment intent generation with VNPAY v2.1 redirect URLs, public GET IPN checksum verification, idempotent state machine transitions, manual/automated refund orchestration with atomic ceiling reservation, outbox domain events, and multi-instance reconciliation workers.

### Key Architectural Invariants
- **Source of Truth & Persistence:** PostgreSQL (`payment_db`) manages `payments`, `refunds`, and `payment_audit_logs` via Hibernate DDL Auto (`spring.jpa.hibernate.ddl-auto: update`). Outbox events use `common-library` transactional outbox.
- **VNPAY v2.1 Payment Intent:** `POST /api/v1/payments/initiate` fetches validated snapshot amount/currency/status from `order-service` via internal RestClient (`GET /internal/orders/{orderId}`). Generates deterministic HMAC-SHA512 signed payment URL (`vnp_Amount = VND * 100`) and stores `vnp_TxnRef` atomically. Concurrent initiate retries return the stored payment URL.
- **Public IPN Verification & CAS Transitions:** Public callback (`GET /api/v1/payments/vnpay/ipn`) bypasses JWT at API Gateway and verifies VNPAY HMAC-SHA512 signature before database mutation. State transitions use atomic compare-and-swap (CAS) and append outbox events (`payment.succeeded`, `payment.failed`, `payment.late-success`). Replays return `RspCode: "02"`.
- **Refund Orchestration & Hold Invariant:** Manual refunds (`POST /api/v1/admin/payments/{paymentId}/refunds`) and automated compensation reserve funds atomically (`total_refunded + refund_in_progress <= amount`) under row locks before calling VNPAY outside database transactions.
- **Ambiguous Refund & Reconciliation:** Timeouts or pending VNPAY responses retain the hold; no duplicate refund command is issued. Reconciliation workers query VNPAY (`vnp_Command=querydr`) with fresh request IDs. After 3 indeterminate attempts, transitions to `MANUAL_RECONCILIATION_REQUIRED` for Finance/Admin resolution (`POST /api/v1/admin/payments/{paymentId}/refunds/{refundId}/resolve`).
- **Saga Consumers & Idempotency:** Kafka consumers for `order.cancelled` (auto-refund when `refundRequired=true`), `inventory.reservation-commit-failed` (Saga compensation), `inventory.reservation-expired` (payment expiration), and `payment.late-success` (automatic refund after a late capture) enforce idempotency using `processed_events` and domain idempotency keys (`AUTO_REFUND:<orderId>`).
- **Zero-Trust Network Isolation:** Payment service port `8085` remains strictly internal. Gateway routes only public API (`/api/v1/payments/**`, `/api/v1/admin/payments/**`) and public GET IPN (`/api/v1/payments/vnpay/ipn`).

### Deployment Configuration (`payment-service.yml`)
- **Required Environment Variables:**
  - `VNPAY_TMN_CODE` (`vnpay.tmn-code`): Merchant terminal code issued by VNPAY.
  - `VNPAY_HASH_SECRET` (`vnpay.hash-secret`): HMAC-SHA512 secret key (injected via secret store).
  - `VNPAY_IPN_URL` (`vnpay.ipn-url`): Publicly accessible HTTPS IPN callback URL (`https://<domain>/api/v1/payments/vnpay/ipn`).
  - `VNPAY_RETURN_URL` (`vnpay.return-url`): Frontend redirect URL after checkout completion.
  - `VNPAY_IP_ADDRESS` (`vnpay.ip-address`): API-calling server outbound egress IP for merchant refund and querydr calls.
- **Default Settings:**
  - `vnpay.pay-url`: `https://sandbox.vnpayment.vn/paymentv2/vpcpay.html`
  - `vnpay.api-url`: `https://sandbox.vnpayment.vn/merchant_webapi/api/transaction`
  - `vnpay.version`: `2.1.0`
  - `vnpay.locale`: `vn`
  - `vnpay.order-type`: `other`
  - `vnpay.expire-minutes`: `15` (aligned with inventory reservation TTL)

---

## 11. Configuration & Environment Variables Architecture (v0.1.16)

To resolve configuration duplication, drift, and single-point-of-truth ambiguity across `.env`, `docker-compose`, and Spring Boot `application.yaml` files, the project implements a clean 3-tier configuration hierarchy:

### Configuration Hierarchy & Roles

```
┌─────────────────────────────────────────────────────────────────┐
│              .env / local.env (Secrets & Env Overrides)         │
│  - Single source of truth for secrets and deployment-specific   │
│    values (passwords, JWT secrets, RSA keys, VNPAY, Mail credentials).
└───────────────────────────────┬─────────────────────────────────┘
                                │ Loaded via env_file / direnv / IDE
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│        Config Server & Config Repo (e-commerce-services-config)  │
│  - Single source of truth for business and topology settings     │
│  - application.yml: shared Kafka, Redis, Eureka, Actuator       │
│  - <service>.yml: service-specific routes, topics, retries, etc.│
│  - Uses standardized placeholders with fallback defaults         │
└───────────────────────────────┬─────────────────────────────────┘
                                │ Injected via Spring Cloud Config
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│          Microservices (src/main/resources/application.yaml)    │
│  - Minimal bootstrap config ONLY (app name, port, configserver) │
│  - Zero hardcoded secrets, duplicate routes, or redundant configs
└─────────────────────────────────────────────────────────────────┘
```

### Standardized Environment Variable Names

| Category | Standard Variable | Description |
| :--- | :--- | :--- |
| **Database** | `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | PostgreSQL connection details |
| **Cache** | `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | Redis connection parameters |
| **Kafka** | `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker endpoints (`localhost:9092` / `kafka:29092`) |
| **Search** | `ELASTICSEARCH_HOST`, `ELASTICSEARCH_PORT` | Elasticsearch endpoints |
| **Mail** | `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` | Mail sender credentials |
| **Security** | `JWT_SECRET`, `GATEWAY_ASSERTION_PRIVATE_KEY`, `GATEWAY_ASSERTION_PUBLIC_KEY` | Zero-trust authentication keys |
| **Service Auth** | `INTERNAL_KEY_ORDER`, `INTERNAL_KEY_NOTIFICATION`, `INTERNAL_KEY_PAYMENT` | Inter-service shared secret keys |
| **Payment** | `VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET`, `VNPAY_IPN_URL`, `VNPAY_RETURN_URL` | VNPAY integration keys |
| **Cloud** | `CONFIG_SERVER_URL`, `EUREKA_SERVER_URL`, `SPRING_PROFILES_ACTIVE` | Spring Cloud discovery & config |

---

## 12. Database Schema Automation for MVP (v0.1.17)

To eliminate Flyway Community Edition version incompatibility errors with PostgreSQL (PostgreSQL 16+) and accelerate local MVP feature iterations, the project transitioned all 6 relational microservices from Flyway to Hibernate Schema Automation:

### Architecture & Mechanisms
- **Automated DDL Evolution:** All services use `spring.jpa.hibernate.ddl-auto: update` managed globally through `config-server` (`application.yml` and service configs). Tables, columns, foreign keys, indexes, and transactional outbox schemas are dynamically synchronized from `@Entity` classes at service startup.
- **ApplicationRunner Data Seeders:** Core initial database records are seeded idempotently via Spring Boot `ApplicationRunner` components:
  - `IdentityDataSeeder` (`identity-service`): Seeds standard roles (`ROLE_CUSTOMER`, `ROLE_CATALOG_STAFF`, `ROLE_INVENTORY_STAFF`, `ROLE_ORDER_STAFF`, `ROLE_FINANCE_STAFF`, `ROLE_ADMINISTRATOR`) and default administrator account (`admin@ecom.local` / `Admin@123456`).
  - `NotificationTemplateDataSeeder` (`notification-service`): Seeds 5 default Vietnamese HTML email templates (`user.registered`, `order.status-changed`, `order.cancelled`, `payment.succeeded`, `payment.refund-completed`).
- **Archived SQL Reference:** Existing SQL migrations in `src/main/resources/db/migration/` are retained as architectural reference and baseline for future staging/production migration tool adoption.


