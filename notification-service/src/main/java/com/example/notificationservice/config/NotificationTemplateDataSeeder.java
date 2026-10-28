package com.example.notificationservice.config;

import com.example.notificationservice.domain.NotificationChannel;
import com.example.notificationservice.domain.NotificationTemplate;
import com.example.notificationservice.repository.NotificationTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationTemplateDataSeeder implements ApplicationRunner {

    private final NotificationTemplateRepository notificationTemplateRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (notificationTemplateRepository.count() == 0) {
            log.info("No notification templates found. Seeding default email templates...");
            List<NotificationTemplate> templates = createDefaultTemplates();
            notificationTemplateRepository.saveAll(templates);
            log.info("Successfully seeded {} notification templates.", templates.size());
        } else {
            log.info("Notification templates already exist (count={}), skipping seeding.",
                    notificationTemplateRepository.count());
        }
    }

    private List<NotificationTemplate> createDefaultTemplates() {
        return List.of(
                // 1. user.registered
                NotificationTemplate.builder()
                        .eventType("user.registered")
                        .channel(NotificationChannel.EMAIL)
                        .language("vi")
                        .subjectTemplate("[E-Commerce] Xác minh tài khoản của bạn")
                        .bodyTemplate("<!DOCTYPE html><html><body><h2>Xin chào {{fullName}},</h2>" +
                                "<p>Cảm ơn bạn đã đăng ký tài khoản tại hệ thống E-Commerce.</p>" +
                                "<p>Vui lòng nhấn vào liên kết dưới đây để xác minh tài khoản của bạn:</p>" +
                                "<p><a href=\"{{verificationUrl}}\">Xác minh tài khoản</a></p>" +
                                "<p>Nếu bạn không thực hiện đăng ký này, xin vui lòng bỏ qua email.</p><br/>" +
                                "<p>Trân trọng,<br/>Đội ngũ E-Commerce</p></body></html>")
                        .version(1)
                        .isActive(true)
                        .build(),

                // 2. order.status-changed (CONFIRMED)
                NotificationTemplate.builder()
                        .eventType("order.status-changed")
                        .channel(NotificationChannel.EMAIL)
                        .language("vi")
                        .subjectTemplate("[E-Commerce] Xác nhận đơn hàng #{{orderCode}}")
                        .bodyTemplate("<!DOCTYPE html><html><body><h2>Xin chào {{customerName}},</h2>" +
                                "<p>Đơn hàng <strong>#{{orderCode}}</strong> của bạn đã được xác nhận thành công.</p>" +
                                "<p><strong>Tổng giá trị:</strong> {{totalAmount}} {{currency}}</p>" +
                                "<p><strong>Địa chỉ nhận hàng:</strong> {{shippingAddress}}</p>" +
                                "<div>{{orderItemsHtml}}</div><br/>" +
                                "<p>Chúng tôi sẽ tiếp tục thông báo khi đơn hàng được vận chuyển.</p>" +
                                "<p>Trân trọng,<br/>Đội ngũ E-Commerce</p></body></html>")
                        .version(1)
                        .isActive(true)
                        .build(),

                // 3. order.cancelled
                NotificationTemplate.builder()
                        .eventType("order.cancelled")
                        .channel(NotificationChannel.EMAIL)
                        .language("vi")
                        .subjectTemplate("[E-Commerce] Thông báo hủy đơn hàng #{{orderCode}}")
                        .bodyTemplate("<!DOCTYPE html><html><body><h2>Xin chào {{customerName}},</h2>" +
                                "<p>Đơn hàng <strong>#{{orderCode}}</strong> của bạn đã bị hủy.</p>" +
                                "<p><strong>Lý do:</strong> {{reason}}</p>" +
                                "<p>{{refundNotice}}</p><br/>" +
                                "<p>Nếu bạn có thắc mắc, vui lòng liên hệ bộ phận hỗ trợ.</p>" +
                                "<p>Trân trọng,<br/>Đội ngũ E-Commerce</p></body></html>")
                        .version(1)
                        .isActive(true)
                        .build(),

                // 4. payment.succeeded
                NotificationTemplate.builder()
                        .eventType("payment.succeeded")
                        .channel(NotificationChannel.EMAIL)
                        .language("vi")
                        .subjectTemplate("[E-Commerce] Biên nhận thanh toán thành công cho đơn hàng #{{orderCode}}")
                        .bodyTemplate("<!DOCTYPE html><html><body><h2>Xin chào {{customerName}},</h2>" +
                                "<p>Thanh toán cho đơn hàng <strong>#{{orderCode}}</strong> đã thành công.</p>" +
                                "<p><strong>Số tiền:</strong> {{amount}} {{currency}}</p>" +
                                "<p><strong>Mã giao dịch cổng thanh toán:</strong> {{providerPaymentId}}</p>" +
                                "<p><strong>Thời gian thanh toán:</strong> {{paidAt}}</p><br/>" +
                                "<p>Cảm ơn bạn đã mua sắm tại E-Commerce.</p>" +
                                "<p>Trân trọng,<br/>Đội ngũ E-Commerce</p></body></html>")
                        .version(1)
                        .isActive(true)
                        .build(),

                // 5. payment.refund-completed
                NotificationTemplate.builder()
                        .eventType("payment.refund-completed")
                        .channel(NotificationChannel.EMAIL)
                        .language("vi")
                        .subjectTemplate("[E-Commerce] Thông báo hoàn tiền thành công cho đơn hàng #{{orderCode}}")
                        .bodyTemplate("<!DOCTYPE html><html><body><h2>Xin chào {{customerName}},</h2>" +
                                "<p>Yêu cầu hoàn tiền cho đơn hàng <strong>#{{orderCode}}</strong> đã được thực hiện thành công.</p>" +
                                "<p><strong>Số tiền hoàn:</strong> {{amount}} {{currency}}</p>" +
                                "<p><strong>Mã giao dịch hoàn tiền:</strong> {{providerRefundId}}</p><br/>" +
                                "<p>Số tiền sẽ được ghi nhận vào phương thức thanh toán ban đầu theo quy định của đơn vị thanh toán.</p>" +
                                "<p>Trân trọng,<br/>Đội ngũ E-Commerce</p></body></html>")
                        .version(1)
                        .isActive(true)
                        .build(),

                // 6. user.password-reset-requested
                NotificationTemplate.builder()
                        .eventType("user.password-reset-requested")
                        .channel(NotificationChannel.EMAIL)
                        .language("vi")
                        .subjectTemplate("[E-Commerce] Yêu cầu đặt lại mật khẩu")
                        .bodyTemplate("<!DOCTYPE html><html><body><h2>Xin chào {{fullName}},</h2>" +
                                "<p>Bạn đã yêu cầu đặt lại mật khẩu cho tài khoản tại hệ thống E-Commerce.</p>" +
                                "<p>Mã đặt lại mật khẩu của bạn là: <strong>{{resetToken}}</strong></p>" +
                                "<p>Nếu bạn không thực hiện yêu cầu này, xin vui lòng bỏ qua email.</p><br/>" +
                                "<p>Trân trọng,<br/>Đội ngũ E-Commerce</p></body></html>")
                        .version(1)
                        .isActive(true)
                        .build()
        );
    }
}
