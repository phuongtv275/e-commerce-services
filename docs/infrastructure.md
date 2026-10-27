# THIẾT KẾ CẤU TRÚC HẠ TẦNG TỔNG THỂ (INFRASTRUCTURE ARCHITECTURE)
## Hệ thống E-Commerce Microservices đa kênh (Backend)
**Mã dự án:** OMNI-ECOM-BACKEND  
**Nền tảng:** Java Spring Boot 3.x, Apache Kafka, PostgreSQL, Redis, Docker Compose  
**Tài liệu tham chiếu:** [docs/requirement.md](file:///home/trgphun/projects/personal/rikkei/module4/e-commerce-services/docs/requirement.md) (SRS-OMNI-ECOM-001)

---

## 1. SƠ ĐỒ KIẾN TRÚC TỔNG QUAN (HIGH-LEVEL TOPOLOGY)

```
===================================================================================================================
                                              CENTRAL CONFIG TIER
===================================================================================================================
                                      [Thư mục cấu hình: ./config-repo]
                                                      │
                                                      ▼
                                       +─────────────────────────────+
                                       │        CONFIG SERVER        │
                                       │   (Spring Cloud Config)     │
                                       │     (Port Container: 8888)  │
                                       │     (Host: Không publish)   │
                                       +──────────────┬──────────────+
                                                      │ (Nạp config khi boot / refresh qua backend-net)
                                                      ▼
===================================================================================================================
                                              CLIENT LAYER (Postman / Web / Mobile Clients)
===================================================================================================================
                                                        |
                                                        | HTTPS / HTTP (Port 8080 - TLS Termination tại Gateway)
                                                        v
+-----------------------------------------------------------------------------------------------------------------+
|                                                   API GATEWAY (Spring Cloud Gateway)                            |
|  - TLS / HTTPS Termination & Host Entrypoint (8080:8080)   - Correlation ID Propagation (X-Correlation-Id)      |
|  - Global Header Sanitization (Strip incoming X-User-*)    - Centralized Error Handling                         |
|  - JWT Authentication Verification (Protected Routes)      - CORS & Access Logs                                 |
|  - Rate Limiting (Token Bucket via Redis)                  - Path-based Routing & Outbox Replay (/api/v1/*)     |
|  - Bootstrap Config từ Config Server (http://config-server:8888)                                                |
+-----------------------------------------------------------------------------------------------------------------+
           |                        |                       |                       |                      |
           v (HTTP/REST)            v (HTTP/REST)           v (HTTP/REST)           v (HTTP/REST)          v (HTTP/REST)
+-----------------------+ +-----------------------+ +-----------------------+ +--------------------+ +--------------------+
|   identity-service    | |    catalog-service    | |   inventory-service   | |   order-service    | |  payment-service   |
| (Container Port: 8081)| | (Container Port: 8082)| | (Container Port: 8083)| |(ContainerPort:8084)| |(ContainerPort:8085)|
| - Users, Auth, RBAC   | | - Category, Product,  | | - Stock, Reservation  | | - Cart, Checkout   | | - Payment Intent   |
| - Address Book        | |   SKU, Brand, Images  | | - Atomic Decrement    | | - State Machine    | | - MoMo Sandbox     |
| - Audit Trail         | | - Search Read-Model   | | - Flash Sale Lock     | | - Snapshot Data    | | - Webhook Verify   |
| - Internal Sec Auth   | | - Internal Sec Auth   | | - Internal Sec Auth   | | - Internal Sec Auth| | - Internal Sec Auth|
+-----------------------+ +-----------------------+ +-----------------------+ +--------------------+ +--------------------+
           |                         |                         |                       |                      |
           |                         |                         |                       |                      |
           v                         v                         v                       v                      v
     [PostgreSQL:              [PostgreSQL:              [PostgreSQL:            [PostgreSQL:           [PostgreSQL:
      identity_db]              catalog_db]              inventory_db]             order_db]             payment_db]
                                     |                         |
                                     v                         v
                              [Elasticsearch]           [Redis Stock]
                                (Search Index)           (Distributed Lock)
           |                         |                         |                       |                      |
           +-------------------------+-------------------------+-----------------------+----------------------+
                                     |
                                     | (Transactional Outbox Publisher)
                                     v
+-----------------------------------------------------------------------------------------------------------------+
|                                            APACHE KAFKA CLUSTER (KRaft / 9092)                                  |
|  Topics: order.created, order.status.changed, order.cancelled, payment.succeeded, payment.failed,               |
|          inventory.reserved, inventory.released, inventory.reservation-commit-failed, reservation.expired,      |
|          catalog.product.changed, notification.send (*.dlq cho Dead Letter Queues)                              |
|  Partitioning by Business Key (orderId, skuId) - Durable Log - Dead Letter Queue (DLQ)                          |
+-----------------------------------------------------------------------------------------------------------------+
                                     |                                                         |
                                     | (Event Listeners: Order, Payment, Inventory)            | (Event Listeners)
                                     v                                                         v
                       [Choreography-based Saga Engine]                            +-----------------------+
                (Decentralized Event-driven Coordination                           | notification-service  |
                 with Compensating Actions & Auto-Refund)                          | (Container Port: 8086)|
                                                                                   | - Consumer Events     |
                                                                                   | - MailHog / SMTP      |
                                                                                   | - Template Engine     |
                                                                                   | - Internal Sec Auth   |
                                                                                   +-----------+-----------+
                                                                                               |
                                                                                               v
                                                                                         [PostgreSQL:
                                                                                        notification_db]
```

---

## 2. PHÂN TÁCH DỊCH VỤ & DANH MỤC CÁC MODULE (SERVICE BREAKDOWN)

Mã nguồn được tổ chức theo cấu trúc **Maven Multi-module** trong cùng một repository:

| Module Name | Công nghệ | Cổng Container | Cổng Host | Cơ sở dữ liệu | Trách nhiệm chính |
| :--- | :--- | :---: | :---: | :--- | :--- |
| **`config-server`** | Spring Cloud Config Server | 8888 | `-` (Internal) | File System (`config-repo/`) | Quản lý cấu hình tập trung cho toàn bộ hệ thống; cung cấp cấu hình qua HTTP REST nội bộ; không mở ra host. |
| **`api-gateway`** | Spring Cloud Gateway | 8080 | **8080** (Public) | Redis (shared) | Điểm vào duy nhất; TLS termination; xác thực JWT; rate limiting; định tuyến; correlation ID; xóa header giả mạo. |
| **`identity-service`** | Spring Boot 3.x | 8081 | `-` (Internal) | PostgreSQL (`identity_db`) | Quản lý người dùng, hồ sơ, sổ địa chỉ, phân quyền RBAC (6 vai trò), JWT token provider, xác thực email. |
| **`catalog-service`** | Spring Boot 3.x | 8082 | `-` (Internal) | PostgreSQL (`catalog_db`) + Elasticsearch | Quản lý danh mục, thương hiệu, sản phẩm, biến thể SKU, giá decimal; đồng bộ read-model sang Elasticsearch. |
| **`inventory-service`** | Spring Boot 3.x | 8083 | `-` (Internal) | PostgreSQL (`inventory_db`) + Redis | Quản lý kho, Stock Ledger bất biến, Reservation (khóa giữ kho), chống oversell dưới tải Flash Sale bằng CAS lock. |
| **`order-service`** | Spring Boot 3.x | 8084 | `-` (Internal) | PostgreSQL (`order_db`) | Quản lý giỏ hàng, Checkout tạo snapshot, Order State Machine, xử lý Idempotency, điều phối Saga đơn hàng. |
| **`payment-service`** | Spring Boot 3.x | 8085 | `-` (Internal) | PostgreSQL (`payment_db`) | Tạo Payment Intent, tích hợp MoMo Sandbox API, nhận Webhook chữ ký số HMAC-SHA256, hoàn tiền (Refund). |
| **`notification-service`** | Spring Boot 3.x | 8086 | `-` (Internal) | PostgreSQL (`notification_db`) | Tiêu thụ sự kiện từ Kafka; render template email; gửi mail qua SMTP/MailHog; auto-redrive worker & DLQ. |
| **`common-library`** | Java Library Module | - | - | - | Thư viện dùng chung: DTO, Base Exception, Error Codes, Correlation ID filter, Outbox model, Kafka Event contract. |

> **Nguyên tắc an toàn cổng mạng (Port Isolation Rule):** Chỉ duy nhất `api-gateway` publish cổng `8080` ra ngoài Host máy chủ để tiếp nhận request từ client. Tất cả microservices nghiệp vụ và `config-server` tuyệt đối **KHÔNG publish cổng ra host** (`-`), chỉ giao tiếp thông qua Docker network nội bộ `backend-net` và `data-net` để loại bỏ nguy cơ bypass xác thực, rate limiting và rò rỉ dữ liệu.

---

## 3. THIẾT KẾ DỮ LIỆU & BẢO ĐẢM TÍNH ĐỘC LẬP (DATABASE PER SERVICE)

Tuân thủ nghiêm ngặt ràng buộc **CON-02** và **DR-001**:
* Mỗi microservice sở hữu một schema/database độc lập, có user/password riêng với quyền giới hạn (Least Privilege).
* Không chia sẻ database, không join bảng xuyên service, không tạo foreign key giữa các database khác nhau.
* Các liên kết giữa các miền nghiệp vụ sử dụng **Opaque Identifier** (UUID v4 hoặc ULID) dạng chuỗi.

### Bố trí Port & Dịch vụ phụ trợ (Backing Services):
1. **Spring Cloud Config Server (Port 8888):**
   * Quản lý cấu hình tập trung từ thư mục `./config-repo` (Native profile).
   * Cung cấp endpoint HTTP REST (`http://config-server:8888/{service-name}/{profile}`) cho toàn bộ microservices lúc khởi động.
2. **PostgreSQL Cluster (Port 5432):**
   * Chạy 1 container PostgreSQL (hoặc các instance độc lập) chứa các database riêng biệt:
     * `identity_db`
     * `catalog_db`
     * `inventory_db`
     * `order_db`
     * `payment_db`
     * `notification_db`
3. **Apache Kafka (Port 9092 / 29092):**
   * Sử dụng Kafka KRaft mode (không cần Zookeeper, tối ưu tài nguyên) cho môi trường Docker Compose.
   * Kèm container **Kafka UI** (Port 8089) để trực quan hóa topics, partitions, consumer groups, message logs.
   * **Danh mục Kafka Topics:**
     * *Business Topics:* `order.created`, `order.status.changed`, `order.cancelled`, `payment.succeeded`, `payment.failed`, `inventory.reserved`, `inventory.released`, `inventory.reservation-commit-failed`, `reservation.expired`, `refund.completed`, `catalog.product.changed`, `notification.send`.
     * *Dead Letter Queues (DLQ):* Hậu tố `*.dlq` cho từng luồng lỗi bất khả kháng, bao gồm `order.created.dlq`, `payment.succeeded.dlq`, `inventory.reservation-commit.dlq` (cô lập các sự kiện commit thiếu reservation), `notification.send.dlq`.
     * *Partitioning Strategy:* Partition theo Business Key (`orderId` cho các event liên quan đến Order/Payment/Reservation; `skuId` cho Catalog/Stock).
     * *Consumer Groups:* `order-service-group`, `payment-service-group`, `inventory-service-group`, `notification-service-group`, `catalog-indexer-group`.
4. **Redis Cluster / Single Instance (Port 6379):**
   * Hỗ trợ 2 mục đích chính:
     * Phục vụ API Gateway: Lưu trữ bucket rate limiting.
     * Phục vụ Inventory Service: Distributed lock (Redisson / Lua Script atomic decrement) bảo vệ tồn kho Flash Sale.
5. **Elasticsearch (Port 9200):**
   * Elasticsearch 8.x làm search engine read-model cho `catalog-service`.
6. **MailHog (SMTP Port 1025 / Web UI: 8025):**
   * Fake SMTP Server để kiểm thử toàn diện luồng gửi email thông báo mà không cần email thực.

---

## 4. CHIẾN LƯỢC GIAO TIẾP & ĐIỀU PHỐI GIAO DỊCH PHÂN TÁN (SAGA & OUTBOX)

### 4.1. Transactional Outbox Pattern (CON-06)
Để bảo đảm tính nhất quán giữa cơ sở dữ liệu cục bộ và Message Broker (Zero Event Loss):
1. Mỗi service có bảng `outbox_events` nằm trong cùng database của service đó:
   * Cột: `id` (UUID), `aggregate_type`, `aggregate_id`, `event_type`, `event_version` (INT default 1), `payload` (JSONB), `status` (`PENDING`, `PUBLISHED`, `FAILED_RETRYABLE`, `DEAD_LETTER`), `retry_count` (INT default 0), `next_retry_at` (TIMESTAMPTZ), `last_error` (TEXT), `created_at`, `published_at`, `correlation_id`.
2. Khi cập nhật nghiệp vụ (ví dụ tạo đơn), bản ghi nghiệp vụ và bản ghi `outbox_events` được commit trong **cùng 1 Local Database Transaction**.
3. Một background worker (Spring Scheduled Polling mỗi 2-5s hoặc CDC) quét các event `status IN ('PENDING', 'FAILED_RETRYABLE') AND (next_retry_at IS NULL OR next_retry_at <= now())` theo batch (size 50, sắp xếp theo `created_at ASC`), gửi lên Apache Kafka với topic tương ứng.
4. **Retry Bền Vững & Dead Letter Queue (DLQ) cho Outbox:**
   * Sau khi nhận ACK từ Kafka: cập nhật trạng thái event thành `PUBLISHED`, `published_at = now()`.
   * Nếu gửi lỗi (mạng/broker sự cố): tăng `retry_count`, tính `next_retry_at = now() + (2^retryCount * 1000ms)` (tối đa 5 phút), chuyển sang `FAILED_RETRYABLE` để worker tiếp tục retry bền vững.
   * Sau 10 lần retry thất bại liên tục (hoặc sau 24h không thể khôi phục): chuyển sang trạng thái `DEAD_LETTER` kèm log `ERROR` và cảnh báo an toàn.
   * Hệ thống cung cấp Admin Replay API chuẩn tắc (canonical) `POST /api/v1/admin/{service}/outbox/replay/{id}` (và endpoint nội bộ `POST /internal/outbox/replay/{id}`) được bảo vệ bằng phân quyền đa tầng: `JwtAuthenticationFilter` tại API Gateway và `@PreAuthorize("hasRole('ADMINISTRATOR')")` tại tầng Controller của từng microservice để kích hoạt phát lại các sự kiện trong `DEAD_LETTER` sau khi khắc phục sự cố.

### 4.2. Choreography-based Saga cho Luồng Đặt Hàng & Thanh toán (CON-05)
Kiến trúc sử dụng **Choreography-based Saga** (phi tập trung), không sử dụng central orchestrator độc lập. Các microservices phản ứng độc lập thông qua chuỗi Domain Events trên Apache Kafka:

```
[Khách hàng Checkout]
        |
        v
1. Order Service (OrderCreated -> status: PENDING)
        |
        +---> [Gọi nội bộ/Sync REST] ---> 2. Inventory Service: Giữ kho (Reservation created)
                                                |
                 +------------------------------+------------------------------+
                 | (Đủ tồn kho: Success)                                       | (Hết hàng: Fail)
                 v                                                             v
3. Khách hàng gọi Payment Service: Khởi tạo thanh toán          Order chuyển CANCELLED
   (POST /api/v1/payments/initiate -> nhận URL MoMo)                (Báo lỗi 409 cho khách)
   Khách thực hiện trả tiền qua MoMo Sandbox
                 |
                 v (MoMo Webhook IPN)
4. Payment Service xác thực toàn bộ chữ ký, amount & đối soát -> SUCCEEDED
   Phát event: `PaymentSucceededEvent` (topic: `payment.succeeded`)
                 |
                 +-------------------------------------------------------------+
                 v                                                             v
5. Inventory Service: Commit Reservation                         6. Order Service: Chuyển CONFIRMED
   (Trừ onHand, trừ reserved bằng CAS atomic)                       Phát event: `OrderStatusChangedEvent`
   (Nếu commit fail -> phát `ReservationCommitFailedEvent`)                     |
                                                                               v
                                                                 7. Notification Service:
                                                                    Gửi Email xác nhận đơn
```

**Kịch bản bồi hoàn (Compensating Actions):**
* **Trường hợp thanh toán thất bại / hết hạn (15 phút):**
  * `payment-service` phát event `PaymentFailedEvent` hoặc Scheduler ở `inventory-service` phát `ReservationExpiredEvent`.
  * `inventory-service` nhận event -> Kích hoạt hoàn kho: Giải phóng reservation (`reserved -= qty`).
  * `order-service` nhận event -> Cập nhật trạng thái đơn thành `CANCELLED`.
* **Trường hợp thanh toán thành công đến muộn (Late-Success Webhook):**
  * MoMo IPN gửi báo thành công sau khi reservation đã hết hạn (15 phút) và đơn đã bị hủy: `payment-service` tiến hành xác minh toàn bộ chữ ký, partnerCode, requestId, currency và amount. Nếu hợp lệ, ghi nhận trạng thái thanh toán `payments.status = 'SUCCEEDED'`.
  * Áp dụng State Machine hoàn tiền chuẩn (Phase 8 & 9): Tạm giữ hạn mức `refund_in_progress += amount`, chuyển `payments.status = 'REFUND_PENDING'` với `idempotencyKey = "AUTO_REFUND:" + orderId`, gọi MoMo Refund API. **Chỉ khi MoMo xác nhận hoàn tất thành công (hoặc qua Reconciliation Worker), hệ thống mới chuyển `payments.status = 'REFUNDED'` và phát sự kiện `RefundCompletedEvent` lên Kafka**, giữ đơn ở trạng thái `CANCELLED` và gửi email thông báo giải thích cho khách hàng (Bảo đảm Zero Money Loss).
* **Trường hợp Commit kho thất bại sau khi đã thanh toán (ReservationCommitFailedEvent):**
  * Nếu `inventory-service` gặp lỗi không thể commit (do race condition / dữ liệu bất thường), service phát event `ReservationCommitFailedEvent`.
  * `payment-service` lắng nghe event này -> kích hoạt hoàn tiền tự động 100% cho khách hàng qua MoMo tuân thủ cơ chế trên với `idempotencyKey = "AUTO_REFUND:" + orderId`.
  * `order-service` cập nhật đơn hàng thành `CANCELLED` (reason: `INVENTORY_COMMIT_FAILED`).
* **Trường hợp hủy đơn sau khi đã thanh toán thành công (Customer/Staff Cancel):**
  * Kích hoạt Saga bồi hoàn: `order-service` phát `OrderCancelledEvent` (`refundRequired=true`).
  * `payment-service` gọi MoMo Refund API hoàn trả tiền cho khách hàng; `inventory-service` nhả tồn kho/restock bằng CAS từ `COMMITTED` sang `RESTOCKED`.

---

## 5. BẢO MẬT HẠ TẦNG & GIAO TIẾP MẠNG (SECURITY & NETWORKING)

1. **Docker Networks & Thứ tự khởi động (Startup Sequence):**
   * `frontend-net`: Kết nối mạng ngoài vào duy nhất `api-gateway` (Port 8080).
   * `backend-net`: Mạng nội bộ kết nối giữa `config-server`, `api-gateway` và các microservices.
   * `data-net`: Mạng cô lập chỉ dành cho các microservices kết nối tới PostgreSQL, Kafka, Redis, Elasticsearch. Không expose port database ra ngoài production.
   * **Quy tắc bảo vệ cổng dịch vụ (Port Isolation):** Trong môi trường triển khai, CHỈ duy nhất cổng 8080 của API Gateway được publish ra ngoài máy chủ. Các cổng 8081-8086 của các microservices và cổng 8888 của Config Server hoàn toàn ẩn trong Docker network `backend-net` để ngăn chặn bypass xác thực và rate-limit.
   * **Thứ tự khởi động trong Docker Compose:**
     1. Tầng Backing Services: `postgres`, `kafka`, `redis`, `elasticsearch`, `mailhog`.
     2. Tầng Config Server: `config-server` (chờ healthy check).
     3. Tầng Microservices & Gateway: Các service nạp cấu hình từ `http://config-server:8888` trước khi bind port nghiệp vụ.
2. **TLS / HTTPS Termination:**
   * Toàn bộ lưu lượng client HTTPS được terminate tập trung tại API Gateway (Port 8080/443) hoặc Reverse Proxy Ingress phía trước.
   * Giao tiếp nội bộ giữa API Gateway và các microservices nghiệp vụ diễn ra thông qua giao thức HTTP trên mạng nội bộ `backend-net` đã được cô lập tuyệt đối khỏi internet.
3. **Authentication, Authorization (RBAC) & Header Sanitization:**
   * Khách hàng đăng nhập qua `identity-service` -> nhận Access Token (JWT HMAC-SHA256, thời hạn 15 phút) và Refresh Token (7 ngày).
   * **Chống giả mạo danh tính (Header Stripping):** Bộ lọc `SanitizationGlobalFilter` (`@Order(-1)`) tại `api-gateway` chủ động xóa sạch (strip) 100% các header nhạy cảm do client bên ngoài gửi vào (`X-User-Id`, `X-User-Roles`, `X-User-Email`, `X-Gateway-Assertion`, `X-Calling-Service`, `X-Internal-Service-Key`) trên mọi route (cả public và protected) trước khi forward downstream.
   * Sau khi Gateway xác minh chữ ký JWT hợp lệ trên các protected route, Gateway ký token khẳng định bất đối xứng (`X-Gateway-Assertion`) bằng Private Key RSA 2048 (`GATEWAY_ASSERTION_PRIVATE_KEY`) có ràng buộc service nhận (`aud: <target-service>`). Downstream services chỉ nắm Public Key (`GATEWAY_ASSERTION_PUBLIC_KEY`) để verify chữ ký và **bắt buộc kiểm tra `aud == spring.application.name`** trước khi dựng `SecurityContext`, kèm downstream headers:
     * `X-Gateway-Assertion: <jwt-signed-by-gateway-private-key-with-aud>`
     * `X-User-Id: <uuid>`
     * `X-User-Roles: ROLE_CUSTOMER,ROLE_ADMIN...`
     * `X-Correlation-Id: <trace-uuid>`
   * **Bảo vệ API Nội bộ & Phân quyền Danh tính Caller (Caller-Specific Internal Auth):** Toàn bộ API nội bộ giữa các microservices (`/internal/**`) bắt buộc truyền cặp header `X-Calling-Service: <caller>` và `X-Internal-Service-Key: <caller-key>` nạp từ Config Server. `InternalServiceFilter` cấp scoped role theo caller (`ROLE_SERVICE_ORDER`, `ROLE_SERVICE_PAYMENT`, `ROLE_SERVICE_NOTIFICATION`...), các endpoint nội bộ áp dụng `@PreAuthorize` theo quyền cụ thể (ví dụ chỉ `ROLE_SERVICE_ORDER` được giữ kho hoặc đọc user profile), tuyệt đối không dùng quyền chung chung, thu hẹp tối đa bán kính thiệt hại (blast radius). Các route `/internal/**` bị cấm định tuyến qua API Gateway.
4. **Bảo mật Webhook MoMo:**
   * Bắt buộc xác minh chữ ký HMAC-SHA256 sử dụng `secretKey` của MoMo Sandbox trước khi đọc nội dung payload.
   * Kiểm tra Replay Attack qua `responseTime` (chênh lệch không quá 5 phút) và `requestId`.
   * Kiểm tra Idempotency và đối soát chính xác `amount`, `currency` trước khi thực hiện chuyển đổi trạng thái giao dịch.

---

## 6. QUẢN LÝ CẤU HÌNH & BIẾN MÔI TRƯỜNG (12-FACTOR APP)

Toàn bộ thông số nhạy cảm được cấu hình thông qua file `../local.env` mẫu (`.env.example`):
* `CONFIG_SERVER_URL=http://config-server:8888`
* `GATEWAY_ASSERTION_PRIVATE_KEY="-----BEGIN PRIVATE KEY-----\nMIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQD...==\n-----END PRIVATE KEY-----"`
* `GATEWAY_ASSERTION_PUBLIC_KEY="-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA...==\n-----END PUBLIC KEY-----"`
* `INTERNAL_SERVICE_KEYS_ORDER_SERVICE=order-service-internal-key-min-32-chars`
* `INTERNAL_SERVICE_KEYS_PAYMENT_SERVICE=payment-service-internal-key-min-32-chars`
* `INTERNAL_SERVICE_KEYS_NOTIFICATION_SERVICE=notification-service-internal-key-min-32-chars`
* `LOAD_TEST_SECRET=load-test-secret-key-change-in-staging`
* `POSTGRES_USER=postgres`
* `POSTGRES_PASSWORD=postgres`
* `POSTGRES_DB=ecom_db`
* `KAFKA_BOOTSTRAP_SERVERS=kafka:9092`
* `REDIS_HOST=redis`
* `REDIS_PORT=6379`
* `ELASTICSEARCH_URIS=http://elasticsearch:9200`
* `JWT_SECRET_KEY=your-jwt-256-bit-secret-key-for-development-environment`
* `JWT_EXPIRATION_MS=900000`
* `JWT_REFRESH_EXPIRATION_MS=604800000`
* `MOMO_PARTNER_CODE=MOMO_SANDBOX_PARTNER_CODE`
* `MOMO_ACCESS_KEY=MOMO_SANDBOX_ACCESS_KEY`
* `MOMO_SECRET_KEY=MOMO_SANDBOX_SECRET_KEY`
* `MOMO_ENDPOINT=https://test-payment.momo.vn`
* `MOMO_NOTIFY_URL=https://<your-public-domain-or-ngrok>/api/v1/payments/momo/ipn`
* `MOMO_RETURN_URL=http://localhost:3000/payment-result`
* `MAIL_HOST=mailhog.`
* `MAIL_PORT=1025`
* `RESERVATION_TIMEOUT_MINUTES=15`

> **Lưu ý triển khai MoMo Webhook Local:** Server MoMo Sandbox trên internet yêu cầu gọi tới một Public HTTPS URL. Trong môi trường local development, developer sử dụng công cụ tunnel (như `ngrok http 8080` hoặc Cloudflare Tunnel) để công khai endpoint `https://<subdomain>.ngrok-free.app/api/v1/payments/momo/ipn`. Đối với kiểm thử tự động nội bộ hoặc chạy Postman Collection offline, collection cung cấp sẵn thư mục giả lập Webhook gọi trực tiếp vào API Gateway mà không cần kết nối mạng ngoài.
