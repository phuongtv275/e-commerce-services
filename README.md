# E-Commerce Services

Backend Microservices platform for multi-channel e-commerce system built with Spring Boot, Spring Cloud, Gradle, and Docker.

- **Artifact Name:** `e-commerce-services`
- **Version:** `0.1.3` (Semantic Versioning)
- **External Config Repository:** `../e-commerce-services-config`

---

## 1. System Architecture & Ports Matrix

| Service | Port | Description | Technology Stack |
| :--- | :---: | :--- | :--- |
| **common-library** | N/A | Shared Library: API Response, Global Exception, Tracing & Zero-Trust Filters, Transactional Outbox, Event Contracts, Idempotency | Plain Java Library (`java-library`), Java 21, Spring Boot BOM |
| **api-gateway** | `8080` | Edge Gateway, Routing, Rate Limiting | Spring Cloud Gateway (WebFlux), Eureka Client |
| **identity-service** | `8081` | Authentication, Authorization, Users | Spring Boot WebMVC, PostgreSQL (`identity_db`) |
| **catalog-service** | `8082` | Products, Categories, Search | Spring Boot WebMVC, PostgreSQL (`catalog_db`), Elasticsearch |
| **inventory-service** | `8083` | Stock, Warehouses, Distributed Locks | Spring Boot WebMVC, PostgreSQL (`inventory_db`), Redis |
| **order-service** | `8084` | Cart, Orders, Checkout | Spring Boot WebMVC, PostgreSQL (`order_db`), Kafka |
| **payment-service** | `8085` | Payment Transactions, Refunds | Spring Boot WebMVC, PostgreSQL (`payment_db`) |
| **notification-service** | `8086` | Email, SMS, Webhook Notifications | Spring Boot WebMVC, PostgreSQL (`notification_db`), MailHog |
| **eureka-server** | `8761` | Service Registry & Discovery | Spring Cloud Netflix Eureka Server |
| **config-server** | `8888` | Centralized Configuration Management | Spring Cloud Config Server (`native` profile) |
| **PostgreSQL** | `5432` | Multi-Tenant Database | PostgreSQL 16 Alpine (6 independent databases) |
| **Kafka Broker** | `9092` | Event Broker (KRaft Mode) | Confluent cp-kafka:7.4.0 (Internal: 29092, Host: 9092) |
| **Kafka UI** | `8089` | Kafka Cluster Management UI | Provectus Kafka-UI |
| **Redis** | `6379` | Cache & Distributed Locks | Redis 7.0 Alpine (`--requirepass rikkeiacademy`) |
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
├── gradle.properties                 # version=0.1.3
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
