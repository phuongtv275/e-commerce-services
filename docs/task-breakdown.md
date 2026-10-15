# BẢNG PHÂN RÃ CÔNG VIỆC DỰ ÁN (TASK BREAKDOWN)
## Hệ thống E-Commerce Microservices đa kênh (Backend)
**Mã dự án:** OMNI-ECOM-BACKEND  
**Nền tảng:** Java Spring Boot 3.x, Apache Kafka, PostgreSQL, Redis, Docker Compose  
**Tài liệu tham chiếu:** [docs/requirement.md](file:///home/trgphun/projects/personal/rikkei/module4/e-commerce-services/docs/requirement.md) & [docs/infrastructure.md](file:///home/trgphun/projects/personal/rikkei/module4/e-commerce-services/docs/infrastructure.md)

---

## 1. TỔNG QUAN CÁC GIAI ĐOẠN PHÁT TRIỂN (PHASES ROADMAP)

```
[Phase 1: Project Setup & Docker Infra]
                  │
                  ▼
[Phase 2: Common Library & Shared Core]
                  │
                  ▼
[Phase 3: API Gateway & Security Routing]
                  │
                  ▼
[Phase 4: Identity & User Service (IAM)]
                  │
                  ▼
[Phase 5: Product Catalog & Search Engine]
                  │
                  ▼
[Phase 6: Inventory Service & Flash Sale Concurrency]
                  │
                  ▼
[Phase 7: Order Service & State Machine]
                  │
                  ▼
[Phase 8: Payment Service & MoMo Integration]
                  │
                  ▼
[Phase 9: Distributed Saga Orchestration]
                  │
                  ▼
[Phase 10: Notification Service & Mail Engine]
                  │
                  ▼
[Phase 11: Postman Collection, Testing & Documentation]
```

---

## 2. CHI TIẾT CÁC TASK THEO TỪNG GIAI ĐOẠN

### GIAI ĐOẠN 1: THIẾT LẬP NỀN TẢNG & HẠ TẦNG DOCKER (PHASE 1)
| Task ID | Tên Task | Mô tả chi tiết | Tiêu chí nghiệm thu (Acceptance Criteria) | Phụ thuộc |
| :--- | :--- | :--- | :--- | :--- |
| **TSK-01.1** | Khởi tạo cấu trúc Maven Multi-Module | Tạo `pom.xml` gốc quản lý dependency versions (Spring Boot 3.x, Java 17/21, Spring Cloud, Kafka, PostgreSQL, Lombok,...). Tạo các module con: `common-library`, `config-server`, `api-gateway`, `identity-service`, `catalog-service`, `inventory-service`, `order-service`, `payment-service`, `notification-service`. | `mvn clean compile` thành công toàn bộ module từ thư mục root. | Không |
| **TSK-01.2** | Cấu hình Docker Compose cho hạ tầng phụ trợ | Viết file `docker-compose.yml` khởi chạy trong project `ecom`: PostgreSQL (tạo sẵn 6 database), Apache Kafka (KRaft), Kafka UI (port 8089), Redis (port 6379), Elasticsearch (port 9200), MailHog (port 8025/1025). | `docker compose -p ecom up -d` khởi động ổn định, các container healthy, truy cập được Kafka UI và MailHog UI. | Không |
| **TSK-01.3** | Cấu hình Dockerfile cho từng Microservice | Viết multi-stage `Dockerfile` tối ưu kích thước image cho từng module Java Spring Boot. Viết cấu hình `docker-compose.apps.yml` để chạy toàn bộ services kết hợp với `docker-compose.yml` (chạy lệnh `docker compose -p ecom -f docker-compose.yml -f docker-compose.apps.yml up -d --build`). | Các service build thành image Docker và start thành công trong container, kết nối thông suốt với `config-server` và hạ tầng trong project `ecom`. | TSK-01.1 |
| **TSK-01.4** | Xây dựng Spring Cloud Config Server & Kho cấu hình tập trung | Tạo module `config-server` (port 8888) với `@EnableConfigServer`, profile `native`. Tạo thư mục `config-repo/` chứa file cấu hình chung `application.yml` và các file cấu hình riêng cho từng service. Cấu hình Docker healthcheck cho `config-server`. | Endpoint `http://localhost:8888/<service-name>/default` trả về đúng cấu hình YAML từ `config-repo/`. | TSK-01.1 |


---

### GIAI ĐOẠN 2: THƯ VIỆN DÙNG CHUNG (COMMON LIBRARY) (PHASE 2)
| Task ID | Tên Task | Mô tả chi tiết | Tiêu chí nghiệm thu (Acceptance Criteria) | Phụ thuộc |
| :--- | :--- | :--- | :--- | :--- |
| **TSK-02.1** | Chuẩn hóa API Response & Error Handling | Xây dựng `ApiResponse<T>` chuẩn theo Phụ lục A của SRS: `code`, `message`, `data`, `correlationId`, `details`. Định nghĩa danh mục mã lỗi chuẩn theo Phụ lục B (`VALIDATION_ERROR`, `RESOURCE_NOT_FOUND`, `STATE_CONFLICT`,...). | Controller trả về đúng định dạng JSON chuẩn; GlobalExceptionHandler bắt lỗi và map mã HTTP chính xác. | TSK-01.1 |
| **TSK-02.2** | Correlation ID & Logging Filter | Xây dựng bộ lọc gán/chuyển tiếp `X-Correlation-Id` qua SLF4J MDC; cấu hình log có cấu trúc JSON. | Mọi dòng log đều in kèm `correlationId` xuyên suốt request. | TSK-02.1 |
| **TSK-02.3** | Mô hình Transactional Outbox dùng chung | Xây dựng Entity `OutboxEvent`, Repository và Polling Publisher Service tái sử dụng cho các microservice. Hỗ trợ đầy đủ vòng đời trạng thái: `PENDING`, `PUBLISHED`, `FAILED_RETRYABLE` (với exponential backoff), và `DEAD_LETTER`. | Lưu thành công event vào DB cục bộ; quét publish lên Kafka kèm cập nhật trạng thái `PUBLISHED`; chuyển sang `FAILED_RETRYABLE` khi broker lỗi và `DEAD_LETTER` sau ngưỡng retry tối đa. | TSK-01.2 |
| **TSK-02.4** | Event Contract & Idempotent Consumer | Định nghĩa POJO cho các Domain Event (`OrderCreatedEvent`, `PaymentLateSuccessEvent`, `ReservationCommitFailedEvent`,...). Xây dựng cơ chế Idempotent Consumer dựa trên bảng `processed_events` có ràng buộc duy nhất `(eventId, consumerGroup)`. | Consumer độc lập không bỏ qua event của nhau; tự động bỏ qua event trùng lặp trong cùng group. | TSK-01.2 |
| **TSK-02.5** | Quản lý Outbox Dead-Letter & Replay API | Xây dựng cơ chế chuyển sang `DEAD_LETTER` sau ngưỡng retry tối đa (10 lần); cung cấp Replay API (`POST /api/v1/admin/{service}/outbox/replay/{id}` và nội bộ `POST /internal/outbox/replay/{id}`) được kiểm soát quyền `ROLE_ADMINISTRATOR` cho phép quản trị viên tái phát sự kiện sau khi khắc phục lỗi broker/mạng. | Đảm bảo cam kết At-least-once bền vững, không bao giờ mất mát sự kiện. | TSK-02.3 |

---

### GIAI ĐOẠN 3: API GATEWAY (PHASE 3)
| Task ID | Tên Task | Mô tả chi tiết | Tiêu chí nghiệm thu (Acceptance Criteria) | Phụ thuộc |
| :--- | :--- | :--- | :--- | :--- |
| **TSK-03.1** | Cấu hình Dynamic Routing & Versioning | Cấu hình Spring Cloud Gateway định tuyến: `/api/v1/auth/**`, `/api/v1/users/**` -> `identity-service`, `/api/v1/catalog/**` -> `catalog-service`, `/api/v1/inventory/**` -> `inventory-service`, `/api/v1/orders/**` -> `order-service`, `/api/v1/payments/**` -> `payment-service`, `/api/v1/admin/notifications/**` -> `notification-service`, và các route quản trị Outbox Replay canonical `/api/v1/admin/{service}/outbox/**` (kết hợp phân quyền đa tầng: JwtAuthenticationFilter tại Gateway và `@PreAuthorize("hasRole('ADMINISTRATOR')")` tại từng service). | Request gọi qua Gateway (port 8080) được chuyển tiếp đúng service đích tương ứng, bao gồm cả các API quản trị Outbox Replay được kiểm soát quyền hạn chặt chẽ. | TSK-01.1 |
| **TSK-03.2** | Sanitization & JWT Authentication Filter | `SanitizationGlobalFilter` (@Order(-1)) strip sạch 100% header `X-User-*`, `X-Gateway-Assertion`, `X-Calling-Service` và `X-Internal-Service-Key` trên mọi routes; `JwtAuthenticationFilter` xác minh JWT token trên protected routes, ký khẳng định `X-Gateway-Assertion` bằng Private Key RSA kèm claim `aud` trói buộc định danh service đích và gán claims đã kiểm chứng vào header downstream. | Header giả mạo bị xóa bỏ; chặn request sai token với HTTP 401; chuyển tiếp request hợp lệ kèm header nhận diện user và assertion có chữ ký RSA trói buộc audience. | TSK-02.1 |
| **TSK-03.3** | Rate Limiting bằng Redis & Load-test Profile Bypass | Tích hợp Redis RateLimiter trên Gateway áp dụng thuật toán Token Bucket cho các endpoint nhạy cảm (login: 10/min, register: 5/min, refresh-token: 20/min, reset/forgot-password: 5/min, resend-verification: 3/min, checkout: 5/min, webhook: 120/min). Bổ sung `RateLimiterBypassFilter` kiểm tra header `X-Load-Test-Secret` (chỉ whitelist cho các route đọc Catalog) để bypass rate limiting khi chạy benchmark tải thông lượng 500–1.000 req/s. | Khi vượt quá ngưỡng cấu hình, trả về HTTP 429 `RATE_LIMIT_EXCEEDED` kèm `Retry-After`; cho phép bypass an toàn khi có secret hợp lệ trên route cho phép. | TSK-01.2 |
| **TSK-03.4** | Cấu hình CORS & Security Headers | Cấu hình CORS whitelist an toàn, không dùng wildcard khi `allowCredentials: true`; gắn security headers (`X-Content-Type-Options`, `X-Frame-Options`, `Strict-Transport-Security`). | Trình duyệt và Postman gọi được API hợp lệ; chặn origin không nằm trong whitelist. | TSK-03.1 |

---

### GIAI ĐOẠN 4: IDENTITY & USER SERVICE (PHASE 4)
| Task ID | Tên Task | Mô tả chi tiết | Tiêu chí nghiệm thu (Acceptance Criteria) | Phụ thuộc |
| :--- | :--- | :--- | :--- | :--- |
| **TSK-04.1** | Thiết kế Database & Liquibase/Flyway Migration | Thiết kế các bảng: `users` (có `email_verified`, status `PENDING_VERIFICATION`), `password_resets`, `roles`, `user_roles`, `addresses`, `audit_logs`, `outbox_events`. | Migration chạy tự động khi khởi động service tạo đầy đủ bảng và seed dữ liệu 6 vai trò mặc định. | TSK-01.2 |
| **TSK-04.2** | Đăng ký, Kích hoạt Email & Đăng nhập (AuthN) | API `POST /api/v1/auth/register` (tạo user `PENDING_VERIFICATION`, sinh token kích hoạt); API `POST /api/v1/auth/verify-email` (kích hoạt `ACTIVE`); API `POST /api/v1/auth/login` (chỉ cho phép user `ACTIVE` đăng nhập, cấp token). | Đăng ký gửi email kích hoạt; chỉ tài khoản đã xác minh mới đăng nhập được; cấp cặp Access Token & Refresh Token. | TSK-04.1, TSK-02.1 |
| **TSK-04.3** | Quản lý Refresh Token & Thu hồi phiên | API `POST /api/v1/auth/refresh-token` (cơ chế xoay vòng token - rotation và phát hiện reuse); API `POST /api/v1/auth/logout`. | Refresh Token cũ bị vô hiệu hóa khi cấp mới; phát hiện token tái sử dụng thì thu hồi toàn bộ session. | TSK-04.2 |
| **TSK-04.4** | Hồ sơ cá nhân & Sổ địa chỉ | API `GET/PUT /api/v1/users/profile`; API CRUD `/api/v1/users/addresses` (hỗ trợ đặt địa chỉ mặc định, kiểm tra quyền sở hữu). | User chỉ sửa được hồ sơ và địa chỉ của chính mình; địa chỉ mặc định được cập nhật chính xác. | TSK-04.2 |
| **TSK-04.5** | Phân quyền RBAC & Quản trị IAM | Spring Method Security (`@PreAuthorize`) bảo vệ API theo role; API cho Quản trị viên gán/thu hồi quyền người dùng; ghi audit log cho mọi thay đổi vai trò. | Chỉ `Administrator` mới được cấp quyền; audit log ghi nhận đầy đủ actor, action, timestamp. | TSK-04.2 |
| **TSK-04.6** | Quên Mật Khẩu, Đổi Mật Khẩu & Thu Hồi Đa Phiên | API `POST /api/v1/auth/forgot-password`, `reset-password`, `change-password` và `revoke-all-sessions` (thu hồi toàn bộ Refresh Token trên mọi thiết bị); bảo mật API `/internal/users/{id}` qua cặp header caller-specific `X-Calling-Service` và `X-Internal-Service-Key` (phân quyền scoped role `@PreAuthorize("hasAnyRole('SERVICE_ORDER', 'SERVICE_NOTIFICATION')")`). | Đặt lại mật khẩu an toàn qua OTP/token có hạn; thu hồi cưỡng chế toàn bộ phiên khi cần; internal API chặn truy cập ngoài và kiểm soát danh tính caller. | TSK-04.2 |

---

### GIAI ĐOẠN 5: PRODUCT CATALOG & SEARCH SERVICE (PHASE 5)
| Task ID | Tên Task | Mô tả chi tiết | Tiêu chí nghiệm thu (Acceptance Criteria) | Phụ thuộc |
| :--- | :--- | :--- | :--- | :--- |
| **TSK-05.1** | Thiết kế Database & Quản lý Danh mục/Thương hiệu | Schema: `categories`, `brands`, `products`, `skus`, `product_images`. API CRUD Danh mục (cây phân cấp, thuật toán DFS/BFS ngăn chu trình lặp); API CRUD Thương hiệu. | Ngăn chặn vòng lặp danh mục cha-con; không cho phép xóa cứng danh mục đang có sản phẩm. | TSK-01.2 |
| **TSK-05.2** | Quản lý Sản phẩm & Biến thể SKU | API CRUD Sản phẩm và biến thể SKU (giá Decimal, tiền tệ VND, mã SKU duy nhất toàn hệ thống, đa thuộc tính màu/size, upload/link ảnh). | Kiểm tra tính duy nhất của mã SKU; giá không âm; sản phẩm chỉ active khi cả Product và SKU đều active. | TSK-05.1 |
| **TSK-05.3** | Đồng bộ dữ liệu sang Elasticsearch qua Outbox | Khi Product/SKU thay đổi, ghi event vào `outbox_events`; Outbox Publisher đẩy lên topic `catalog.events`; Consumer đồng bộ dữ liệu vào Elasticsearch index. | Dữ liệu trên Elasticsearch tự động cập nhật khớp với database chính; có API reindex toàn bộ. | TSK-05.2, TSK-02.3 |
| **TSK-05.4** | API Tìm kiếm Full-text, Lọc & Phân trang | API `GET /api/v1/catalog/search` truy vấn Elasticsearch: tìm kiếm theo tên, mô tả, SKU; lọc theo category, brand, khoảng giá, trạng thái còn hàng; phân trang và sắp xếp. | Tốc độ tìm kiếm p95 < 500ms; kết quả khớp chính xác theo bộ lọc; phân trang ổn định. | TSK-05.3 |
| **TSK-05.5** | API Xem chi tiết sản phẩm / SKU | API `GET /api/v1/catalog/products/{id}` hiển thị đầy đủ thông tin, danh sách biến thể SKU, giá hiện hành, hình ảnh và trạng thái bán. | Trả về dữ liệu chi tiết cho khách hàng xem và chọn SKU. | TSK-05.2 |

---

### GIAI ĐOẠN 6: INVENTORY SERVICE (PHASE 6)
| Task ID | Tên Task | Mô tả chi tiết | Tiêu chí nghiệm thu (Acceptance Criteria) | Phụ thuộc |
| :--- | :--- | :--- | :--- | :--- |
| **TSK-06.1** | Schema Tồn kho & Sổ cái Biến động (Stock Ledger) | Bảng: `stocks` (`sku_id`, `warehouse_id`, `on_hand`, `reserved`, `available`), `stock_movements`, `reservations`, `reservation_items`, `inventory_quarantine_logs`. | Khởi tạo tồn kho; đảm bảo công thức `available = on_hand - reserved` luôn đúng; sẵn sàng bảng lưu vết đơn cô lập `inventory_quarantine_logs`. | TSK-01.2 |
| **TSK-06.2** | API Điều chỉnh tồn kho & Audit Ledger | API cho `InventoryStaff` nhập kho/điều chỉnh kho kèm lý do bắt buộc; ghi bản ghi `stock_movements` bất biến (Append-only). | Không cho phép cập nhật làm số lượng âm; lưu đầy đủ lịch sử biến động. | TSK-06.1 |
| **TSK-06.3** | Cơ chế Khóa giữ kho (Reservation) chống Overselling | API giữ kho: Atomic Conditional Update (`UPDATE stocks SET reserved = reserved + :qty WHERE available >= :qty`) hoặc Redis Distributed Lock; tạo bản ghi `reservations` có `expires_at` (15 phút). | Giữ kho đồng thời không bị oversell; nếu 1 SKU trong đơn không đủ hàng thì rollback toàn bộ reservation. | TSK-06.1 |
| **TSK-06.4** | Commit & Release Reservation | API nội bộ `POST /internal/inventory/reservations/{orderId}/release` và Kafka Consumer xử lý: Commit trừ kho chính thức (`on_hand -= qty, reserved -= qty`) khi thanh toán thành công; Release trả lại kho (`reserved -= qty`) khi đơn hủy hoặc thanh toán thất bại. Nếu commit gặp `reservation == null` (orphan/mất dữ liệu), ghi log `inventory_quarantine_logs`, phát outbox lên topic nghiệp vụ `inventory.reservation-commit-failed` (để payment-service tự động hoàn tiền) và đẩy bản tin lỗi vào `inventory.reservation-commit.dlq` trước khi commit Kafka offset. | Đảm bảo tính Idempotent khi nhận event trùng lặp; không trừ hay hoàn kho 2 lần; cô lập an toàn đơn mồ côi qua DLQ và kích hoạt bồi hoàn tiền tự động. | TSK-06.3, TSK-02.4 |
| **TSK-06.5** | Scheduler quét Reservation quá hạn | Background Job (Spring `@Scheduled` hoặc Redis Key Expiration) tự động quét các reservation quá hạn 15 phút chưa thanh toán -> giải phóng kho và phát event `ReservationExpired`. | Reservation quá hạn được tự động giải phóng chính xác; an toàn khi chạy trên nhiều instance. | TSK-06.3 |

---

### GIAI ĐOẠN 7: ORDER SERVICE & CART (PHASE 7)
| Task ID | Tên Task | Mô tả chi tiết | Tiêu chí nghiệm thu (Acceptance Criteria) | Phụ thuộc |
| :--- | :--- | :--- | :--- | :--- |
| **TSK-07.1** | Quản lý Giỏ hàng (Cart API) | Schema `carts`, `cart_items`. API thêm SKU vào giỏ, cập nhật số lượng, xóa SKU, và làm rỗng giỏ `DELETE /api/v1/orders/cart`; tính tạm tính; validate lại giá và tồn kho khi load giỏ. | Thao tác giỏ hàng KHÔNG giữ tồn kho; hiển thị cảnh báo nếu SKU bị đổi giá hoặc hết hàng; hỗ trợ làm rỗng giỏ deterministic cho testing. | TSK-01.2 |
| **TSK-07.2** | Tạo Đơn hàng (Checkout) & Snapshot dữ liệu | Schema `orders`, `order_items`, `order_status_history`. API `POST /api/v1/orders/checkout` nhận `Idempotency-Key`; chụp Snapshot sản phẩm (tên, giá, SKU) và Snapshot địa chỉ giao hàng. | Gọi lặp cùng `Idempotency-Key` không tạo thêm đơn; đơn hàng lưu snapshot bất biến, không bị đổi khi Catalog đổi. | TSK-07.1, TSK-06.3 |
| **TSK-07.3** | State Machine & Vòng đời Đơn hàng | Cài đặt máy trạng thái: `PENDING` -> `CONFIRMED` -> `SHIPPING` -> `COMPLETED`, hoặc `CANCELLED`. Kiểm soát chặt chẽ điều kiện chuyển trạng thái và ghi log lịch sử. | Chặn hoàn toàn các bước chuyển trạng thái không hợp lệ; phát domain event qua Outbox mỗi lần đổi trạng thái. | TSK-07.2, TSK-02.3 |
| **TSK-07.4** | Tra cứu & Quản lý Đơn hàng | API cho Khách hàng xem danh sách/chi tiết đơn của chính mình; API cho `OrderStaff` tra cứu, lọc và cập nhật trạng thái đơn (sang `SHIPPING`, `COMPLETED`). | Chống lỗ hổng IDOR (User không xem được đơn của người khác); nhân viên vận hành thao tác đúng quyền. | TSK-07.3 |
| **TSK-07.5** | Yêu cầu Hủy đơn hàng (Order Cancellation) | API cho Khách hàng hủy đơn ở trạng thái `PENDING`; API cho nhân viên hủy đơn; phát sự kiện `OrderCancelled` để kích hoạt Saga hủy. | Hủy đơn chuyển trạng thái an toàn, phát event kích hoạt hoàn tồn và hoàn tiền. | TSK-07.3 |

---

### GIAI ĐOẠN 8: PAYMENT SERVICE & TÍCH HỢP MOMO (PHASE 8)
| Task ID | Tên Task | Mô tả chi tiết | Tiêu chí nghiệm thu (Acceptance Criteria) | Phụ thuộc |
| :--- | :--- | :--- | :--- | :--- |
| **TSK-08.1** | Schema Thanh toán & Quản lý Payment Intent | Schema `payments`, `refunds`, `payment_audit_logs`. API tạo Payment Intent gắn với `orderId`; lấy số tiền từ snapshot đơn hàng. | Tuyệt đối không nhận số tiền từ client gửi lên; tạo Payment Intent ở trạng thái `PENDING`. | TSK-01.2 |
| **TSK-08.2** | Tích hợp MoMo Sandbox API | Triển khai MoMo Adapter: tạo yêu cầu thanh toán (tạo QR Code / App redirect URL) qua MoMo API; tính toán chữ ký số HMAC-SHA256 theo tài liệu MoMo. | Gọi thành công MoMo Sandbox, nhận về `payUrl` và `qrCodeUrl` để khách hàng thanh toán. | TSK-08.1 |
| **TSK-08.3** | Xử lý MoMo IPN Webhook an toàn | Endpoint `POST /api/v1/payments/momo/ipn`: Xác minh chữ ký số MoMo, kiểm tra `orderId`, `amount`; xử lý Idempotent; cập nhật `payments` sang `SUCCEEDED`/`FAILED`. | Webhook giả mạo chữ ký bị từ chối; Webhook trùng lặp xử lý an toàn; phát event `PaymentSucceeded` qua Outbox. | TSK-08.2, TSK-02.3 |
| **TSK-08.4** | Hoàn tiền (MoMo Refund) & Đối soát | API hoàn tiền qua MoMo Refund API; kiểm tra tổng tiền hoàn không vượt quá số tiền đã thu; tác vụ đối soát giao dịch định kỳ. | Hoàn tiền idempotent; phát event `RefundCompleted`; báo cáo sai lệch giao dịch nếu có. | TSK-08.3 |

---

### GIAI ĐOẠN 9: ĐIỀU PHỐI SAGA & TÍCH HỢP SỰ KIỆN KAFKA (PHASE 9)
| Task ID | Tên Task | Mô tả chi tiết | Tiêu chí nghiệm thu (Acceptance Criteria) | Phụ thuộc |
| :--- | :--- | :--- | :--- | :--- |
| **TSK-09.1** | Saga Luồng Đặt hàng Thành công (Happy Path) | Kết nối chuỗi sự kiện: Tạo đơn (`PENDING`) -> Giữ kho -> Tạo MoMo Payment Intent -> MoMo Webhook báo thành công -> Trừ kho chính thức -> Đơn chuyển `CONFIRMED`. | Toàn bộ các bước hội tụ trạng thái nhất quán giữa 3 service; dữ liệu tồn và đơn chính xác. | TSK-06.4, TSK-07.3, TSK-08.3 |
| **TSK-09.2** | Saga Luồng Thất bại & Bồi hoàn (Compensating Actions) | Xử lý khi thanh toán thất bại hoặc hết hạn 15 phút: Giải phóng reservation -> Đổi đơn thành `CANCELLED`. Xử lý khi đơn bị lỗi sau khi đã trừ tiền: Kích hoạt hoàn tiền MoMo. | Không để tồn kho bị giam vô hạn; tiền được hoàn đúng tài khoản; hệ thống tự phục hồi nhất quán. | TSK-09.1 |
| **TSK-09.3** | Xử lý Dead Letter Queue (DLQ) & Quan sát Saga | Cấu hình Kafka DLQ cho các message lỗi nghiệp vụ/quá số lần retry; xây dựng dashboard/log theo dõi các Saga bị treo. | Message lỗi không làm nghẽn Kafka consumer; có log cảnh báo và khả năng retry thủ công. | TSK-09.1, TSK-02.4 |

---

### GIAI ĐOẠN 10: NOTIFICATION SERVICE (PHASE 10)
| Task ID | Tên Task | Mô tả chi tiết | Tiêu chí nghiệm thu (Acceptance Criteria) | Phụ thuộc |
| :--- | :--- | :--- | :--- | :--- |
| **TSK-10.1** | Schema Thông báo & Kafka Consumer | Schema `notification_logs` (hỗ trợ `QUEUED`, `SENT`, `DELIVERED`, `FAILED`, `DEAD_LETTER`, `REDRIVING`, `EXPIRED_UNSENT`), `notification_templates`. Consumer lắng nghe các event: Đăng ký thành công, Đặt hàng thành công, Thanh toán thành công, Đơn hàng bị hủy. | Tiêu thụ message bền vững; ghi log trạng thái gửi tin đầy đủ. | TSK-01.2, TSK-09.1 |
| **TSK-10.2** | Template Email & Gửi mail qua MailHog | Tích hợp Thymeleaf render template email; gửi email xác nhận qua MailHog (SMTP port 1025). | Email hiển thị đẹp mắt, đầy đủ chi tiết đơn hàng/mã xác nhận; xem được email trên giao diện Web MailHog. | TSK-10.1 |
| **TSK-10.3** | Resilience Test: Notification Service Down | Kiểm thử kịch bản: Tắt Notification Service, thực hiện đặt hàng và thanh toán. Bật lại Notification Service. | Đơn hàng vẫn tạo thành công; sau khi service sống lại, toàn bộ email được gửi bù đầy đủ, không mất mát nhờ cơ chế offset commit và idempotency. | TSK-10.2 |
| **TSK-10.4** | Auto-Redrive Worker & Admin Redrive API | Xây dựng Scheduled Job quét `notification_logs` có `status = 'DEAD_LETTER'` sau sự cố mạng/SMTP để tự động gửi lại; xây dựng API `POST /api/v1/admin/notifications/redrive-dlq`. | Tự động hồi phục và gửi bù các email bị nghẽn sau sự cố hạ tầng; hỗ trợ kích hoạt thủ công từ admin. | TSK-10.1 |

---

### GIAI ĐOẠN 11: POSTMAN COLLECTION, KIỂM THỬ TẢI & HOÀN THIỆN (PHASE 11)
| Task ID | Tên Task | Mô tả chi tiết | Tiêu chí nghiệm thu (Acceptance Criteria) | Phụ thuộc |
| :--- | :--- | :--- | :--- | :--- |
| **TSK-11.1** | Xây dựng Toàn bộ Postman Collection & Environments | Tạo Postman Collection đầy đủ các API (Auth, Catalog, Cart, Inventory, Order, MoMo Payment, Admin). Thiết lập biến môi trường, tự động lưu JWT token sau login. Tài khoản Staff/Admin được seed sẵn hoặc phân quyền qua API admin. | Postman chạy được toàn bộ luồng E2E từ đăng ký đến thanh toán MoMo và tra cứu đơn hàng; không mở API đăng ký staff công khai. | Tất cả service |
| **TSK-11.2** | Viết kịch bản Kiểm thử Tải & Đồng thời (k6 Load Test) | Tách bạch 2 kịch bản: 1) **Concurrency Test:** Dùng `per-vu-iterations` (500 VUs x 1 iteration) kiểm thử 500 khách hàng đồng thời mua 1 SKU tồn 10 chiếc; hàm `setup()` thực hiện reset giỏ hàng về rỗng và pre-seeding đúng 1 đơn vị SKU vào 500 giỏ hàng độc lập (fail-fast ngay nếu không đủ 500); 2) **Throughput Test:** Dùng `constant-arrival-rate` kiểm thử 2 giai đoạn nối tiếp chứng minh cả hai mốc 500 req/s và 1.000 req/s trên các API duyệt danh mục. | Concurrency Test: Đúng 10 đơn thành công (`orders_created = 10`), 490 đơn báo mã `INVENTORY_INSUFFICIENT`, không phát sinh lỗi bất thường (`unexpected_errors = 0`), tồn khả dụng = 0, `dropped_iterations = 0`. Throughput Test: Đạt cả hai mốc 500 req/s và 1.000 req/s, p95 < 200ms, error rate < 1%. | TSK-06.3, TSK-07.2 |
| **TSK-11.3** | Kiểm thử Bảo mật & Rà soát OWASP API Top 10 | Kiểm tra AuthN, AuthZ, BOLA/IDOR, Rate limit, che PII trong log, chữ ký Webhook MoMo. | Đạt các tiêu chí bảo mật NFR-SEC-001 đến NFR-SEC-013. | TSK-11.1 |
| **TSK-11.4** | Hướng dẫn Chạy Dự án & Báo cáo Nghiệm thu | Hoàn thiện tài liệu `README.md`: Hướng dẫn cấu hình môi trường, 1 lệnh `docker compose -p ecom -f docker-compose.yml -f docker-compose.apps.yml up -d --build` chạy toàn bộ hệ thống, hướng dẫn import Postman. | Người khác clone repo có thể khởi chạy và test thành công ngay lập tức. | TSK-11.1, TSK-11.2 |
