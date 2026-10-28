# Tài Liệu Tham Khảo Schema CSDL - Notification Service

Các file SQL migration (V1, V2, V3) trong thư mục này được giữ lại làm tài liệu tham khảo kiến trúc schema CSDL sau khi dự án chuyển sang cơ chế Hibernate DDL auto `update` kết hợp `ApplicationRunner` data seeders cho giai đoạn MVP theo đặc tả SPEC-TECH-DB-SCHEMA-MVP.

## Danh mục tài liệu:
- `V1__init_notification_schema.sql`: Khởi tạo bảng `notification_templates`, `notifications`, `notification_recipients`, `notification_logs`, cùng các chỉ mục và trigger tương ứng.
- `V2__seed_notification_templates.sql`: Danh mục các mẫu thông báo mặc định ban đầu.
- `V3__add_outbox_events_schema.sql`: Cấu trúc bảng transactional outbox phục vụ đồng bộ sự kiện và xử lý bất đồng bộ.
