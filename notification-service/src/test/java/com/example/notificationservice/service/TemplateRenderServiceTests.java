package com.example.notificationservice.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateRenderServiceTests {

    private final TemplateRenderService renderService = new TemplateRenderService();

    @Test
    @DisplayName("Positive: Render subject and body with normal parameters")
    void testRenderSubjectAndBody() {
        String subjectTemplate = "Xác nhận đơn hàng #{{orderCode}} của {{customerName}}";
        String bodyTemplate = "<p>Xin chào {{customerName}}, đơn hàng #{{orderCode}} với tổng tiền {{totalAmount}} {{currency}} đã được tiếp nhận.</p>";

        Map<String, Object> params = Map.of(
                "orderCode", "ORD-12345",
                "customerName", "Nguyễn Văn A",
                "totalAmount", "500000",
                "currency", "VND"
        );

        String subject = renderService.renderSubject(subjectTemplate, params);
        assertEquals("Xác nhận đơn hàng #ORD-12345 của Nguyễn Văn A", subject);

        String body = renderService.renderBody(bodyTemplate, params);
        assertEquals("<p>Xin chào Nguyễn Văn A, đơn hàng #ORD-12345 với tổng tiền 500000 VND đã được tiếp nhận.</p>", body);
    }

    @Test
    @DisplayName("Positive: Safe HTML parameter orderItemsHtml is not double-escaped")
    void testOrderItemsHtmlNotEscaped() {
        String bodyTemplate = "<div>{{orderItemsHtml}}</div>";
        String itemsHtml = "<tr><td>Sản phẩm A</td><td>x1</td><td>100.000 VND</td></tr>";

        Map<String, Object> params = Map.of("orderItemsHtml", itemsHtml);

        String body = renderService.renderBody(bodyTemplate, params);
        assertEquals("<div><tr><td>Sản phẩm A</td><td>x1</td><td>100.000 VND</td></tr></div>", body);
    }

    @Test
    @DisplayName("Negative: Malicious HTML/XSS input in parameters is escaped")
    void testEscapeMaliciousInput() {
        String bodyTemplate = "<p>Xin chào {{customerName}}</p>";
        Map<String, Object> params = Map.of("customerName", "<script>alert('XSS')</script>");

        String body = renderService.renderBody(bodyTemplate, params);
        assertFalse(body.contains("<script>"));
        assertTrue(body.contains("&lt;script&gt;alert(&#39;XSS&#39;)&lt;/script&gt;"));
    }

    @Test
    @DisplayName("Negative: Missing parameter replaced with empty string")
    void testMissingParameter() {
        String bodyTemplate = "<p>Xin chào {{customerName}}, mã {{orderCode}}</p>";
        Map<String, Object> params = new HashMap<>();
        params.put("customerName", "Trần B");

        String body = renderService.renderBody(bodyTemplate, params);
        assertEquals("<p>Xin chào Trần B, mã </p>", body);
    }

    @Test
    @DisplayName("Negative: Null templates or null parameters handled gracefully")
    void testNullInputs() {
        assertEquals("", renderService.renderSubject(null, Map.of()));
        assertEquals("", renderService.renderBody(null, Map.of()));
        assertEquals("Hello {{name}}", renderService.renderBody("Hello {{name}}", null));
    }
}
