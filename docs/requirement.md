# TÀI LIỆU YÊU CẦU DỰ ÁN (PROJECT REQUIREMENTS)
## Hệ thống Thương mại điện tử đa kênh theo kiến trúc Microservices
**Mã tài liệu nguồn:** SRS-OMNI-ECOM-001 (v1.0 - IEEE 830-1998)  
**Ngày trích xuất/biên soạn:** 2026-10-11  

---

## 1. TỔNG QUAN & PHẠM VI (SCOPE DEFINITION)

### 1.1. Mục tiêu hệ thống & Quyết định Công nghệ (In-Scope v1.0)
Hệ thống cung cấp nền tảng bán lẻ trực tuyến thông qua một điểm truy cập API thống nhất (API Gateway). 
* **Phạm vi triển khai:** Chỉ tập trung phát triển **Back-end Microservices APIs** và **Tài liệu kiểm thử Postman / OpenAPI**. Không bao gồm giao diện Web Frontend và Mobile App.
* **Ngôn ngữ & Nền tảng:** **Java Spring Boot**, kiến trúc **Monorepo** chia các service thành các Maven/Gradle module.
* **Cơ sở dữ liệu:** **PostgreSQL** (mỗi service 1 database riêng theo chuẩn Database-per-service). Tách riêng độc lập **Order Service** và **Inventory Service**.
* **Message Broker:** **Apache Kafka** cho giao tiếp sự kiện bất đồng bộ và Saga.
* **Cổng thanh toán:** **MoMo (Sandbox)** tích hợp qua chuẩn Adapter và Webhook chữ ký số.
* **Hạ tầng triển khai:** **Docker Compose** container hóa toàn bộ hệ thống (dịch vụ và hạ tầng phụ trợ).

Phạm vi nghiệp vụ v1.0 bao gồm:
1. **Quản lý người dùng & phân quyền:** Đăng ký, đăng nhập (JWT/RBAC), quản lý hồ sơ, sổ địa chỉ.
2. **Quản lý danh mục & sản phẩm (Catalog):** Quản trị danh mục phân cấp, thương hiệu, sản phẩm, biến thể SKU, giá, hình ảnh, đồng bộ chỉ mục tìm kiếm full-text.
3. **Duyệt & Tìm kiếm:** Tìm kiếm full-text, lọc đa tiêu chí, phân trang, trang chi tiết SKU/sản phẩm.
4. **Giỏ hàng & Đơn hàng:** Giỏ hàng theo người dùng; tạo đơn hàng lưu snapshot giá/thông tin; quản lý vòng đời trạng thái đơn hàng (State Machine).
5. **Quản lý tồn kho thời gian thực:** Kiểm tra, giữ chỗ (Reservation), commit trừ tồn kho, hoàn trả tồn kho an toàn khi chịu tải cao (Flash Sale), chống Overselling tuyệt đối.
6. **Thanh toán & Đối soát:** Tích hợp cổng MoMo (Sandbox), xử lý Webhook bất đồng bộ idempotent, theo dõi giao dịch, hoàn tiền (Refund), đối soát định kỳ.
7. **Điều phối giao dịch phân tán (Distributed Transaction):** Mô hình Saga orchestration/choreography kèm hành động bù (Compensating Actions); Transactional Outbox Pattern tránh mất sự kiện.
8. **Dịch vụ thông báo (Notification):** Tiêu thụ sự kiện gửi Email (bắt buộc), SMS/Push (theo cấu hình); đảm bảo tính bền vững (DLQ, retry).

---

### 1.2. Ngoài phạm vi phiên bản 1.0 (Out-of-Scope)
Căn cứ Mục 1.2 & 2.7 của SRS cùng xác nhận từ Product Owner, các hạng mục sau **TUYỆT ĐỐI KHÔNG NẰM TRONG PHẠM VI v1.0**:
* **Giao diện Client (Frontend / Mobile):** Không phát triển ứng dụng Web Frontend và Mobile App (chỉ cung cấp APIs và Postman Collection).
* **Quản lý điểm bán vật lý (POS) và kho vật lý:** Không quản lý POS tại cửa hàng và không chuyển kho đa địa điểm nâng cao (v1.0 chỉ hỗ trợ 1 kho logic mặc định).
* **Đồng bộ sàn thương mại điện tử:** Không đồng bộ listing với sàn thứ ba (Shopee, Lazada, TikTok Shop,...) hoặc mạng xã hội.
* **Khuyến mãi phức tạp:** Không có loyalty points, voucher, coupon và promotion engine phức tạp.
* **Marketplace nhiều nhà bán:** Không có cơ chế multi-vendor, chia hoa hồng và thanh toán cho bên bán (Seller payout).
* **Sản phẩm phi vật lý:** Không hỗ trợ đấu giá, subscription (thuê bao), sản phẩm số (digital goods).
* **Vận tải chuyên sâu:** Không có hệ thống quản lý vận tải (TMS) hoặc thuật toán tối ưu tuyến giao hàng chuyên dụng.
* **AI & Phân tích nâng cao:** Không có AI recommendation engine, dynamic pricing và big data analytics.
* **Đa ngôn ngữ & Đa tiền tệ:** Không hỗ trợ i18n, đa quốc gia; chỉ hỗ trợ mặc định duy nhất đơn vị tiền tệ **VND**.
* **Đổi trả hàng sau giao:** Không có quy trình đổi trả hàng sau khi giao thành công (Return/RMA), ngoại trừ quy trình hoàn tiền cơ bản (Refund) khi hủy đơn trước/trong giao dịch.

---

## 2. CÁC RÀNG BUỘC KIẾN TRÚC & HỆ THỐNG (SYSTEM CONSTRAINTS)

* **CON-01:** Hệ thống PHẢI được phân tách thành tối thiểu 4 microservices có thể build, test và triển khai độc lập.
* **CON-02 (Database per Service):** Mỗi dịch vụ PHẢI sở hữu cơ sở dữ liệu riêng; cấm tuyệt đối việc join bảng hoặc truy vấn chéo trực tiếp database giữa các dịch vụ.
* **CON-03 (API Gateway):** Toàn bộ API công khai PHẢI đi qua API Gateway qua giao thức HTTPS.
* **CON-04 (Asynchronous Messaging):** Tối thiểu một luồng nghiệp vụ cốt lõi PHẢI giao tiếp qua Message Broker theo mô hình hướng sự kiện bất đồng bộ.
* **CON-05 (Distributed Saga):** Quy trình Đặt hàng – Tồn kho – Thanh toán PHẢI xử lý giao dịch phân tán bằng Saga kèm hành động bù (Compensating Actions).
* **CON-06 (Transactional Outbox):** Các dịch vụ phát sinh Domain Event PHẢI áp dụng Outbox Pattern (hoặc tương đương) đảm bảo At-least-once delivery, không làm mất sự kiện khi crash.
* **CON-09 (Secret Management):** Mật khẩu, private keys, API keys tuyệt đối không hardcode trong mã nguồn, phải quản lý qua cơ chế bảo mật/environment variables/secret vault.
* **DR-001 – DR-008 (Data Integrity):**
  * Định danh chia sẻ giữa các service phải là opaque identifier (UUID/ULID).
  * Lịch sử giao dịch tài chính, biến động tồn (Stock Ledger), trạng thái đơn, audit log là bất biến (Append-only).
  * Mọi mốc thời gian lưu trữ ở định dạng chuẩn UTC (ISO 8601).
  * Số tiền lưu bằng Decimal có độ chính xác cố định hoặc số nguyên đơn vị nhỏ nhất, không dùng số thực dấu phẩy động (Binary Float).

---

## 3. DANH SÁCH TOÀN BỘ YÊU CẦU CHỨC NĂNG (FUNCTIONAL REQUIREMENTS)

### 3.1. Phân hệ Định danh & Người dùng (Identity & Access Management - IAM)
* **FR-IAM-001:** Đăng ký tài khoản cho khách vãng lai bằng email hoặc số điện thoại (theo cấu hình).
* **FR-IAM-002:** Kiểm tra định dạng, tính duy nhất của định danh đăng nhập (đã chuẩn hóa).
* **FR-IAM-003:** Áp dụng chính sách mật khẩu mạnh (độ dài, ký tự); mật khẩu phải được băm an toàn (Argon2id, bcrypt), không lưu plaintext.
* **FR-IAM-004:** Tạo tài khoản và gửi yêu cầu kích hoạt/xác minh (nếu bật cấu hình).
* **FR-IAM-005:** Token xác minh dùng 1 lần, có hạn sử dụng, lưu trữ dạng hash/secure token.
* **FR-IAM-006:** Ghi Audit log khi đăng ký, kích hoạt, hoặc thay đổi định danh/mật khẩu.
* **FR-IAM-007:** Đăng nhập cấp cặp Access Token (ngắn hạn) và Refresh Token.
* **FR-IAM-008:** Phản hồi thông báo lỗi đăng nhập chung, không làm lộ sự tồn tại của tài khoản.
* **FR-IAM-009:** Rate limit và cơ chế chống Brute Force đối với login, refresh token, reset password.
* **FR-IAM-010:** Access Token JWT chứa subject, issued-at, expiration, roles/permissions.
* **FR-IAM-011:** Refresh Token có thể thu hồi (revoke), xoay vòng (rotation) và phát hiện reuse.
* **FR-IAM-012:** Đăng xuất phiên hiện tại; hỗ trợ thu hồi toàn bộ phiên (revoke all sessions).
* **FR-IAM-013:** Luồng Quên và Đặt lại mật khẩu qua token bảo mật một lần, có thời hạn.
* **FR-IAM-014:** Xem và cập nhật hồ sơ cá nhân (họ tên, phone,...).
* **FR-IAM-015:** CRUD sổ địa chỉ giao hàng, chỉ định địa chỉ mặc định.
* **FR-IAM-016:** Xác thực quyền sở hữu địa chỉ; từ chối truy cập chéo giữa các user.
* **FR-IAM-017:** RBAC hỗ trợ tối thiểu 6 vai trò: `Customer`, `CatalogStaff`, `InventoryStaff`, `OrderStaff`, `FinanceStaff`, `Administrator`.
* **FR-IAM-018:** Mọi endpoint nội bộ/bảo vệ phải xác thực (AuthN) và phân quyền (AuthZ) phía server.
* **FR-IAM-019:** Chỉ tài khoản quản trị IAM mới được gán/thu hồi quyền; mọi thay đổi phải ghi Audit log.

### 3.2. Phân hệ Quản lý Danh mục & Sản phẩm (Product Catalog)
* **FR-CAT-001:** Quản lý danh mục (Category): Tạo, xem, sửa, kích hoạt / ngừng kích hoạt.
* **FR-CAT-002:** Hỗ trợ cây danh mục phân cấp (Parent-Child) và thuật toán ngăn chặn chu trình lặp (Cycle prevention).
* **FR-CAT-003:** Quản lý thương hiệu (Brand): Tạo, xem, sửa, kích hoạt / ngừng kích hoạt.
* **FR-CAT-004:** Ngăn chặn xóa cứng (Hard delete) danh mục/thương hiệu đang có sản phẩm liên kết; bắt buộc chuyển trạng thái Inactive hoặc từ chối.
* **FR-CAT-005:** CRUD thông tin sản phẩm (Product): tên, mô tả, danh mục, thương hiệu, trạng thái, metadata.
* **FR-CAT-006:** Mỗi sản phẩm có 1 hoặc nhiều SKU; mã SKU phải là duy nhất trên toàn hệ thống.
* **FR-CAT-007:** Mỗi SKU có tập thuộc tính biến thể (màu sắc, kích cỡ,...), giá bán, loại tiền tệ (VND), trạng thái bán.
* **FR-CAT-008:** Giá bán là số không âm, lưu dạng Decimal/Integer đơn vị nhỏ nhất, không dùng binary float.
* **FR-CAT-009:** Hỗ trợ đa hình ảnh cho Product/SKU, chỉ định ảnh đại diện, kiểm tra định dạng MIME, kích thước tệp upload an toàn.
* **FR-CAT-010:** Sản phẩm chỉ hiển thị ra ngoài khi cả Product và SKU đều ở trạng thái công bố (Active/Published).
* **FR-CAT-011:** Ghi log lịch sử thay đổi (Audit trail) cho các cập nhật catalog, giá và SKU.
* **FR-CAT-012:** Phát sự kiện qua Outbox để cập nhật chỉ mục tìm kiếm Elasticsearch khi catalog có biến động (retry được, quan sát được).
* **FR-CAT-013:** Danh sách sản phẩm public có phân trang ổn định (Cursor/Offset pagination).
* **FR-CAT-014:** Tìm kiếm Full-text search theo tên, mô tả, mã SKU, thương hiệu, danh mục.
* **FR-CAT-015:** Lọc đa điều kiện: theo danh mục, thương hiệu, khoảng giá, trạng thái còn hàng, thuộc tính biến thể.
* **FR-CAT-016:** Sắp xếp: theo độ liên quan (Relevance), giá (tăng/giảm), ngày cập nhật/mới nhất.
* **FR-CAT-017:** Chi tiết sản phẩm hiển thị thông tin, danh sách SKU đang bán, giá hiện tại, hình ảnh và trạng thái còn hàng.
* **FR-CAT-018:** Nguồn sự thật (Single Source of Truth) là database chính của Catalog; Elasticsearch chỉ là read-model/chỉ mục dẫn xuất; phải có cơ chế Reindex toàn diện khi lệch dữ liệu.

### 3.3. Phân hệ Quản lý Giỏ hàng (Cart)
* **FR-CART-001:** Mỗi khách hàng đã đăng nhập có duy nhất 1 giỏ hàng hoạt động, đồng bộ xuyên suốt các kênh Web/Mobile.
* **FR-CART-002:** Thêm SKU đang mở bán vào giỏ với số lượng nguyên dương.
* **FR-CART-003:** Cập nhật số lượng hoặc xóa SKU khỏi giỏ hàng.
* **FR-CART-004:** Giới hạn số lượng tối đa đặt cho mỗi SKU/mỗi lần thêm theo cấu hình.
* **FR-CART-005:** Thao tác thêm vào giỏ hàng KHÔNG GIỮ TỒN KHO.
* **FR-CART-006:** Tự động đánh giá lại trạng thái bán, giá hiện hành và khả năng đáp ứng tồn kho mỗi khi load giỏ hàng.
* **FR-CART-007:** Hiển thị cảnh báo trực quan cho người dùng nếu sản phẩm trong giỏ bị đổi giá, hết hàng hoặc ngừng kinh doanh trước khi checkout.
* **FR-CART-008:** Tổng tiền trong giỏ hàng chỉ là ước tính; tổng tiền chính thức được chốt khi tạo đơn hàng.

### 3.4. Phân hệ Quản lý Tồn kho (Inventory)
* **FR-INV-001:** Quản lý tồn kho theo SKU và kho logic (v1.0 mặc định 1 kho).
* **FR-INV-002:** Quản lý 3 chỉ số cho mỗi SKU: `onHand` (thực tế), `reserved` (tạm giữ), `available = onHand - reserved`.
* **FR-INV-003:** Chức năng nhập/điều chỉnh tồn kho cho thủ kho kèm lý do bắt buộc.
* **FR-INV-004:** Mọi biến động tồn kho phải ghi vào sổ cái bất biến (Stock Movement Ledger: skuId, warehouseId, delta, type, referenceId, actor, timestamp).
* **FR-INV-005:** Tuyệt đối không cho phép `onHand`, `reserved`, hoặc `available` bị âm.
* **FR-INV-006:** Khi tạo đơn hàng, thực hiện kiểm tra và giữ tồn kho (Reservation) cho toàn bộ item trong đơn như một atomic operation.
* **FR-INV-007:** Bản ghi Reservation có mã duy nhất, orderId, skuId, quantity, status (`PENDING`, `COMMITTED`, `RELEASED`), thời điểm hết hạn (`expiresAt`).
* **FR-INV-008:** Yêu cầu giữ tồn kho lặp lại với cùng Idempotency Key / OrderId phải trả về kết quả nhất quán, không giữ kho 2 lần.
* **FR-INV-009 (Chống Overselling):** Bảo đảm không oversell dưới tải đồng thời cao (Flash Sale) bằng Conditional Update (Atomic Update: `available >= qty`), Database Lock, hoặc Distributed Lock (Redis Redlock).
* **FR-INV-010:** Nếu bất kỳ item nào trong đơn không đủ hàng, toàn bộ reservation của đơn đó phải bị hủy/rollback (All-or-Nothing).
* **FR-INV-011:** Reservation tự động hết hạn và giải phóng tồn kho (`reserved -= qty`) sau khoảng thời gian cấu hình (mặc định 15 phút) nếu chưa thanh toán.
* **FR-INV-012:** Khi thanh toán thành công, chuyển Reservation thành trừ tồn chính thức (`onHand -= qty`, `reserved -= qty`) đúng 1 lần.
* **FR-INV-013:** Khi đơn hàng bị hủy trước khi thanh toán, giải phóng Reservation đúng 1 lần.
* **FR-INV-014:** Khi đơn hàng bị hủy sau khi đã trừ kho chính thức, thực hiện giao dịch hoàn tồn (Stock movement type: RESTOCK) có tham chiếu đơn gốc.
* **FR-INV-015:** Job quét Reservation hết hạn (Scheduler / Consumer) phải an toàn khi chạy song song trên nhiều instance (Idempotent / Distributed Lock).

### 3.5. Phân hệ Quản lý Đơn hàng (Order)
* **FR-ORD-001:** Chỉ người dùng đã đăng nhập mới được tạo đơn hàng trong v1.0.
* **FR-ORD-002:** Tạo đơn hàng bắt buộc truyền `Idempotency-Key` trên Header; gửi trùng lặp cùng payload không tạo đơn mới.
* **FR-ORD-003:** Kiểm tra quyền sở hữu địa chỉ, giỏ hàng không rỗng, SKU hợp lệ, giá và tồn kho trước khi chấp nhận đơn.
* **FR-ORD-004:** Chụp Snapshot dữ liệu sản phẩm tại thời điểm mua: mã/tên SKU, tên sản phẩm, biến thể, đơn giá, số lượng, giảm giá, thành tiền.
* **FR-ORD-005:** Chụp Snapshot thông tin người nhận và địa chỉ giao hàng tại thời điểm đặt hàng.
* **FR-ORD-006:** Tính toán chi tiết các khoản tiền: Tạm tính (subtotal), Giảm giá (discount), Thuế (tax), Phí vận chuyển (shipping fee), Tổng tiền thanh toán (grand total).
* **FR-ORD-007:** Sinh mã đơn hàng (`orderCode`) duy nhất, thân thiện với người dùng, không chứa PII.
* **FR-ORD-008:** Lưu đơn và reservation thành công, Order Service phát domain event `OrderCreated` qua Transactional Outbox.
* **FR-ORD-009:** Nếu giữ kho thất bại, chuyển đơn sang trạng thái `FAILED`/`CANCELLED` ngay lập tức, không cho phép thanh toán.
* **FR-ORD-010:** Quản lý vòng đời đơn hàng theo State Machine nghiêm ngặt:
  * Trạng thái tối thiểu: `PENDING` -> `CONFIRMED` -> `SHIPPING` -> `COMPLETED`, hoặc `CANCELLED`.
  * Cấm các bước chuyển trạng thái không hợp lệ.
* **FR-ORD-011:** Ghi log chi tiết lịch sử chuyển trạng thái (Order Status History): trạng thái cũ, trạng thái mới, lý do, người thao tác, thời gian.
* **FR-ORD-012:** Khách hàng chỉ xem được đơn hàng của chính mình (chống IDOR).
* **FR-ORD-013:** Nhân viên vận hành được tìm kiếm, lọc đơn hàng theo mã, khách hàng, trạng thái, thời gian.
* **FR-ORD-014:** Nhân viên vận hành chuyển trạng thái đơn hàng theo đúng phân quyền.
* **FR-ORD-015:** Mỗi lần chuyển trạng thái đơn hàng thành công, phát domain event tương ứng (`OrderStatusChanged`, `OrderConfirmed`,...) qua Outbox.
* **FR-ORD-016:** Khách hàng có quyền yêu cầu hủy đơn khi đơn ở trạng thái cho phép (`PENDING` hoặc theo chính sách).
* **FR-ORD-017:** Lưu lý do hủy và tác nhân hủy đơn.
* **FR-ORD-018:** Hủy đơn kích hoạt Saga hủy: giải phóng/hoàn tồn kho và khởi tạo yêu cầu hoàn tiền nếu đã thanh toán.
* **FR-ORD-019:** Đơn chỉ chuyển `CANCELLED` khi các bước tiên quyết của Saga hủy hoàn tất.
* **FR-ORD-020:** Yêu cầu hủy đơn lặp lại phải idempotent.

### 3.6. Phân hệ Thanh toán & Đối soát (Payment & Billing)
* **FR-PAY-001:** Khởi tạo Payment Intent liên kết với đơn hàng, số tiền, loại tiền (VND), cổng thanh toán (provider), trạng thái.
* **FR-PAY-002:** Số tiền thanh toán bắt buộc lấy từ snapshot của đơn hàng, TUYỆT ĐỐI không nhận số tiền từ client gửi lên.
* **FR-PAY-003:** Khởi tạo thanh toán phải idempotent theo đơn hàng và lượt thanh toán (`paymentAttempt`).
* **FR-PAY-004:** Tích hợp ít nhất 1 cổng thanh toán (Sandbox: VNPay, MoMo, ZaloPay, Stripe); thiết kế mẫu Adapter để dễ mở rộng cổng khác.
* **FR-PAY-005:** Lưu mã giao dịch provider (`providerRef`), không lưu thông tin thẻ nhạy cảm (PAN, CVV) tuân thủ chuẩn an toàn.
* **FR-PAY-006:** Quản lý trạng thái giao dịch: `PENDING`, `SUCCEEDED`, `FAILED`, `EXPIRED`, `REFUND_PENDING`, `REFUNDED`, `PARTIALLY_REFUNDED`.
* **FR-PAY-007:** Webhook endpoint bắt buộc kiểm tra chữ ký số (HMAC / RSA signature verification) từ provider.
* **FR-PAY-008:** Kiểm tra timestamp / replay attack protection trên Webhook request.
* **FR-PAY-009:** Xử lý Webhook trùng lặp an toàn (Idempotency), không ghi nhận thanh toán 2 lần hoặc phát trùng sự kiện.
* **FR-PAY-010:** Đối chiếu nghiêm ngặt `orderId`, `amount`, `currency` từ Webhook với Payment Intent trước khi đánh dấu thành công.
* **FR-PAY-011:** Chỉ coi Webhook/truy vấn trực tiếp provider là nguồn sự thật xác nhận thanh toán; không tin cậy redirect từ trình duyệt của khách hàng.
* **FR-PAY-012:** Cập nhật kết quả thanh toán xong, Payment Service phát event (`PaymentSucceeded`, `PaymentFailed`) qua Outbox.
* **FR-PAY-013:** Phản hồi HTTP 200/SUCCESS nhanh chóng cho Provider Webhook; tác vụ xử lý nghiệp vụ nặng chuyển sang nền/bất đồng bộ.
* **FR-PAY-014:** Nhân viên tài chính có quyền yêu cầu hoàn tiền (Refund) toàn phần hoặc từng phần.
* **FR-PAY-015:** Tổng số tiền hoàn lũy kế không được vượt quá số tiền thực tế đã thanh toán thành công.
* **FR-PAY-016:** Yêu cầu hoàn tiền phải idempotent, lưu lý do, người phê duyệt và audit trail.
* **FR-PAY-017:** Kết quả hoàn tiền cập nhật giao dịch và phát event `RefundCompleted`.
* **FR-PAY-018:** Tác vụ đối soát định kỳ (Reconciliation Job) so sánh dữ liệu nội bộ và báo cáo provider, phát hiện sai lệch.
* **FR-PAY-019:** Cấm sửa/xóa trực tiếp giao dịch tài chính đã chốt; mọi điều chỉnh phải tạo bản ghi bù trừ có tham chiếu.

### 3.7. Điều phối Saga & Giao tiếp Sự kiện (Saga Orchestration & Event-Driven)
* **FR-SAGA-001:** Mỗi Saga instance có mã định danh (`sagaId`), trạng thái, `correlationId` và nhật ký bước xử lý (`saga_step_log`).
* **FR-SAGA-002:** Luồng đặt hàng chuẩn đảm bảo hội tụ dữ liệu giữa Order, Inventory, Payment.
* **FR-SAGA-003:** Khi thanh toán thất bại/quá hạn: kích hoạt bù giải phóng tồn kho (`Release Reservation`) và đổi trạng thái đơn.
* **FR-SAGA-004:** Khi thanh toán thành công nhưng xác nhận đơn thất bại: kích hoạt bù hoàn tiền tự động hoặc đẩy vào queue cảnh báo can thiệp thủ công.
* **FR-SAGA-005:** Toàn bộ Compensating Action (hành động bù) phải idempotent và lưu audit trail.
* **FR-SAGA-006:** Dịch vụ tạm thời không khả dụng phải có Retry Policy (Exponential backoff + Jitter); lỗi nghiệp vụ dứt khoát không retry vô hạn.
* **FR-SAGA-007:** Xử lý Out-of-order Events an toàn dựa trên versioning hoặc kiểm tra tính hợp lệ của state transition.
* **FR-SAGA-008:** Outbox Publisher quét bảng `outbox`, publish lên broker, đánh dấu trạng thái đã phát (có cơ chế republish khi broker phục hồi).
* **FR-SAGA-009:** Message Consumer lưu bảng `processed_events` (hoặc kiểm tra business key) để đảm bảo Idempotency trước At-least-once delivery.
* **FR-SAGA-010:** Dashboard/CLI quản trị cho phép quan sát các Saga bị lỗi (Stuck/Failed Saga), kích hoạt retry hoặc trigger bồi hoàn thủ công.

### 3.8. Dịch vụ Thông báo (Notification Service)
* **FR-NOT-001:** Tiêu thụ domain events: Đăng ký tài khoản, Tạo đơn, Thanh toán thành công, Đổi trạng thái đơn, Hủy đơn, Hoàn tiền.
* **FR-NOT-002:** Bắt buộc hỗ trợ kênh Email; SMS/Push notification sẵn sàng theo cấu hình provider.
* **FR-NOT-003:** Quản lý mẫu thông báo (Notification Templates) theo sự kiện, kênh, ngôn ngữ, phiên bản.
* **FR-NOT-004:** Dữ liệu thông báo lấy hoàn toàn từ event payload / snapshot đính kèm, cấm query trực tiếp database dịch vụ khác.
* **FR-NOT-005:** Quản lý trạng thái gửi: `QUEUED`, `SENT`, `DELIVERED`, `FAILED` kèm provider message ID.
* **FR-NOT-006:** Retry khi gặp lỗi tạm thời; chuyển Dead Letter Queue (DLQ) và cảnh báo khi lỗi vĩnh viễn hoặc quá số lần retry.
* **FR-NOT-007:** Xử lý deduplication, không gửi trùng lặp thông báo khi nhận event trùng.
* **FR-NOT-008:** Sự cố/sập Notification Service KHÔNG ĐƯỢC làm gián đoạn luồng đặt hàng hoặc thanh toán.
* **FR-NOT-009:** Mặt nạ hóa PII trong log thông báo; tuân thủ chính sách bảo mật dữ liệu cá nhân.

### 3.9. API Gateway & Truyền thông
* **FR-GW-001:** Điểm vào duy nhất cho mọi API nghiệp vụ công khai.
* **FR-GW-002:** Định tuyến động (Dynamic routing) theo service path và API versioning (`/api/v1/...`).
* **FR-GW-003:** Kiểm tra tính hợp lệ, chữ ký và thời hạn của JWT trên các endpoint được bảo vệ.
* **FR-GW-004:** Chuyển tiếp danh tính người dùng và vai trò qua internal headers an toàn (`X-User-Id`, `X-User-Roles`).
* **FR-GW-005:** Rate limiting theo IP, Client ID, hoặc User ID (sử dụng Token Bucket / Leaky Bucket qua Redis).
* **FR-GW-006:** Giới hạn request size, header size và request timeout.
* **FR-GW-007:** Tự động sinh hoặc chuyển tiếp `X-Correlation-Id` cho mọi request, ghi Access Log có cấu trúc (JSON).
* **FR-GW-008:** Cấu hình CORS chặt chẽ theo whitelist domain, cấm wildcard `*` kèm credentials trên production.

### 3.10. Quản trị Hệ thống & Audit Log (Admin & Audit)
* **FR-ADM-001:** Giao diện quản trị phân quyền cho Catalog, Kho, Đơn hàng, Thanh toán, Người dùng.
* **FR-ADM-002:** Bắt buộc ghi Audit log cho mọi hành vi nhạy cảm: actor, action, target, delta (before/after), timestamp, IP, correlationId.
* **FR-ADM-003:** Audit log là bất biến (Append-only / Tamper-proof), không cho phép sửa/xóa từ tầng ứng dụng.
* **FR-ADM-004:** Mọi truy vấn danh sách lớn phải áp dụng phân trang và giới hạn `max_page_size`.
* **FR-ADM-005:** Kiểm tra quyền và ghi audit khi thực hiện trích xuất dữ liệu (Export).

---

## 4. DANH SÁCH YÊU CẦU PHI CHỨC NĂNG (NON-FUNCTIONAL REQUIREMENTS)

### 4.1. Hiệu năng & Tải (Performance & Scalability)
* **NFR-PERF-001:** Đọc Catalog: 95% requests <= 500ms, 99% requests <= 1000ms đo tại Gateway ở tải bình thường.
* **NFR-PERF-002:** Ghi thông thường: 95% requests <= 1000ms, 99% requests <= 2000ms.
* **NFR-PERF-003:** Tạo đơn hàng: p95 <= 2000ms ở tải mục tiêu (không chờ gửi email).
* **NFR-PERF-004:** Giao tiếp nội bộ Order -> Catalog tra cứu SKU: p95 < 5ms trong mạng nội bộ.
* **NFR-PERF-005 (Load Capacity):** Vượt qua bài kiểm thử tải 500 req/s liên tục trong 15 phút; mục tiêu mở rộng 1,000 req/s.
* **NFR-PERF-006 (Concurrency & Flash Sale):** Cạnh tranh đồng thời 500 - 1,000 requests cùng mua 1 SKU tồn kho có hạn: không oversell, không tồn kho âm, không deadlock.
* **NFR-PERF-007 (Stateless Scaling):** Các dịch vụ là stateless, có khả năng scale ngang (Horizontal scaling); Order, Inventory, Payment scale độc lập.
* **NFR-PERF-008:** Truy vấn danh sách bắt buộc giới hạn kích thước, ngăn chặn unbound query.

### 4.2. Độ sẵn sàng, Tin cậy & Phục hồi (Availability & Resilience)
* **NFR-AVL-001:** Mục tiêu sẵn sàng >= 99.5% thời gian hoạt động.
* **NFR-AVL-002:** Notification sập không ảnh hưởng tới tạo đơn / thanh toán; sau khi phục hồi sẽ xử lý bù tin nhắn.
* **NFR-AVL-003:** Áp dụng Circuit Breaker, Timeout, Retry có giới hạn, Bulkhead để tránh cascading failure.
* **NFR-AVL-004:** Message Broker phải lưu trữ tin nhắn bền vững (Durable / Persistent message delivery).
* **NFR-AVL-005:** Zero Data Loss đối với đơn hàng đã xác nhận, giao dịch thanh toán thành công và biến động tồn kho.
* **NFR-AVL-006:** Background worker / job phải tự phục hồi và tiếp tục xử lý an toàn sau khi khởi động lại.
* **NFR-AVL-007:** Khi một dịch vụ độc lập gặp sự cố, các tính năng không liên quan vẫn phải hoạt động bình thường (Graceful degradation).

### 4.3. Bảo mật (Security)
* **NFR-SEC-001:** Toàn bộ kết nối bên ngoài bắt buộc sử dụng HTTPS (TLS 1.2+ / TLS 1.3).
* **NFR-SEC-002:** Mật khẩu băm bằng Argon2id hoặc bcrypt với salt ngẫu nhiên.
* **NFR-SEC-003:** Quản lý Secret tập trung, không xuất hiện trong mã nguồn và log.
* **NFR-SEC-004:** Áp dụng nguyên tắc đặc quyền tối thiểu (Least Privilege) cho người dùng, service account và database user.
* **NFR-SEC-005:** Chống OWASP API Security Top 10: chống BOLA/IDOR, broken authentication, mass assignment.
* **NFR-SEC-006:** Chống SQL/NoSQL Injection, XSS, CSRF, SSRF, Path Traversal.
* **NFR-SEC-007:** Rate limiting nghiêm ngặt cho login, register, forgot-password, checkout, webhook.
* **NFR-SEC-008:** Mã hóa PII và dữ liệu thanh toán khi truyền tải; che mờ dữ liệu nhạy cảm trong log.
* **NFR-SEC-009:** Log phải lọc bỏ password, token, authorization header, OTP, card data.
* **NFR-SEC-010:** Upload file kiểm tra kỹ định dạng MIME thực tế, dung lượng, đổi tên file ngẫu nhiên và lưu tại storage cô lập.
* **NFR-SEC-011:** Quét lỗ hổng dependency định kỳ (Dependency check / Trivy).

### 4.4. Khả năng bảo trì & Tiêu chuẩn mã (Maintainability)
* **NFR-MNT-001:** Ranh giới Bounded Context rõ ràng giữa các service; tài liệu cấu hình và cách chạy độc lập.
* **NFR-MNT-002:** Đầy đủ hợp đồng API: OpenAPI (Swagger) cho REST, `.proto` cho gRPC, JSON Schema / Avro cho Events.
* **NFR-MNT-003:** Versioning cho API và Event để đảm bảo Backward Compatibility.
* **NFR-MNT-004:** Unit Test tự động cho core business logic (tồn kho, tính tiền, state machine, idempotency).
* **NFR-MNT-005:** Quy trình CI/CD tích hợp Linter, Unit Test, Integration Test và Security Scan.
* **NFR-MNT-006:** Database Migration được phiên bản hóa (Flyway / Liquibase / Goose / Prisma migrate).
* **NFR-MNT-007:** Cấu hình ứng dụng theo chuẩn 12-Factor App (tách biệt biến môi trường).

---

## 5. CÁC QUY TẮC NGHIỆP VỤ CỐT LÕI (BUSINESS RULES - BR)

* **BR-001:** SKU là đơn vị duy nhất để bán và quản lý tồn kho; Product chỉ đóng vai trò phân nhóm/mô tả.
* **BR-002:** Giá và thông tin sản phẩm trong Đơn hàng là Snapshot bất biến, không bị biến động theo Catalog sau khi đã đặt.
* **BR-003:** Thao tác thêm vào giỏ hàng KHÔNG GIỮ TỒN KHO.
* **BR-004:** Tồn kho khả dụng (`available`) không bao giờ được âm; đơn hàng xác nhận không được vượt quá số lượng tồn khả dụng.
* **BR-005:** Mỗi bản ghi Reservation chỉ được chuyển trạng thái Commit (trừ kho) hoặc Release (hủy giữ) đúng 1 lần.
* **BR-006:** Đơn hàng chỉ được chuyển `CONFIRMED` khi điều kiện thanh toán hoàn tất thành công.
* **BR-007:** Với thanh toán trực tuyến, Webhook / xác minh từ cổng thanh toán là nguồn sự thật; màn hình redirect của khách hàng chỉ dùng để hiển thị giao diện.
* **BR-008:** Số tiền thanh toán bắt buộc bằng đúng tổng tiền chốt trên đơn hàng.
* **BR-009:** Tổng số tiền hoàn (Refund) không được vượt quá tổng tiền thực tế đã thu của đơn hàng.
* **BR-010:** Mọi thao tác cập nhật tồn kho, thanh toán, chuyển trạng thái đơn hàng phải tuyệt đối Idempotent khi bị gọi lại nhiều lần.
* **BR-011:** Trạng thái đơn hàng chỉ chuyển đổi theo Ma trận State Machine hợp lệ:
  * `PENDING` -> `CONFIRMED` (khi thanh toán thành công)
  * `PENDING` -> `CANCELLED` (khi thanh toán thất bại / hết hạn reservation / khách hủy)
  * `CONFIRMED` -> `SHIPPING` (nhân viên giao vận xuất kho)
  * `CONFIRMED` -> `CANCELLED` (chính sách đặc biệt: kích hoạt hoàn tiền + hoàn kho)
  * `SHIPPING` -> `COMPLETED` (giao thành công)
  * `SHIPPING` -> `CANCELLED` (ngoại lệ có phê duyệt)
* **BR-012:** Lỗi gửi email/thông báo KHÔNG ĐƯỢC làm hủy bỏ đơn hàng đã thanh toán và giữ kho thành công.
* **BR-013:** Khách hàng chỉ truy cập dữ liệu của chính mình; nhân viên truy cập theo phân quyền RBAC.
* **BR-014:** Mọi thay đổi giá, tồn kho, phân quyền, hoàn tiền phải có Audit log.
* **BR-015:** Xóa dữ liệu nghiệp vụ ưu tiên Soft Delete (đánh dấu ngừng hoạt động) thay vì Hard Delete.

---

## 6. MA TRẬN TRUY VẾT & TIÊU CHÍ NGHIỆM THU (USE CASES & ACCEPTANCE)

1. **UC-01 (Đăng ký & Đăng nhập):** Khách đăng ký, xác thực, đăng nhập nhận JWT; kiểm tra rate limit và brute-force.
2. **UC-02 (Tìm kiếm & Xem sản phẩm):** Tìm kiếm full-text bằng Elasticsearch, lọc theo thuộc tính/giá, phân trang; Elasticsearch lệch thì Catalog DB vẫn là nguồn sự thật.
3. **UC-03 (Đặt hàng & Thanh toán thành công):** Snapshot giỏ hàng -> Giữ kho -> Tạo đơn `PENDING` -> Khách thanh toán -> Nhận Webhook -> Commit trừ kho -> Đơn `CONFIRMED` -> Gửi Email xác nhận.
4. **UC-04 (Thanh toán thất bại / Quá hạn):** Webhook báo thất bại hoặc hết hạn 15 phút -> Giải phóng Reservation (`reserved -= qty`) -> Hủy đơn -> Thông báo khách hàng.
5. **UC-05 (Flash Sale Concurrency):** Bắn 100 - 1,000 req/s đồng thời mua 1 SKU có số lượng ít: không oversell, không âm kho, các đơn thừa nhận thông báo hết hàng, không deadlock.
6. **UC-06 (Hủy đơn đã thanh toán):** Kích hoạt Saga hủy -> Hoàn tiền idempotent -> Hoàn tồn kho -> Đổi trạng thái `CANCELLED` -> Gửi email.
7. **UC-07 (Resilience - Notification down):** Tắt Notification Service, đặt hàng và thanh toán thành công; sau khi bật lại Notification, các sự kiện trong Message Broker được tiêu thụ và gửi bù đầy đủ, không bị trùng lặp.

---

## 7. CẬP NHẬT TRẠNG THÁI CÁC ĐIỂM QUYẾT ĐỊNH (TBD STATUS)

### 7.1. Các mục ĐÃ ĐƯỢC CHỐT (Resolved Decisions)
* **TBD-01 (Message Broker):** Đã chốt chọn **Apache Kafka** làm Message Broker chính cho EDA và Saga.
* **TBD-02 (Cổng thanh toán Sandbox):** Đã chốt chọn **MoMo (Sandbox)**.
* **TBD-09 (Mobile Support / Client scope):** Đã chốt **Chỉ làm Back-end Microservices APIs + Tài liệu Postman / OpenAPI**; không làm Mobile/Frontend.
* **TBD-10 (Kho hàng):** Đã chốt duy trì **1 kho logic mặc định** cho v1.0.
* **Database & Service Boundary:** Đã chốt **PostgreSQL** cho các dịch vụ quan hệ; **tách riêng biệt** `order-service` và `inventory-service`.
* **Hạ tầng triển khai:** Đã chốt **Docker Compose**.

### 7.2. Các mục nghiệp vụ chi tiết còn lại
* **TBD-03 (Thời gian giữ kho):** Mặc định 15 phút (cấu hình được qua biến môi trường).
* **TBD-04 (Chính sách hủy đơn):** Khách hàng được tự hủy khi đơn ở trạng thái `PENDING`.
* **TBD-05 (Thuế & Phí vận chuyển):** v1.0 mặc định thuế = 0, phí vận chuyển = 0 (hoặc cấu hình cố định trong mã cấu hình).
* **TBD-08 (Kênh định danh):** Đăng ký bằng Email; mật khẩu băm an toàn; tài khoản kích hoạt để sẵn sàng demo.
* **TBD-11 (Chính sách hoàn tiền):** Hủy đơn tự động kích hoạt yêu cầu hoàn tiền qua MoMo API; nhân viên tài chính có quyền kích hoạt bồi hoàn thủ công qua endpoint quản trị.
* **TBD-12 (Dữ liệu tải):** k6 load test script cho kịch bản Flash Sale (500 - 1.000 req/s, race condition 1 SKU).

