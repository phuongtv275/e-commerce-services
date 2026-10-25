# E-Commerce Services

Backend Microservices platform for multi-channel e-commerce system built with Spring Boot, Spring Cloud, Gradle, and Docker.

- **Artifact Name:** `e-commerce-services`
- **Version:** `0.1.15` (Semantic Versioning)
- **External Config Repository:** `../e-commerce-services-config`

---

## 1. System Architecture & Ports Matrix

| Service | Port | Description | Technology Stack |
| :--- | :---: | :--- | :--- |
| **common-library** | N/A | Shared Library: API Response, Global Exception, Tracing & Zero-Trust Filters, Transactional Outbox, Event Contracts, Idempotency | Plain Java Library (`java-library`), Java 21, Spring Boot BOM |
| **api-gateway** | `8080` | Single Public Entrypoint, Centralized Routing, Header Sanitization, JWT Auth & Blacklist, RSA Assertion Signing (Audience Binding), Redis Rate Limiter, W3C CORS & Security Headers | Spring Cloud Gateway (WebFlux), Reactive Redis, Eureka Client |
| **identity-service** | Internal (`8081`) | Authentication, Authorization, Users, IAM, Audit Logs, Inter-Service Auth (Isolated in private Docker network) | Spring Boot WebMVC, PostgreSQL (`identity_db`), Redis Blacklist, Flyway |
| **catalog-service** | Internal (`8082`) | Products, Categories, Brands, SKUs, Elasticsearch Search & Safe Reindexing (Isolated in private Docker network) | Spring Boot WebMVC, PostgreSQL (`catalog_db`), Flyway, Elasticsearch 8.13, Spring Kafka |
| **inventory-service** | Internal (`8083`) | Stock ledger, reservations, expiry and oversell-safe stock updates | Spring Boot WebMVC, PostgreSQL (`inventory_db`), Kafka |
| **order-service** | Internal (`8084`) | Cart, Orders, Checkout (Isolated in private Docker network) | Spring Boot WebMVC, PostgreSQL (`order_db`), Kafka |
| **payment-service** | Internal (`8085`) | VNPAY Payment Intent & IPN, Refunds & Compensation, Reconciliation (Isolated in private Docker network) | Spring Boot WebMVC, PostgreSQL (`payment_db`), Kafka, Flyway |
| **notification-service** | Internal (`8086`) | Email, SMS, Webhook Notifications (Isolated in private Docker network) | Spring Boot WebMVC, PostgreSQL (`notification_db`), MailHog |
| **eureka-server** | `8761` | Service Registry & Discovery | Spring Cloud Netflix Eureka Server |
| **config-server** | `8888` | Centralized Configuration Management | Spring Cloud Config Server (`native` profile) |
| **PostgreSQL** | `5432` | Multi-Tenant Database | PostgreSQL 16 Alpine (6 independent databases) |
| **Kafka Broker** | `9092` | Event Broker (KRaft Mode) | Confluent cp-kafka:7.4.0 (Internal: 29092, Host: 9092) |
| **Kafka UI** | `8089` | Kafka Cluster Management UI | Provectus Kafka-UI |
| **Redis** | `6379` | Cache & Distributed Locks | Redis 7.0 Alpine (`--requirepass rikkeiacademy`) |
| **Redis Insight** | `5540` | Redis GUI Management Web UI | Redis Insight (`http://localhost:5540`) |
| **Elasticsearch** | `9200` | Full-Text Search Engine | Elasticsearch 8.13.4 |
| **MailHog** | `1025` / `8025` | Mock SMTP & Mail Web UI | MailHog (SMTP: 1025, Web: 8025) |

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
Start PostgreSQL, Kafka, Redis, Elasticsearch, MailHog, Config Server, and Eureka Server:
```bash
docker compose -p ecom up -d
```

Verify services are healthy:
- Config Server Health: `curl http://localhost:8888/actuator/health`
- Eureka Server Dashboard: `http://localhost:8761`
- Kafka UI: `http://localhost:8089`
- MailHog UI: `http://localhost:8025`

### Run All Microservices
To launch all business microservices alongside backing infrastructure:
```bash
docker compose -p ecom -f docker-compose.yml -f docker-compose.apps.yml up -d --build
```

---

## 5. Identity Service Architecture (`identity-service`)

Identity Service handles authentication, authorization, user profiles, address books, and administrative IAM capabilities with zero-trust inter-service boundaries.

### Key Architectural Invariants
- **Password Security:** Hashes stored using BCrypt with cost factor 12 (`BCryptPasswordEncoder(12)`).
- **Session & Refresh Token Security:** Refresh tokens are never stored plaintext; only SHA-256 hashes are persisted. Supports token rotation with pessimistic locking (`PESSIMISTIC_WRITE`). Token reuse attacks immediately revoke the entire token family.
- **Token Invalidation:** Logout invalidates access tokens using Redis TTL-based blacklisting and immediately revokes active refresh tokens.
- **Anti-BOLA Protection:** All address book operations (`GET`, `PUT`, `DELETE`, `PATCH /default`) strictly enforce `user_id == currentUserId`.
- **Atomic Password Resets:** Reset tokens are consumed using atomic CAS SQL updates (`UPDATE ... SET used = true WHERE token_hash = :hash AND used = false AND expires_at > now()`) preventing concurrent replay.
- **Inter-Service Security:** Internal endpoints (`/internal/**`) require caller-specific keys (`X-Calling-Service` & `X-Internal-Service-Key`), assigning caller-scoped roles (`ROLE_SERVICE_ORDER`, `ROLE_SERVICE_NOTIFICATION`). Rejects `X-Gateway-Assertion` to enforce private network boundary.
- **Audit Logging:** Administrative IAM modifications (role assignment/revocation) and security-critical operations are recorded in `audit_logs` with actor details, deltas, and correlation IDs.
- **E2E Integration Verification:** 25 production-grade test scenarios across public authentication, session security, BOLA prevention, RBAC, and zero-trust internal endpoints.

---

## 6. Phase 4 End-to-End Test Suite

An automated shell script is provided to verify all 25 security and functional scenarios against running services (API Gateway on port `8080` and Identity Service on port `8081`):

```bash
bash /home/trgphun/.gemini/antigravity-cli/brain/a807e32a-a68d-4dfe-98f4-0e82fb87f635/scratch/test-identity-gateway-e2e.sh
```

---

## 7. Catalog Service Architecture (`catalog-service` v0.1.1)

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

## 8. Payment Service Architecture (`payment-service` v0.1.1)

Payment Service handles payment intent generation with VNPAY v2.1 redirect URLs, public GET IPN checksum verification, idempotent state machine transitions, manual/automated refund orchestration with atomic ceiling reservation, outbox domain events, and multi-instance reconciliation workers.

### Key Architectural Invariants
- **Source of Truth & Persistence:** PostgreSQL (`payment_db`) manages `payments`, `refunds`, and `payment_audit_logs` via Flyway migrations (`V1__init_payment_schema.sql`). Outbox events use `common-library` transactional outbox.
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
