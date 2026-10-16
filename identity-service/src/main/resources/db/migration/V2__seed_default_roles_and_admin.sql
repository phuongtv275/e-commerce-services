-- 1. Khởi tạo 6 vai trò tiêu chuẩn
INSERT INTO roles (id, name, description) VALUES
    ('00000000-0000-0000-0000-000000000001', 'ROLE_CUSTOMER', 'Khách hàng mua sắm trực tuyến'),
    ('00000000-0000-0000-0000-000000000002', 'ROLE_CATALOG_STAFF', 'Nhân viên quản lý danh mục sản phẩm'),
    ('00000000-0000-0000-0000-000000000003', 'ROLE_INVENTORY_STAFF', 'Nhân viên quản lý kho và tồn kho'),
    ('00000000-0000-0000-0000-000000000004', 'ROLE_ORDER_STAFF', 'Nhân viên điều phối và quản lý đơn hàng'),
    ('00000000-0000-0000-0000-000000000005', 'ROLE_FINANCE_STAFF', 'Nhân viên tài chính, đối soát thanh toán'),
    ('00000000-0000-0000-0000-000000000006', 'ROLE_ADMINISTRATOR', 'Quản trị viên toàn quyền hệ thống')
ON CONFLICT (name) DO NOTHING;

-- 2. Khởi tạo tài khoản Administrator mặc định
-- Mật khẩu mặc định: Admin@123456 (BCrypt cost 12 hash: $2a$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy)
INSERT INTO users (
    id, email, password_hash, full_name, phone_number, status, email_verified, created_at, updated_at
) VALUES (
    '10000000-0000-0000-0000-000000000001',
    'admin@ecom.local',
    '$2a$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
    'System Administrator',
    '0900000001',
    'ACTIVE',
    TRUE,
    now(),
    now()
) ON CONFLICT (email) DO NOTHING;

-- 3. Gán quyền ROLE_ADMINISTRATOR cho tài khoản Admin mặc định
INSERT INTO user_roles (user_id, role_id, granted_at)
VALUES (
    '10000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000006',
    now()
) ON CONFLICT DO NOTHING;
