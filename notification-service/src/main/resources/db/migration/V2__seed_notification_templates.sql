-- Seed default Vietnamese HTML email templates for 5 approved topics

-- 1. user.registered
INSERT INTO notification_templates (
    id, event_type, channel, language, subject_template, body_template, version, is_active, created_at
) VALUES (
    gen_random_uuid(),
    'user.registered',
    'EMAIL',
    'vi',
    '[E-Commerce] Xác minh tài khoản của bạn',
    '<!DOCTYPE html><html><body><h2>Xin chào {{fullName}},</h2><p>Cảm ơn bạn đã đăng ký tài khoản tại hệ thống E-Commerce.</p><p>Vui lòng nhấn vào liên kết dưới đây để xác minh tài khoản của bạn:</p><p><a href="{{verificationUrl}}">Xác minh tài khoản</a></p><p>Nếu bạn không thực hiện đăng ký này, xin vui lòng bỏ qua email.</p><br/><p>Trân trọng,<br/>Đội ngũ E-Commerce</p></body></html>',
    1,
    TRUE,
    now()
);

-- 2. order.status-changed (CONFIRMED)
INSERT INTO notification_templates (
    id, event_type, channel, language, subject_template, body_template, version, is_active, created_at
) VALUES (
    gen_random_uuid(),
    'order.status-changed',
    'EMAIL',
    'vi',
    '[E-Commerce] Xác nhận đơn hàng #{{orderCode}}',
    '<!DOCTYPE html><html><body><h2>Xin chào {{customerName}},</h2><p>Đơn hàng <strong>#{{orderCode}}</strong> của bạn đã được xác nhận thành công.</p><p><strong>Tổng giá trị:</strong> {{totalAmount}} {{currency}}</p><p><strong>Địa chỉ nhận hàng:</strong> {{shippingAddress}}</p><div>{{orderItemsHtml}}</div><br/><p>Chúng tôi sẽ tiếp tục thông báo khi đơn hàng được vận chuyển.</p><p>Trân trọng,<br/>Đội ngũ E-Commerce</p></body></html>',
    1,
    TRUE,
    now()
);

-- 3. order.cancelled
INSERT INTO notification_templates (
    id, event_type, channel, language, subject_template, body_template, version, is_active, created_at
) VALUES (
    gen_random_uuid(),
    'order.cancelled',
    'EMAIL',
    'vi',
    '[E-Commerce] Thông báo hủy đơn hàng #{{orderCode}}',
    '<!DOCTYPE html><html><body><h2>Xin chào {{customerName}},</h2><p>Đơn hàng <strong>#{{orderCode}}</strong> của bạn đã bị hủy.</p><p><strong>Lý do:</strong> {{reason}}</p><p>{{refundNotice}}</p><br/><p>Nếu bạn có thắc mắc, vui lòng liên hệ bộ phận hỗ trợ.</p><p>Trân trọng,<br/>Đội ngũ E-Commerce</p></body></html>',
    1,
    TRUE,
    now()
);

-- 4. payment.succeeded
INSERT INTO notification_templates (
    id, event_type, channel, language, subject_template, body_template, version, is_active, created_at
) VALUES (
    gen_random_uuid(),
    'payment.succeeded',
    'EMAIL',
    'vi',
    '[E-Commerce] Biên nhận thanh toán thành công cho đơn hàng #{{orderCode}}',
    '<!DOCTYPE html><html><body><h2>Xin chào {{customerName}},</h2><p>Thanh toán cho đơn hàng <strong>#{{orderCode}}</strong> đã thành công.</p><p><strong>Số tiền:</strong> {{amount}} {{currency}}</p><p><strong>Mã giao dịch cổng thanh toán:</strong> {{providerPaymentId}}</p><p><strong>Thời gian thanh toán:</strong> {{paidAt}}</p><br/><p>Cảm ơn bạn đã mua sắm tại E-Commerce.</p><p>Trân trọng,<br/>Đội ngũ E-Commerce</p></body></html>',
    1,
    TRUE,
    now()
);

-- 5. payment.refund-completed
INSERT INTO notification_templates (
    id, event_type, channel, language, subject_template, body_template, version, is_active, created_at
) VALUES (
    gen_random_uuid(),
    'payment.refund-completed',
    'EMAIL',
    'vi',
    '[E-Commerce] Thông báo hoàn tiền thành công cho đơn hàng #{{orderCode}}',
    '<!DOCTYPE html><html><body><h2>Xin chào {{customerName}},</h2><p>Yêu cầu hoàn tiền cho đơn hàng <strong>#{{orderCode}}</strong> đã được thực hiện thành công.</p><p><strong>Số tiền hoàn:</strong> {{amount}} {{currency}}</p><p><strong>Mã giao dịch hoàn tiền:</strong> {{providerRefundId}}</p><br/><p>Số tiền sẽ được ghi nhận vào phương thức thanh toán ban đầu theo quy định của đơn vị thanh toán.</p><p>Trân trọng,<br/>Đội ngũ E-Commerce</p></body></html>',
    1,
    TRUE,
    now()
);
